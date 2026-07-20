package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "canonical_channels")
data class CanonicalChannelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
)
