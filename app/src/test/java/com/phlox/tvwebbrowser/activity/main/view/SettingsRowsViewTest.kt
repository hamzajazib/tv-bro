package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Key
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Row
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SettingsRowsViewTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()
    private val log = ArrayList<String>()

    private val rows = SettingsRowsView(activity).apply {
        callback = object : SettingsRowsView.Callback {
            override fun openChoice(row: Row.Choice, anchor: View) {}
            override fun choose(key: Key, index: Int) {}
            override fun toggle(row: Row.Toggle) {}
            override fun setSlider(key: Key, value: Int) {}
            override fun setText(key: Key, text: String) { log += "text $text" }
            override fun act(row: Row) { log += "act ${row.key}" }
        }
        activity.setContentView(this)
    }

    private fun field(text: String, busy: Boolean) = listOf(Section("Search", listOf(
        Row.Field(Key.SEARCH_SUGGEST_URL, "Suggestions address", null, text, "hint", action = "Detect", busy = busy))))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A button hidden while its field is busy would lose the focus of the remote */
    @Test
    fun theButtonOfAFieldKeepsTheFocusWhileItIsBusy() {
        rows.render(field("", busy = false))
        idle()
        val button = rows.findViewById<View>(R.id.btnAction)
        val progress = rows.findViewById<View>(R.id.pbBusy)
        assertTrue(button.requestFocus())
        button.performClick()
        assertEquals(listOf("act SEARCH_SUGGEST_URL"), log)

        rows.render(field("", busy = true))
        idle()
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isFocused)

        //the answer fills the field, the focus is still on the button
        rows.render(field("https://example.com/ac?q=[query]", busy = false))
        idle()
        assertEquals(View.GONE, progress.visibility)
        assertTrue(button.isFocused)
        assertEquals("https://example.com/ac?q=[query]", rows.findViewById<EditText>(R.id.etValue).text.toString())
    }

    /** With the cursor at the start, Right of the remote would go through the whole address before the button */
    @Test
    fun aFocusedFieldWithAButtonHasTheCursorAtItsEnd() {
        rows.render(field("https://example.com/ac?q=[query]", busy = false))
        idle()
        val text = rows.findViewById<EditText>(R.id.etValue)
        assertTrue(text.requestFocus())
        idle()
        assertEquals(text.text.length, text.selectionStart)
        assertEquals(text.text.length, text.selectionEnd)
    }
}
