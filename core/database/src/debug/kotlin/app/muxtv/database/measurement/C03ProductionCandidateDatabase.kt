package app.muxtv.database.measurement

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        C03ProductionCandidateSourceEntity::class,
        C03ProductionCandidateRevisionEntity::class,
        C03ProductionCandidateRefreshOwnerEntity::class,
        C03ProductionCandidateSearchPayloadEntity::class,
        C03ProductionCandidateSearchDocumentEntity::class,
        C03ProductionCandidateSearchDocumentFtsEntity::class,
        C03ProductionCandidatePayloadEntity::class,
        C03ProductionCandidateMembershipEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class C03ProductionCandidateDatabase : RoomDatabase() {
    abstract fun candidateDao(): C03ProductionCandidateDao
}
