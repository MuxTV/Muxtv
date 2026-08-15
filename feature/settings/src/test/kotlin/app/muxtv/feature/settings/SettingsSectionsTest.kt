package app.muxtv.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSectionsTest {
    @Test
    fun `sources precede doctor and both are top level sections`() {
        val sections = settingsSections()
        assertThat(sections.map { it.section })
            .containsExactly(SettingsSection.SOURCES, SettingsSection.DOCTOR)
            .inOrder()
    }

    @Test
    fun `every section has non blank label description and test tag`() {
        settingsSections().forEach { section ->
            assertThat(section.label).isNotEmpty()
            assertThat(section.description).isNotEmpty()
            assertThat(section.testTag).isNotEmpty()
        }
    }
}
