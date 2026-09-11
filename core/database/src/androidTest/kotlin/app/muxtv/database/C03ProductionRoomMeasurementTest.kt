package app.muxtv.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionRoomMeasurementTest {
    @Test
    fun reportContractRequiresFileBackedProductionRoomEvidence() {
        val reportClass = runCatching {
            Class.forName("app.muxtv.database.measurement.C03ProductionRoomMeasurementReport")
        }.getOrNull()

        assertWithMessage("C03 file-backed production Room measurement report contract is missing.")
            .that(reportClass)
            .isNotNull()

        val fields = checkNotNull(reportClass).declaredFields.map { it.name }.toSet()
        assertThat(fields).containsAtLeast(
            "schemaVersion",
            "methodVersion",
            "sourceCommit",
            "warmupIterations",
            "measuredIterations",
            "entryCount",
            "environment",
            "scenarios",
            "repeatedRevisionStorage",
            "safety",
            "redactionPassed",
            "limitations",
        )
    }
}
