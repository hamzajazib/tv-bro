package com.phlox.tvwebbrowser.service.downloads.stream

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels

/**
 * Packs the segments of tiny HLS streams (2 s of 10 fps H.264 with B-frames and AAC, made by ffmpeg) into MP4 files and
 * reads them back
 */
@RunWith(RobolectricTestRunner::class)
class StreamMuxerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("streams/$path")!!.use { it.readBytes() }

    private fun inputs(vararg paths: String): InputStream =
        sequenceOf(paths.map { path -> { ByteArrayInputStream(resource(path)) as InputStream } })

    /** The samples of a track of the file: their timestamps (µs) and whether they are key frames */
    private class Track(var format: Format? = null, val times: ArrayList<Long> = ArrayList(), val keys: ArrayList<Boolean> = ArrayList())

    private fun read(file: File, extractor: Extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)): Map<Int, Track> {
        val tracks = HashMap<Int, Track>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = Track()
                tracks[type] = track
                return object : TrackOutput {
                    override fun format(format: Format) {
                        track.format = format
                    }

                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                        val buffer = ByteArray(length)
                        return input.read(buffer, 0, length)
                    }

                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        track.times += timeUs
                        track.keys += flags and C.BUFFER_FLAG_KEY_FRAME != 0
                    }
                }
            }

            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        FileInputStream(file).use { stream ->
            var input = DefaultExtractorInput({ b, o, l -> stream.read(b, o, l).let { if (it < 0) C.RESULT_END_OF_INPUT else it } },
                0, file.length())
            val position = PositionHolder()
            while (true) {
                when (extractor.read(input, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        stream.channel.position(position.position)
                        input = DefaultExtractorInput({ b, o, l -> stream.read(b, o, l).let { if (it < 0) C.RESULT_END_OF_INPUT else it } },
                            position.position, file.length())
                    }
                }
            }
        }
        return tracks
    }

    private fun pack(fragmented: Boolean, vararg chunks: Pair<Long, Array<String>>): File {
        val file = folder.newFile("out.mp4")
        val output = FileOutputStream(file)
        val muxer = StreamMuxer(StreamMuxer.muxerFor(output))
        for ((start, paths) in chunks) muxer.writeChunk(inputs(*paths), fragmented, start)
        muxer.finish()
        return file
    }

    private fun checkTracks(tracks: Map<Int, Track>, seconds: Int, maxStartUs: Long = 100_000) {
        val video = tracks[C.TRACK_TYPE_VIDEO]!!
        val audio = tracks[C.TRACK_TYPE_AUDIO]!!
        assertEquals(MimeTypes.VIDEO_H264, video.format!!.sampleMimeType)
        assertEquals(64, video.format!!.width)
        assertEquals(MimeTypes.AUDIO_AAC, audio.format!!.sampleMimeType)
        assertEquals(10 * seconds, video.times.size)
        //the B-frames make the order of decoding differ from that of showing
        assertTrue(video.times != video.times.sorted())
        val shown = video.times.sorted()
        assertTrue("starts at ${shown.first()}", shown.first() <= maxStartUs)
        assertTrue("audio starts at ${audio.times.first()}", audio.times.first() <= maxStartUs)
        //frames 100 ms apart, the whole stream long
        assertEquals(100_000L * (10 * seconds - 1), shown.last() - shown.first(), 2_000.0)
        assertTrue(video.keys.first())
        assertTrue(audio.times.zipWithNext().all { (a, b) -> b > a })
        assertEquals(seconds * 1_000_000.0, audio.times.last().toDouble(), 100_000.0)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) = assertEquals(expected.toDouble(), actual.toDouble(), delta)

    @Test
    fun packsMpegTsSegments() {
        checkTracks(read(pack(false, 0L to arrayOf("ts/s0.ts", "ts/s1.ts"))), 2)
    }

    @Test
    fun packsFragmentedMp4SegmentsAfterTheirInitSegment() {
        //ffmpeg starts the tracks with empty edits, which Media3 (also its player) does not apply to fragments: the
        //video starts after the delay of its B-frames
        checkTracks(read(pack(true, 0L to arrayOf("fmp4/init.mp4", "fmp4/s0.m4s", "fmp4/s1.m4s"))), 2, maxStartUs = 200_000)
    }

    @Test
    fun aChunkAfterADiscontinuityContinuesTheTimeline() {
        //the same segments again, as after a discontinuity: their timestamps start anew in the stream
        val tracks = read(pack(false, 0L to arrayOf("ts/s0.ts", "ts/s1.ts"), 2_000_000L to arrayOf("ts/s0.ts", "ts/s1.ts")))
        checkTracks(tracks, 4)
    }

    @Test
    fun aFragmentedFileStartsAtZero() {
        val file = folder.newFile("out.mp4")
        //an output that can not seek, as some providers of the Storage Access Framework give
        val muxer = StreamMuxer(FragmentedMp4Muxer.Builder(Channels.newChannel(FileOutputStream(file))).build())
        muxer.writeChunk(inputs("ts/s0.ts", "ts/s1.ts"), false, 0)
        muxer.finish()
        val t = read(file, FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED))
        checkTracks(t, 2)
    }

    @Test
    fun refusesWhatIsNotMpegTs() {
        val muxer = StreamMuxer(StreamMuxer.muxerFor(FileOutputStream(folder.newFile("out.mp4"))))
        try {
            muxer.writeChunk(ByteArrayInputStream("#EXTM3U\n".repeat(100).toByteArray()), false, 0)
            fail()
        } catch (e: StreamMuxer.FormatException) {
        }
    }

    @Test
    fun stopsWhenAsked() {
        val muxer = StreamMuxer(StreamMuxer.muxerFor(FileOutputStream(folder.newFile("out.mp4")))) { true }
        try {
            muxer.writeChunk(inputs("ts/s0.ts"), false, 0)
            fail()
        } catch (e: StreamMuxer.StoppedException) {
        }
    }
}
