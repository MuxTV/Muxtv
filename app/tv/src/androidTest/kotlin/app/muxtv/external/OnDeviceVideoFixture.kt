package app.muxtv.external

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a short solid-color H.264 MP4 on the device itself, so app-level journeys can evidence
 * a real first rendered frame without committing binary media assets to the repository.
 *
 * The encoder follows the official MediaCodec state contract:
 * `createEncoderByType -> configure -> createInputSurface -> start`, with frames rendered through
 * EGL/OpenGL ES onto the input surface (surface input is not software-drawable: `lockCanvas` on a
 * codec input surface is documented as unreliable). Each frame carries an explicit presentation
 * timestamp via `EGLExt.eglPresentationTimeANDROID`, so the media timeline is deterministic:
 * frame *i* is at `i / FRAME_RATE` seconds, independent of rendering jitter.
 *
 * EGL config selection explicitly requires `EGL_RECORDABLE_ANDROID=1`, matching the Android
 * codec-input-surface contract instead of relying on an emulator-permissive window config.
 *
 * If the device image lacks an H.264 encoder or decoder, callers must skip via a JUnit assumption
 * — the fixture itself fails loudly rather than degrading silently.
 */
object OnDeviceVideoFixture {
    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val FRAME_RATE = 24
    private const val BIT_RATE = 400_000
    private const val KEY_FRAME_INTERVAL_SECONDS = 1
    private const val ENCODE_TIMEOUT_MICROS = 30_000L
    private const val ENCODE_DEADLINE_SECONDS = 60L

    fun hasRequiredCodecs(): Boolean =
        hasCodec(MediaFormat.MIMETYPE_VIDEO_AVC, isEncoder = true) &&
            hasCodec(MediaFormat.MIMETYPE_VIDEO_AVC, isEncoder = false)

    fun encode(context: Context, durationSeconds: Int): ByteArray {
        val frames = durationSeconds * FRAME_RATE
        val outputFile = File(context.cacheDir, "ep08-journey-${System.nanoTime()}.mp4")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglSurface = EGL14.EGL_NO_SURFACE
        var eglContext = EGL14.EGL_NO_CONTEXT
        var inputSurface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            eglDisplay = initEgl()
            val eglConfig = chooseEglConfig(eglDisplay)
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    2,
                    EGL14.EGL_NONE,
                ),
                0,
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                inputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }

            var trackIndex = -1
            var muxerStarted = false
            var encodedFrames = 0
            var eosWritten = false
            var frameIndex = 0

            val deadline = SystemClock.elapsedRealtime() + ENCODE_DEADLINE_SECONDS * 1_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!eosWritten && frameIndex < frames) {
                    val framePtsUs = frameIndex * 1_000_000L / FRAME_RATE
                    drawFrame(frameIndex)
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, framePtsUs * 1_000L)
                    check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
                    frameIndex++
                    if (frameIndex == frames) {
                        codec.signalEndOfInputStream()
                        eosWritten = true
                    }
                }

                val info = MediaCodec.BufferInfo()
                val outputIndex = codec.dequeueOutputBuffer(info, ENCODE_TIMEOUT_MICROS)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "output format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val buffer: ByteBuffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                        val isCodecConfig =
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isEndOfStream =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!isCodecConfig && muxerStarted && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                            encodedFrames++
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (isEndOfStream) {
                            check(muxerStarted) { "EOS before any sample was muxed" }
                            break
                        }
                    }
                }
            }
            check(encodedFrames >= frames / 2) {
                "H.264 encoder produced only $encodedFrames of $frames frames"
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglTerminate(eglDisplay) }
            }
            inputSurface?.release()
        }
        return outputFile.readBytes()
    }

    private fun initEgl(): EGLDisplay {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        return display
    }

    private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                attributes,
                0,
                configs,
                0,
                1,
                configCount,
                0,
            ) && configCount[0] >= 1,
        ) { "eglChooseConfig failed" }
        return checkNotNull(configs[0]) { "no recordable EGL config matched" }
    }

    private fun drawFrame(frameIndex: Int) {
        GLES20.glClearColor((frameIndex * 2 % 255) / 255f, 90f / 255f, 200f / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun hasCodec(mimeType: String, isEncoder: Boolean): Boolean {
        val codec = runCatching {
            if (isEncoder) {
                MediaCodec.createEncoderByType(mimeType)
            } else {
                MediaCodec.createDecoderByType(mimeType)
            }
        }.getOrNull()
        codec?.release()
        return codec != null
    }
}
