package app.muxtv.designsystem

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvTokensTest {
    @Test
    fun `focus state remains visible at television distance`() {
        assertThat(TvTokens.Focus.scale).isAtLeast(1.04f)
        assertThat(TvTokens.Focus.outlineWidth.value).isAtLeast(2f)
        assertThat(TvTokens.Focus.focusedAlpha).isEqualTo(1f)
    }

    @Test
    fun `motion does not delay core remote actions`() {
        assertThat(TvTokens.Motion.focusDurationMillis).isAtMost(180)
        assertThat(TvTokens.Motion.screenDurationMillis).isAtMost(300)
    }
}
