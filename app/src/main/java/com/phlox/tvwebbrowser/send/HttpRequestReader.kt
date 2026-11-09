package com.phlox.tvwebbrowser.send

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** A request to the server of the TV, its [body] limited to [contentLength] bytes (none without it) */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Names in lower case */
    val headers: Map<String, String>,
    val body: InputStream
) {
    /** -1 without the header */
    val contentLength: Long = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L

    fun header(name: String): String? = headers[name.lowercase()]
}

/** A request the server can not take: answered with [status] */
class HttpException(val status: Int) : IOException("HTTP $status")

/** Reads HTTP/1.1 requests: the request line, headers of at most [MAX_HEAD] bytes, no chunked bodies */
object HttpRequestReader {
    const val MAX_HEAD = 16 * 1024

    /** @return null when the stream ends before the request starts */
    fun read(input: InputStream): HttpRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpException(400)
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpException(400)
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"]?.lowercase()?.let { it != "identity" } == true) throw HttpException(411)
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val length = headers["content-length"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw HttpException(400) } ?: 0L
        return HttpRequest(parts[0], path, query, headers, LimitedInputStream(input, length))
    }

    fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull {
        val name = it.substringBefore('=')
        if (name.isEmpty()) null else decode(name) to decode(it.substringAfter('=', ""))
    }.toMap()

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        ""
    }

    /** The bytes up to the empty line, as ISO-8859-1 (headers are ASCII) */
    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (bytes.size() == 0) return null
                throw EOFException()
            }
            bytes.write(b)
            if (bytes.size() > MAX_HEAD) throw HttpException(431)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        return String(bytes.toByteArray(), 0, bytes.size() - 4, Charsets.ISO_8859_1)
    }

    /** The body of a request: ends after its length, which [remaining] tells */
    class LimitedInputStream(input: InputStream, length: Long) : FilterInputStream(input) {
        var remaining = length
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b < 0) throw EOFException()
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException()
            remaining -= n
            return n
        }

        override fun skip(n: Long): Long = super.skip(minOf(n, remaining)).also { remaining -= it }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

        override fun markSupported() = false

        /** The connection is closed by the server, not by the body */
        override fun close() {}
    }
}
