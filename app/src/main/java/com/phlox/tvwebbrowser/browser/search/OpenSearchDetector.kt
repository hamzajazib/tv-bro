package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.utils.FaviconExtractor
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder

/**
 * Finds where a search engine gives its suggestions: sites tell it in their OpenSearch description, an XML
 * file a `<link rel="search">` of their pages points to, as a `Url` of the type
 * `application/x-suggestions+json`.
 */
class OpenSearchDetector(
    private val open: (url: URL, accept: String?) -> URLConnection = { url, accept -> FaviconExtractor.open(url, accept) }
) {
    /**
     * The address of the suggestions (with [SearchEngines.QUERY]) of the engine that searches at [searchUrl],
     * null when its site tells none; blocks. The description is looked for in the start page of the site,
     * then in a page of its search results, then at `/opensearch.xml`.
     */
    fun detect(searchUrl: String): String? {
        if (SearchEngines.hostOf(searchUrl) == null) return null
        val search = try {
            URL(searchUrl.trim().replace(SearchEngines.QUERY, URLEncoder.encode(SAMPLE_QUERY, "UTF-8")))
        } catch (e: Exception) {
            return null
        }
        if (!search.protocol.equals("http", true) && !search.protocol.equals("https", true)) return null
        val root = URL(search, "/")
        val tried = HashSet<String>()
        for (page in listOf(root, search)) {
            for (description in descriptionsOf(page)) {
                if (tried.add(description.toString())) suggestUrlAt(description)?.let { return it }
            }
        }
        val wellKnown = URL(root, "/opensearch.xml")
        return if (tried.add(wellKnown.toString())) suggestUrlAt(wellKnown) else null
    }

    /** The OpenSearch descriptions the [page] points to; none when it can not be read */
    private fun descriptionsOf(page: URL): List<URL> = try {
        val connection = open(page, "text/html,application/xhtml+xml,*/*;q=0.8")
        //relative addresses are of the page the redirects ended at
        descriptionLinks(read(connection, MAX_HTML_CHARS), connection.url ?: page)
    } catch (e: IOException) {
        emptyList()
    }

    private fun suggestUrlAt(description: URL): String? = try {
        val connection = open(description, "application/opensearchdescription+xml,application/xml,text/xml,*/*;q=0.8")
        suggestTemplate(read(connection, MAX_XML_CHARS), connection.url ?: description)
    } catch (e: IOException) {
        null
    }

    private fun read(connection: URLConnection, maxChars: Int): String {
        try {
            if (connection is HttpURLConnection && connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val charset = SearchSuggestClient.charsetOf(connection.contentType)
            InputStreamReader(connection.getInputStream(), charset).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (text.length < maxChars) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - text.length))
                    if (read < 0) break
                    text.append(buffer, 0, read)
                }
                return text.toString()
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    companion object {
        const val DESCRIPTION_TYPE = "application/opensearchdescription+xml"
        const val SUGGESTIONS_TYPE = "application/x-suggestions+json"
        private const val SAMPLE_QUERY = "test"
        private const val MAX_HTML_CHARS = 512 * 1024
        private const val MAX_XML_CHARS = 64 * 1024

        private val LINK_TAG = Regex("<link\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val URL_TAG = Regex("<(?:\\w+:)?Url\\b([^>]*?)(/>|>(.*?)</(?:\\w+:)?Url\\s*>)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val PARAM_TAG = Regex("<(?:\\w+:)?Param\\b([^>]*)>", RegexOption.IGNORE_CASE)
        /** name=value with the value in double or single quotes or without them */
        private val ATTRIBUTE = Regex("([^\\s=/>\"']+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
        private val PARAMETER = Regex("\\{([^{}]*)\\}")
        /** What the parameters of a template other than the search terms are replaced with */
        private val PARAMETER_VALUES = mapOf("inputEncoding" to "UTF-8", "outputEncoding" to "UTF-8",
            "language" to "*", "count" to "10", "startIndex" to "1", "startPage" to "1")

        private fun attributes(tag: String): Map<String, String> = ATTRIBUTE.findAll(tag).associate { match ->
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
            match.groupValues[1].lowercase() to decodeEntities(value)
        }

        private fun decodeEntities(value: String) = if (!value.contains('&')) value else value
            .replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

        /** The addresses of the OpenSearch descriptions the `<link>`s of the [html] of the page at [base] give */
        fun descriptionLinks(html: String, base: URL): List<URL> = LINK_TAG.findAll(html).mapNotNull { match ->
            val attributes = attributes(match.groupValues[1])
            val rel = attributes["rel"]?.lowercase()?.split(Regex("\\s+")) ?: return@mapNotNull null
            if ("search" !in rel || attributes["type"]?.trim()?.lowercase() != DESCRIPTION_TYPE) return@mapNotNull null
            val href = attributes["href"]?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            webUrl(base, href)
        }.distinctBy { it.toString() }.toList()

        /**
         * The address of the suggestions the OpenSearch description [xml] (found at [base]) tells, with
         * [SearchEngines.QUERY] in place of the search terms; null when it tells none
         */
        fun suggestTemplate(xml: String, base: URL): String? {
            for (match in URL_TAG.findAll(xml)) {
                val attributes = attributes(match.groupValues[1])
                if (attributes["type"]?.trim()?.lowercase() != SUGGESTIONS_TYPE) continue
                if (attributes["method"]?.trim()?.equals("get", ignoreCase = true) == false) continue
                var template = attributes["template"]?.trim()?.ifEmpty { null } ?: continue
                //Mozilla's descriptions give the parameters as elements
                val params = PARAM_TAG.findAll(match.groupValues[3]).mapNotNull { param ->
                    val paramAttributes = attributes(param.groupValues[1])
                    val name = paramAttributes["name"]?.ifEmpty { null } ?: return@mapNotNull null
                    name + "=" + (paramAttributes["value"] ?: "")
                }.toList()
                if (params.isNotEmpty()) {
                    template += (if (template.contains('?')) "&" else "?") + params.joinToString("&")
                }
                val filled = PARAMETER.replace(template) { parameter ->
                    //optional parameters ({startPage?}) are left out
                    val name = parameter.groupValues[1]
                    if (name.removeSuffix("?") == "searchTerms") SearchEngines.QUERY else PARAMETER_VALUES[name] ?: ""
                }
                if (!filled.contains(SearchEngines.QUERY)) continue
                //the brackets of the query are not legal in a URL: it is resolved without them
                val marker = "tvbroquery"
                val resolved = webUrl(base, filled.replace(SearchEngines.QUERY, marker)) ?: continue
                val url = resolved.toString().replace(marker, SearchEngines.QUERY)
                if (SearchEngines.isSuggestUrl(url)) return url
            }
            return null
        }

        private fun webUrl(base: URL, address: String): URL? = try {
            URL(base, address).takeIf { it.protocol.equals("http", true) || it.protocol.equals("https", true) }
        } catch (e: Exception) {
            null
        }
    }
}
