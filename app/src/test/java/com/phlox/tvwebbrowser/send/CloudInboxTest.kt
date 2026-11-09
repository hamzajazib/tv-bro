package com.phlox.tvwebbrowser.send

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/** The REST calls of [CloudInbox] against a fake database */
@RunWith(RobolectricTestRunner::class)
class CloudInboxTest {
    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    private val requests = Collections.synchronizedList(ArrayList<String>())
    /** What the fake database answers: the status line and the body for a request line */
    @Volatile
    private var answer: (String) -> Pair<String, String> = { "200 OK" to "null" }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                socket.use {
                    val request = HttpRequestReader.read(socket.getInputStream().buffered()) ?: return@use
                    val body = String(request.body.readBytes())
                    val line = "${request.method} ${request.path}"
                    requests += if (body.isEmpty()) line else "$line $body"
                    val (status, text) = answer(line)
                    val type = if (request.header("accept") == "text/event-stream") "text/event-stream" else "application/json"
                    socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
                        "Date: Mon, 09 Nov 2026 10:00:00 GMT\r\nConnection: close\r\n\r\n$text").toByteArray())
                }
            }
        }
    }

    private val cloud = CloudInbox("http://127.0.0.1:${server.localPort}") { URL(it.toString()).openConnection() as HttpURLConnection }

    @After
    fun tearDown() = server.close()

    @Test
    fun writesGoWhereThePageReadsThem() = runBlocking {
        cloud.putInfo("inbox1", CloudInbox.Info("Living room", listOf("192.168.1.5:8765"), "key1", 100L))
        cloud.putCode("K7Q2M9XP", "inbox1", 200L)
        cloud.deleteMessage("inbox1", "-m1")
        cloud.deleteCode("K7Q2M9XP")
        cloud.deleteInfo("inbox1")
        assertEquals("PUT /inbox/inbox1/info.json", requests[0].substringBefore(" {"))
        val info = JSONObject(requests[0].substringAfter(" {").let { "{$it" })
        assertEquals("Living room", info.getString("name"))
        assertEquals("192.168.1.5:8765", info.getJSONArray("lan").getString(0))
        assertEquals("key1", info.getString("key"))
        assertEquals(100L, info.getLong("exp"))
        assertEquals("PUT /codes/K7Q2M9XP.json {\"inbox\":\"inbox1\",\"exp\":200}", requests[1])
        assertEquals("DELETE /inbox/inbox1/msgs/-m1.json", requests[2])
        assertEquals("DELETE /codes/K7Q2M9XP.json", requests[3])
        assertEquals("DELETE /inbox/inbox1/info.json", requests[4])
        //the clock of the server comes from its Date
        val serverTime = 1794218400000L
        assertTrue(Math.abs(cloud.serverTime() - serverTime) < 5_000)
    }

    @Test
    fun refusedWritesFail() = runBlocking {
        answer = { "401 Unauthorized" to "{\"error\":\"Permission denied\"}" }
        try {
            cloud.putCode("K7Q2M9XP", "inbox1", 200L)
            fail()
        } catch (e: CloudInbox.CloudException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun theStreamGivesTheMessages() = runBlocking {
        answer = {
            "200 OK" to "event: put\ndata: {\"path\":\"/\",\"data\":{\"-a\":{\"text\":\"old\",\"t\":1}}}\n\n" +
                "event: keep-alive\ndata: null\n\n" +
                "event: put\ndata: {\"path\":\"/-b\",\"data\":{\"text\":\"example.com\",\"from\":\"Safari\",\"t\":2}}\n\n" +
                "event: put\ndata: {\"path\":\"/-a\",\"data\":null}\n\n"
        }
        val got = ArrayList<Pair<List<String>, Boolean>>()
        cloud.listen("inbox1") { messages, initial -> got += messages.map { it.text } to initial }
        assertEquals(listOf(listOf("old") to true, listOf("example.com") to false), got)
        assertEquals("GET /inbox/inbox1/msgs.json", requests.single())
    }

    @Test
    fun aCancelledStreamFails() = runBlocking {
        answer = { "200 OK" to "event: cancel\ndata: \"Permission denied\"\n\n" }
        try {
            cloud.listen("inbox1") { _, _ -> }
            fail()
        } catch (e: CloudInbox.CloudException) {
            assertEquals(403, e.status)
        }
    }
}
