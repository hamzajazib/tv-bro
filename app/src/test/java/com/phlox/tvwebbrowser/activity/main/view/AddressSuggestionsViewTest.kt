package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.Row
import com.phlox.tvwebbrowser.singleton.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h300dp-xhdpi")
class AddressSuggestionsViewTest {
    //the rows look for favicons, which reads the database
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()

    /** Without room for all rows above the phone bar, the worst ones are cut, the best one stays by the bar */
    @Test
    fun theBestRowStaysNextToThePhoneBar() {
        val root = FrameLayout(activity)
        val list = AddressSuggestionsView(activity)
        list.loadFavicon = { null }
        list.rowLayout = R.layout.view_phone_address_suggestion_row
        list.bestAtBottom = true
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200))
        list.render((1..6).map { Row.Page("https://site$it.com/", "Site $it", "site$it.com", listOf("site")) })
        shadowOf(Looper.getMainLooper()).idle()

        val bestRow = list.getChildAt(list.childCount - 1)
        assertTrue("the rows (${list.childCount}) do not fit ${list.height}", list.getChildAt(0).top < 0)
        assertEquals(list.height, bestRow.bottom)
        assertEquals("Site 1", bestRow.findViewById<android.widget.TextView>(R.id.tvTitle).text.toString())
    }

    private fun list(): AddressSuggestionsView {
        val list = AddressSuggestionsView(activity)
        list.loadFavicon = { null }
        activity.setContentView(list)
        return list
    }

    private fun page(n: Int) = Row.Page("https://site$n.com/", "Site $n", "site$n.com", listOf("s"))

    private fun search(text: String) = Row.Search(text, listOf("s"))

    @Test
    fun aSearchHasNoAddressAndAnArrowThatFillsTheField() {
        val list = list()
        val opened = ArrayList<Row>()
        val filled = ArrayList<String>()
        list.onOpen = { opened += it }
        list.onFill = { filled += it }
        list.render(listOf(page(1), search("site news")))
        shadowOf(Looper.getMainLooper()).idle()

        val pageRow = list.getChildAt(0)
        val searchRow = list.getChildAt(1)
        assertEquals(android.view.View.VISIBLE, pageRow.findViewById<android.view.View>(R.id.tvCaption).visibility)
        assertEquals(android.view.View.GONE, pageRow.findViewById<android.view.View>(R.id.ibFill).visibility)
        assertEquals("site news", searchRow.findViewById<android.widget.TextView>(R.id.tvTitle).text.toString())
        assertEquals(android.view.View.GONE, searchRow.findViewById<android.view.View>(R.id.tvCaption).visibility)
        assertEquals(android.view.View.VISIBLE, searchRow.findViewById<android.view.View>(R.id.ibFill).visibility)

        searchRow.findViewById<android.view.View>(R.id.ibFill).performClick()
        assertEquals(listOf("site news"), filled)
        assertTrue(opened.isEmpty())
        searchRow.performClick()
        pageRow.performClick()
        assertEquals(listOf(search("site news"), page(1)), opened)

        //the view of a search shows a page again, and the other way
        list.render(listOf(search("site news"), page(1)))
        assertEquals(android.view.View.GONE, list.getChildAt(0).findViewById<android.view.View>(R.id.tvCaption).visibility)
        assertEquals(android.view.View.VISIBLE, list.getChildAt(1).findViewById<android.view.View>(R.id.tvCaption).visibility)
        assertEquals(android.view.View.GONE, list.getChildAt(1).findViewById<android.view.View>(R.id.ibFill).visibility)
    }

    /** The searches come after the pages are shown: the row chosen meanwhile stays chosen */
    @Test
    fun theChosenRowStaysChosenWhileItIsInTheList() {
        val list = list()
        val highlights = ArrayList<Int>()
        list.onHighlightChanged = { highlights += it }
        list.render(listOf(page(1), page(2), page(3)))
        list.moveHighlight(down = true)
        list.moveHighlight(down = true)
        assertEquals(page(2), list.highlightedRow)

        list.render(listOf(page(1), page(2), search("site news")))
        assertEquals(page(2), list.highlightedRow)
        assertTrue(list.getChildAt(1).isSelected)

        //it moved
        list.render(listOf(page(2), search("site news")))
        assertEquals(0, list.highlighted)
        assertTrue(list.getChildAt(0).isSelected)

        //it is gone: none is chosen
        list.render(listOf(page(1), search("site news")))
        assertEquals(-1, list.highlighted)
        assertEquals(null, list.highlightedRow)
        assertEquals(listOf(0, 1, 0, -1), highlights)
    }
}
