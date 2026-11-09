package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewTvSendScreenBinding
import com.phlox.tvwebbrowser.send.QrBitmap
import com.phlox.tvwebbrowser.send.SendLinks
import com.phlox.tvwebbrowser.send.SendSession
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The screen of the TV that other devices send to, over the whole window: the QR code of the page
 * "Send to TV Bro" of the site and its short address, the state of the cloud and of the local network,
 * what came lately and New link. Back closes it; [Callback.onClosed] gives the focus back.
 */
class TvSendScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        val state: StateFlow<SendSession.State?>
        /** What came lately, the newest first */
        val received: StateFlow<List<String>>
        fun onOpened()
        fun newLink()
        fun onClosed()
    }

    private val vb: ViewTvSendScreenBinding
    private lateinit var scope: CoroutineScope
    private lateinit var callback: Callback
    private var jobs = emptyList<Job>()
    private var shownInbox: String? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        //clicks and the cursor do not reach the page under it
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsSurface))
        vb = ViewTvSendScreenBinding.inflate(LayoutInflater.from(context), this)
        vb.ibBack.setOnClickListener { hide() }
        vb.llNewLink.setOnClickListener { callback.newLink() }
    }

    fun attach(scope: CoroutineScope, callback: Callback) {
        this.scope = scope
        this.callback = callback
    }

    fun show() {
        if (isOpen) return
        visibility = VISIBLE
        backCallback.isEnabled = true
        shownInbox = null
        vb.ivQr.setImageDrawable(null)
        callback.onOpened()
        jobs = listOf(
            scope.launch { callback.state.collect(::render) },
            scope.launch { callback.received.collect(::renderReceived) }
        )
        vb.ibBack.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed()
    }

    private fun render(state: SendSession.State?) {
        val inbox = state?.inbox
        if (inbox != shownInbox) {
            shownInbox = inbox
            vb.ivQr.setImageBitmap(inbox?.let { QrBitmap.of(SendLinks.pageUrl(it)) })
            //the bitmap is drawn at the pixels of its modules: no blur
            (vb.ivQr.drawable as? BitmapDrawable)?.isFilterBitmap = false
        }
        vb.pbQr.isVisible = inbox == null
        vb.tvAddress.text = state?.code?.let(SendLinks::codeAddress) ?: context.getString(R.string.send_code_waiting)
        vb.tvCloud.text = context.getString(when (state?.cloud) {
            SendSession.CloudState.LISTENING -> R.string.send_cloud_listening
            SendSession.CloudState.OFFLINE -> R.string.send_cloud_offline
            else -> R.string.send_cloud_connecting
        })
        val pages = state?.localPages.orEmpty()
        vb.tvLocal.text = if (pages.isEmpty()) context.getString(R.string.send_no_local_network)
        else context.getString(R.string.send_local_network, pages.joinToString(", "))
    }

    private fun renderReceived(received: List<String>) {
        vb.tvReceived.text = if (received.isEmpty()) context.getString(R.string.send_nothing_received)
        else received.joinToString("\n")
    }

    /** Keys move the focus only inside the screen, not to the bar under it */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }
}
