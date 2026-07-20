package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "profiles",
    indices = [Index(value = ["isPrimary"])],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isPrimary: Boolean,
    val archivedAtEpochMillis: Long? = null,
)
