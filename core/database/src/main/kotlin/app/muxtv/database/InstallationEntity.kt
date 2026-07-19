package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "installations")
data class InstallationEntity(
    @PrimaryKey val id: String,
    val primaryProfileId: String,
)
