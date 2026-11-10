package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator
import com.phlox.tvwebbrowser.browser.VisitedHistoryLogger
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.dao.HistoryDao

/**
 * The browsing history.
 */
class HistoryRepository(private val dao: HistoryDao) : VisitedHistoryLogger.Store, HistoryNavigator.Store,
    AddressSuggestions.Store {
    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }

    /**
     * Deletes the visits older than the [retention] (Settings, Advanced). Nothing else limits the history.
     */
    suspend fun trim(retention: Config.HistoryRetention, now: Long = System.currentTimeMillis()) {
        dao.deleteWhereTimeLessThan(now - retention.days * DAY)
    }

    suspend fun last(limit: Int): List<HistoryItem> = dao.last(limit)

    suspend fun mostVisited(): List<HistoryItem> = dao.frequentlyUsedUrls()

    /**
     * The whole history, the latest items first: it is searched in memory (SQLite's LIKE folds the case
     * of ASCII letters only), and [trim] keeps at most a year of it.
     */
    override suspend fun all(): List<HistoryItem> = dao.allByTime()

    override suspend fun delete(items: List<HistoryItem>) = dao.delete(*items.toTypedArray())

    override suspend fun deleteAll() = dao.deleteWhereTimeLessThan(Long.MAX_VALUE)

    override suspend fun insert(item: HistoryItem): Long = dao.insert(item)

    override suspend fun updateTitle(id: Long, title: String) = dao.updateTitle(id, title)
}
