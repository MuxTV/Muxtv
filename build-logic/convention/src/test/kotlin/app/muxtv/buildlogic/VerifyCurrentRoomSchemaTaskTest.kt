package app.muxtv.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

class VerifyCurrentRoomSchemaTaskTest {
    @Test
    fun `extracts current version from database owner`() {
        assertEquals(
            10,
            extractCurrentDatabaseVersion(
                "internal const val CURRENT_DATABASE_VERSION = 10",
            ),
        )
    }

    @Test
    fun `rejects source without current version owner`() {
        val failure = assertFailsWith<GradleException> {
            extractCurrentDatabaseVersion("internal const val OTHER_VERSION = 10")
        }

        assertTrue(failure.message.orEmpty().contains("CURRENT_DATABASE_VERSION"))
    }

    @Test
    fun `extracts version and identity from Room schema`() {
        val metadata = extractRoomSchemaMetadata(
            """
            {
              "formatVersion": 1,
              "database": {
                "version": 10,
                "identityHash": "identity-v10",
                "entities": []
              }
            }
            """.trimIndent(),
        )

        assertEquals(10, metadata.version)
        assertEquals("identity-v10", metadata.identityHash)
    }

    @Test
    fun `rejects Room schema without identity`() {
        val failure = assertFailsWith<GradleException> {
            extractRoomSchemaMetadata(
                """
                {
                  "database": {
                    "version": 10,
                    "entities": []
                  }
                }
                """.trimIndent(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("identityHash"))
    }
}
