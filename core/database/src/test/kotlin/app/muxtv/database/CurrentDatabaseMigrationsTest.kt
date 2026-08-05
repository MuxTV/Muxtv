package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurrentDatabaseMigrationsTest {
    @Test
    fun currentMigrationChainIsContiguousAndReachesCurrentVersion() {
        val migrations = CURRENT_DATABASE_MIGRATIONS.toList()

        assertThat(migrations.first().startVersion).isEqualTo(1)
        assertThat(migrations.last().endVersion).isEqualTo(CURRENT_DATABASE_VERSION)
        assertThat(
            migrations.all { migration ->
                migration.endVersion == migration.startVersion + 1
            },
        ).isTrue()
        assertThat(
            migrations.zipWithNext().all { (current, next) ->
                current.endVersion == next.startVersion
            },
        ).isTrue()
    }

    @Test
    fun omittingLatestMigrationIsRejected() {
        val failure = runCatching {
            validateCurrentMigrationChain(
                migrations = CURRENT_DATABASE_MIGRATIONS.dropLast(1),
                currentVersion = CURRENT_DATABASE_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("ends at")
        assertThat(failure).hasMessageThat().contains("version $CURRENT_DATABASE_VERSION")
    }

    @Test
    fun emptyMigrationChainIsRejected() {
        val failure = runCatching {
            validateCurrentMigrationChain(
                migrations = emptyList(),
                currentVersion = CURRENT_DATABASE_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("must not be empty")
    }

    @Test
    fun migrationChainThatDoesNotStartAtOneIsRejected() {
        val failure = runCatching {
            validateCurrentMigrationChain(
                migrations = CURRENT_DATABASE_MIGRATIONS.drop(1),
                currentVersion = CURRENT_DATABASE_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("start at version 1")
    }

    @Test
    fun nonContiguousMigrationChainIsRejected() {
        val failure = runCatching {
            validateCurrentMigrationChain(
                migrations = listOf(
                    CURRENT_DATABASE_MIGRATIONS.first(),
                    CURRENT_DATABASE_MIGRATIONS.last(),
                ),
                currentVersion = CURRENT_DATABASE_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("not contiguous")
    }

    @Test
    fun duplicateMigrationEdgeIsRejected() {
        val first = CURRENT_DATABASE_MIGRATIONS.first()
        val failure = runCatching {
            validateCurrentMigrationChain(
                migrations = listOf(first, first) + CURRENT_DATABASE_MIGRATIONS.drop(1),
                currentVersion = CURRENT_DATABASE_VERSION,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("duplicate edges")
    }
}
