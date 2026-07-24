package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "pending_source_preparations",
    indices = [Index(value = ["expiresAtEpochMillis"])],
)
data class PendingSourcePreparationEntity(
    @PrimaryKey val preparationId: String,
    val scheme: String,
    val host: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)
