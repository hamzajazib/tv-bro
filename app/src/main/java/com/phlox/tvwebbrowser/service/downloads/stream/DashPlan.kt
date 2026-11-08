package com.phlox.tvwebbrowser.service.downloads.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import com.phlox.tvwebbrowser.model.StreamSpec
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.abs

/**
 * The plan of a DASH manifest: the representation of the video chosen and one of audio, in each period (a period
 * is a part: its timestamps start anew). A representation of one file (SegmentBase) is one segment, downloaded
 * whole; those of SegmentList and SegmentTemplate have their segments and init segment.
 */
object DashPlan {
    private val CONTENT_PROTECTION = Regex("<(?:[A-Za-z0-9_]+:)?ContentProtection\\b")

    /**
     * The manifest [text] read from [url]
     * @throws UnsupportedStreamException for a manifest that protects its media: Media3 drops a ContentProtection
     * it can not read (no pssh, no default_KID), so its formats do not tell all of them
     */
    fun parse(text: String, url: String): DashManifest {
        if (CONTENT_PROTECTION.containsMatchIn(text)) throw UnsupportedStreamException(StreamProblem.PROTECTED)
        return DashManifestParser().parse(Uri.parse(url), ByteArrayInputStream(text.toByteArray()))
    }

    /** @throws UnsupportedStreamException for a live (dynamic) or protected manifest */
    fun check(manifest: DashManifest) {
        if (manifest.dynamic) throw UnsupportedStreamException(StreamProblem.LIVE)
        if (manifest.getPeriodCount() == 0) throw UnsupportedStreamException(StreamProblem.EMPTY)
    }

    fun videos(period: Period): List<Representation> = representations(period, C.TRACK_TYPE_VIDEO)

    fun audios(period: Period): List<Representation> = representations(period, C.TRACK_TYPE_AUDIO)

    private fun representations(period: Period, type: Int): List<Representation> =
        period.adaptationSets.filter { it.type == type }.flatMap { it.representations }

    fun isProtected(representation: Representation) = representation.format.drmInitData != null

    /**
     * The video of [spec] in a period: the representation of its id, else of its height and the bitrate nearest
     * to its own
     */
    fun chooseVideo(period: Period, spec: StreamSpec): Representation? {
        val videos = videos(period).filter { !isProtected(it) }
        videos.firstOrNull { it.format.id != null && it.format.id == spec.videoId }?.let { return it }
        val height = spec.height ?: 0
        val sameHeight = videos.filter { it.format.height == height }.ifEmpty { videos }
        return sameHeight.minByOrNull { abs(it.format.bitrate.toLong() - (spec.videoBitrate ?: Int.MAX_VALUE)) }
    }

    /**
     * The audio of [spec] in a period: the representation of its id, else the best of its language, else the
     * default audio ([defaultAudio])
     */
    fun chooseAudio(period: Period, spec: StreamSpec): Representation? {
        val audios = audios(period).filter { !isProtected(it) && StreamMuxer.isSupported(it.format) }
        audios.firstOrNull { it.format.id != null && it.format.id == spec.audioId }?.let { return it }
        spec.audioLanguage?.let { language ->
            audios.filter { it.format.language == language }.maxByOrNull { it.format.bitrate }?.let { return it }
        }
        return defaultAudio(period)
    }

    /**
     * The audio to save with the video: of the language of the system, else the one the manifest marks as the
     * default or main, else the first; the best bitrate of its kind
     */
    fun defaultAudio(period: Period): Representation? {
        val audios = audios(period).filter { !isProtected(it) && StreamMuxer.isSupported(it.format) }
        if (audios.isEmpty()) return null
        val system = Locale.getDefault().language
        val chosen = audios.filter { languageOf(it.format) == system }.ifEmpty {
            audios.filter { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 || it.format.roleFlags and C.ROLE_FLAG_MAIN != 0 }
        }.ifEmpty { audios }
        return chosen.maxByOrNull { it.format.bitrate }
    }

    private fun languageOf(format: Format): String? = format.language?.let { Locale.forLanguageTag(it).language }

    /** The plan of [spec] in [manifest]; @throws UnsupportedStreamException if it has no video it can save */
    fun plan(manifest: DashManifest, spec: StreamSpec): StreamPlan {
        check(manifest)
        val video = TrackBuilder("v")
        val audio = TrackBuilder("a")
        for (i in 0 until manifest.getPeriodCount()) {
            val period = manifest.getPeriod(i)
            val durationUs = manifest.getPeriodDurationUs(i)
            if (durationUs == C.TIME_UNSET) throw UnsupportedStreamException(StreamProblem.LIVE)
            val periodVideo = chooseVideo(period, spec)
            if (periodVideo == null) {
                //a period of audio only (or protected): the file has no room for it
                if (videos(period).any(::isProtected)) throw UnsupportedStreamException(StreamProblem.PROTECTED)
                continue
            }
            video.add(periodVideo, i, period.startMs * 1000, durationUs)
            chooseAudio(period, spec)?.let { audio.add(it, i, period.startMs * 1000, durationUs) }
        }
        if (video.segments.isEmpty()) throw UnsupportedStreamException(StreamProblem.EMPTY)
        val durationUs = (0 until manifest.getPeriodCount()).sumOf { manifest.getPeriodDurationUs(it) }
        return StreamPlan(video.build(durationUs), audio.takeIf { it.segments.isNotEmpty() }?.build(durationUs))
    }

    private class TrackBuilder(val name: String) {
        val segments = ArrayList<TrackPlan.Segment>()
        val inits = ArrayList<TrackPlan.Piece>()

        fun add(representation: Representation, part: Int, periodStartUs: Long, periodDurationUs: Long) {
            val base = representation.baseUrls.first().url
            val index: DashSegmentIndex? = if (representation is Representation.MultiSegmentRepresentation) {
                representation.index
            } else {
                null
            }
            if (index == null) {
                //one file: its init, index and fragments together
                segments += TrackPlan.Segment(segments.size, TrackPlan.Piece(base), null, part, periodStartUs, periodDurationUs)
                return
            }
            val count = index.getSegmentCount(periodDurationUs)
            if (count == DashSegmentIndex.INDEX_UNBOUNDED.toLong()) throw UnsupportedStreamException(StreamProblem.LIVE)
            val init = representation.initializationUri?.let { uri ->
                inits += TrackPlan.Piece(uri.resolveUriString(base), uri.start, uri.length.takeIf { it != C.LENGTH_UNSET.toLong() })
                inits.size - 1
            }
            val first = index.firstSegmentNum
            for (n in first until first + count) {
                val uri = index.getSegmentUrl(n)
                val piece = TrackPlan.Piece(uri.resolveUriString(base), uri.start, uri.length.takeIf { it != C.LENGTH_UNSET.toLong() })
                segments += TrackPlan.Segment(segments.size, piece, init, part, periodStartUs + index.getTimeUs(n),
                    index.getDurationUs(n, periodDurationUs))
            }
        }

        fun build(durationUs: Long) = TrackPlan(name, segments, inits, durationUs)
    }
}
