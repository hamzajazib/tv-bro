package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The searches the search engine of the settings suggests for the text of the address field: only while
 * the suggestions are on (Settings, Search), never in incognito mode, and only for texts that are not
 * addresses ([SearchSuggestRules]). An engine that does not answer gives none, silently, and the
 * [limiter] keeps the requests few: an engine that refuses or keeps failing is not asked for a while.
 */
class SearchEngineSuggestions(
    private val config: Config,
    private val client: SearchSuggestClient = SearchSuggestClient(),
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val limiter: SearchSuggestLimiter = SearchSuggestLimiter.shared
) : AddressSuggestions.SearchSource {
    /** The address of the suggestions of the engine, null when it is not to be asked */
    private fun template(): String? {
        if (!config.searchSuggestions || config.incognitoMode) return null
        return SearchEngines.suggestUrlOf(config.searchEngineURL.value, config.customSearchSuggestUrl)
    }

    override fun asks(text: String): Boolean {
        if (!SearchSuggestRules.shouldAsk(text)) return false
        val host = SearchEngines.hostOf(template() ?: return false) ?: return false
        return !limiter.isPaused(host)
    }

    override suspend fun suggest(text: String): List<String>? {
        if (!SearchSuggestRules.shouldAsk(text)) return null
        val template = template() ?: return null
        val host = SearchEngines.hostOf(template) ?: return null
        if (!limiter.tryRequest(host)) return null
        return withContext(ioContext) {
            try {
                client.fetch(template, text).also { limiter.onAnswer(host) }
            } catch (e: SearchSuggestRefusedException) {
                limiter.onRefused(host)
                null
            } catch (e: Exception) {
                limiter.onError(host)
                null
            }
        }
    }
}
