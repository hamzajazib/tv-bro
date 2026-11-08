package com.phlox.tvwebbrowser.service.downloads.stream

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer

/**
 * Packs the samples of the segments of a stream into one MP4 file without decoding them: the extractors of
 * Media3 read each chunk of segments (MPEG-TS, or fragmented MP4 after its init segment) and its video and audio
 * samples go to the muxer, their timestamps continuing from chunk to chunk.
 */
class StreamMuxer(private val muxer: Muxer, private val shouldStop: () -> Boolean = { false }) {
    /** The stream has a track the file can not hold, or no video or audio at all */
    class FormatException(message: String) : IOException(message)

    /** Asked to stop before the file was complete */
    class StoppedException : IOException("Stopped")

    companion object {
        /**
         * Added to all timestamps, so the samples shown before the first one decoded (B-frames) are not negative;
         * the MP4 muxer moves the earliest one to 0.
         */
        private const val TIME_OFFSET_US = 10_000_000L

        /**
         * A muxer writing to [output]: a regular MP4 file (with its index, as players like it) when the output can
         * seek, a fragmented one else (a provider of the Storage Access Framework that gives a pipe)
         */
        fun muxerFor(output: FileOutputStream): Muxer {
            val seekable = try {
                output.channel.position()
                output.channel.size()
                true
            } catch (e: IOException) {
                false
            }
            return if (seekable) Mp4Muxer.Builder(SeekableMuxerOutput.of(output)).build()
                else FragmentedMp4Muxer.Builder(output.channel).build()
        }

        fun isSupported(format: Format): Boolean {
            val mime = format.sampleMimeType ?: return false
            return when {
                MimeTypes.isVideo(mime) -> mime in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES
                MimeTypes.isAudio(mime) -> mime in Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES
                else -> false
            }
        }
    }

    /** The muxer tracks by type ([C.TRACK_TYPE_VIDEO], [C.TRACK_TYPE_AUDIO]) */
    private val muxerTracks = HashMap<Int, Int>()
    private val formats = HashMap<Int, Format>()
    private var samples = 0

    /**
     * Writes the samples of one chunk: [input] is its segments in order (the init segment first for fMP4), whose
     * first sample is shown at [startUs] of the stream
     */
    fun writeChunk(input: InputStream, fragmentedMp4: Boolean, startUs: Long) {
        val adjuster = TimestampAdjuster(TIME_OFFSET_US + startUs)
        val extractor: Extractor = if (fragmentedMp4) {
            FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0, adjuster, null,
                emptyList(), null)
        } else {
            TsExtractor(TsExtractor.MODE_SINGLE_PMT, 0, SubtitleParser.Factory.UNSUPPORTED, adjuster,
                DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
        }
        val output = ChunkOutput()
        extractor.init(output)
        val extractorInput: ExtractorInput = DefaultExtractorInput(InputReader(input), 0, C.LENGTH_UNSET.toLong())
        val recognized = try {
            extractor.sniff(extractorInput)
        } catch (e: java.io.EOFException) {
            false
        }
        if (!recognized) {
            throw FormatException(if (fragmentedMp4) "Not fragmented MP4" else "Not MPEG-TS")
        }
        extractorInput.resetPeekPosition()
        val position = PositionHolder()
        try {
            while (true) {
                if (shouldStop()) throw StoppedException()
                when (extractor.read(extractorInput, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> throw FormatException("The stream asks to seek")
                }
            }
            //a track without a format is not in the file
            if (!started && pending.isNotEmpty()) start()
        } finally {
            extractor.release()
        }
    }

    /** Ends the file; @throws FormatException if no sample was written */
    fun finish() {
        if (samples == 0) throw FormatException("No video or audio")
        muxer.close()
    }

    /**
     * The tracks are added to the muxer once all video and audio tracks of the first chunk have their formats (the
     * fragmented muxer takes no track after the first sample); the samples that come before wait in [pending]
     */
    private var started = false
    private val pending = ArrayList<Triple<Int, ByteBuffer, BufferInfo>>()

    private fun start() {
        started = true
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
            val format = formats[type] ?: continue
            if (!isSupported(format)) throw FormatException("Unsupported format ${format.sampleMimeType}")
            muxerTracks[type] = muxer.addTrack(format)
        }
        for ((type, sample, info) in pending) write(type, sample, info)
        pending.clear()
    }

    private fun write(type: Int, sample: ByteBuffer, info: BufferInfo) {
        //a track that comes after the start is not in the file
        val track = muxerTracks[type] ?: return
        muxer.writeSampleData(track, sample, info)
        samples++
    }

    /** The tracks of one extractor */
    private inner class ChunkOutput : ExtractorOutput {
        val outputs = ArrayList<SampleOutput>()
        private var ended = false

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) return IgnoredOutput
            //the first track of each type
            if (outputs.any { it.type == type }) return IgnoredOutput
            return SampleOutput(type, this).also { outputs += it }
        }

        override fun endTracks() {
            ended = true
            startIfReady()
        }

        override fun seekMap(seekMap: SeekMap) {}

        fun startIfReady() {
            if (!started && ended && outputs.all { formats[it.type] != null }) start()
        }
    }

    /** Collects the bytes of samples until their metadata comes, then writes them */
    private inner class SampleOutput(val type: Int, private val chunk: ChunkOutput) : TrackOutput {
        private var buffer = ByteArray(64 * 1024)
        private var length = 0

        override fun format(format: Format) {
            val known = formats[type]
            if (known == null) {
                formats[type] = format
                chunk.startIfReady()
            } else if (known.sampleMimeType != format.sampleMimeType) {
                //a track of the file has one format
                throw FormatException("The format changes from ${known.sampleMimeType} to ${format.sampleMimeType}")
            }
        }

        private fun ensureCapacity(more: Int) {
            if (length + more > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + more))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            ensureCapacity(length)
            val count = input.read(buffer, this.length, length)
            if (count == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            this.length += count
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensureCapacity(length)
            data.readBytes(buffer, this.length, length)
            this.length += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val start = length - offset - size
            if (start < 0) throw FormatException("Broken sample")
            if (size > 0) {
                val sample = ByteBuffer.allocateDirect(size)
                sample.put(buffer, start, size)
                sample.flip()
                val info = BufferInfo(timeUs, size, flags and C.BUFFER_FLAG_KEY_FRAME)
                if (started) write(type, sample, info) else pending += Triple(type, sample, info)
            }
            //the bytes of the samples that follow
            System.arraycopy(buffer, start + size, buffer, 0, offset)
            length = offset
        }
    }

    /** Tracks the file does not keep (metadata, captions) */
    private object IgnoredOutput : TrackOutput {
        private val skip = ByteArray(4096)

        override fun format(format: Format) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val count = input.read(skip, 0, minOf(length, skip.size))
            if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
    }

    private class InputReader(private val input: InputStream) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = input.read(buffer, offset, length)
            return if (count < 0) C.RESULT_END_OF_INPUT else count
        }
    }
}

/** [inputs] read one after another */
fun sequenceOf(inputs: List<() -> InputStream>): InputStream {
    val iterator = inputs.iterator()
    return SequenceInputStream(object : java.util.Enumeration<InputStream> {
        override fun hasMoreElements() = iterator.hasNext()
        override fun nextElement() = iterator.next()()
    })
}
