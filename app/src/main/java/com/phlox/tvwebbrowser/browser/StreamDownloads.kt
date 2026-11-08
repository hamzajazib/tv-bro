package com.phlox.tvwebbrowser.browser

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.StreamSpec
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.service.downloads.stream.DashPlan
import com.phlox.tvwebbrowser.service.downloads.stream.HlsPlan
import com.phlox.tvwebbrowser.service.downloads.stream.StreamMuxer
import com.phlox.tvwebbrowser.service.downloads.stream.StreamProblem
import com.phlox.tvwebbrowser.service.downloads.stream.UnsupportedStreamException
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads of video streams of pages (HLS, DASH) as MP4 files: the qualities a stream offers, read from its
 * playlists or manifest before the download starts, and the request of the one chosen. The download service
 * ([VideoStreamDownloadTask][com.phlox.tvwebbrowser.service.downloads.stream.VideoStreamDownloadTask]) saves it.
 */
object StreamDownloads {
    private const val MAX_PLAYLIST_SIZE = 8 * 1024 * 1024
    private const val TIMEOUT = 10_000
    private const val MAX_NAME_LENGTH = 100
    const val MIME_TYPE = "video/mp4"

    /** A quality to download: its [spec], and its height and bitrate for the label (0 if unknown) */
    data class Option(val spec: StreamSpec, val height: Int, val bitrate: Int)

    sealed interface Result {
        /** The qualities, the best first */
        data class Options(val options: List<Option>) : Result
        /** The stream can not be saved: one of [Download.Error] (live, protected, its format, a connection error) */
        data class Refused(val error: String) : Result
    }

    fun isDownloadable(video: PageVideo): Boolean = video.kind == PageVideo.Kind.HLS || video.kind == PageVideo.Kind.DASH

    /** Reads the playlists of the [video] with its headers and the [cookies] of the engine (null for GeckoView) */
    suspend fun options(video: PageVideo, cookies: String?): Result = withContext(Dispatchers.IO) {
        val headers = ExternalVideoPlayer.headers(video, cookies)
        try {
            val text = read(video.url, headers)
            if (video.kind == PageVideo.Kind.DASH) return@withContext dash(DashPlan.parse(text, video.url), video.url)
            if (HlsPlan.isProtected(text)) return@withContext Result.Refused(Download.Error.PROTECTED)
            when (val playlist = HlsPlaylistParser().parse(Uri.parse(video.url), ByteArrayInputStream(text.toByteArray()))) {
                is HlsMultivariantPlaylist -> multivariant(playlist, headers)
                is HlsMediaPlaylist -> {
                    val plan = HlsPlan.of(playlist)
                    Result.Options(listOf(Option(StreamSpec(StreamSpec.Kind.HLS, video.url, height = video.height,
                        duration = plan.durationUs / 1e6), video.height ?: 0, 0)))
                }
                else -> Result.Refused(Download.Error.FORMAT)
            }
        } catch (e: UnsupportedStreamException) {
            Result.Refused(errorOf(e.problem))
        } catch (e: HttpCodeException) {
            Result.Refused(Download.Error.http(e.code))
        } catch (e: androidx.media3.common.ParserException) {
            Result.Refused(Download.Error.FORMAT)
        } catch (e: IOException) {
            Result.Refused(Download.Error.CONNECTION)
        }
    }

    /**
     * The variants, each with its audio: inside its segments, or the rendition of its group ([audioOf]). The
     * playlist of the best one tells whether the stream can be saved and its duration.
     */
    private fun multivariant(playlist: HlsMultivariantPlaylist, headers: Map<String, String>): Result {
        if (playlist.sessionKeyDrmInitData.isNotEmpty()) return Result.Refused(Download.Error.PROTECTED)
        val variants = playlist.variants
            .distinctBy { it.url }
            .sortedWith(compareByDescending<HlsMultivariantPlaylist.Variant> { it.format.height }
                .thenByDescending { bitrateOf(it.format) })
        if (variants.isEmpty()) return Result.Refused(Download.Error.FORMAT)
        val best = variants.first().url.toString()
        val text = read(best, headers)
        if (HlsPlan.isProtected(text)) return Result.Refused(Download.Error.PROTECTED)
        val duration = HlsPlan.parse(text, best).durationUs / 1e6
        return Result.Options(variants.map { variant ->
            val height = variant.format.height.takeIf { it > 0 }
            val bitrate = bitrateOf(variant.format)
            Option(StreamSpec(StreamSpec.Kind.HLS, variant.url.toString(), height = height,
                bitrate = bitrate.takeIf { it > 0 }, duration = duration,
                audioUrl = audioOf(playlist, variant)?.url?.toString()), height ?: 0, bitrate)
        })
    }

    /**
     * The audio rendition of the group of [variant] (null when its audio is inside its segments): of the language
     * of the system, else the default one, else the first
     */
    fun audioOf(playlist: HlsMultivariantPlaylist, variant: HlsMultivariantPlaylist.Variant): HlsMultivariantPlaylist.Rendition? {
        val group = variant.audioGroupId ?: return null
        val renditions = playlist.audios.filter { it.groupId == group && it.url != null }
        if (renditions.isEmpty()) return null
        val system = Locale.getDefault().language
        return renditions.firstOrNull { rendition ->
            rendition.format.language?.let { Locale.forLanguageTag(it).language } == system
        } ?: renditions.firstOrNull { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
            ?: renditions.first()
    }

    /** H.264 first, as most players take it, then H.265, then the others */
    private val codecOrder = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)

    private fun codecRank(format: Format): Int = codecOrder.indexOf(format.sampleMimeType).let { if (it < 0) codecOrder.size else it }

    /**
     * The qualities of a DASH manifest: the heights of the video of its first period with video, each with the
     * representation of the codec most players take ([codecOrder]) and its best bitrate, with the default audio
     * ([DashPlan.defaultAudio])
     */
    private fun dash(manifest: DashManifest, url: String): Result {
        DashPlan.check(manifest)
        val period = (0 until manifest.getPeriodCount()).map { manifest.getPeriod(it) }
            .firstOrNull { DashPlan.videos(it).isNotEmpty() } ?: return Result.Refused(Download.Error.FORMAT)
        val all = DashPlan.videos(period)
        val videos = all.filter { !DashPlan.isProtected(it) && StreamMuxer.isSupported(it.format) }
        if (videos.isEmpty()) {
            return Result.Refused(if (all.any(DashPlan::isProtected)) Download.Error.PROTECTED else Download.Error.FORMAT)
        }
        val audio = DashPlan.defaultAudio(period)
        val durationUs = if (manifest.durationMs != C.TIME_UNSET) manifest.durationMs * 1000
            else (0 until manifest.getPeriodCount()).sumOf { manifest.getPeriodDurationUs(it) }
        val best = videos.groupBy { it.format.height }.values
            .map { sameHeight -> sameHeight.minWith(compareBy<Representation> { codecRank(it.format) }.thenByDescending { it.format.bitrate }) }
            .sortedByDescending { it.format.height }
        return Result.Options(best.map { video ->
            val height = video.format.height.takeIf { it > 0 }
            val videoBitrate = video.format.bitrate.takeIf { it > 0 }
            val bitrate = (videoBitrate ?: 0) + (audio?.format?.bitrate?.coerceAtLeast(0) ?: 0)
            Option(StreamSpec(StreamSpec.Kind.DASH, url, height = height, bitrate = bitrate.takeIf { it > 0 },
                duration = durationUs.takeIf { it > 0 }?.let { it / 1e6 }, videoId = video.format.id,
                videoBitrate = videoBitrate, audioId = audio?.format?.id, audioLanguage = audio?.format?.language),
                height ?: 0, bitrate)
        })
    }

    /** The average bitrate, which tells the size, else the peak one */
    private fun bitrateOf(format: Format): Int =
        if (format.averageBitrate > 0) format.averageBitrate else maxOf(format.peakBitrate, format.bitrate)

    fun errorOf(problem: StreamProblem): String = when (problem) {
        StreamProblem.LIVE -> Download.Error.LIVE
        StreamProblem.PROTECTED -> Download.Error.PROTECTED
        else -> Download.Error.FORMAT
    }

    /**
     * The download of [option] of the [video] as an MP4 file named after its [title] (the title of the frame or the
     * tab) and its quality
     */
    fun request(video: PageVideo, option: Option, title: String?, cookies: String?): DownloadRequest {
        val quality = if (option.height > 0) " ${option.height}p" else ""
        val name = fileName(title?.takeIf { it.isNotBlank() } ?: ExternalVideoPlayer.title(video)) + quality + ".mp4"
        return DownloadRequest(url = video.url, referer = video.pageUrl ?: "", userAgent = video.userAgent,
            cookies = cookies, fileName = name, mimeType = MIME_TYPE, size = option.spec.estimatedSize,
            streamSpec = option.spec)
    }

    /** [title] as a name of a file: no characters file systems refuse, no extension of a stream */
    fun fileName(title: String): String {
        var name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        val extension = MimeTypeMap.getFileExtensionFromUrl(name)
        if (extension.equals("m3u8", ignoreCase = true) || extension.equals("mpd", ignoreCase = true)) {
            name = name.dropLast(extension.length + 1)
        }
        name = name.take(MAX_NAME_LENGTH).trim().trim('.')
        return name.ifEmpty { "video" }
    }

    private class HttpCodeException(val code: Int) : IOException("HTTP $code")

    private fun read(url: String, headers: Map<String, String>): String {
        val connection = AppProxy.open(URL(url)) as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpCodeException(code)
            connection.inputStream.use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    result.write(buffer, 0, count)
                    if (result.size() > MAX_PLAYLIST_SIZE) throw IOException("The playlist is too big")
                }
                return result.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
