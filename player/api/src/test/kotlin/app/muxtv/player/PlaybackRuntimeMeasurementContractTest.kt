package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRuntimeMeasurementContractTest {
    @Test
    fun `runtime measurement boundary is stable typed and identity free`() {
        val snapshotClass = runCatching {
            Class.forName("app.muxtv.player.PlaybackRuntimeMeasurementSnapshot")
        }.getOrNull()
        val readerClass = runCatching {
            Class.forName("app.muxtv.player.PlaybackRuntimeMeasurementReader")
        }.getOrNull()
        val transportClass = runCatching {
            Class.forName("app.muxtv.player.PlaybackRuntimeTransport")
        }.getOrNull()
        val codecClass = runCatching {
            Class.forName("app.muxtv.player.PlaybackRuntimeVideoCodec")
        }.getOrNull()
        val hdrClass = runCatching {
            Class.forName("app.muxtv.player.PlaybackRuntimeHdr")
        }.getOrNull()

        assertThat(snapshotClass).isNotNull()
        assertThat(readerClass).isNotNull()
        assertThat(transportClass).isNotNull()
        assertThat(codecClass).isNotNull()
        assertThat(hdrClass).isNotNull()
        snapshotClass!!
        readerClass!!

        val fieldNames = snapshotClass.declaredFields.map { field -> field.name }
        listOf(
            "profileId",
            "channelId",
            "sourceId",
            "variantId",
            "locator",
            "uri",
            "url",
            "host",
            "headers",
            "credentials",
        ).forEach { forbidden ->
            assertThat(fieldNames).doesNotContain(forbidden)
        }

        val snapshotMethod = readerClass.methods.single { method -> method.name == "snapshot" }
        assertThat(snapshotMethod.parameterCount).isEqualTo(0)
        assertThat(snapshotMethod.returnType).isEqualTo(snapshotClass)
    }
}
