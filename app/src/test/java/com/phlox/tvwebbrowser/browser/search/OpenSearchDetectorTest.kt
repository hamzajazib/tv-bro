package com.phlox.tvwebbrowser.browser.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenSearchDetectorTest {
    private class FakeConnection(url: URL, private val body: String?, private val finalUrl: URL = url) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = if (body == null) 404 else 200
        override fun getContentType() = "text/html; charset=utf-8"
        override fun getInputStream(): InputStream = ByteArrayInputStream((body ?: "").toByteArray())
        override fun getURL(): URL = finalUrl
    }

    /** The pages of the fake web by their addresses; the others are not found */
    private val pages = HashMap<String, String>()
    private val redirects = HashMap<String, String>()
    private val asked = ArrayList<String>()

    private val detector = OpenSearchDetector { url, _ ->
        asked += url.toString()
        val end = redirects[url.toString()]?.let { URL(it) } ?: url
        FakeConnection(url, pages[end.toString()], end)
    }

    private val base = URL("https://example.com/")

    private fun description(vararg urls: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/" xmlns:moz="http://www.mozilla.org/2006/browser/search/">
        <ShortName>Example</ShortName>
        ${urls.joinToString("\n")}
        </OpenSearchDescription>"""

    @Test
    fun theLinksOfAPageToItsDescriptionsAreFound() {
        val html = """<html><head><title>Example</title>
            <link rel="stylesheet" href="/style.css">
            <link rel="icon search" type="image/png" href="/icon.png">
            <LINK REL='Search' TYPE='Application/OpenSearchDescription+xml' title="Example" HREF='/os.xml?a=1&amp;b=2'>
            <link href=//cdn.example.com/os2.xml type=application/opensearchdescription+xml rel=search />
            <link rel="search" type="application/opensearchdescription+xml" href="javascript:alert(1)">
            <link rel="search" type="application/opensearchdescription+xml" href="/os.xml?a=1&amp;b=2">
            </head><body></body></html>"""
        assertEquals(listOf("https://example.com/os.xml?a=1&b=2", "https://cdn.example.com/os2.xml"),
            OpenSearchDetector.descriptionLinks(html, URL("https://example.com/search/page")).map { it.toString() })
        assertTrue(OpenSearchDetector.descriptionLinks("<html></html>", base).isEmpty())
    }

    @Test
    fun theSuggestionsOfADescriptionBecomeATemplate() {
        //DuckDuckGo
        assertEquals("https://duckduckgo.com/ac/?q=[query]&type=list", OpenSearchDetector.suggestTemplate(description(
            """<Url type="text/html" method="get" template="https://duckduckgo.com/?q={searchTerms}"/>""",
            """<Url type="application/x-suggestions+json" template="https://duckduckgo.com/ac/?q={searchTerms}&amp;type=list"/>"""
        ), base))
        //Wikipedia: other parameters, some optional
        assertEquals("https://en.wikipedia.org/w/api.php?action=opensearch&search=[query]&namespace=0&lang=*&enc=UTF-8&page=",
            OpenSearchDetector.suggestTemplate(description(
                """<Url type="application/x-suggestions+xml" template="https://en.wikipedia.org/w/api.php?format=xml&amp;search={searchTerms}"/>""",
                """<Url type="application/x-suggestions+json" method="GET" template="https://en.wikipedia.org/w/api.php?action=opensearch&amp;search={searchTerms}&amp;namespace=0&amp;lang={language}&amp;enc={inputEncoding}&amp;page={startPage?}"/>"""
            ), base))
    }

    @Test
    fun parametersGivenAsElementsAndRelativeTemplatesAreRead() {
        assertEquals("https://example.com/suggest?q=[query]&client=tv", OpenSearchDetector.suggestTemplate(description(
            """<Url type="application/x-suggestions+json" method="GET" template="/suggest">
                 <Param name="q" value="{searchTerms}"/>
                 <moz:Param name="client" value="tv"/>
               </Url>"""
        ), URL("https://example.com/static/opensearch.xml")))
    }

    @Test
    fun aDescriptionWithoutSuggestionsGivesNone() {
        assertNull(OpenSearchDetector.suggestTemplate(description(
            """<Url type="text/html" template="https://example.com/?q={searchTerms}"/>"""), base))
        //not asked with GET, without the search terms, not a web address
        assertNull(OpenSearchDetector.suggestTemplate(description(
            """<Url type="application/x-suggestions+json" method="post" template="https://example.com/s?q={searchTerms}"/>""",
            """<Url type="application/x-suggestions+json" template="https://example.com/s"/>""",
            """<Url type="application/x-suggestions+json" template="file:///sdcard/{searchTerms}"/>"""), base))
        assertNull(OpenSearchDetector.suggestTemplate("<html><body>Not found</body></html>", base))
        assertNull(OpenSearchDetector.suggestTemplate("", base))
    }

    @Test
    fun theDescriptionIsFoundFromTheStartPageOfTheSite() {
        pages["https://example.com/"] = """<link rel="search" type="application/opensearchdescription+xml" href="os.xml">"""
        pages["https://example.com/os.xml"] = description(
            """<Url type="application/x-suggestions+json" template="https://ac.example.com/?q={searchTerms}"/>""")
        assertEquals("https://ac.example.com/?q=[query]", detector.detect("https://example.com/search?q=[query]&x=1"))
        assertEquals(listOf("https://example.com/", "https://example.com/os.xml"), asked)
    }

    @Test
    fun thePageOfTheSearchAndTheUsualAddressAreTriedToo() {
        //the start page moved to another site, whose links are relative to it
        redirects["https://example.com/"] = "https://www.example.com/home"
        pages["https://www.example.com/home"] = """<link rel="search" type="application/opensearchdescription+xml" href="os.xml">"""
        pages["https://www.example.com/os.xml"] = description(
            """<Url type="text/html" template="https://www.example.com/?q={searchTerms}"/>""")
        pages["https://example.com/search?q=test"] =
            """<link rel="search" type="application/opensearchdescription+xml" href="/search-os.xml">"""
        pages["https://example.com/search-os.xml"] = description(
            """<Url type="application/x-suggestions+json" template="/ac?q={searchTerms}"/>""")
        assertEquals("https://example.com/ac?q=[query]", detector.detect(" https://example.com/search?q=[query] "))
        assertEquals(listOf("https://example.com/", "https://www.example.com/os.xml", "https://example.com/search?q=test",
            "https://example.com/search-os.xml"), asked)

        asked.clear()
        pages.clear()
        redirects.clear()
        pages["https://example.com/opensearch.xml"] = description(
            """<Url type="application/x-suggestions+json" template="https://example.com/ac?q={searchTerms}"/>""")
        assertEquals("https://example.com/ac?q=[query]", detector.detect("https://example.com/search?q=[query]"))
    }

    @Test
    fun aSiteThatTellsNothingOrIsNoWebAddressGivesNone() {
        assertNull(detector.detect("https://example.com/search?q=[query]"))
        assertEquals(listOf("https://example.com/", "https://example.com/search?q=test", "https://example.com/opensearch.xml"), asked)
        asked.clear()
        assertNull(detector.detect(""))
        assertNull(detector.detect("not an address"))
        assertNull(detector.detect("file:///sdcard/[query]"))
        assertTrue(asked.isEmpty())
    }
}
