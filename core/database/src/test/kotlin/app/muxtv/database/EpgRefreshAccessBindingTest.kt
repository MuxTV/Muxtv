package app.muxtv.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgRefreshAccessBindingTest {
    @Test
    fun `null snapshot does not own a newly attached access binding`() {
        assertFalse(
            epgRefreshAccessBindingMatches(
                expectedAccessRef = null,
                currentAccessRef = "new-access",
            ),
        )
    }

    @Test
    fun `null snapshot still owns an unchanged null binding`() {
        assertTrue(
            epgRefreshAccessBindingMatches(
                expectedAccessRef = null,
                currentAccessRef = null,
            ),
        )
    }
}
