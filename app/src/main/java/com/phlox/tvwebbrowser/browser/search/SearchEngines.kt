package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.Config
import java.net.URI

/** What the app knows about the search engines of [Config]: where they suggest searches */
object SearchEngines {
    const val QUERY = "[query]"

    /** A label of the host of each engine of [Config.SearchEnginesURLs] (its sites in all countries) */
    private val HOST_LABELS = listOf(
        setOf("google"), setOf("bing"), setOf("yahoo"), setOf("duckduckgo"), setOf("yandex", "ya"), setOf("startpage")
    )

    /**
     * The address of the suggestions (with [QUERY]) of the search engine of [searchUrl], null when it has
     * none: that of an engine of the list; for the user's own engine [customSuggestUrl], else that of the
     * engine of the list its host belongs to (google.com.ua).
     */
    fun suggestUrlOf(searchUrl: String, customSuggestUrl: String): String? {
        val index = Config.SearchEnginesURLs.indexOf(searchUrl)
        if (index >= 0 && Config.SearchEnginesSuggestURLs[index].isNotEmpty()) {
            return Config.SearchEnginesSuggestURLs[index]
        }
        val custom = customSuggestUrl.trim()
        if (custom.isNotEmpty()) return custom.takeIf { isSuggestUrl(it) }
        val host = hostOf(searchUrl) ?: return null
        val labels = host.split('.')
        val known = HOST_LABELS.indexOfFirst { names -> labels.any { it in names } }
        return if (known >= 0) Config.SearchEnginesSuggestURLs[known] else null
    }

    /** An http or https address with the [QUERY] in it */
    fun isSuggestUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && url.contains(QUERY) && hostOf(url) != null
    }

    /** The host of the [url] in lower case, null when it has none */
    fun hostOf(url: String): String? = try {
        //the placeholder is not a legal part of a URI
        URI(url.replace(QUERY, "q").trim()).host?.lowercase()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
