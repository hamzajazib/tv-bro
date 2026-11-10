package com.phlox.tvwebbrowser.browser.search

/**
 * Keeps the requests for search suggestions few, so a search engine does not take the device for a robot:
 * at most [MAX_PER_MINUTE] requests a minute to all engines, and an engine that refuses (429, 403) or fails
 * [MAX_ERRORS] times in a row is left alone for [PAUSE_MS]. One for the process ([shared]).
 */
class SearchSuggestLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    /** The times of the requests of the last minute */
    private val requests = ArrayDeque<Long>()
    /** Until when each engine (by its host) is not asked */
    private val pausedUntil = HashMap<String, Long>()
    /** The failures in a row of each engine */
    private val errors = HashMap<String, Int>()

    /** The engine at [host] is left alone for now */
    @Synchronized
    fun isPaused(host: String): Boolean {
        val until = pausedUntil[host] ?: return false
        if (clock() < until) return true
        pausedUntil.remove(host)
        return false
    }

    /**
     * Counts a request to the engine at [host]
     * @return false when it must not be made: the engine is paused, or there were too many this minute
     */
    @Synchronized
    fun tryRequest(host: String): Boolean {
        if (isPaused(host)) return false
        val now = clock()
        while (requests.isNotEmpty() && now - requests.first() >= MINUTE_MS) requests.removeFirst()
        if (requests.size >= MAX_PER_MINUTE) return false
        requests.addLast(now)
        return true
    }

    @Synchronized
    fun onAnswer(host: String) {
        errors.remove(host)
    }

    /** The engine refused the request (too many, or not allowed) */
    @Synchronized
    fun onRefused(host: String) = pause(host)

    /** The engine could not be reached or gave an error */
    @Synchronized
    fun onError(host: String) {
        val count = (errors[host] ?: 0) + 1
        if (count >= MAX_ERRORS) pause(host) else errors[host] = count
    }

    private fun pause(host: String) {
        errors.remove(host)
        pausedUntil[host] = clock() + PAUSE_MS
    }

    companion object {
        const val MAX_PER_MINUTE = 30
        const val MAX_ERRORS = 2
        const val PAUSE_MS = 10 * 60 * 1000L
        private const val MINUTE_MS = 60 * 1000L

        val shared = SearchSuggestLimiter()
    }
}
