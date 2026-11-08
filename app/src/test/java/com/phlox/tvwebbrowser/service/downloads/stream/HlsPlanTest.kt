package com.phlox.tvwebbrowser.service.downloads.stream

import com.phlox.tvwebbrowser.model.StreamSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class HlsPlanTest {
    private val base = "https://cdn.example.com/film/720/index.m3u8"

    private fun problemOf(text: String): HlsPlan.Problem? = try {
        HlsPlan.parse(text, base)
        null
    } catch (e: HlsPlan.UnsupportedStreamException) {
        e.problem
    }

    @Test
    fun plansSegmentsWithKeysAndRanges() {
        val plan = HlsPlan.parse("""
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="/keys/k1"
            #EXTINF:6.0,
            s7.ts
            #EXT-X-KEY:METHOD=AES-128,URI="k2",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4.5,
            #EXT-X-BYTERANGE:1000@200
            https://other.example.com/all.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2.0,
            s9.ts
            #EXT-X-ENDLIST
        """.trimIndent(), base)
        assertEquals(3, plan.segments.size)
        assertEquals(12_500_000L, plan.durationUs)
        assertTrue(plan.inits.isEmpty())

        val first = plan.segments[0]
        assertEquals("https://cdn.example.com/film/720/s7.ts", first.piece.url)
        assertNull(first.piece.length)
        assertEquals("https://cdn.example.com/keys/k1", first.piece.key!!.url)
        //no IV: the media sequence number
        assertArrayEquals(ByteArray(15) + byteArrayOf(7), first.piece.key!!.iv)
        assertEquals(0L, first.startUs)

        val second = plan.segments[1]
        assertEquals("https://other.example.com/all.ts", second.piece.url)
        assertEquals(200L, second.piece.offset)
        assertEquals(1000L, second.piece.length)
        assertEquals("https://cdn.example.com/film/720/k2", second.piece.key!!.url)
        assertArrayEquals(ByteArray(16) { it.toByte() }, second.piece.key!!.iv)
        assertEquals(6_000_000L, second.startUs)

        assertNull(plan.segments[2].piece.key)
        assertEquals(1, plan.chunks.size)
    }

    @Test
    fun aDiscontinuityOrAnotherInitSegmentStartsAChunk() {
        val plan = HlsPlan.parse("""
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init1.mp4"
            #EXTINF:4,
            a.m4s
            #EXTINF:4,
            b.m4s
            #EXT-X-DISCONTINUITY
            #EXT-X-MAP:URI="init2.mp4"
            #EXTINF:4,
            c.m4s
            #EXT-X-GAP
            #EXTINF:4,
            missing.m4s
            #EXT-X-ENDLIST
        """.trimIndent(), base)
        //the gap is not downloaded
        assertEquals(listOf("a.m4s", "b.m4s", "c.m4s"), plan.segments.map { it.piece.url.substringAfterLast('/') })
        assertEquals(listOf("init1.mp4", "init2.mp4"), plan.inits.map { it.url.substringAfterLast('/') })
        val chunks = plan.chunks
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].init)
        assertEquals(2, chunks[0].segments.size)
        assertEquals(1, chunks[1].init)
        assertEquals(8_000_000L, chunks[1].startUs)
    }

    @Test
    fun refusesWhatCanNotBeSaved() {
        //no end: live
        assertEquals(HlsPlan.Problem.LIVE, problemOf("""
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            a.ts
        """.trimIndent()))
        assertEquals(HlsPlan.Problem.PROTECTED, problemOf("""
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key"
            #EXTINF:4,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        assertEquals(HlsPlan.Problem.PROTECTED, problemOf("""
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",URI="data:text/plain;base64,AAAA"
            #EXTINF:4,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        assertEquals(HlsPlan.Problem.NOT_MEDIA, problemOf("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()))
    }

    @Test
    fun aes128WithAnIdentityKeyIsNotProtected() {
        assertFalse(HlsPlan.isProtected("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n"))
        assertFalse(HlsPlan.isProtected("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"identity\"\n"))
        assertFalse(HlsPlan.isProtected("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n"))
        assertTrue(HlsPlan.isProtected("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"\n"))
    }

    @Test
    fun decryptsAes128Segments() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val iv = HlsPlan.ivOf(null, 42)
        val data = ByteArray(1000) { (it % 251).toByte() }
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
        val decrypted = CipherInputStream(ByteArrayInputStream(encrypted), HlsCrypto.cipher(key, iv)).readBytes()
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun ivOfAShortHexIsPaddedOnTheLeft() {
        assertArrayEquals(ByteArray(15) + byteArrayOf(0x1f), HlsPlan.ivOf("0x1F", 0))
    }

    @Test
    fun specGoesThroughJson() {
        val spec = StreamSpec(StreamSpec.Kind.HLS, base, height = 720, bitrate = 2_000_000, duration = 60.0)
        assertEquals(spec, StreamSpec.fromJson(spec.toJson()))
        assertEquals(15_000_000L, spec.estimatedSize)
        assertEquals(StreamSpec(StreamSpec.Kind.HLS, base), StreamSpec.fromJson(StreamSpec(StreamSpec.Kind.HLS, base).toJson()))
        assertNull(StreamSpec.fromJson("""{"kind":"SMOOTH","url":"x"}"""))
        assertNull(StreamSpec.fromJson("nonsense"))
        if (StreamSpec(StreamSpec.Kind.HLS, base).estimatedSize != 0L) fail()
    }
}
