package app.muxtv.database

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test-only transport for the redacted synthetic C364 report. Deliberately avoids
 * depending on AGP/UTP's private test-results.log or a large instrumentation Bundle.
 * Keep each ASCII log message below Android's per-entry payload limit.
 */
internal object C03ProductionRoomHostedTransport {
    const val ARGUMENT = "c03ProductionRoomHostedTransport"
    const val VERSION = "logcat-v1"
    private const val TAG = "MuxTVC364"
    private const val CHUNK_SIZE = 2800
    private const val MAX_BYTES = 512 * 1024

    fun publish(sourceCommit: String, bytes: ByteArray) {
        require(sourceCommit.matches(Regex("[0-9a-f]{40}")))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(CHUNK_SIZE)
        Log.i(TAG, "C364-V1 BEGIN $sourceCommit ${bytes.size} ${chunks.size} $digest")
        chunks.forEachIndexed { index, chunk ->
            Log.i(TAG, "C364-V1 CHUNK $index $chunk")
        }
        Log.i(TAG, "C364-V1 END $digest")
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
        // Larger than the previously observed 142,606-byte canonical JSON.
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        C03ProductionRoomHostedTransport.publish(source, payload)
    }
}
