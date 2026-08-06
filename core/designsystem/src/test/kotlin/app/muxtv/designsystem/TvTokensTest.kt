package app.muxtv.designsystem

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvTokensTest {
    @Test
    fun `dense remote focus keeps geometry stable and fully readable`() {
        assertThat(TvTokens.Focus.scale).isEqualTo(1f)
        assertThat(TvTokens.Focus.outlineWidth.value).isAtLeast(2f)
        assertThat(TvTokens.Focus.focusedAlpha).isEqualTo(1f)
        assertThat(TvTokens.Focus.unfocusedAlpha).isEqualTo(1f)
    }

    @Test
    fun `dense remote focus feedback is immediate while route motion stays bounded`() {
        assertThat(TvTokens.Motion.focusDurationMillis).isEqualTo(0)
        assertThat(TvTokens.Motion.screenDurationMillis).isAtMost(300)
    }
}
