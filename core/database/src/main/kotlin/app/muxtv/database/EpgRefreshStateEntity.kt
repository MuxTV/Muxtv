package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "epg_refresh_states",
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["sourceId"],
)
data class EpgRefreshStateEntity(
    val sourceId: String,
    val state: String,
    val runToken: String? = null,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val lastSuccessRevision: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val resultFamily: String? = null,
    val resultCode: String? = null,
    val httpStatus: Int? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(state.isNotBlank())
        require(runToken == null || runToken.isNotBlank())
        require(startedAtEpochMillis == null || startedAtEpochMillis >= 0)
        require(completedAtEpochMillis == null || completedAtEpochMillis >= 0)
        require(lastSuccessRevision == null || lastSuccessRevision > 0)
        require(lastSuccessAtEpochMillis == null || lastSuccessAtEpochMillis >= 0)
        require(resultFamily == null || resultFamily.isNotBlank())
        require(resultCode == null || resultCode.isNotBlank())
        require(httpStatus == null || httpStatus in 100..599)
    }
}
