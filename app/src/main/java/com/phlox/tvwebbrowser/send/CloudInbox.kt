package com.phlox.tvwebbrowser.send

import com.phlox.tvwebbrowser.net.AppProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The inbox of the TV in the Realtime Database of Firebase, through its REST API (no Firebase library):
 * pages of the site write messages there from any network, the TV listens to them with server-sent events
 * while it waits for them.
 *
 * ```
 * /inbox/<inbox>/info         {name, lan: ["192.168.1.5:8765"], key, exp}  the TV, for the page
 * /inbox/<inbox>/msgs/<push>  {text, from, t}                             the page, deleted by the TV
 * /codes/<code>               {inbox, exp}                                the short address of the TV
 * ```
 * The rules (`database.rules.json` of the site) let anyone who knows an inbox or a code read it and write
 * messages, and nobody list them.
 */
class CloudInbox(
    private val databaseUrl: String = DATABASE_URL,
    private val connect: (URL) -> HttpURLConnection = { AppProxy.open(it) as HttpURLConnection }
) {
    companion object {
        const val DATABASE_URL = "https://tv-bro-3546c.firebaseio.com"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 20_000
        /** The server sends keep-alive events every 30 s or so */
        private const val STREAM_READ_TIMEOUT = 90_000
    }

    class Message(val key: String, val text: String, val from: String, val time: Long)

    class Info(val name: String, val lan: List<String>, val key: String, val expires: Long) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("lan", org.json.JSONArray(lan))
            .put("key", key)
            .put("exp", expires)
    }

    /** The answer of the database was not taken (the rules refused it, or another error) */
    class CloudException(val status: Int) : IOException("HTTP $status")

    /** How far the clock of the server is ahead of this one, from the Date of its answers */
    @Volatile
    var clockOffset = 0L
        private set

    /** The time of the server */
    fun serverTime(): Long = System.currentTimeMillis() + clockOffset

    suspend fun putInfo(inbox: String, info: Info) = write("PUT", "inbox/${encode(inbox)}/info", info.toJson())

    /** The TV stopped listening: nothing of it stays in the database (the page tells it does not wait) */
    suspend fun deleteInfo(inbox: String) = write("DELETE", "inbox/${encode(inbox)}/info", null)

    /** The code must be new: the rules refuse to overwrite one */
    suspend fun putCode(code: String, inbox: String, expires: Long) =
        write("PUT", "codes/${encode(code)}", JSONObject().put("inbox", inbox).put("exp", expires))

    suspend fun deleteCode(code: String) = write("DELETE", "codes/${encode(code)}", null)

    suspend fun deleteMessage(inbox: String, key: String) = write("DELETE", "inbox/${encode(inbox)}/msgs/${encode(key)}", null)

    private suspend fun write(method: String, path: String, json: JSONObject?) = withContext(Dispatchers.IO) {
        val connection = connect(URL("$databaseUrl/$path.json"))
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.useCaches = false
            if (json != null) {
                val body = json.toString().toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            noteClock(connection)
            (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            if (status !in 200..299) throw CloudException(status)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Listens to the messages of the [inbox] until the stream ends (returns) or fails (throws an
     * IOException); the coroutine is cancelled by closing the connection. [onMessages] gets the messages
     * there when the stream starts (`initial`), then each new one, on the thread of the stream.
     */
    suspend fun listen(inbox: String, onMessages: (messages: List<Message>, initial: Boolean) -> Unit) =
        withContext(Dispatchers.IO) {
            val connection = connect(URL("$databaseUrl/inbox/${encode(inbox)}/msgs.json"))
            coroutineScope {
                //a blocked read ends only when the connection is closed
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    runInterruptible { stream(connection, onMessages) }
                } finally {
                    closer.cancel()
                }
            }
        }

    private fun stream(connection: HttpURLConnection, onMessages: (List<Message>, Boolean) -> Unit) {
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = STREAM_READ_TIMEOUT
        connection.useCaches = false
        //the database may redirect (307) to the server of the data
        connection.instanceFollowRedirects = true
        val status = connection.responseCode
        noteClock(connection)
        if (status != 200) {
            connection.errorStream?.use { it.readBytes() }
            throw CloudException(status)
        }
        val reader = SseReader(connection.inputStream.bufferedReader())
        var initial = true
        while (true) {
            val event = reader.next() ?: return
            when (event.name) {
                "put", "patch" -> {
                    val messages = parseEvent(event)
                    if (initial || messages.isNotEmpty()) onMessages(messages, initial)
                    initial = false
                }
                //the rules refused the data
                "cancel", "auth_revoked" -> throw CloudException(403)
            }
        }
    }

    /** The messages a `put` or `patch` gives: of the whole list (path `/`) or one (`/<key>`) */
    internal fun parseEvent(event: SseEvent): List<Message> {
        val json = try {
            JSONObject(event.data)
        } catch (e: JSONException) {
            return emptyList()
        }
        val path = json.optString("path").trim('/')
        val data = json.opt("data")
        return when {
            path.isEmpty() && data is JSONObject -> data.keys().asSequence().mapNotNull { key ->
                (data.opt(key) as? JSONObject)?.let { messageOf(key, it) }
            }.toList()
            !path.contains('/') && data is JSONObject -> listOfNotNull(messageOf(path, data))
            //deleted, or a part of a message
            else -> emptyList()
        }
    }

    private fun messageOf(key: String, json: JSONObject): Message? {
        val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
        return Message(key, text.take(SendRules.MAX_TEXT_LENGTH), SendRules.cleanFrom(json.optString("from")), json.optLong("t"))
    }

    private fun noteClock(connection: HttpURLConnection) {
        val date = connection.getHeaderFieldDate("Date", 0L)
        //the header has whole seconds
        if (date > 0) clockOffset = date + 500 - System.currentTimeMillis()
    }

    private fun encode(segment: String) = URLEncoder.encode(segment, "UTF-8")
}
