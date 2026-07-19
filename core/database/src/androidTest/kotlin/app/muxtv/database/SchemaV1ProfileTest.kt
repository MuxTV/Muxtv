package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV1ProfileTest {
    private lateinit var database: MuxTvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cleanInitializationCreatesExactlyOnePrimaryProfileAndIsIdempotent() = runTest {
        val initializer = DatabaseInitializer(database)
        initializer.initialize()
        initializer.initialize()

        val profiles = database.profileDao().getAll()
        assertThat(profiles).hasSize(1)
        assertThat(profiles.single().name).isEqualTo("Основной")
        assertThat(profiles.single().isPrimary).isTrue()
        assertThat(database.profileDao().deleteAdditional(profiles.single().id)).isEqualTo(0)
    }

    @Test
    fun arbitraryAdditionalProfileNameIsAccepted() = runTest {
        DatabaseInitializer(database).initialize()
        database.profileDao().insert(
            ProfileEntity(id = "profile-office", name = "Кабинет", isPrimary = false),
        )

        assertThat(database.profileDao().getById("profile-office")?.name).isEqualTo("Кабинет")
    }
}
