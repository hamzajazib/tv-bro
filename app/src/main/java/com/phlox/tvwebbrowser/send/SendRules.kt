package com.phlox.tvwebbrowser.send

import com.phlox.tvwebbrowser.browser.UrlInputResolver
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a page of another device may send to the TV and how the TV takes it: texts typed as into the
 * address bar, files with names safe on the TV, and the url of the download of a sent file.
 */
object SendRules {
    /** The longest text a page sends (the rules of the database take as much) */
    const val MAX_TEXT_LENGTH = 2048
    /** The name of the sender ("Chrome on Android"), as the page makes it */
    const val MAX_FROM_LENGTH = 60
    const val MAX_URL_LENGTH = 8192
    /** In bytes of UTF-8: file systems take 255 */
    const val MAX_FILE_NAME_BYTES = 200
    /** The scheme of the url of a download that came from another device (it is not downloaded again) */
    private const val SENT_FILE_SCHEME = "tvbro-phone:"

    /** Error codes of the requests of the page of the TV */
    const val FAILED = "failed"
    /** The TV can not save files until the user allows it there (it asked) */
    const val NEED_PERMISSION = "need_permission"
    /** The TV could not write the file */
    const val STORAGE = "storage"
    /** The user cancelled the file on the TV */
    const val CANCELLED = "cancelled"

    /**
     * The page the TV opens for a sent [text]: an address, or a search of the text (as the address bar
     * does); null for nothing to open. Only http and https pages are opened (never javascript:, file:, intent:…).
     */
    fun urlOf(text: String, searchEngineUrlTemplate: String): String? {
        val clean = text.take(MAX_TEXT_LENGTH).replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        return UrlInputResolver.resolve(clean, searchEngineUrlTemplate)?.takeIf(::isAllowedUrl)
    }

    fun isAllowedUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        val scheme = url.substringBefore(':', "").lowercase()
        return (scheme == "http" || scheme == "https") && url.length > scheme.length + 3
    }

    /** The name of the sender without control characters, short */
    fun cleanFrom(from: String?): String =
        from.orEmpty().filter { it >= ' ' && it != '\u007f' }.trim().take(MAX_FROM_LENGTH)

    /**
     * The name of a sent file, safe to be a file name of the TV: no folders (`../`), no control characters
     * and characters FAT drives do not take, not hidden, not too long (its extension kept)
     */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.').trim()
        if (clean.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) return clean.ifEmpty { "file" }
        val ext = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 16 }
        val suffix = if (ext != null) ".$ext" else ""
        val stem = if (ext != null) clean.substring(0, clean.length - suffix.length) else clean
        return (takeUtf8Bytes(stem, MAX_FILE_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size).trim() + suffix)
            .ifEmpty { "file" }
    }

    /** The beginning of the [text] that has at most [max] bytes of UTF-8, whole code points only */
    private fun takeUtf8Bytes(text: String, max: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > max) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    /** A type as `image/jpeg`, else null */
    fun safeMimeType(mimeType: String?): String? =
        mimeType?.trim()?.lowercase()?.takeIf { it.length <= 127 && MIME_TYPE.matches(it) }

    private val MIME_TYPE = Regex("[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*")

    /** The url of a download that the device [from] sent */
    fun sentFileUrl(from: String): String = SENT_FILE_SCHEME + URLEncoder.encode(from, "UTF-8")

    /** The name of the device that sent the download of the [url], null if it was not sent */
    fun senderOfFileUrl(url: String): String? {
        if (!url.startsWith(SENT_FILE_SCHEME)) return null
        return try {
            URLDecoder.decode(url.substring(SENT_FILE_SCHEME.length), "UTF-8").takeIf { it.isNotBlank() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** A request of a sending device that the TV refused, [code] of [SendRules] */
class SendException(val code: String) : Exception(code)
