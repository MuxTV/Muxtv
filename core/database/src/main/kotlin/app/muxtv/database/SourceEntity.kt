package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val credentialRef: String? = null,
    val activeRevision: Long = 0,
)
