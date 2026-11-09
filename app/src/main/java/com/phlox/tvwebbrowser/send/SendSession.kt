package com.phlox.tvwebbrowser.send

import android.util.Log
import com.phlox.tvwebbrowser.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Waits for what other devices send to the TV while its screen "Send from phone" is open: messages of the
 * inbox in the cloud ([CloudInbox]) and requests to the server of the TV in the local network
 * ([LocalSendServer]). It starts when the screen opens ([open]) and stops when it closes or the activity
 * stops ([close]): the free plan of the database takes 100 connections at once for all TVs. A file that is
 * coming meanwhile still comes whole, unless the session is aborted. Lives on the main thread.
 */
class SendSession(
    private val settings: SendSettings,
    private val cloud: CloudInbox,
    private val scope: CoroutineScope,
    /** Outlives the session: the writes that tell the page the TV stopped */
    private val cleanupScope: CoroutineScope,
    private val host: Host,
    private val createServer: (key: String, LocalSendServer.Host) -> LocalSendServer = { key, h -> LocalSendServer(key, h) }
) {
    interface Host : LocalSendServer.Host

    enum class CloudState { CONNECTING, LISTENING, OFFLINE }

    data class State(
        val inbox: String,
        /** Null until the cloud took it */
        val code: String?,
        val cloud: CloudState,
        /** `http://<address>:<port>/` of the page of the TV, empty without a network or server */
        val localPages: List<String>
    )

    companion object {
        private const val TAG = "SendSession"
        /** How long the page and the rules take the TV for listening after its last word (its process may die) */
        private const val INFO_TTL = 3 * 60_000L
        private const val INFO_REFRESH = 60_000L
        private const val CODE_TTL = 10 * 60_000L
        /** Messages written before the TV listened are opened only if they are this fresh */
        private const val QUEUED_MESSAGE_AGE = 2 * 60_000L
        private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
    }

    private val _state = MutableStateFlow<State?>(null)
    /** Null while the session does not run */
    val state: StateFlow<State?> = _state

    private var server: LocalSendServer? = null
    /** Servers of closed sessions that may still be finishing files */
    private val finishing = ArrayList<LocalSendServer>()
    private var key = ""
    private var inbox = ""
    private var jobs = emptyList<Job>()
    private var codeJob: Job? = null
    /** Messages opened already: the TV may get one again when its deletion failed */
    private val handled = LinkedHashSet<String>()

    val isRunning: Boolean get() = _state.value != null

    fun open() {
        if (isRunning) return
        start()
        codeJob = scope.launch { rotateCodes() }
    }

    /** A new inbox: the links devices know stop working */
    fun newLink() {
        settings.newInbox()
        val wasRunning = isRunning
        close()
        if (wasRunning) open()
    }

    /**
     * Stops listening to the cloud and the local network at once and deletes the info and the code of the TV
     * (the page of the site then tells the TV does not wait; the database keeps nothing of it).
     * @param abort also cuts the files that are coming (the activity is destroyed)
     */
    fun close(abort: Boolean = false) {
        if (abort) {
            finishing.forEach { it.stop() }
            finishing.clear()
        }
        if (!isRunning) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        codeJob?.cancel()
        codeJob = null
        server?.let {
            if (abort) {
                it.stop()
            } else {
                it.stopListening()
                finishing += it
            }
        }
        server = null
        val state = _state.value
        _state.value = null
        if (state != null) {
            cleanupScope.launch {
                ignoreErrors { cloud.deleteInfo(state.inbox) }
                state.code?.let { ignoreErrors { cloud.deleteCode(it) } }
            }
        }
    }

    private fun start() {
        inbox = settings.inbox
        key = SendLinks.newKey()
        val server = createServer(key, host)
        val port = server.start(settings.serverPort)
        if (port != 0) {
            settings.serverPort = port
            this.server = server
        }
        clockKnown.value = false
        _state.value = State(inbox, null, CloudState.CONNECTING, localPages(port))
        if (BuildConfig.DEBUG) Log.d(TAG, "link: ${SendLinks.pageUrl(inbox)} local: ${_state.value?.localPages}")
        jobs = listOf(
            scope.launch { keepInfo() },
            scope.launch { listen() }
        )
    }

    /** The info was written once: the clock of the server is known (the TV's may be far off) */
    private val clockKnown = MutableStateFlow(false)

    private fun localPages(port: Int): List<String> =
        if (port == 0) emptyList() else settings.localAddresses().map { "http://$it:$port/" }

    private fun lanAddresses(): List<String> {
        val port = server?.port ?: 0
        return if (port == 0) emptyList() else settings.localAddresses().map { "$it:$port" }
    }

    /** Tells the page of the site where the TV is and until when it listens */
    private suspend fun keepInfo() {
        while (true) {
            try {
                val offset = cloud.clockOffset
                cloud.putInfo(inbox, CloudInbox.Info(settings.deviceName, lanAddresses(), key, cloud.serverTime() + INFO_TTL))
                //the time it wrote was of a clock far off: again with the one of the server
                if (Math.abs(cloud.clockOffset - offset) > 30_000L) continue
                clockKnown.value = true
                delay(INFO_REFRESH)
            } catch (e: IOException) {
                Log.w(TAG, "can not write the info: $e")
                delay(RETRY_DELAYS.first() * 5)
            }
        }
    }

    private suspend fun rotateCodes() {
        clockKnown.first { it }
        while (true) {
            val code = SendLinks.newCode()
            try {
                cloud.putCode(code, inbox, cloud.serverTime() + CODE_TTL)
            } catch (e: IOException) {
                Log.w(TAG, "can not write a code: $e")
                delay(RETRY_DELAYS[1])
                continue
            }
            val old = _state.value?.code
            _state.value = _state.value?.copy(code = code)
            old?.let { dropCode(it) }
            //a minute before it expires, so a code read from the screen still works
            delay(CODE_TTL - 60_000L)
        }
    }

    private fun dropCode(code: String) {
        cleanupScope.launch { ignoreErrors { cloud.deleteCode(code) } }
    }

    private suspend fun listen() {
        var failures = 0
        while (true) {
            try {
                cloud.listen(inbox) { messages, initial ->
                    scope.launch { onCloudMessages(messages, initial) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "the inbox stream failed: $e")
            }
            setCloud(CloudState.OFFLINE)
            delay(RETRY_DELAYS[failures.coerceAtMost(RETRY_DELAYS.size - 1)])
            failures++
            setCloud(CloudState.CONNECTING)
        }
    }

    private fun setCloud(cloudState: CloudState) {
        _state.value = _state.value?.copy(cloud = cloudState)
    }

    private suspend fun onCloudMessages(messages: List<CloudInbox.Message>, initial: Boolean) {
        if (!isRunning) return
        if (initial) setCloud(CloudState.LISTENING)
        val now = cloud.serverTime()
        for (message in messages.sortedBy { it.time }) {
            val fresh = !initial || now - message.time < QUEUED_MESSAGE_AGE
            if (fresh && handled.add(message.key)) {
                if (handled.size > 100) handled.remove(handled.first())
                host.onText(message.text, message.from)
            }
            cleanupScope.launch { ignoreErrors { cloud.deleteMessage(inbox, message.key) } }
        }
    }

    private suspend fun ignoreErrors(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: IOException) {
            Log.d(TAG, "write failed: $e")
        }
    }
}
