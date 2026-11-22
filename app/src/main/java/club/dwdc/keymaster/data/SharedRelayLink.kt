package club.dwdc.keymaster.data

import club.dwdc.keymaster.RelayClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One relay link per host, shared by every connector that reaches the host
 * through it.
 *
 * RFCOMM derives its DLCI from the server channel, so a phone can hold only
 * one connection to a given channel on a given adapter. Before 0.6.1 every
 * attach opened its own [RelayClient]. A second realm for a second avatar on
 * the same host therefore opened a second RFCOMM connection, which failed and
 * took the first realm's working link down with it (mission 53, verified on
 * hardware 2026-11-22). The relay behind the link already keeps the avatars
 * apart by pubkey, so one link can carry them all.
 *
 * [handle] returns a [RelayClient] per connector. Handles with the same key
 * share one underlying client. The first connect builds it with that handle's
 * `create`, and it is closed when the last handle closes.
 *
 * Routing:
 * - `EVENT`, `EOSE` and `CLOSED` carry a subscription id and go only to the
 *   handle that sent the matching `REQ`. A message for a subscription nobody
 *   holds is dropped.
 * - Everything else (`OK`, `NOTICE`, `AUTH`, …) goes to every handle. Each
 *   transport already ignores what isn't its own: it drops events it can't
 *   decrypt and replies it isn't waiting for.
 *
 * Link lifetime:
 * - Connecting is serialised per key, so two connectors reconnecting at once
 *   never open two links. The second one finds the first one's link and
 *   joins it.
 * - A link that closes or fails is gone for good: every handle on it gets
 *   `onClosed`, and the next connect opens a new link. A stale handle's
 *   `send` is dropped, and its `close` does nothing to the new link.
 * - Closing a handle never calls its own listener. Before 0.6.1, closing a
 *   dead connector during a reconnect fired DISCONNECTED again, which started
 *   another reconnect loop.
 */
class RelayLinkPool(private val log: (String) -> Unit = {}) {
    private val links = ConcurrentHashMap<String, Link>()
    private val connectLocks = ConcurrentHashMap<String, Any>()

    /**
     * A new handle on the link for [key]; nothing is opened until `connect()`.
     * [create] builds the underlying client if no live link exists then.
     */
    fun handle(key: String, create: () -> RelayClient): RelayClient = Handle(key, create)

    /** How many handles hold the live link for [key]. For tests and logs. */
    fun users(key: String): Int = links[key]?.handles?.size ?: 0

    /** Whether a live link exists for [key]. */
    fun isOpen(key: String): Boolean = links[key]?.alive == true

    private fun join(handle: Handle): Link {
        val lock = connectLocks.computeIfAbsent(handle.key) { Any() }
        synchronized(lock) {
            val existing = links[handle.key]
            if (existing != null && existing.alive) {
                existing.handles.add(handle)
                log("Link ${handle.key}: joined (${existing.handles.size} users)")
                handle.listener?.onConnected()
                return existing
            }
            val link = Link(handle.key, handle.create())
            link.open()
            link.handles.add(handle)
            links[handle.key] = link
            log("Link ${handle.key}: opened")
            handle.listener?.onConnected()
            return link
        }
    }

    private fun forget(link: Link) {
        links.remove(link.key, link)
    }

    private inner class Link(val key: String, val client: RelayClient) {
        val handles = CopyOnWriteArrayList<Handle>()
        private val owners = ConcurrentHashMap<String, Handle>()

        @Volatile
        var alive = false
            private set

        fun open() {
            client.setListener(object : RelayClient.Listener {
                // The connecting handle is told by join() once it is on the
                // list; nobody else is on a link that is just opening.
                override fun onConnected() {}

                override fun onMessage(text: String) = route(text)

                override fun onError(error: String) {
                    handles.forEach { it.listener?.onError(error) }
                }

                override fun onClosed() = died()
            })
            client.connect()
            alive = true
        }

        fun send(from: Handle, message: String) {
            if (!alive) {
                log("Link $key: dropping send on a closed link")
                return
            }
            subscriptionOf(message, "REQ")?.let { owners[it] = from }
            subscriptionOf(message, "CLOSE")?.let { owners.remove(it) }
            client.send(message)
        }

        fun release(handle: Handle) {
            if (!handles.remove(handle)) return
            owners.entries.filter { it.value === handle }.forEach { (sub, _) ->
                owners.remove(sub)
                if (alive) client.send("[\"CLOSE\",\"$sub\"]")
            }
            if (handles.isEmpty()) {
                log("Link $key: last user left; closing")
                alive = false
                forget(this)
                client.close()
            } else {
                log("Link $key: user left (${handles.size} remain)")
            }
        }

        private fun route(text: String) {
            val sub = subscriptionOf(text, "EVENT")
                ?: subscriptionOf(text, "EOSE")
                ?: subscriptionOf(text, "CLOSED")
            if (sub == null) {
                handles.forEach { it.listener?.onMessage(text) }
                return
            }
            owners[sub]?.listener?.onMessage(text)
        }

        private fun died() {
            if (!alive && handles.isEmpty()) return // closed by its last user
            alive = false
            forget(this)
            val gone = handles.toList()
            handles.clear()
            owners.clear()
            log("Link $key: closed under ${gone.size} users")
            gone.forEach { it.listener?.onClosed() }
        }
    }

    private inner class Handle(val key: String, val create: () -> RelayClient) : RelayClient {
        @Volatile
        var listener: RelayClient.Listener? = null
            private set

        @Volatile
        private var link: Link? = null

        override fun setListener(listener: RelayClient.Listener) {
            this.listener = listener
        }

        override fun connect() {
            checkNotNull(listener) { "listener must be set before connect()" }
            link = join(this)
        }

        override fun send(message: String) {
            val current = link
            if (current == null) {
                log("Link $key: send before connect; dropped")
                return
            }
            current.send(this, message)
        }

        override fun close() {
            link?.release(this)
            link = null
        }
    }

    companion object {
        private val SUBSCRIPTION = Regex("""^\s*\[\s*"([A-Z]+)"\s*,\s*"([^"\\]*)"""")

        /** The subscription id of a `[TYPE, "sub", …]` message of the given type. */
        fun subscriptionOf(message: String, type: String): String? {
            val m = SUBSCRIPTION.find(message) ?: return null
            return if (m.groupValues[1] == type) m.groupValues[2] else null
        }

        /** The key under which connectors share a link. */
        fun keyOf(isRfcomm: Boolean, btAddr: String?, channel: Int, relay: String?): String =
            if (isRfcomm) "rfcomm:${btAddr.orEmpty().uppercase()}:$channel"
            else "ws:${relay.orEmpty()}"
    }
}
