package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
abstract class InitializationDao {
    @Query("SELECT COUNT(*) FROM profiles WHERE isPrimary = 1")
    protected abstract suspend fun countPrimaryProfiles(): Int

    @Query("SELECT COUNT(*) FROM installations")
    protected abstract suspend fun countInstallations(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertInstallation(installation: InstallationEntity)

    @Transaction
    open suspend fun ensureInitialized() {
        if (countPrimaryProfiles() == 0) {
            insertProfile(
                ProfileEntity(
                    id = PRIMARY_PROFILE_ID,
                    name = PRIMARY_PROFILE_NAME,
                    isPrimary = true,
                ),
            )
        }
        if (countInstallations() == 0) {
            insertInstallation(
                InstallationEntity(
                    id = LOCAL_INSTALLATION_ID,
                    primaryProfileId = PRIMARY_PROFILE_ID,
                ),
            )
        }
    }

    companion object {
        const val LOCAL_INSTALLATION_ID = "installation-local"
        const val PRIMARY_PROFILE_ID = "profile-primary"
        const val PRIMARY_PROFILE_NAME = "Основной"
    }
}
