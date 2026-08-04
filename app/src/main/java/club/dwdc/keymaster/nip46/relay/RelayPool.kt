package club.dwdc.keymaster.nip46.relay

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient

/**
 * Manages multiple relay connections with automatic reconnection.
 * Deduplicates relay URLs and provides broadcast methods.
 */
class RelayPool(private val client: OkHttpClient) : RelayListener {

    private val relays = mutableMapOf<String, NostrRelay>()
    private val pendingSubscriptions = mutableMapOf<String, String>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectDelays = mutableMapOf<String, Long>()
    private var poolListener: RelayPoolListener? = null

    fun setListener(listener: RelayPoolListener) {
        poolListener = listener
    }

    fun addRelay(url: String) {
        val normalized = normalizeUrl(url)
        if (relays.containsKey(normalized)) return
        val relay = NostrRelay(normalized, this, client)
        relays[normalized] = relay
        reconnectDelays[normalized] = INITIAL_BACKOFF_MS
        relay.connect()
    }

    fun removeRelay(url: String) {
        val normalized = normalizeUrl(url)
        relays.remove(normalized)?.disconnect()
        reconnectDelays.remove(normalized)
    }

    fun sendEventToAll(eventJson: String) {
        for (relay in relays.values) {
            if (relay.isConnected) {
                relay.sendEvent(eventJson)
            }
        }
    }

    fun subscribeAll(subscriptionId: String, filterJson: String) {
        pendingSubscriptions[subscriptionId] = filterJson
        for (relay in relays.values) {
            if (relay.isConnected) {
                relay.subscribe(subscriptionId, filterJson)
            }
        }
    }

    fun unsubscribeAll(subscriptionId: String) {
        pendingSubscriptions.remove(subscriptionId)
        for (relay in relays.values) {
            if (relay.isConnected) {
                relay.unsubscribe(subscriptionId)
            }
        }
    }

    /**
     * Force immediate reconnect on all disconnected relays, bypassing backoff.
     * Called by NetworkCallback when network is restored.
     */
    fun reconnectAll() {
        val disconnected = relays.values.filter { !it.isConnected }
        if (disconnected.isEmpty()) return
        Log.i(TAG, "Forcing reconnect on ${disconnected.size} disconnected relay(s)")
        for (relay in disconnected) {
            reconnectDelays[relay.url] = INITIAL_BACKOFF_MS  // reset backoff
            reconnectHandler.removeCallbacksAndMessages(null) // cancel pending retries
            relay.connect()
        }
    }

    fun disconnectAll() {
        reconnectHandler.removeCallbacksAndMessages(null)
        for (relay in relays.values) {
            relay.disconnect()
        }
        relays.clear()
        pendingSubscriptions.clear()
        reconnectDelays.clear()
    }

    // RelayListener callbacks

    override fun onRelayConnected(relay: NostrRelay) {
        reconnectDelays[relay.url] = INITIAL_BACKOFF_MS
        // Re-send pending subscriptions to this relay
        for ((subId, filterJson) in pendingSubscriptions) {
            relay.subscribe(subId, filterJson)
        }
        poolListener?.onRelayConnected(relay.url)
    }

    override fun onRelayMessage(relay: NostrRelay, message: String) {
        poolListener?.onRelayMessage(relay.url, message)
    }

    override fun onRelayDisconnected(relay: NostrRelay, error: Throwable?) {
        if (!relays.containsKey(relay.url)) return // removed, don't reconnect

        val delay = reconnectDelays[relay.url] ?: INITIAL_BACKOFF_MS
        Log.d(TAG, "Scheduling reconnect to ${relay.url} in ${delay}ms")
        reconnectHandler.postDelayed({
            if (relays.containsKey(relay.url)) {
                Log.d(TAG, "Reconnecting to ${relay.url}")
                relay.connect()
            }
        }, delay)
        // Exponential backoff: 1s -> 2s -> 4s -> ... -> 60s max
        reconnectDelays[relay.url] = (delay * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }

    companion object {
        private const val TAG = "RelayPool"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60000L
    }
}

interface RelayPoolListener {
    fun onRelayConnected(relayUrl: String)
    fun onRelayMessage(relayUrl: String, message: String)
}
