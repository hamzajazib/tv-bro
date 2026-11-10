package com.phlox.tvwebbrowser.browser.search

/**
 * Which texts of the address field are sent to the search engine for its suggestions: searches, not
 * addresses (those stay on the device)
 */
object SearchSuggestRules {
    const val MAX_LENGTH = 100
    /** Shorter texts are not asked about, unless they are a word by themselves (an ideograph) */
    const val MIN_LENGTH = 2
    /** A letter or a digit before a dot: a host (example.com, 192.168.1.1), also while it is typed (example.) */
    private val HOST_DOT = Regex("[\\p{L}\\p{N}]\\.")

    fun shouldAsk(text: String): Boolean {
        val query = text.trim()
        if (query.isEmpty() || query.length > MAX_LENGTH) return false
        if (query.length < MIN_LENGTH && !Character.isIdeographic(query.codePointAt(0))) return false
        if (query.contains("://")) return false
        //words are a search
        if (query.any { it.isWhitespace() }) return true
        //one word: an address has a scheme or a port (about:, host:8080), a path or a host with dots
        if (query.contains(':') || query.contains('/')) return false
        if (HOST_DOT.containsMatchIn(query)) return false
        return !query.equals("localhost", ignoreCase = true)
    }
}
