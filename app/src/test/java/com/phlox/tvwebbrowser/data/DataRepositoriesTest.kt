package com.phlox.tvwebbrowser.data

import androidx.room.Room
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.TabEntity
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataRepositoriesTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val data = DataRepositories(db)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedSelectedTabUnselectsTheOthers() = runBlocking {
        val a = data.tabs.save(TabEntity(url = "https://a.com/", selected = true, position = 0))
        val b = data.tabs.save(TabEntity(url = "https://b.com/", selected = true, position = 1))
        assertEquals(mapOf(a to false, b to true), data.tabs.load(incognito = false).associate { it.id to it.selected })

        //an update keeps the id
        assertEquals(a, data.tabs.save(TabEntity(id = a, url = "https://a2.com/", selected = true, position = 0)))
        assertEquals(mapOf(a to true, b to false), data.tabs.load(incognito = false).associate { it.id to it.selected })
    }

    @Test
    fun tabSelectionIsKeptPerMode() = runBlocking {
        val incognito = data.tabs.save(TabEntity(url = "https://i.com/", selected = true, incognito = true))
        data.tabs.save(TabEntity(url = "https://a.com/", selected = true))
        assertEquals(true, data.tabs.load(incognito = true).single { it.id == incognito }.selected)
    }

    @Test
    fun savedBookmarkGetsItsId() = runBlocking {
        val item = FavoriteItem().apply { title = "A"; url = "https://a.com/" }
        data.favorites.save(item)
        assertNotEquals(0L, item.id)
        item.title = "A2"
        data.favorites.save(item)
        assertEquals(listOf("A2"), data.favorites.all().map { it.title })
    }

    @Test
    fun historyKeepsTheVisitsOfTheChosenTime() = runBlocking {
        val day = 24 * 60 * 60 * 1000L
        val now = 1_000 * day
        for (daysAgo in listOf(0, 6, 8, 100, 364, 366, 800)) {
            data.history.insert(HistoryItem().apply { url = "https://a.com/$daysAgo"; title = ""; time = now - daysAgo * day })
        }
        suspend fun kept() = data.history.all().map { it.url.substringAfterLast('/').toInt() }

        //however few they are, the visits older than the time go
        data.history.trim(Config.HistoryRetention.YEAR, now)
        assertEquals(listOf(0, 6, 8, 100, 364), kept())
        data.history.trim(Config.HistoryRetention.THREE_MONTHS, now)
        assertEquals(listOf(0, 6, 8), kept())
        data.history.trim(Config.HistoryRetention.WEEK, now)
        assertEquals(listOf(0, 6), kept())
        //a longer time brings nothing back and deletes nothing
        data.history.trim(Config.HistoryRetention.YEAR, now)
        assertEquals(listOf(0, 6), kept())
    }
}
