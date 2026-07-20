package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY isPrimary DESC, name ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id AND isPrimary = 0")
    suspend fun deleteAdditional(id: String): Int
}
