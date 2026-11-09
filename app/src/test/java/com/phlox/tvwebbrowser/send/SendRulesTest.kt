package com.phlox.tvwebbrowser.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class SendRulesTest {
    private val search = "https://search.example/?q=[query]"

    @Test
    fun textsOpenAsTypedIntoTheAddressBar() {
        assertEquals("https://example.com", SendRules.urlOf("example.com", search))
        assertEquals("https://example.com/a?b=1", SendRules.urlOf("  https://example.com/a?b=1\n", search))
        assertEquals("https://search.example/?q=cats+and+dogs", SendRules.urlOf("cats and\ndogs", search))
        assertNull(SendRules.urlOf("   ", search))
    }

    @Test
    fun onlyWebPagesAreOpened() {
        //searched for, never run
        assertTrue(SendRules.urlOf("javascript:alert(1)", search)!!.startsWith("https://search.example/"))
        assertTrue(SendRules.urlOf("file:///sdcard/a.html", search)!!.startsWith("https://search.example/"))
        assertNull(SendRules.urlOf("x", "javascript:[query]"))
        assertTrue(SendRules.isAllowedUrl("HTTP://example.com"))
        assertFalse(SendRules.isAllowedUrl("intent://x#Intent;end"))
        assertFalse(SendRules.isAllowedUrl("about:home"))
        assertFalse(SendRules.isAllowedUrl("https://"))
        assertFalse(SendRules.isAllowedUrl("https://example.com/" + "a".repeat(SendRules.MAX_URL_LENGTH)))
    }

    @Test
    fun namesOfFilesAreMadeSafe() {
        assertEquals("photo.jpg", SendRules.safeFileName("photo.jpg"))
        assertEquals("passwd", SendRules.safeFileName("../../etc/passwd"))
        assertEquals("evil.apk", SendRules.safeFileName("..\\..\\evil.apk"))
        assertEquals("file", SendRules.safeFileName(".."))
        assertEquals("file", SendRules.safeFileName(""))
        assertEquals("hidden", SendRules.safeFileName(".hidden"))
        assertEquals("ab.txt", SendRules.safeFileName("a\u0000\nb.txt"))
        assertEquals("what.txt", SendRules.safeFileName("what?.txt"))
        assertEquals("Фото 1.jpg", SendRules.safeFileName("Фото 1.jpg"))
        //long names keep their extension and fit in 255 bytes of the file systems
        val long = SendRules.safeFileName("я".repeat(300) + ".mp4")
        assertTrue(long.endsWith("я.mp4"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= SendRules.MAX_FILE_NAME_BYTES)
        val emoji = SendRules.safeFileName("😀".repeat(100))
        assertEquals(0, emoji.length % 2)
    }

    @Test
    fun typesAndUrlsOfFiles() {
        assertEquals("image/jpeg", SendRules.safeMimeType("Image/JPEG"))
        assertNull(SendRules.safeMimeType("text/html; charset=utf-8"))
        assertNull(SendRules.safeMimeType(null))
        val url = SendRules.sentFileUrl("Chrome · Android & Co")
        assertFalse(url.startsWith("http"))
        assertEquals("Chrome · Android & Co", SendRules.senderOfFileUrl(url))
        assertNull(SendRules.senderOfFileUrl(SendRules.sentFileUrl("")))
        assertNull(SendRules.senderOfFileUrl("https://example.com/a.jpg"))
        assertEquals("ab", SendRules.cleanFrom(" a\u0000b "))
    }

    @Test
    fun linksAndCodes() {
        val inbox = SendLinks.newInbox()
        assertEquals(22, inbox.length)
        assertTrue(inbox.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals("https://tvbro.phlox.dev/send/#$inbox", SendLinks.pageUrl(inbox))
        //never under /sync, which 3.0 betas open in the app
        assertFalse(SendLinks.pageUrl(inbox).startsWith("https://tvbro.phlox.dev/sync"))
        val code = SendLinks.newCode()
        assertEquals(SendLinks.CODE_LENGTH, code.length)
        assertTrue(code.all { it in SendLinks.CODE_ALPHABET })
        assertEquals("tvbro.phlox.dev/c/$code", SendLinks.codeAddress(code))
    }

    @Test
    fun serverSentEventsAreRead() {
        val stream = "event: put\ndata: {\"path\":\"/\",\"data\":null}\n\n" +
            ": a comment\n\nevent: keep-alive\ndata: null\n\n" +
            "event: patch\ndata: {\"a\":1}\ndata: {\"b\":2}\n\nevent: put\ndata: cut"
        val reader = SseReader(ByteArrayInputStream(stream.toByteArray()).bufferedReader())
        assertEquals(SseEvent("put", "{\"path\":\"/\",\"data\":null}"), reader.next())
        assertEquals(SseEvent("keep-alive", "null"), reader.next())
        assertEquals(SseEvent("patch", "{\"a\":1}\n{\"b\":2}"), reader.next())
        //an event without its empty line was cut
        assertNull(reader.next())
    }

    @Test
    fun eventsOfTheInboxGiveMessages() {
        val inbox = CloudInbox("http://unused")
        val all = inbox.parseEvent(SseEvent("put", """{"path":"/","data":{
            "-a":{"text":"example.com","from":"Chrome","t":2},"-b":{"text":" "},"-c":5}}"""))
        assertEquals(listOf("-a"), all.map { it.key })
        assertEquals("example.com", all[0].text)
        assertEquals("Chrome", all[0].from)
        assertEquals(2L, all[0].time)
        val one = inbox.parseEvent(SseEvent("put", """{"path":"/-d","data":{"text":"cats","t":3}}"""))
        assertEquals(listOf("-d"), one.map { it.key })
        assertEquals("", one[0].from)
        //deleted, a part of a message, not JSON
        assertTrue(inbox.parseEvent(SseEvent("put", """{"path":"/-d","data":null}""")).isEmpty())
        assertTrue(inbox.parseEvent(SseEvent("put", """{"path":"/-d/text","data":"x"}""")).isEmpty())
        assertTrue(inbox.parseEvent(SseEvent("put", "null")).isEmpty())
        val patch = inbox.parseEvent(SseEvent("patch", """{"path":"/","data":{"-e":{"text":"a"},"-f":{"text":"b"}}}"""))
        assertEquals(setOf("-e", "-f"), patch.map { it.key }.toSet())
    }
}
