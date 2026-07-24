package app.muxtv.database

data class PendingSourcePreparation(
    val preparationId: String,
    val scheme: String,
    val host: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(preparationId.isNotBlank())
        require(scheme == "http" || scheme == "https")
        require(host.isNotBlank())
        require(createdAtEpochMillis >= 0)
        require(expiresAtEpochMillis > createdAtEpochMillis)
        require(host.none { it == '/' || it == '?' || it == '#' || it.isWhitespace() })
    }

    override fun toString(): String =
        "PendingSourcePreparation(preparationId=<redacted>, scheme=$scheme, host=$host, " +
            "createdAtEpochMillis=$createdAtEpochMillis, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

interface PendingSourcePreparationStore {
    suspend fun upsert(preparation: PendingSourcePreparation)

    suspend fun remove(preparationId: String): Boolean

    suspend fun get(preparationId: String): PendingSourcePreparation?

    suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation>
}
