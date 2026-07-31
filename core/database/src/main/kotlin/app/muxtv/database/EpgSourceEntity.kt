package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "epg_sources",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerSourceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["providerSourceId"])],
)
data class EpgSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
    val activeRevision: Long = 0,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(activeRevision >= 0)
    }

    override fun toString(): String =
        "EpgSourceEntity(providerLinked=${providerSourceId != null}, accessRefPresent=${accessRef != null}, " +
            "defaultZonePresent=${defaultZoneId != null}, activeRevision=$activeRevision)"
}
