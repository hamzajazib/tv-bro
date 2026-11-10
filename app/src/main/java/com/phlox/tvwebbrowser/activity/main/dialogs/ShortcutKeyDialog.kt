package com.phlox.tvwebbrowser.activity.main.dialogs

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.singleton.shortcuts.toDisplayString
import com.phlox.tvwebbrowser.utils.NavigationReservedShortcutKeyCodes

/**
 * Adds a key to a [shortcut]: the next key pressed (held long, as a long press; a modifier with it) is added
 * to its keys and the dialog closes. A key another action has is added only when it is pressed a second
 * time, after the dialog told which action loses it. Clear removes all keys of the action, Default gives it
 * its default keys again. The keys of the browser navigation (the D-pad, OK, Back) still move the focus to
 * the buttons, so they can not be set. A dialog has its own window, so the keys do not reach the shortcuts
 * of the activity while it is open.
 */
class ShortcutKeyDialog(context: Context, private val shortcut: Shortcut) : AlertDialog(context) {
    private val shortcutMgr = ShortcutMgr.getInstance(context)
    /** A long press was seen: it is saved when the key goes up, so the up does not reach the activity */
    private var longPressBinding: KeyBinding? = null
    /** A key of another action pressed once: pressed again, it moves to this one */
    private var keyToMove: KeyBinding? = null
    /** The modifiers held when the key went down: they may be released before it */
    private var downModifiers = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(shortcut.titleResId)
        setMessage(defaultMessage())
        if (shortcutMgr.bindingsOf(shortcut).isNotEmpty()) {
            setButton(DialogInterface.BUTTON_NEUTRAL, context.getString(R.string.clear)) { _, _ ->
                shortcutMgr.save(shortcut, emptyList())
            }
        }
        setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel)) { _, _ -> }
        if (!shortcutMgr.hasDefaultBindings(shortcut)) {
            setButton(DialogInterface.BUTTON_POSITIVE, context.getString(R.string.settings_keys_default)) { _, _ ->
                shortcutMgr.resetToDefault(shortcut)
            }
        }
        super.onCreate(savedInstanceState)
    }

    private fun defaultMessage(): String {
        val keys = shortcutMgr.bindingsOf(shortcut)
        var message = context.getString(R.string.settings_press_key) + "\n\n" +
                context.getString(R.string.settings_key_now_s, keys.toDisplayString(context))
        if (keys.size >= Shortcut.MAX_BINDINGS) {
            message += "\n" + context.getString(R.string.settings_keys_max_d, Shortcut.MAX_BINDINGS)
        }
        return message
    }

    private fun resolveKeyCode(event: KeyEvent): Int = if (event.keyCode != 0) event.keyCode else event.scanCode

    private fun isReserved(event: KeyEvent) =
        resolveKeyCode(event) in NavigationReservedShortcutKeyCodes.reservedForUserShortcuts

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyDown(keyCode, event)
        //a modifier waits for the key it is held with
        if (!KeyBinding.isModifierKey(resolveKeyCode(event)) && event.repeatCount == 0) {
            downModifiers = event.modifiers
            event.startTracking()
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyLongPress(keyCode, event)
        longPressBinding = KeyBinding(resolveKeyCode(event), event.modifiers, longPress = true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyUp(keyCode, event)
        if (KeyBinding.isModifierKey(resolveKeyCode(event))) return true
        val binding = longPressBinding ?: KeyBinding(resolveKeyCode(event), downModifiers)
        longPressBinding = null
        val owner = shortcutMgr.shortcutOf(binding)
        if (owner != null && owner != shortcut && keyToMove != binding) {
            keyToMove = binding
            setMessage(context.getString(R.string.settings_key_taken_s_s, binding.toDisplayString(context),
                context.getString(owner.titleResId)))
            return true
        }
        shortcutMgr.add(shortcut, binding)
        dismiss()
        return true
    }
}
