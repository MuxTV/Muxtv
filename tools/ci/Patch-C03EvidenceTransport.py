from pathlib import Path

path = Path("core/database/src/androidTest/kotlin/app/muxtv/database/RefreshDeltaDatabaseMeasurementTest.kt")
text = path.read_text()

old_import = "import androidx.test.ext.junit.runners.AndroidJUnit4\n"
new_import = "import android.os.Bundle\nimport android.util.Base64\nimport androidx.test.ext.junit.runners.AndroidJUnit4\n"
if text.count(old_import) != 1:
    raise SystemExit("AndroidJUnit4 import marker not unique")
text = text.replace(old_import, new_import, 1)

old_assertions = '''        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(0L)
'''
new_assertions = '''        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(0L)

        val encodedReport = Base64.encodeToString(output.readBytes(), Base64.NO_WRAP)
        instrumentation.addResults(
            Bundle().apply {
                putString(RESULT_REPORT_BASE64, encodedReport)
            },
        )
'''
if text.count(old_assertions) != 1:
    raise SystemExit("output assertion marker not unique")
text = text.replace(old_assertions, new_assertions, 1)

old_constant = '        const val EVIDENCE_REF = "c03-room/c03-refresh-delta.json"\n'
new_constant = '''        const val EVIDENCE_REF = "c03-room/c03-refresh-delta.json"
        const val RESULT_REPORT_BASE64 = "c03RefreshDeltaDatabaseMeasurementReportBase64"
'''
if text.count(old_constant) != 1:
    raise SystemExit("evidence ref marker not unique")
text = text.replace(old_constant, new_constant, 1)

path.write_text(text)
