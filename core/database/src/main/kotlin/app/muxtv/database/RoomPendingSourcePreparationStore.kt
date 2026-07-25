package app.muxtv.database

internal class RoomPendingSourcePreparationStore(
    private val dao: PendingSourcePreparationDao,
) : PendingSourcePreparationStore {
    override suspend fun upsert(preparation: PendingSourcePreparation) {
        dao.upsert(preparation.toEntity())
    }

    override suspend fun remove(preparationId: String): Boolean {
        require(preparationId.isNotBlank())
        return dao.delete(preparationId) == 1
    }

    override suspend fun get(preparationId: String): PendingSourcePreparation? {
        require(preparationId.isNotBlank())
        return dao.get(preparationId)?.toModel()
    }

    override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? {
        require(nowEpochMillis >= 0)
        return dao.getLatestActive(nowEpochMillis)?.toModel()
    }

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> {
        require(nowEpochMillis >= 0)
        require(limit in 1..MAX_CLEANUP_BATCH_SIZE)
        return dao.getExpired(nowEpochMillis, limit).map(PendingSourcePreparationEntity::toModel)
    }

    private companion object {
        const val MAX_CLEANUP_BATCH_SIZE = 100
    }
}

private fun PendingSourcePreparation.toEntity(): PendingSourcePreparationEntity =
    PendingSourcePreparationEntity(
        preparationId = preparationId,
        scheme = scheme,
        host = host,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

private fun PendingSourcePreparationEntity.toModel(): PendingSourcePreparation =
    PendingSourcePreparation(
        preparationId = preparationId,
        scheme = scheme,
        host = host,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
