package com.phlox.tvwebbrowser.send

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections

/**
 * The server of the TV in the local network, plain HTTP: browsers of the same network open its page
 * (`http://<address>:<port>/#k=<key>`, the page of the site links there) and send texts and files from it.
 * The page is of the same origin, so it works in any browser; the page of the site (https) can only ask
 * [PING] (Chrome, after the user allows the local network). The key in the fragment of the link goes
 * with each request ([KEY_HEADER]): other devices of the network can open the page but send nothing.
 * The traffic is not encrypted.
 *
 * - `GET /`, `GET /send.js`: the page ([Host.asset])
 * - `GET /ping`: whether the TV is reachable, for the origins of the site ([PING_ORIGINS])
 * - `POST /text` `{"text":…,"from":…}`: opens the text as if typed into the address bar
 * - `PUT /file?name=…&type=…&from=…`: the bytes of a file (with Content-Length), saved as a download;
 *   answered when it is saved, with its `name` (the TV may rename it)
 *
 * Errors are answered as `{"error":<code of SendRules>}`.
 */
class LocalSendServer(
    private val key: String,
    private val host: Host,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val bindAddress: InetAddress? = null
) {
    interface Host {
        /** A file of the page of the TV, null if there is none */
        fun asset(path: String): Asset?
        /** On the main thread */
        fun onText(text: String, from: String)
        /** On the main thread; @throws SendException */
        suspend fun receiveFile(name: String, mimeType: String?, size: Long, from: String): IncomingFile
    }

    class Asset(val bytes: ByteArray, val contentType: String)

    /** Where the bytes of a sent file go */
    interface IncomingFile {
        /** Closed by the server when the file is whole */
        val output: OutputStream
        /** The file did not come whole */
        fun fail(reason: String)
        /** @return the name the file was saved with; @throws SendException */
        suspend fun awaitSaved(): String
    }

    companion object {
        private const val TAG = "LocalSendServer"
        const val PING = "/ping"
        const val KEY_HEADER = "X-TVBro-Key"
        /** The page of the site asks whether the TV is reachable */
        val PING_ORIGINS = setOf("https://tvbro.phlox.dev", "https://tv-bro-3546c.web.app", "https://tv-bro-3546c.firebaseapp.com")
        private const val READ_TIMEOUT = 30_000
        private const val MAX_CONNECTIONS = 8
        /** A JSON body of a text, far above [SendRules.MAX_TEXT_LENGTH] */
        private const val MAX_TEXT_BODY = 32 * 1024
        /** Read after an error before the bytes of a file, so the browser gets the answer instead of a reset */
        private const val MAX_DRAIN = 8L * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = Semaphore(MAX_CONNECTIONS)
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** 0 while it does not listen */
    @Volatile
    var port = 0
        private set

    /** Listens on [preferredPort] if it is free, else on another one; @return the port, 0 if it can not listen */
    fun start(preferredPort: Int): Int {
        if (serverSocket != null) return port
        val socket = bind(preferredPort) ?: return 0
        serverSocket = socket
        port = socket.localPort
        acceptJob = scope.launch {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += client
                launch {
                    connections.withPermit { handle(client) }
                }
            }
        }
        return port
    }

    /** Takes no new requests; those in progress (a file that is coming) go on */
    fun stopListening() {
        port = 0
        try {
            serverSocket?.close()
        } catch (e: IOException) {
        }
        serverSocket = null
    }

    /** Takes no new requests and cuts those in progress */
    fun stop() {
        stopListening()
        synchronized(sockets) {
            sockets.forEach {
                try {
                    it.close()
                } catch (e: IOException) {
                }
            }
        }
        scope.cancel()
    }

    private fun bind(preferred: Int): ServerSocket? {
        for (port in listOf(preferred, 0).distinct()) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, port))
                }
            } catch (e: IOException) {
                Log.w(TAG, "can not listen on $port", e)
            }
        }
        return null
    }

    private suspend fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = READ_TIMEOUT
                socket.tcpNoDelay = true
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream()
                val request = try {
                    HttpRequestReader.read(input) ?: return
                } catch (e: HttpException) {
                    respond(output, e.status, error("bad_request"))
                    return
                }
                route(request, output)
            }
        } catch (e: SocketException) {
            //closed by stop(), or by the browser
        } catch (e: IOException) {
            Log.d(TAG, "connection: $e")
        } catch (e: Exception) {
            //any device of the network can connect: nothing it sends may stop the app
            Log.e(TAG, "connection", e)
        } finally {
            sockets -= socket
        }
    }

    private suspend fun route(request: HttpRequest, output: OutputStream) {
        val path = request.path
        when {
            path == PING -> {
                val origin = request.header("origin")?.takeIf { it in PING_ORIGINS }
                val headers = buildMap {
                    if (origin != null) {
                        put("Access-Control-Allow-Origin", origin)
                        put("Vary", "Origin")
                        //the preflight of Private Network Access, which older Chrome versions send
                        put("Access-Control-Allow-Private-Network", "true")
                        put("Access-Control-Allow-Methods", "GET")
                    }
                }
                if (request.method == "OPTIONS") respond(output, 204, null, headers)
                else respond(output, 200, JSONObject().put("app", "tvbro"), headers)
            }
            request.method == "GET" -> {
                val asset = host.asset(if (path == "/") "/index.html" else path)
                if (asset == null) respond(output, 404, error("not_found"))
                else respondBytes(output, 200, asset.contentType, asset.bytes, emptyMap())
            }
            !hasKey(request) -> respond(output, 403, error("key"))
            path == "/text" && request.method == "POST" -> receiveText(request, output)
            path == "/file" && request.method == "PUT" -> receiveFile(request, output)
            else -> respond(output, 404, error("not_found"))
        }
    }

    private fun hasKey(request: HttpRequest): Boolean {
        val given = request.header(KEY_HEADER) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), key.toByteArray())
    }

    private suspend fun receiveText(request: HttpRequest, output: OutputStream) {
        if (request.contentLength !in 1..MAX_TEXT_BODY) {
            respond(output, 413, error(SendRules.FAILED))
            return
        }
        val json = try {
            JSONObject(String(request.body.readBytes(), Charsets.UTF_8))
        } catch (e: JSONException) {
            respond(output, 400, error("bad_request"))
            return
        }
        val text = json.optString("text").take(SendRules.MAX_TEXT_LENGTH)
        if (text.isBlank()) {
            respond(output, 400, error("bad_request"))
            return
        }
        val from = SendRules.cleanFrom(json.optString("from"))
        withContext(mainDispatcher) { host.onText(text, from) }
        respond(output, 200, JSONObject())
    }

    private suspend fun receiveFile(request: HttpRequest, output: OutputStream) {
        val size = request.contentLength
        if (size < 0) {
            respond(output, 411, error(SendRules.FAILED))
            return
        }
        val name = SendRules.safeFileName(request.query["name"].orEmpty())
        val mimeType = SendRules.safeMimeType(request.query["type"])
        val from = SendRules.cleanFrom(request.query["from"])
        val file = try {
            withContext(mainDispatcher) { host.receiveFile(name, mimeType, size, from) }
        } catch (e: SendException) {
            refuse(request.body, output, e.code)
            return
        } catch (e: Exception) {
            Log.e(TAG, "can not receive $name", e)
            refuse(request.body, output, SendRules.FAILED)
            return
        }
        try {
            file.output.use { request.body.copyTo(it, 128 * 1024) }
        } catch (e: IOException) {
            //the browser stopped sending, or the download was cancelled on the TV
            file.fail("not received whole")
            try {
                respond(output, 500, error(SendRules.FAILED))
            } catch (e: IOException) {
            }
            return
        }
        try {
            respond(output, 200, JSONObject().put("name", file.awaitSaved()))
        } catch (e: SendException) {
            respond(output, 500, error(e.code))
        }
    }

    /** Answers before the bytes of the file, reading a part of them so the browser takes the answer */
    private fun refuse(body: InputStream, output: OutputStream, code: String) {
        respond(output, if (code == SendRules.NEED_PERMISSION) 409 else 500, error(code))
        try {
            var left = MAX_DRAIN
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val n = body.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
        } catch (e: IOException) {
        }
    }

    private fun error(code: String) = JSONObject().put("error", code)

    private fun respond(output: OutputStream, status: Int, json: JSONObject?, headers: Map<String, String> = emptyMap()) =
        respondBytes(output, status, "application/json; charset=utf-8", json?.toString()?.toByteArray(), headers)

    private fun respondBytes(output: OutputStream, status: Int, contentType: String, body: ByteArray?, headers: Map<String, String>) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            .append("Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        if (body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n")
            head.append("Content-Length: ").append(body.size).append("\r\n")
        } else {
            head.append("Content-Length: 0\r\n")
        }
        headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) output.write(body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }
}
