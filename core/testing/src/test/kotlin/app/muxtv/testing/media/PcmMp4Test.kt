package app.muxtv.testing.media

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class PcmMp4Test {
    @Test
    fun singleTrackFileHasConsistentBoxStructure() {
        val sampleCount = 4_800
        val bytes = PcmMp4.build(
            tracks = listOf(PcmMp4.Track(trackId = 1, sampleCount = sampleCount)),
            samplesPerChunk = 16,
        )

        val top = boxes(bytes)
        assertThat(top.keys).containsExactly("ftyp", "moov", "mdat")
        assertThat(top.values.flatten().sumOf { it.size }).isEqualTo(bytes.size)

        val moovChildren = childrenOf(moov(bytes))
        assertThat(moovChildren.keys).containsExactly("mvhd", "trak")
        assertThat(moovChildren["trak"]).hasSize(1)

        val stbl = stbl(bytes)
        assertThat(stbl.keys).containsExactly("stsd", "stts", "stsc", "stsz", "stco")

        assertThat(readU32(stbl["stsd"]!!, 12)).isEqualTo(1)
        assertThat(fourCc(stbl["stsd"]!!, 20)).isEqualTo("sowt")
        assertThat(readU32(stbl["stts"]!!, 12)).isEqualTo(1)
        assertThat(readU32(stbl["stts"]!!, 16)).isEqualTo(sampleCount)
        assertThat(readU32(stbl["stts"]!!, 20)).isEqualTo(PcmMp4.FRAMES_PER_SAMPLE)
        assertThat(readU32(stbl["stsz"]!!, 12)).isEqualTo(PcmMp4.BYTES_PER_SAMPLE)
        assertThat(readU32(stbl["stsz"]!!, 16)).isEqualTo(sampleCount)
    }

    @Test
    fun chunkOffsetsPointInsideMdatPayload() {
        val sampleCount = 4_800
        val samplesPerChunk = 16
        val bytes = PcmMp4.build(
            tracks = listOf(PcmMp4.Track(trackId = 1, sampleCount = sampleCount)),
            samplesPerChunk = samplesPerChunk,
        )

        val mdat = topBox(bytes, "mdat")
        val mdatPayloadStart = bytes.indexOfArray(mdat) + 8
        val mdatPayloadEnd = bytes.indexOfArray(mdat) + mdat.size

        val stco = stbl(bytes)["stco"]!!
        val chunkCount = readU32(stco, 12).toInt()
        assertThat(chunkCount).isEqualTo(sampleCount / samplesPerChunk)
        for (index in 0 until chunkCount) {
            val offset = readU32(stco, 16 + index * 4)
            assertThat(offset).isAtLeast(mdatPayloadStart.toLong())
            assertThat(offset).isLessThan(mdatPayloadEnd.toLong())
        }
        val mdatBox = topBox(bytes, "mdat")
        val expectedPayload = sampleCount * PcmMp4.BYTES_PER_SAMPLE
        assertThat(mdatBox.size - 8).isEqualTo(expectedPayload)
    }

    @Test
    fun syncTableFollowsConfiguredIntervalAndIsOmittedForAllSync() {
        val allSync = PcmMp4.build(
            tracks = listOf(PcmMp4.Track(trackId = 1, sampleCount = 800)),
            samplesPerChunk = 16,
        )
        assertThat(stbl(allSync)).doesNotContainKey("stss")

        val interval = 80
        val sparseSync = PcmMp4.build(
            tracks = listOf(PcmMp4.Track(trackId = 1, sampleCount = 800, syncSampleInterval = interval)),
            samplesPerChunk = 16,
        )
        val stss = stbl(sparseSync)["stss"]!!
        val entryCount = readU32(stss, 12).toInt()
        assertThat(entryCount).isEqualTo(800 / interval)
        assertThat(readU32(stss, 16)).isEqualTo(1)
        assertThat(readU32(stss, 16 + 4)).isEqualTo(interval + 1)
    }

    @Test
    fun dualTrackFileCarriesTwoTracksWithInterleavedOffsets() {
        val sampleCount = 4_800
        val samplesPerChunk = 16
        val bytes = PcmMp4.build(
            tracks = listOf(
                PcmMp4.Track(trackId = 1, sampleCount = sampleCount),
                PcmMp4.Track(trackId = 2, sampleCount = sampleCount),
            ),
            samplesPerChunk = samplesPerChunk,
        )

        val moovChildren = childrenOf(moov(bytes))
        assertThat(moovChildren["trak"]).hasSize(2)

        val chunksPerTrack = sampleCount / samplesPerChunk
        val chunkBytes = samplesPerChunk * PcmMp4.BYTES_PER_SAMPLE
        val firstTrackOffsets = chunkOffsets(bytes, trackIndex = 0)
        val secondTrackOffsets = chunkOffsets(bytes, trackIndex = 1)
        assertThat(firstTrackOffsets).hasSize(chunksPerTrack)
        assertThat(secondTrackOffsets).hasSize(chunksPerTrack)
        for (chunk in 0 until chunksPerTrack) {
            val first = firstTrackOffsets[chunk]
            val second = secondTrackOffsets[chunk]
            assertThat(first).isLessThan(second)
            assertThat(second - first).isEqualTo(chunkBytes.toLong())
        }
    }

    @Test
    fun durationMillisMatchesSampleCount() {
        assertThat(PcmMp4.durationMillis(4_800)).isEqualTo(60_000)
        assertThat(PcmMp4.durationMillis(800)).isEqualTo(10_000)
    }

    private fun chunkOffsets(bytes: ByteArray, trackIndex: Int): List<Long> {
        val traks = childrenOf(moov(bytes))["trak"]!!
        val stco = stblOfTrack(traks[trackIndex])["stco"]!!
        val count = readU32(stco, 12).toInt()
        return (0 until count).map { readU32(stco, 16 + it * 4) }
    }

    private fun stbl(bytes: ByteArray): Map<String, ByteArray> {
        val traks = childrenOf(moov(bytes))["trak"]!!
        return stblOfTrack(traks.first())
    }

    private fun stblOfTrack(trak: ByteArray): Map<String, ByteArray> {
        val mdia = childrenOf(trak)["mdia"]!!.first()
        val minf = childrenOf(mdia)["minf"]!!.first()
        val stbl = childrenOf(minf)["stbl"]!!.first()
        return childrenOf(stbl).mapValues { it.value.first() }
    }

    private fun moov(bytes: ByteArray): ByteArray = topBox(bytes, "moov")

    private fun topBox(bytes: ByteArray, type: String): ByteArray =
        boxes(bytes)[type]!!.first()

    private fun childrenOf(box: ByteArray): Map<String, List<ByteArray>> =
        boxes(box, startOffset = BOX_HEADER_SIZE)

    private fun boxes(
        parent: ByteArray,
        startOffset: Int = 0,
    ): Map<String, List<ByteArray>> {
        val result = linkedMapOf<String, MutableList<ByteArray>>()
        var offset = startOffset
        while (offset + 8 <= parent.size) {
            val size = readU32(parent, offset).toInt()
            val type = fourCc(parent, offset + 4)
            require(size >= 8 && offset + size <= parent.size) {
                "malformed box $type at $offset"
            }
            result.getOrPut(type) { mutableListOf() }
                .add(parent.copyOfRange(offset, offset + size))
            offset += size
        }
        require(offset == parent.size) { "trailing bytes after boxes" }
        return result
    }

    private fun ByteArray.indexOfArray(target: ByteArray): Int {
        for (index in 0..size - target.size) {
            if (copyOfRange(index, index + target.size).contentEquals(target)) return index
        }
        return -1
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private companion object {
        const val BOX_HEADER_SIZE = 8
    }
}
