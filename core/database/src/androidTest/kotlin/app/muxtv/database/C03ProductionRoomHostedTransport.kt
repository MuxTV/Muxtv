package app.muxtv.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only binary file transport via AGP's additional Android test output directory. */
internal object C03ProductionRoomHostedTransport {
    const val ARGUMENT = "c03ProductionRoomHostedTransport"
    const val VERSION = "additional-output-v1"
    private const val MAX_BYTES = 512 * 1024

    fun publish(sourceCommit: String, mode: String, bytes: ByteArray) {
        require(sourceCommit.matches(Regex("[0-9a-f]{40}")))
        require(mode == "probe" || mode == "canonical")
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val outputDir = File(requireNotNull(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"),
        ) { "AGP additionalTestOutputDir is required for hosted C364." })
        require(outputDir.isAbsolute)
        require(outputDir.isDirectory || outputDir.mkdirs())
        val payload = File(outputDir, "c364-$mode-payload.bin")
        val manifest = File(outputDir, "c364-$mode-manifest.json")
        check(!payload.exists() && !manifest.exists()) { "Stale C364 transport files." }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Write the manifest last; a partial publication must not satisfy the host gate.
        payload.writeBytes(bytes)
        check(payload.length() == bytes.size.toLong())
        manifest.writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("transport", VERSION)
                .put("sourceCommit", sourceCommit)
                .put("mode", mode)
                .put("payloadBytes", bytes.size)
                .put("payloadSha256", digest)
                .put("payloadName", payload.name)
                .toString(),
            Charsets.UTF_8,
        )
    }
}

@CatalogDatabaseMeasurement
@RunWith(AndroidJUnit4::class)
class C03ProductionRoomTransportProbeTest {
    @Test
    fun transfersBoundedSyntheticPayload() {
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString(C03ProductionRoomHostedTransport.ARGUMENT) ==
            C03ProductionRoomHostedTransport.VERSION)
        val source = requireNotNull(arguments.getString("c03ProductionRoomSourceCommit"))
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        C03ProductionRoomHostedTransport.publish(source, "probe", payload)
    }
}
