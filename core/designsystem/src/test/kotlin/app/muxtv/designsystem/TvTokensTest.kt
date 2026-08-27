package app.muxtv.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvTokensTest {
    @Test
    fun `dense focus preserves geometry and full content visibility`() {
        assertThat(TvTokens.Focus.scale).isEqualTo(1f)
        assertThat(TvTokens.Focus.outlineWidth.value).isAtLeast(1f)
        assertThat(TvTokens.Focus.focusedAlpha).isEqualTo(1f)
        assertThat(TvTokens.Focus.unfocusedAlpha).isEqualTo(1f)
    }

    @Test
    fun `u1 restores evidence proven lounge light scale`() {
        assertThat(TvTokens.Focus.outlineWidth.value).isEqualTo(3f)
        assertThat(TvTokens.Spacing.sectionGap.value).isEqualTo(40f)
        assertThat(TvTokens.Size.homeCardWidth.value).isEqualTo(300f)
        assertThat(TvTokens.Size.homeCardHeight.value).isEqualTo(140f)
        assertThat(TvTokens.Typography.heroTitle.value).isEqualTo(48f)
        assertThat(TvTokens.Typography.sectionTitle.value).isEqualTo(26f)
        assertThat(TvTokens.Typography.cardTitle.value).isEqualTo(20f)
        assertThat(TvTokens.Typography.metadata.value).isEqualTo(15f)
    }

    @Test
    fun `repeated dpad focus has no geometric transition delay`() {
        assertThat(TvTokens.Motion.focusDurationMillis).isEqualTo(0)
        assertThat(TvTokens.Motion.screenDurationMillis).isAtMost(300)
    }

    @Test
    fun `motion easings are deliberate curves and overlay exit is faster than entry`() {
        assertThat(TvTokens.Motion.easeOut).isInstanceOf(CubicBezierEasing::class.java)
        assertThat(TvTokens.Motion.easeInOut).isInstanceOf(CubicBezierEasing::class.java)
        assertThat(TvTokens.Motion.overlayOutMillis).isLessThan(TvTokens.Motion.overlayInMillis)
        assertThat(TvTokens.Motion.overlayInMillis).isAtMost(300)
    }

    @Test
    fun `lounge rail stays within the reference width reservation`() {
        assertThat(TvTokens.Size.railExpanded.value).isAtLeast(128f)
        assertThat(TvTokens.Size.railExpanded.value).isAtMost(144f)
    }

    @Test
    fun `channel rows and guide cells never scale on focus`() {
        assertThat(TvTokens.Focus.scale).isEqualTo(1f)
    }

    @Test
    fun `semantic palette keeps green reserved for live playing progress`() {
        assertThat(TvTokens.Color.liveGreen).isNotEqualTo(TvTokens.Color.accent)
        assertThat(TvTokens.Color.accent).isNotEqualTo(TvTokens.Color.liveGreen)
    }

    @Test
    fun `text roles keep primary and secondary contrast pairs`() {
        assertThat(TvTokens.Color.textPrimary).isNotEqualTo(TvTokens.Color.textSecondary)
        assertThat(TvTokens.Color.accentSoft).isNotEqualTo(TvTokens.Color.accentSoft2)
    }
}
