package com.phlox.tvwebbrowser.send

import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class LocalSendServerTest {
    private val key = "secret-key"
    private val texts = Collections.synchronizedList(ArrayList<Pair<String, String>>())
    private val files = Collections.synchronizedList(ArrayList<Received>())
    /** The error the next file gets, null to take it */
    @Volatile
    private var refuseWith: String? = null

    class Received(val name: String, val type: String?, val size: Long, val from: String) {
        val bytes = ByteArrayOutputStream()
        @Volatile
        var failed: String? = null
    }

    private val host = object : LocalSendServer.Host {
        override fun asset(path: String) =
            if (path == "/index.html") LocalSendServer.Asset("<html>page</html>".toByteArray(), "text/html; charset=utf-8") else null

        override fun onText(text: String, from: String) {
            texts += text to from
        }

        override suspend fun receiveFile(name: String, mimeType: String?, size: Long, from: String): LocalSendServer.IncomingFile {
            refuseWith?.let { throw SendException(it) }
            val received = Received(name, mimeType, size, from)
            files += received
            return object : LocalSendServer.IncomingFile {
                override val output: OutputStream = received.bytes
                override fun fail(reason: String) {
                    received.failed = reason
                }
                override suspend fun awaitSaved() = "saved-" + received.name
            }
        }
    }

    private val server = LocalSendServer(key, host, Dispatchers.Unconfined, InetAddress.getLoopbackAddress())
    private val port = server.start(0)

    @After
    fun tearDown() = server.stop()

    private class Response(val status: Int, val body: String, val headers: Map<String, List<String>>)

    private fun request(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        return Response(status, text, connection.headerFields.filterKeys { it != null })
    }

    @Test
    fun servesThePage() {
        assertTrue(port > 0)
        val page = request("GET", "/")
        assertEquals(200, page.status)
        assertEquals("<html>page</html>", page.body)
        assertEquals(404, request("GET", "/../etc/passwd").status)
    }

    /** HttpURLConnection does not send Origin: the request is written by hand */
    private fun ping(origin: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: tv\r\nOrigin: $origin\r\n\r\n".toByteArray())
        String(socket.getInputStream().readBytes())
    }

    @Test
    fun theSiteAsksWhetherTheTvIsThere() {
        val answer = ping("https://tvbro.phlox.dev")
        assertTrue(answer, answer.startsWith("HTTP/1.1 200"))
        assertTrue(answer, answer.contains("\r\nAccess-Control-Allow-Origin: https://tvbro.phlox.dev\r\n"))
        assertEquals("tvbro", JSONObject(answer.substringAfter("\r\n\r\n")).getString("app"))
        //other sites can not read the answer
        assertFalse(ping("https://evil.example").contains("Access-Control-Allow-Origin"))
    }

    @Test
    fun textsNeedTheKey() {
        val body = JSONObject().put("text", "example.com").put("from", "Chrome\u0000 · Android").toString().toByteArray()
        assertEquals(403, request("POST", "/text", body).status)
        assertEquals(403, request("POST", "/text", body, mapOf(LocalSendServer.KEY_HEADER to "wrong")).status)
        assertTrue(texts.isEmpty())
        assertEquals(200, request("POST", "/text", body, mapOf(LocalSendServer.KEY_HEADER to key)).status)
        assertEquals(listOf("example.com" to "Chrome · Android"), texts.toList())
        val empty = JSONObject().put("text", " ").toString().toByteArray()
        assertEquals(400, request("POST", "/text", empty, mapOf(LocalSendServer.KEY_HEADER to key)).status)
    }

    @Test
    fun filesAreReceivedWhole() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val response = request("PUT", "/file?name=..%2Fphoto%3F.jpg&type=image%2Fjpeg&from=Safari", bytes,
            mapOf(LocalSendServer.KEY_HEADER to key))
        assertEquals(200, response.status)
        assertEquals("saved-photo.jpg", JSONObject(response.body).getString("name"))
        val file = files.single()
        assertEquals("photo.jpg", file.name)
        assertEquals("image/jpeg", file.type)
        assertEquals(bytes.size.toLong(), file.size)
        assertEquals("Safari", file.from)
        assertArrayEquals(bytes, file.bytes.toByteArray())
        assertNull(file.failed)
    }

    @Test
    fun aRefusedFileIsAnswered() {
        refuseWith = SendRules.NEED_PERMISSION
        val response = request("PUT", "/file?name=a.txt", "abc".toByteArray(), mapOf(LocalSendServer.KEY_HEADER to key))
        assertEquals(409, response.status)
        assertEquals(SendRules.NEED_PERMISSION, JSONObject(response.body).getString("error"))
    }

    @Test
    fun aCutFileFails() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val head = "PUT /file?name=big.bin HTTP/1.1\r\nHost: tv\r\n${LocalSendServer.KEY_HEADER}: $key\r\n" +
                "Content-Length: 1000\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(ByteArray(10))
                flush()
            }
            socket.shutdownOutput()
            //the answer comes after the server saw the end
            val answer = String(socket.getInputStream().readBytes())
            assertTrue(answer, answer.startsWith("HTTP/1.1 500"))
        }
        assertEquals("not received whole", files.single().failed)
    }

    @Test
    fun brokenRequestsAreRefused() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            assertTrue(String(socket.getInputStream().readBytes()).startsWith("HTTP/1.1 400"))
        }
    }
}
