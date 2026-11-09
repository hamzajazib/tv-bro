package com.phlox.tvwebbrowser.send

import java.io.BufferedReader

/** An event of a stream of server-sent events: its name (`message` when the server gave none) and data */
data class SseEvent(val name: String, val data: String)

/**
 * Reads server-sent events (text/event-stream) line by line: `event:` and `data:` fields, an empty line
 * ends an event; comments (`:`) and other fields are skipped.
 */
class SseReader(private val reader: BufferedReader) {
    /** @return the next event, null at the end of the stream */
    fun next(): SseEvent? {
        var name: String? = null
        var data: StringBuilder? = null
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) {
                if (data != null || name != null) return SseEvent(name ?: "message", data?.toString().orEmpty())
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> name = value
                "data" -> data = (data?.append('\n') ?: StringBuilder()).append(value)
            }
        }
    }
}
