package app.muxtv.benchmark.refreshdelta

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** C03-only benchmark variants. None of these types are part of the production catalog API. */
enum class RefreshDeltaVariant {
    A_CURRENT_MUXTV,
    B_IMMUTABLE_COW,
    C_OWNTV_REFERENCE,
}

enum class RefreshPublicationDisposition {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class RefreshDeltaItem(
    val baseIdentityKey: String,
    val stableKey: String,
    val contentDigestSha256: String,
    val order: Int,
) {
    init {
        require(baseIdentityKey.isNotBlank())
        require(stableKey.isNotBlank())
        require(contentDigestSha256.matches(SHA256_HEX))
        require(order >= 0)
    }
}

data class RefreshDeltaWrites(
    val payloadWrites: Int = 0,
    val membershipWrites: Int = 0,
    val insertedRows: Int = 0,
    val updatedRows: Int = 0,
    val movedRows: Int = 0,
    val deletedRows: Int = 0,
) {
    init {
        require(payloadWrites >= 0)
        require(membershipWrites >= 0)
        require(insertedRows >= 0)
        require(updatedRows >= 0)
        require(movedRows in 0..updatedRows)
        require(deletedRows >= 0)
    }

    val rowWrites: Int
        get() = insertedRows + updatedRows + deletedRows
}

data class RefreshDeltaResult(
    val variant: RefreshDeltaVariant,
    val activeCount: Int,
    val activeDigestSha256: String,
    val writes: RefreshDeltaWrites,
    val preservedLogicalIdentityCount: Int,
    val previousGoodPreserved: Boolean,
    val staleGenerationRejected: Boolean,
) {
    init {
        require(activeCount >= 0)
        require(activeDigestSha256.matches(SHA256_HEX))
        require(preservedLogicalIdentityCount >= 0)
    }

    override fun toString(): String =
        "RefreshDeltaResult(variant=$variant, activeCount=$activeCount, activeDigestSha256=$activeDigestSha256, " +
            "writes=$writes, preservedLogicalIdentityCount=$preservedLogicalIdentityCount, " +
            "previousGoodPreserved=$previousGoodPreserved, staleGenerationRejected=$staleGenerationRejected)"
}

class RefreshDeltaComparison internal constructor(
    private val results: Map<RefreshDeltaVariant, RefreshDeltaResult>,
) {
    init {
        require(results.keys == RefreshDeltaVariant.entries.toSet())
    }

    operator fun get(variant: RefreshDeltaVariant): RefreshDeltaResult =
        requireNotNull(results[variant])

    val correctnessPassed: Boolean
        get() {
            val values = RefreshDeltaVariant.entries.map(::get)
            return values.map(RefreshDeltaResult::activeCount).distinct().size == 1 &&
                values.map(RefreshDeltaResult::activeDigestSha256).distinct().size == 1
        }

    override fun toString(): String =
        RefreshDeltaVariant.entries.joinToString(
            prefix = "RefreshDeltaComparison(",
            postfix = ")",
            separator = ", ",
        ) { variant -> "$variant=${get(variant)}" }
}

class RefreshDeltaPrototype {
    fun compare(
        previous: List<RefreshDeltaItem>,
        incoming: List<RefreshDeltaItem>,
        disposition: RefreshPublicationDisposition = RefreshPublicationDisposition.SUCCESS,
        incomingGeneration: Long = 2L,
        authoritativeGeneration: Long = incomingGeneration,
    ): RefreshDeltaComparison {
        validateCatalog(previous, "previous")
        validateCatalog(incoming, "incoming")
        require(incomingGeneration > 0)
        require(authoritativeGeneration > 0)

        val results = RefreshDeltaVariant.entries.associateWith { variant ->
            evaluate(
                variant = variant,
                previous = previous,
                incoming = incoming,
                disposition = disposition,
                incomingGeneration = incomingGeneration,
                authoritativeGeneration = authoritativeGeneration,
            )
        }
        return RefreshDeltaComparison(results)
    }

    fun evaluate(
        variant: RefreshDeltaVariant,
        previous: List<RefreshDeltaItem>,
        incoming: List<RefreshDeltaItem>,
        disposition: RefreshPublicationDisposition = RefreshPublicationDisposition.SUCCESS,
        incomingGeneration: Long = 2L,
        authoritativeGeneration: Long = incomingGeneration,
    ): RefreshDeltaResult {
        validateCatalog(previous, "previous")
        validateCatalog(incoming, "incoming")
        require(incomingGeneration > 0)
        require(authoritativeGeneration > 0)

        val staleGeneration = incomingGeneration < authoritativeGeneration
        val publishes = disposition == RefreshPublicationDisposition.SUCCESS && !staleGeneration
        val active = if (publishes) incoming else previous
        val previousByKey = previous.associateBy(RefreshDeltaItem::stableKey)
        val incomingKeys = incoming.asSequence().map(RefreshDeltaItem::stableKey).toHashSet()
        val preservedIdentityCount = incoming.count { it.stableKey in previousByKey }

        val writes = if (!publishes) {
            // This pure model counts accepted/committed writes. Attempted staging I/O and cancellation
            // latency are measured separately by the Android file-backed database runner.
            RefreshDeltaWrites()
        } else {
            when (variant) {
                RefreshDeltaVariant.A_CURRENT_MUXTV -> RefreshDeltaWrites(
                    payloadWrites = incoming.size,
                    membershipWrites = incoming.size,
                    insertedRows = incoming.size,
                )

                RefreshDeltaVariant.B_IMMUTABLE_COW -> {
                    val payloadWrites = incoming.count { item ->
                        previousByKey[item.stableKey]?.contentDigestSha256 != item.contentDigestSha256
                    }
                    RefreshDeltaWrites(
                        payloadWrites = payloadWrites,
                        membershipWrites = incoming.size,
                        insertedRows = payloadWrites,
                    )
                }

                RefreshDeltaVariant.C_OWNTV_REFERENCE -> {
                    var inserted = 0
                    var updated = 0
                    var moved = 0
                    incoming.forEach { item ->
                        val existing = previousByKey[item.stableKey]
                        when {
                            existing == null -> inserted++
                            existing.contentDigestSha256 != item.contentDigestSha256 -> updated++
                            existing.order != item.order -> {
                                updated++
                                moved++
                            }
                        }
                    }
                    val deleted = previous.count { it.stableKey !in incomingKeys }
                    RefreshDeltaWrites(
                        insertedRows = inserted,
                        updatedRows = updated,
                        movedRows = moved,
                        deletedRows = deleted,
                    )
                }
            }
        }

        return RefreshDeltaResult(
            variant = variant,
            activeCount = active.size,
            activeDigestSha256 = RefreshDeltaDigest.activeCatalog(active),
            writes = writes,
            preservedLogicalIdentityCount = preservedIdentityCount,
            previousGoodPreserved = !publishes || variant != RefreshDeltaVariant.C_OWNTV_REFERENCE,
            staleGenerationRejected = staleGeneration,
        )
    }

    private fun validateCatalog(items: List<RefreshDeltaItem>, label: String) {
        val stableKeys = HashSet<String>(items.size)
        val orders = HashSet<Int>(items.size)
        items.forEach { item ->
            require(stableKeys.add(item.stableKey)) { "$label catalog contains a duplicate stable key." }
            require(orders.add(item.order)) { "$label catalog contains a duplicate order." }
        }
    }
}

object RefreshDeltaDigest {
    fun activeCatalog(items: List<RefreshDeltaItem>): String {
        val digest = MessageDigest.getInstance(SHA_256)
        items.sortedWith(compareBy(RefreshDeltaItem::order, RefreshDeltaItem::stableKey)).forEach { item ->
            digest.update(item.stableKey.toByteArray(StandardCharsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(item.contentDigestSha256.toByteArray(StandardCharsets.US_ASCII))
            digest.update(SEPARATOR)
            digest.update(item.order.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(RECORD_SEPARATOR)
        }
        return digest.digest().toHex()
    }
}

object RefreshDeltaCorpus {
    fun baseline(size: Int, seed: Long): List<RefreshDeltaItem> {
        require(size > 0)
        return List(size) { index ->
            val baseKey = "tvg:fixture-${index.toString().padStart(6, '0')}"
            RefreshDeltaItem(
                baseIdentityKey = baseKey,
                stableKey = baseKey,
                contentDigestSha256 = safeContentDigest(
                    "seed=$seed|index=$index|name=Fixture $index|group=Group ${index % 32}|" +
                        "locator=https://fixture.invalid/live/$index?token=baseline-$seed-$index",
                ),
                order = index,
            )
        }
    }

    fun contentDelta(
        previous: List<RefreshDeltaItem>,
        changedPercent: Int,
    ): List<RefreshDeltaItem> {
        require(changedPercent in 0..100)
        val changedCount = ((previous.size.toLong() * changedPercent) / 100L).toInt()
        return previous.mapIndexed { index, item ->
            if (index < changedCount) {
                item.copy(contentDigestSha256 = safeContentDigest("${item.contentDigestSha256}|content-delta"))
            } else {
                item
            }
        }
    }

    fun reverseOrder(previous: List<RefreshDeltaItem>): List<RefreshDeltaItem> =
        previous.asReversed().mapIndexed { newOrder, item -> item.copy(order = newOrder) }

    fun removeTail(previous: List<RefreshDeltaItem>, count: Int): List<RefreshDeltaItem> {
        require(count in 0..previous.size)
        return previous.dropLast(count)
    }

    fun tokenizedLocatorChurn(previous: List<RefreshDeltaItem>): List<RefreshDeltaItem> =
        previous.map { item ->
            // The raw locator never enters the item/evidence object. Its semantic change is represented
            // by a one-way content digest, while stable identity remains independent from it.
            item.copy(contentDigestSha256 = safeContentDigest("${item.contentDigestSha256}|token-churn"))
        }

    fun duplicateCollisionFixture(): List<RefreshDeltaItem> {
        val identities = listOf(
            "name:news|group:general",
            "name:news|group:general",
            "name:news|group:general",
            "name:news|group:sports",
        )
        val occurrences = HashMap<String, Int>()
        return identities.mapIndexed { order, base ->
            val occurrence = occurrences.merge(base, 1, Int::plus)!!
            val stableKey = if (occurrence == 1) base else "$base|#$occurrence"
            RefreshDeltaItem(
                baseIdentityKey = base,
                stableKey = stableKey,
                contentDigestSha256 = safeContentDigest("collision-fixture|$stableKey|order=$order"),
                order = order,
            )
        }
    }

    private fun safeContentDigest(value: String): String =
        MessageDigest.getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHex()
}

private fun ByteArray.toHex(): String {
    val output = CharArray(size * 2)
    var outputIndex = 0
    forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        output[outputIndex++] = HEX[unsigned ushr 4]
        output[outputIndex++] = HEX[unsigned and 0x0f]
    }
    return output.concatToString().lowercase(Locale.ROOT)
}

private const val SHA_256 = "SHA-256"
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val HEX = "0123456789abcdef".toCharArray()
private val SEPARATOR = byteArrayOf(0x1f)
private val RECORD_SEPARATOR = byteArrayOf(0x1e)
