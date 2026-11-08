package com.phlox.tvwebbrowser.service.downloads.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayInputStream

/** The [TrackPlan] of an HLS media playlist */
object HlsPlan {
    /** `#EXT-X-KEY` lines, to find the encryption Media3 ignores (SAMPLE-AES with an identity key) */
    private val KEY_LINE = Regex("^#EXT-X-(?:SESSION-)?KEY:(.*)$", RegexOption.MULTILINE)
    private val METHOD = Regex("METHOD=([A-Z0-9-]+)")
    private val KEYFORMAT = Regex("KEYFORMAT=\"([^\"]*)\"")

    /**
     * The plan of the media playlist [text] read from [url]
     * @throws UnsupportedStreamException for a live, protected or empty stream, or a multivariant playlist
     */
    fun parse(text: String, url: String, name: String = "v"): TrackPlan {
        if (isProtected(text)) throw UnsupportedStreamException(StreamProblem.PROTECTED)
        val playlist = HlsPlaylistParser().parse(Uri.parse(url), ByteArrayInputStream(text.toByteArray()))
        if (playlist is HlsMultivariantPlaylist) throw UnsupportedStreamException(StreamProblem.NOT_MEDIA)
        return of(playlist as HlsMediaPlaylist, name)
    }

    /** The encryption of segments other than AES-128 with an identity key: DRM or SAMPLE-AES */
    fun isProtected(text: String): Boolean = KEY_LINE.findAll(text).any { line ->
        val attributes = line.groupValues[1]
        val method = METHOD.find(attributes)?.groupValues?.get(1) ?: return@any false
        val keyFormat = KEYFORMAT.find(attributes)?.groupValues?.get(1) ?: "identity"
        method != "NONE" && (method != "AES-128" || keyFormat != "identity")
    }

    fun of(playlist: HlsMediaPlaylist, name: String = "v"): TrackPlan {
        if (!playlist.hasEndTag && playlist.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_VOD) {
            throw UnsupportedStreamException(StreamProblem.LIVE)
        }
        if (playlist.protectionSchemes != null || playlist.segments.any { it.drmInitData != null }) {
            throw UnsupportedStreamException(StreamProblem.PROTECTED)
        }
        val base = playlist.baseUri
        val inits = ArrayList<TrackPlan.Piece>()
        val initIndexes = HashMap<HlsMediaPlaylist.Segment, Int>()
        val segments = ArrayList<TrackPlan.Segment>()
        playlist.segments.forEachIndexed { i, segment ->
            if (segment.hasGapTag) return@forEachIndexed
            val mediaSequence = playlist.mediaSequence + i
            val init = segment.initializationSegment?.let { initSegment ->
                initIndexes.getOrPut(initSegment) {
                    inits += pieceOf(base, initSegment, mediaSequence)
                    inits.size - 1
                }
            }
            segments += TrackPlan.Segment(segments.size, pieceOf(base, segment, mediaSequence), init,
                playlist.discontinuitySequence + segment.relativeDiscontinuitySequence,
                segment.relativeStartTimeUs, segment.durationUs)
        }
        if (segments.isEmpty()) throw UnsupportedStreamException(StreamProblem.EMPTY)
        return TrackPlan(name, segments, inits, playlist.durationUs)
    }

    private fun pieceOf(base: String, segment: HlsMediaPlaylist.Segment, mediaSequence: Long): TrackPlan.Piece {
        val key = segment.fullSegmentEncryptionKeyUri?.let {
            TrackPlan.Key(UriUtil.resolve(base, it), ivOf(segment.encryptionIV, mediaSequence))
        }
        val length = segment.byteRangeLength.takeIf { it != C.LENGTH_UNSET.toLong() }
        return TrackPlan.Piece(UriUtil.resolve(base, segment.url), if (length != null) segment.byteRangeOffset else 0, length, key)
    }

    /** The IV of the key tag (`0x…`), else the media sequence number of the segment, both as 16 bytes */
    fun ivOf(iv: String?, mediaSequence: Long): ByteArray {
        val result = ByteArray(16)
        if (iv != null) {
            val hex = iv.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
            for (i in 0 until 16) result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var value = mediaSequence
            for (i in 15 downTo 8) {
                result[i] = (value and 0xFF).toByte()
                value = value shr 8
            }
        }
        return result
    }
}
