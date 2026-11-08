package com.phlox.tvwebbrowser.service.downloads.stream

import com.phlox.tvwebbrowser.model.StreamSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashPlanTest {
    private val url = "https://cdn.example.com/film/manifest.mpd"

    private fun spec(height: Int? = null, videoId: String? = null, audioId: String? = null, language: String? = null) =
        StreamSpec(StreamSpec.Kind.DASH, url, height = height, videoId = videoId, audioId = audioId, audioLanguage = language)

    private val twoPeriods = """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S" minBufferTime="PT2S">
          <Period id="ad" start="PT0S" duration="PT4S">
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate timescale="1000" duration="2000" initialization="ad/${'$'}RepresentationID${'$'}/init.mp4" media="ad/${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s" startNumber="1"/>
              <Representation id="ad-720" codecs="avc1.4d401f" bandwidth="2000000" width="1280" height="720"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
              <SegmentTemplate timescale="1000" duration="2000" initialization="ad/a/init.mp4" media="ad/a/${'$'}Number${'$'}.m4s" startNumber="1"/>
              <Representation id="ad-a" codecs="mp4a.40.2" bandwidth="128000" audioSamplingRate="48000"/>
            </AdaptationSet>
          </Period>
          <Period id="film" start="PT4S" duration="PT6S">
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate timescale="1000" duration="3000" initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s" startNumber="5"/>
              <Representation id="1080" codecs="avc1.640028" bandwidth="5000000" width="1920" height="1080"/>
              <Representation id="720" codecs="avc1.4d401f" bandwidth="2500000" width="1280" height="720"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
              <SegmentTemplate timescale="1000" duration="3000" initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s" startNumber="1"/>
              <Representation id="en" codecs="mp4a.40.2" bandwidth="128000" audioSamplingRate="48000"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="de">
              <SegmentTemplate timescale="1000" duration="3000" initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/${'$'}Number${'$'}.m4s" startNumber="1"/>
              <Representation id="de" codecs="mp4a.40.2" bandwidth="128000" audioSamplingRate="48000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun plansTheChosenRepresentationsOfEachPeriod() {
        val plan = DashPlan.plan(DashPlan.parse(twoPeriods, url), spec(height = 720, videoId = "720", audioId = "de"))
        val video = plan.video
        //the ad has no representation of the id: the one of the height
        assertEquals(listOf("ad/ad-720/1.m4s", "ad/ad-720/2.m4s", "720/5.m4s", "720/6.m4s"),
            video.segments.map { it.piece.url.removePrefix("https://cdn.example.com/film/") })
        assertEquals(listOf(0, 0, 1, 1), video.segments.map { it.part })
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L, 7_000_000L), video.segments.map { it.startUs })
        assertEquals(listOf("ad/ad-720/init.mp4", "720/init.mp4"), video.inits.map { it.url.removePrefix("https://cdn.example.com/film/") })
        assertEquals(listOf(0, 0, 1, 1), video.segments.map { it.init })
        assertEquals(10_000_000L, plan.durationUs)
        assertEquals(2, video.chunks.size)

        val audio = plan.audio!!
        assertEquals(listOf("ad/a/1.m4s", "ad/a/2.m4s", "de/1.m4s", "de/2.m4s"),
            audio.segments.map { it.piece.url.removePrefix("https://cdn.example.com/film/") })
        //each chunk of video with the audio of its period
        assertEquals(listOf(0 to 0, 1 to 1), plan.chunkPairs.map { (v, a) -> v.part to a!!.part })
    }

    @Test
    fun withoutAnIdTheHeightAndTheLanguageChoose() {
        val plan = DashPlan.plan(DashPlan.parse(twoPeriods, url), spec(height = 1080, language = "en"))
        assertEquals("1080/5.m4s", plan.video.segments[2].piece.url.substringAfter("film/"))
        assertEquals("en/1.m4s", plan.audio!!.segments[2].piece.url.substringAfter("film/"))
    }

    @Test
    fun aRepresentationOfOneFileIsOneSegment() {
        val plan = DashPlan.plan(DashPlan.parse("""
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT60S" minBufferTime="PT2S">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" codecs="avc1.4d401f" bandwidth="1000000" width="640" height="360">
                    <BaseURL>video_360.mp4</BaseURL>
                    <SegmentBase indexRange="800-1000"><Initialization range="0-799"/></SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent(), url), spec())
        val segment = plan.video.segments.single()
        assertEquals("https://cdn.example.com/film/video_360.mp4", segment.piece.url)
        assertNull(segment.piece.length)
        assertNull(segment.init)
        assertEquals(60_000_000L, segment.durationUs)
        assertNull(plan.audio)
    }

    @Test
    fun refusesLiveAndProtectedManifests() {
        fun problemOf(text: String): StreamProblem? = try {
            DashPlan.plan(DashPlan.parse(text, url), spec())
            null
        } catch (e: UnsupportedStreamException) {
            e.problem
        }
        assertEquals(StreamProblem.LIVE, problemOf("""
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z" minBufferTime="PT2S">
              <Period start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4">
                <SegmentTemplate timescale="1000" duration="2000" media="${'$'}Number${'$'}.m4s"/>
                <Representation id="v" codecs="avc1.4d401f" bandwidth="1000000" width="640" height="360"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()))
        assertEquals(StreamProblem.PROTECTED, problemOf("""
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static" mediaPresentationDuration="PT60S" minBufferTime="PT2S">
              <Period><AdaptationSet contentType="video" mimeType="video/mp4">
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"><cenc:pssh>AAAAQ3Bzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAACMIARIQ6lGEn/MRT5mCtPVkB6UvPRoIY2FzdGxhYnMiAQ==</cenc:pssh></ContentProtection>
                <SegmentTemplate timescale="1000" duration="2000" media="${'$'}Number${'$'}.m4s"/>
                <Representation id="v" codecs="avc1.4d401f" bandwidth="1000000" width="640" height="360"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()))
    }

    @Test
    fun specWithAudioGoesThroughJson() {
        val spec = StreamSpec(StreamSpec.Kind.DASH, url, height = 720, bitrate = 2_128_000, duration = 10.0,
            videoId = "720", videoBitrate = 2_000_000, audioId = "en", audioLanguage = "en")
        assertEquals(spec, StreamSpec.fromJson(spec.toJson()))
        val hls = StreamSpec(StreamSpec.Kind.HLS, url, audioUrl = "https://cdn.example.com/a.m3u8")
        assertEquals(hls, StreamSpec.fromJson(hls.toJson()))
        if (StreamSpec.fromJson("""{"kind":"HLS","url":"x"}""") == null) fail()
    }
}
