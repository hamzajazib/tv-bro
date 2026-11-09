package com.phlox.tvwebbrowser.send

import android.content.Context
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.webengine.ErrorPageTemplate

/**
 * The files of the page of the TV in the local network (`assets/pages/send`), the page in the language of the TV.
 * @param context the activity: its strings are in the language of the app
 */
class LocalSendPage(private val context: Context, private val tvName: String) {
    private val html by lazy {
        val template = read("pages/send/index.html")
        val strings = mapOf(
            "lang" to context.resources.configuration.locales[0].toLanguageTag(),
            "dir" to ErrorPageTemplate.textDirection(context),
            "title" to s(R.string.send_page_title),
            "tv" to tvName,
            "open_title" to s(R.string.send_page_open_title),
            "address_hint" to s(R.string.send_page_address_hint),
            "send" to s(R.string.send_page_send),
            "files_title" to s(R.string.send_page_files_title),
            "choose_files" to s(R.string.send_page_choose_files),
            "files_hint" to s(R.string.send_page_files_hint),
            "sent" to s(R.string.send_page_sent),
            "sending" to s(R.string.send_page_sending),
            "saved" to s(R.string.send_page_saved),
            "need_permission" to s(R.string.send_page_need_permission),
            "storage" to s(R.string.send_page_storage),
            "cancelled" to s(R.string.send_page_cancelled),
            "failed" to s(R.string.send_page_failed),
            "bad_key" to s(R.string.send_page_bad_key),
            "offline" to s(R.string.send_page_offline)
        )
        ErrorPageTemplate.fill(template, strings).toByteArray()
    }
    private val script by lazy { read("pages/send/send.js").toByteArray() }

    fun asset(path: String): LocalSendServer.Asset? = when (path) {
        "/index.html" -> LocalSendServer.Asset(html, "text/html; charset=utf-8")
        "/send.js" -> LocalSendServer.Asset(script, "text/javascript; charset=utf-8")
        else -> null
    }

    private fun s(id: Int) = context.getString(id)

    private fun read(path: String) = context.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }
}
