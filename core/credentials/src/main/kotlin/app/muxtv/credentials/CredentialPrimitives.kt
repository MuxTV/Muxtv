package app.muxtv.credentials

import java.util.UUID

@JvmInline
value class CredentialId private constructor(
    val value: String,
) {
    companion object {
        fun random(): CredentialId = CredentialId(UUID.randomUUID().toString())

        fun parse(value: String): CredentialId {
            require(value.isNotEmpty()) { "Credential ID must not be empty." }
            val parsed = runCatching { UUID.fromString(value) }
                .getOrElse { throw IllegalArgumentException("Credential ID must be a canonical UUID.", it) }
            val canonical = parsed.toString()
            require(value == canonical) { "Credential ID must be a canonical lower-case UUID." }
            return CredentialId(canonical)
        }
    }
}

class SecretBytes private constructor(
    bytes: ByteArray,
) : AutoCloseable {
    private var value: ByteArray? = bytes

    @Synchronized
    fun copyBytes(): ByteArray = requireOpen().copyOf()

    fun <T> useBytes(block: (ByteArray) -> T): T {
        val temporary = copyBytes()
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        value?.fill(0)
        value = null
    }

    override fun toString(): String = "<redacted>"

    private fun requireOpen(): ByteArray =
        checkNotNull(value) { "SecretBytes is closed." }

    companion object {
        fun copyOf(bytes: ByteArray): SecretBytes = SecretBytes(bytes.copyOf())
    }
}
