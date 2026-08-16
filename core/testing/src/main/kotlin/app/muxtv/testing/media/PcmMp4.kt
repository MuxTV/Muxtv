package app.muxtv.testing.media

import java.io.ByteArrayOutputStream

/**
 * Deterministic, decode-free progressive MP4 fixture for playback evidence.
 *
 * The generator writes a minimal ISO BMFF file with one or two PCM audio tracks (`sowt` sample
 * entry: 16-bit signed little-endian, 8 kHz, mono). Raw PCM is consumed by the Media3 audio sink
 * directly, so the fixture plays on any emulator or device without vendor codec dependencies —
 * no MediaCodec, no binary corpus assets in the repository.
 *
 * Sample tables are written explicitly (stts/stsc/stsz/stco/stss), so seek-map shape is fully
 * controlled: [Track.syncSampleInterval] puts a sync sample every N samples (N = 1 means every
 * sample is a sync point and stss is omitted), which lets tests compare
 * `SeekParameters.DEFAULT` (exact target) against `SeekParameters.CLOSEST_SYNC` (nearest keyframe).
 *
 * All samples are silence (zero payload); playback advances in real time through the audio sink.
 */
object PcmMp4 {
    const val SAMPLE_RATE_HZ = 8_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val FRAMES_PER_SAMPLE = 100
    const val BYTES_PER_SAMPLE = FRAMES_PER_SAMPLE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
    const val MOVIE_TIMESCALE = 1_000L
    private const val SAMPLE_DURATION_TICKS = FRAMES_PER_SAMPLE.toLong()

    data class Track(
        val trackId: Int,
        val sampleCount: Int,
        val syncSampleInterval: Int = 1,
    )

    /**
     * Builds an MP4 with the given tracks interleaved chunk-by-chunk.
     *
     * All tracks must share the same sample count, divisible by [samplesPerChunk].
     */
    fun build(
        tracks: List<Track>,
        samplesPerChunk: Int = 16,
    ): ByteArray {
        require(tracks.isNotEmpty()) { "at least one track is required" }
        require(samplesPerChunk > 0) { "samplesPerChunk must be positive" }
        tracks.forEach { track ->
            require(track.sampleCount > 0) { "sampleCount must be positive" }
            require(track.sampleCount % samplesPerChunk == 0) {
                "sampleCount must be divisible by samplesPerChunk"
            }
            require(track.syncSampleInterval >= 1) { "syncSampleInterval must be >= 1" }
            require(track.trackId > 0) { "trackId must be positive" }
        }

        val chunksPerTrack = tracks.map { it.sampleCount / samplesPerChunk }
        val ftyp = ftypBox()
        val probeMoov = moovBox(tracks, chunksPerTrack, samplesPerChunk, dummyOffsets(tracks, chunksPerTrack))
        val mdatHeaderSize = 8L
        val mdatPayloadStart = ftyp.size.toLong() + probeMoov.size.toLong() + mdatHeaderSize

        val offsets = mutableMapOf<Int, MutableList<Long>>()
        var payloadCursor = 0L
        val maxChunks = chunksPerTrack.max()
        for (chunk in 0 until maxChunks) {
            for ((trackIndex, track) in tracks.withIndex()) {
                if (chunk >= chunksPerTrack[trackIndex]) continue
                offsets.getOrPut(track.trackId) { mutableListOf() }
                    .add(mdatPayloadStart + payloadCursor)
                payloadCursor += samplesPerChunk.toLong() * BYTES_PER_SAMPLE
            }
        }

        val moov = moovBox(tracks, chunksPerTrack, samplesPerChunk, offsets)
        val mdat = mdatBox(tracks)
        return ftyp + moov + mdat
    }

    fun durationMillis(sampleCount: Int): Long =
        sampleCount.toLong() * SAMPLE_DURATION_TICKS * MOVIE_TIMESCALE / SAMPLE_RATE_HZ

    private fun dummyOffsets(tracks: List<Track>, chunksPerTrack: List<Int>): Map<Int, List<Long>> =
        tracks.withIndex().associate { (index, track) ->
            track.trackId to List(chunksPerTrack[index]) { 0L }
        }

    private fun ftypBox(): ByteArray = box("ftyp") { writer ->
        writer.writeFourCc("isom")
        writer.writeU32(0x200)
        writer.writeFourCc("isom")
        writer.writeFourCc("iso2")
        writer.writeFourCc("mp41")
    }

    private fun moovBox(
        tracks: List<Track>,
        chunksPerTrack: List<Int>,
        samplesPerChunk: Int,
        offsets: Map<Int, List<Long>>,
    ): ByteArray = box("moov") { writer ->
        writer.writeBox("mvhd") { mvhd ->
            mvhd.writeU32(0)
            mvhd.writeU32(0)
            mvhd.writeU32(0)
            mvhd.writeU32(MOVIE_TIMESCALE)
            mvhd.writeU32(durationMillis(tracks.first().sampleCount))
            mvhd.writeU32(0x0001_0000)
            mvhd.writeU16(0x0100)
            mvhd.writeU16(0)
            mvhd.writeU32(0)
            mvhd.writeU16(0)
            mvhd.writeU16(0)
            mvhd.writeIdentityMatrix()
            repeat(6) { mvhd.writeU32(0) }
            mvhd.writeU32(tracks.size + 1)
        }
        for ((trackIndex, track) in tracks.withIndex()) {
            writer.writeBox("trak") { trak ->
                trak.writeBox("tkhd") { tkhd ->
                    tkhd.writeU32(0x0000_0003)
                    tkhd.writeU32(0)
                    tkhd.writeU32(0)
                    tkhd.writeU32(track.trackId)
                    tkhd.writeU32(0)
                    tkhd.writeU32(durationMillis(track.sampleCount))
                    tkhd.writeU32(0)
                    tkhd.writeU32(0)
                    tkhd.writeU16(0)
                    tkhd.writeU16(0)
                    tkhd.writeU16(0x0100)
                    tkhd.writeU16(0)
                    tkhd.writeIdentityMatrix()
                    tkhd.writeU32(0)
                    tkhd.writeU32(0)
                }
                trak.writeBox("mdia") { mdia ->
                    mdia.writeBox("mdhd") { mdhd ->
                        mdhd.writeU32(0)
                        mdhd.writeU32(0)
                        mdhd.writeU32(0)
                        mdhd.writeU32(SAMPLE_RATE_HZ)
                        mdhd.writeU32(track.sampleCount * SAMPLE_DURATION_TICKS)
                        mdhd.writeU16(0x55C4)
                        mdhd.writeU16(0)
                    }
                    mdia.writeBox("hdlr") { hdlr ->
                        hdlr.writeU32(0)
                        hdlr.writeU32(0)
                        hdlr.writeFourCc("soun")
                        hdlr.writeU32(0)
                        hdlr.writeU32(0)
                        hdlr.writeU32(0)
                        hdlr.writeBytes(("SoundHandler\u0000").toByteArray(Charsets.US_ASCII))
                    }
                    mdia.writeBox("minf") { minf ->
                        minf.writeBox("smhd") { smhd ->
                            smhd.writeU32(0)
                            smhd.writeU16(0)
                            smhd.writeU16(0)
                        }
                        minf.writeBox("dinf") { dinf ->
                            dinf.writeBox("dref") { dref ->
                                dref.writeU32(0)
                                dref.writeU32(1)
                                dref.writeBox("url ") { url ->
                                    url.writeU32(0x0000_0001)
                                }
                            }
                        }
                        minf.writeBox("stbl") { stbl ->
                            stbl.writeBox("stsd") { stsd ->
                                stsd.writeU32(0)
                                stsd.writeU32(1)
                                stsd.writeAudioSampleEntry()
                            }
                            stbl.writeBox("stts") { stts ->
                                stts.writeU32(0)
                                stts.writeU32(1)
                                stts.writeU32(track.sampleCount)
                                stts.writeU32(SAMPLE_DURATION_TICKS)
                            }
                            stbl.writeBox("stsc") { stsc ->
                                stsc.writeU32(0)
                                stsc.writeU32(1)
                                stsc.writeU32(1)
                                stsc.writeU32(samplesPerChunk)
                                stsc.writeU32(1)
                            }
                            stbl.writeBox("stsz") { stsz ->
                                stsz.writeU32(0)
                                stsz.writeU32(BYTES_PER_SAMPLE)
                                stsz.writeU32(track.sampleCount)
                            }
                            stbl.writeBox("stco") { stco ->
                                val trackOffsets = offsets[track.trackId]
                                    ?: error("missing offsets for track ${track.trackId}")
                                stco.writeU32(0)
                                stco.writeU32(trackOffsets.size)
                                trackOffsets.forEach { stco.writeU32(it) }
                            }
                            if (track.syncSampleInterval > 1) {
                                val syncEntries = (1..track.sampleCount step track.syncSampleInterval).toList()
                                stbl.writeBox("stss") { stss ->
                                    stss.writeU32(0)
                                    stss.writeU32(syncEntries.size)
                                    syncEntries.forEach { stss.writeU32(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mdatBox(tracks: List<Track>): ByteArray {
        val payloadSize = tracks.sumOf { it.sampleCount } * BYTES_PER_SAMPLE
        val output = ByteArrayOutputStream(8 + payloadSize)
        val header = BoxWriter()
        header.writeU32(8 + payloadSize)
        header.writeFourCc("mdat")
        output.write(header.bytes())
        output.write(ByteArray(payloadSize))
        return output.toByteArray()
    }

    private fun box(type: String, body: (BoxWriter) -> Unit): ByteArray {
        val content = BoxWriter()
        body(content)
        val bytes = content.bytes()
        val header = BoxWriter()
        header.writeU32(8 + bytes.size)
        header.writeFourCc(type)
        val output = ByteArrayOutputStream(8 + bytes.size)
        output.write(header.bytes())
        output.write(bytes)
        return output.toByteArray()
    }

    private class BoxWriter {
        private val output = ByteArrayOutputStream()

        fun writeU8(value: Int) {
            output.write(value and 0xFF)
        }

        fun writeU16(value: Int) {
            output.write((value ushr 8) and 0xFF)
            output.write(value and 0xFF)
        }

        fun writeU32(value: Long) {
            output.write(((value ushr 24) and 0xFF).toInt())
            output.write(((value ushr 16) and 0xFF).toInt())
            output.write(((value ushr 8) and 0xFF).toInt())
            output.write((value and 0xFF).toInt())
        }

        fun writeU32(value: Int) = writeU32(value.toLong())

        fun writeFourCc(value: String) {
            require(value.length == 4) { "fourcc must be 4 chars: $value" }
            value.forEach { output.write(it.code and 0xFF) }
        }

        fun writeBytes(bytes: ByteArray) {
            output.write(bytes)
        }

        fun writeIdentityMatrix() {
            writeU32(0x0001_0000)
            writeU32(0)
            writeU32(0)
            writeU32(0)
            writeU32(0x0001_0000)
            writeU32(0)
            writeU32(0)
            writeU32(0)
            writeU32(0x4000_0000)
        }

        fun writeAudioSampleEntry() {
            writeU32(36)
            writeFourCc("sowt")
            writeBytes(ByteArray(6))
            writeU16(1)
            writeU16(0)
            writeU16(0)
            writeU32(0)
            writeU16(CHANNEL_COUNT)
            writeU16(BITS_PER_SAMPLE)
            writeU16(0)
            writeU16(0)
            writeU32((SAMPLE_RATE_HZ shl 16).toLong())
        }

        fun writeBox(type: String, body: (BoxWriter) -> Unit) {
            val content = BoxWriter()
            body(content)
            val bytes = content.bytes()
            writeU32(8 + bytes.size)
            writeFourCc(type)
            writeBytes(bytes)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
