package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.InlineCompletion
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.Row
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
class AddressSuggestionsTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24 * 60 * 60 * 1000L
    private var id = 0L

    private fun visit(url: String, title: String = "", daysAgo: Int = 0) = HistoryItem().apply {
        this.id = ++this@AddressSuggestionsTest.id
        this.url = url
        this.title = title
        time = now - daysAgo * day
    }

    /** The visits, the latest first, as the history gives them */
    private fun entries(vararg visits: HistoryItem) =
        AddressSuggestions.aggregate(visits.sortedByDescending { it.time }, now)

    private fun urls(rows: List<Row>) = rows.map { (it as Row.Page).url }

    /** The rows as short texts: the url of a page, the text of a search after "?" */
    private fun texts(rows: List<Row>) = rows.map {
        when (it) {
            is Row.Page -> it.url
            is Row.Search -> "?" + it.text
        }
    }

    /** A search engine whose answers the test gives ([answer]), or one that answers at once ([replies]) */
    private class FakeEngine(private val replies: ((String) -> List<String>?)? = null) : AddressSuggestions.SearchSource {
        var on = true
        val asked = ArrayList<String>()
        private val waiting = LinkedHashMap<String, CompletableDeferred<List<String>?>>()

        override fun asks(text: String) = on && text.isNotBlank() && !text.contains('.')

        override suspend fun suggest(text: String): List<String>? {
            asked += text
            replies?.let { return it(text) }
            return CompletableDeferred<List<String>?>().also { waiting[text] = it }.await()
        }

        fun answer(text: String, vararg suggestions: String) = waiting.remove(text)!!.complete(suggestions.toList())

        fun fail(text: String) = waiting.remove(text)!!.complete(null)
    }

    private val rendered = ArrayList<List<Row>>()

    private fun suggestions(engine: AddressSuggestions.SearchSource?, vararg history: HistoryItem) =
        AddressSuggestions(object : AddressSuggestions.Store {
            override suspend fun all() = history.sortedByDescending { it.time }
        }, CoroutineScope(Dispatchers.Unconfined), object : AddressSuggestions.Host {
            override fun render(rows: List<Row>) { rendered += rows }
        }, searchContext = EmptyCoroutineContext, clock = { now }, searchSource = engine, searchDelayMs = 0)

    @Test
    fun visitsOfAUrlMakeOneEntryWithTheLatestTitle() {
        val entries = entries(
            visit("https://example.com/", "Old", daysAgo = 3),
            visit("https://example.com", "New", daysAgo = 1),
            visit("https://example.com/", "", daysAgo = 0),
            visit("https://other.com/", "Other", daysAgo = 2)
        )
        assertEquals(2, entries.size)
        val example = entries.first { it.url.contains("example") }
        assertEquals(3, example.visits)
        assertEquals("New", example.title)
        assertEquals("https://example.com/", example.url)
    }

    @Test
    fun aSiteThatStartsWithTheTextComesFirst() {
        val entries = entries(
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://www.youtube.com/watch?v=1", "A video", daysAgo = 20)
        )
        assertEquals(listOf("https://www.youtube.com/watch?v=1", "https://news.com/youtube-story"),
            urls(AddressSuggestions.rank(entries, "you")))
    }

    @Test
    fun moreAndNewerVisitsRankHigherInTheSameClass() {
        val entries = entries(
            visit("https://a.example.com/old", daysAgo = 200),
            visit("https://a.example.com/old", daysAgo = 201),
            visit("https://a.example.com/new", daysAgo = 1),
            visit("https://a.example.com/often", daysAgo = 5),
            visit("https://a.example.com/often", daysAgo = 6),
            visit("https://a.example.com/often", daysAgo = 7)
        )
        assertEquals(listOf("https://a.example.com/often", "https://a.example.com/new", "https://a.example.com/old"),
            urls(AddressSuggestions.rank(entries, "a.ex")))
    }

    @Test
    fun aWordThatStartsAWordOfTheTitleRanksAboveOneFoundInside() {
        val entries = entries(
            visit("https://one.com/", "Superkotlin", daysAgo = 0),
            visit("https://one.com/", "Superkotlin", daysAgo = 0),
            visit("https://two.com/", "Kotlin docs", daysAgo = 30)
        )
        assertEquals(listOf("https://two.com/", "https://one.com/"), urls(AddressSuggestions.rank(entries, "kotlin")))
    }

    @Test
    fun eachWordIsFoundIgnoringCaseAlsoOutsideAscii() {
        val entries = entries(
            visit("https://uk.wikipedia.org/wiki/TV", "Телебачення — Вікіпедія"),
            visit("https://en.wikipedia.org/wiki/TV", "Television — Wikipedia")
        )
        assertEquals(listOf("https://uk.wikipedia.org/wiki/TV"), urls(AddressSuggestions.rank(entries, "вікі тел")))
        assertEquals(listOf("вікі", "тел"), AddressSuggestions.rank(entries, "вікі тел").single().words)
    }

    @Test
    fun theRowsAreLimited() {
        val visits = (1..10).map { visit("https://site$it.com/") }.toTypedArray()
        assertEquals(AddressSuggestions.MAX_ROWS, AddressSuggestions.rank(entries(*visits), "site").size)
        assertTrue(AddressSuggestions.rank(entries(*visits), "  ").isEmpty())
    }

    @Test
    fun theSiteIsCompletedWhileItIsTyped() {
        val entries = entries(
            visit("https://www.youtube.com/watch?v=1"),
            visit("https://www.youtube.com/watch?v=2"),
            visit("https://yoga.com/", daysAgo = 1)
        )
        assertEquals(InlineCompletion("youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "yo"))
        //the case of what was typed stays
        assertEquals(InlineCompletion("YOutube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "YO"))
        assertEquals(InlineCompletion("yoga.com", "https://yoga.com/"), AddressSuggestions.inlineCandidate(entries, "yog"))
    }

    @Test
    fun theTextIsComparedInTheFormItIsTypedIn() {
        val entries = entries(visit("https://www.youtube.com/watch?v=1"))
        assertEquals(InlineCompletion("www.youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "www.you"))
        assertEquals(InlineCompletion("https://www.youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "https://www.y"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "http://www.y"))
    }

    @Test
    fun afterASlashTheUrlIsCompleted() {
        val entries = entries(visit("https://www.youtube.com/watch?v=Ab1"), visit("https://www.youtube.com/feed", daysAgo = 3))
        assertEquals(InlineCompletion("youtube.com/watch?v=Ab1", "https://www.youtube.com/watch?v=Ab1"),
            AddressSuggestions.inlineCandidate(entries, "youtube.com/w"))
        assertEquals(InlineCompletion("youtube.com/feed", "https://www.youtube.com/feed"),
            AddressSuggestions.inlineCandidate(entries, "youtube.com/f"))
    }

    @Test
    fun aSiteOnlyOnHttpKeepsItsSchemeAndPort() {
        val entries = entries(visit("http://router.local:8080/admin"))
        assertEquals(InlineCompletion("router.local:8080", "http://router.local:8080/"),
            AddressSuggestions.inlineCandidate(entries, "rou"))
        //the whole site typed still opens with its own scheme
        assertEquals(InlineCompletion("router.local:8080", "http://router.local:8080/"),
            AddressSuggestions.inlineCandidate(entries, "router.local:8080"))
    }

    @Test
    fun textWithSpacesOrNotMatchingIsNotCompleted() {
        val entries = entries(visit("https://youtube.com/"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "you tube"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "tube"))
        assertNull(AddressSuggestions.inlineCandidate(entries, ""))
    }

    @Test
    fun pagesComeFirstAndEachKindHasItsShareOfTheRows() {
        fun pages(count: Int) = (1..count).map { Row.Page("https://p$it.com/", "P$it", "p$it.com", emptyList()) }
        fun searches(count: Int) = (1..count).map { Row.Search("s$it", emptyList()) }
        fun sizes(pages: Int, searches: Int, asking: Boolean = false) =
            AddressSuggestions.merge(pages(pages), searches(searches), asking).let { rows ->
                rows.count { it is Row.Page } to rows.count { it is Row.Search }
            }
        assertEquals(3 to 3, sizes(6, 10))
        assertEquals(6 to 0, sizes(6, 0))
        assertEquals(0 to 6, sizes(0, 10))
        //one kind takes the rows the other leaves
        assertEquals(1 to 5, sizes(1, 10))
        assertEquals(4 to 2, sizes(5, 2))
        assertEquals(2 to 1, sizes(2, 1))
        //while the engine is asked, the rows of its answer are left free
        assertEquals(3 to 0, sizes(6, 0, asking = true))
        assertEquals(3 to 1, sizes(6, 1, asking = true))
        assertEquals(2 to 0, sizes(2, 0, asking = true))
        val rows = AddressSuggestions.merge(pages(2), searches(2))
        assertEquals(listOf("https://p1.com/", "https://p2.com/", "?s1", "?s2"), texts(rows))
    }

    @Test
    fun theSearchesOfTheEngineComeAfterThePages() {
        val engine = FakeEngine()
        val suggestions = suggestions(engine, visit("https://weather.com/", "Weather"))
        suggestions.start("")
        suggestions.query("weath")
        //the pages show at once, the searches when the engine answers
        assertEquals(listOf("https://weather.com/"), texts(rendered.last()))
        engine.answer("weath", "weather", "Weath", "weather radar", "weather", " weath ")
        //each once, without the text itself
        assertEquals(listOf("https://weather.com/", "?weather", "?weather radar"), texts(rendered.last()))
        assertEquals(listOf("weath"), (rendered.last().last() as Row.Search).words)
    }

    @Test
    fun anAnswerForAnEarlierTextIsDropped() {
        val engine = FakeEngine()
        val suggestions = suggestions(engine)
        suggestions.start("")
        suggestions.query("we")
        suggestions.query("wea")
        engine.answer("we", "web")
        assertTrue(rendered.isEmpty() || rendered.last().isEmpty())
        engine.answer("wea", "weather")
        assertEquals(listOf("?weather"), texts(rendered.last()))
    }

    @Test
    fun theSearchesThatStillFitStayUntilTheAnswerComes() {
        val engine = FakeEngine()
        val suggestions = suggestions(engine)
        suggestions.start("")
        suggestions.query("we")
        engine.answer("we", "weather", "web", "wea")
        suggestions.query("wea")
        //"wea" is the text now, "web" does not start with it
        assertEquals(listOf("?weather"), texts(rendered.last()))
        engine.answer("wea", "weapon", "weather")
        assertEquals(listOf("?weapon", "?weather"), texts(rendered.last()))
        //an engine that does not answer leaves them
        suggestions.query("weat")
        assertEquals(listOf("?weather"), texts(rendered.last()))
        engine.fail("weat")
        assertEquals(listOf("?weather"), texts(rendered.last()))
    }

    @Test
    fun theAnswersAreKeptWhileTheAddressIsEdited() {
        val engine = FakeEngine { listOf("$it one", "$it two") }
        val suggestions = suggestions(engine)
        suggestions.start("")
        suggestions.query("a")
        suggestions.query("ab")
        suggestions.query("a")
        assertEquals(listOf("?a one", "?a two"), texts(rendered.last()))
        //spaces around the text do not make another question
        suggestions.query("a ")
        assertEquals(listOf("a", "ab"), engine.asked)
        suggestions.stop()
        assertTrue(rendered.last().isEmpty())
        suggestions.start("")
        suggestions.query("a")
        assertEquals(listOf("a", "ab", "a"), engine.asked)
    }

    @Test
    fun theEngineIsNotAskedAboutAddressesOrWhenItIsOff() {
        val engine = FakeEngine { listOf("$it suggestion") }
        val suggestions = suggestions(engine, visit("https://example.com/", "Example"))
        suggestions.start("https://example.com/")
        //nothing for the address the editing started with
        assertTrue(engine.asked.isEmpty())
        suggestions.query("exam")
        assertEquals(listOf("https://example.com/", "?exam suggestion"), texts(rendered.last()))
        //an address: the searches go at once
        suggestions.query("example.c")
        assertEquals(listOf("https://example.com/"), texts(rendered.last()))
        suggestions.query("")
        assertTrue(rendered.last().isEmpty())
        engine.on = false
        suggestions.query("exam")
        assertEquals(listOf("https://example.com/"), texts(rendered.last()))
        assertEquals(listOf("exam"), engine.asked)
    }

    @Test
    fun theEngineIsAskedOnceTheTypingPauses() {
        val engine = FakeEngine { listOf("$it!") }
        val scope = kotlinx.coroutines.test.TestScope()
        val suggestions = AddressSuggestions(object : AddressSuggestions.Store {
            override suspend fun all() = emptyList<HistoryItem>()
        }, scope, object : AddressSuggestions.Host {
            override fun render(rows: List<Row>) { rendered += rows }
        }, searchContext = EmptyCoroutineContext, clock = { now }, searchSource = engine)
        suggestions.start("")
        suggestions.query("w")
        scope.testScheduler.advanceTimeBy(AddressSuggestions.SEARCH_DELAY_MS - 1)
        suggestions.query("we")
        scope.testScheduler.advanceTimeBy(AddressSuggestions.SEARCH_DELAY_MS - 1)
        assertTrue(engine.asked.isEmpty())
        scope.testScheduler.advanceTimeBy(2)
        assertEquals(listOf("we"), engine.asked)
        assertEquals(listOf("?we!"), texts(rendered.last()))
    }

    @Test
    fun theListFollowsTheTextOnceTheHistoryIsLoaded() {
        val rendered = ArrayList<List<Row>>()
        val history = listOf(visit("https://example.com/", "Example"), visit("https://other.com/", "Other"))
        val suggestions = AddressSuggestions(object : AddressSuggestions.Store {
            override suspend fun all() = history
        }, CoroutineScope(Dispatchers.Unconfined), object : AddressSuggestions.Host {
            override fun render(rows: List<Row>) { rendered += rows }
        }, searchContext = EmptyCoroutineContext, clock = { now })

        suggestions.start("https://example.com/")
        //nothing for the address the editing started with
        assertTrue(rendered.isEmpty())
        assertNull(suggestions.inlineCompletion("https://example.com/"))

        suggestions.query("ex")
        assertEquals(listOf("https://example.com/"), urls(rendered.last()))
        assertEquals(InlineCompletion("example.com", "https://example.com/"), suggestions.inlineCompletion("ex"))

        suggestions.query("")
        assertTrue(rendered.last().isEmpty())

        suggestions.query("oth")
        suggestions.stop()
        assertTrue(rendered.last().isEmpty())
        assertNull(suggestions.inlineCompletion("oth"))
    }
}
