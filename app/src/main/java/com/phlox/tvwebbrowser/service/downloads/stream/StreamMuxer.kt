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
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer

/**
 * Packs the samples of the segments of a stream into one MP4 file without decoding them. Chunk by chunk, the
 * extractors of Media3 read the segments of the video and, when the audio is a track of its own, those of the audio
 * (each a [Source]: MPEG-TS, fragmented MP4 after its init segment, packed ADTS audio or WebM, found by sniffing),
 * side by side; their samples go to the muxer in the order of their timestamps, which continue from chunk to chunk.
 */
class StreamMuxer(private val muxer: Muxer, private val shouldStop: () -> Boolean = { false }) {
    /** The stream has a track the file can not hold, or no video or audio at all */
    class FormatException(message: String) : IOException(message)

    /** Asked to stop before the file was complete */
    class StoppedException : IOException("Stopped")

    /**
     * The segments of one track of a chunk in order, the init segment first when [hasInit]; [types] are the track
     * types the file takes from it ([C.TRACK_TYPE_VIDEO], [C.TRACK_TYPE_AUDIO]: the audio inside the video segments
     * is dropped when the audio has a source of its own)
     */
    class Source(val input: InputStream, val hasInit: Boolean, val types: Set<Int>)

    companion object {
        /**
         * Added to all timestamps, so the samples shown before the first one decoded (B-frames) are not negative;
         * both muxers move the earliest one to 0.
         */
        private const val TIME_OFFSET_US = 10_000_000L
        private val VIDEO_AND_AUDIO = setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)

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

    /** The muxer tracks by type */
    private val muxerTracks = HashMap<Int, Int>()
    private val formats = HashMap<Int, Format>()
    private var samples = 0

    /**
     * The tracks are added to the muxer once all video and audio tracks of the first chunk have their formats (the
     * fragmented muxer takes no track after the first sample); the samples wait in the queues of their sources
     */
    private var started = false

    /** The sources of the chunk being written: the muxer starts when all of them know their tracks */
    private var chunkReaders: List<SourceReader> = emptyList()

    private fun startIfReady() {
        if (!started && chunkReaders.all { it.knowsTracks }) start()
    }

    private class Sample(val type: Int, val data: ByteBuffer, val info: BufferInfo)

    /**
     * Writes the samples of one chunk with only its video segments (the audio inside them, if any): [input] is its
     * segments in order (the init segment first when [fragmentedMp4]), whose first sample is shown at [startUs]
     */
    fun writeChunk(input: InputStream, fragmentedMp4: Boolean, startUs: Long) =
        writeChunk(listOf(Source(input, fragmentedMp4, VIDEO_AND_AUDIO)), startUs)

    /** Writes the samples of one chunk of [sources] (the video and the audio), shown from [startUs] of the stream */
    fun writeChunk(sources: List<Source>, startUs: Long) {
        //the timestamps of the sources keep their distances: one adjuster maps them all
        val adjuster = TimestampAdjuster(TIME_OFFSET_US + startUs)
        val readers = sources.map { SourceReader(it, adjuster, TIME_OFFSET_US + startUs) }
        chunkReaders = readers
        try {
            while (true) {
                if (shouldStop()) throw StoppedException()
                drain(readers)
                val active = readers.filter { !it.ended }
                if (active.isEmpty()) break
                //the source the order of the samples waits for, else the one with the fewest samples waiting
                val next = (if (started) active.firstOrNull { it.queue.isEmpty() } else null)
                    ?: active.minBy { it.queue.size }
                next.read()
            }
            //a track without a format is not in the file
            if (!started && readers.any { it.queue.isNotEmpty() }) start()
            drain(readers)
        } finally {
            readers.forEach { it.release() }
        }
    }

    /** Ends the file; @throws FormatException if no sample was written */
    fun finish() {
        if (samples == 0) throw FormatException("No video or audio")
        muxer.close()
    }

    private fun start() {
        started = true
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
            val format = formats[type] ?: continue
            if (!isSupported(format)) throw FormatException("Unsupported format ${format.sampleMimeType}")
            muxerTracks[type] = muxer.addTrack(format)
        }
    }

    /**
     * Writes the waiting samples in the order of their timestamps, as long as each source still reading has one
     * waiting (the next one of a source may be earlier than those of the others)
     */
    private fun drain(readers: List<SourceReader>) {
        if (!started) return
        while (true) {
            if (readers.any { !it.ended && it.queue.isEmpty() }) return
            val reader = readers.filter { it.queue.isNotEmpty() }.minByOrNull { it.queue.first().info.presentationTimeUs } ?: return
            write(reader.queue.removeFirst())
        }
    }

    private fun write(sample: Sample) {
        //a track that comes after the start is not in the file
        val track = muxerTracks[sample.type] ?: return
        muxer.writeSampleData(track, sample.data, sample.info)
        samples++
    }

    /** The extractor of a source, the first that recognizes its bytes, and the samples it gave */
    private inner class SourceReader(source: Source, adjuster: TimestampAdjuster, timeOffsetUs: Long) : ExtractorOutput {
        val queue = ArrayDeque<Sample>()
        var ended = false
            private set
        private val types = source.types
        private val outputs = ArrayList<SampleOutput>()
        private var tracksEnded = false
        private val input: ExtractorInput = DefaultExtractorInput(InputReader(source.input), 0, C.LENGTH_UNSET.toLong())
        private val position = PositionHolder()
        /** Added to the timestamps of extractors that do not take the adjuster (they count from 0) */
        private var addedTimeUs = 0L
        private val extractor: Extractor

        init {
            val candidates: List<Pair<Extractor, Boolean>> = if (source.hasInit) {
                listOf(fragmentedMp4(adjuster) to false, MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED) to true)
            } else {
                listOf(ts(adjuster) to false, AdtsExtractor() to true, fragmentedMp4(adjuster) to false,
                    MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED) to true)
            }
            val found = candidates.firstOrNull { (candidate, _) ->
                val recognized = try {
                    candidate.sniff(input)
                } catch (e: EOFException) {
                    false
                }
                input.resetPeekPosition()
                recognized
            } ?: throw FormatException("Unknown format of segments")
            extractor = found.first
            if (found.second) addedTimeUs = timeOffsetUs
            candidates.forEach { if (it.first !== extractor) it.first.release() }
            extractor.init(this)
        }

        private fun fragmentedMp4(adjuster: TimestampAdjuster) =
            FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0, adjuster, null, emptyList(), null)

        private fun ts(adjuster: TimestampAdjuster) =
            TsExtractor(TsExtractor.MODE_SINGLE_PMT, 0, SubtitleParser.Factory.UNSUPPORTED, adjuster,
                DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)

        /** Reads one step: some samples, or the end */
        fun read() {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> ended = true
                Extractor.RESULT_SEEK -> throw FormatException("The stream asks to seek")
            }
        }

        fun release() = extractor.release()

        override fun track(id: Int, type: Int): TrackOutput {
            if (type !in types) return IgnoredOutput
            //the first track of each type
            if (outputs.any { it.type == type }) return IgnoredOutput
            return SampleOutput(type, this).also { outputs += it }
        }

        override fun endTracks() {
            tracksEnded = true
            this@StreamMuxer.startIfReady()
        }

        override fun seekMap(seekMap: SeekMap) {}

        /** It has found all its tracks and their formats */
        val knowsTracks: Boolean get() = tracksEnded && outputs.all { formats[it.type] != null }

        fun add(type: Int, data: ByteBuffer, timeUs: Long, flags: Int) {
            queue.addLast(Sample(type, data, BufferInfo(timeUs + addedTimeUs, data.remaining(), flags and C.BUFFER_FLAG_KEY_FRAME)))
        }
    }

    /** Collects the bytes of samples until their metadata comes, then queues them */
    private inner class SampleOutput(val type: Int, private val reader: SourceReader) : TrackOutput {
        private var buffer = ByteArray(64 * 1024)
        private var length = 0

        override fun format(format: Format) {
            val known = formats[type]
            if (known == null) {
                formats[type] = format
                startIfReady()
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
                throw EOFException()
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
                reader.add(type, sample, timeUs, flags)
            }
            //the bytes of the samples that follow
            System.arraycopy(buffer, start + size, buffer, 0, offset)
            length = offset
        }
    }

    /** Tracks the file does not keep (metadata, captions, audio that has a source of its own) */
    private object IgnoredOutput : TrackOutput {
        private val skip = ByteArray(4096)

        override fun format(format: Format) {}

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val count = input.read(skip, 0, minOf(length, skip.size))
            if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
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
