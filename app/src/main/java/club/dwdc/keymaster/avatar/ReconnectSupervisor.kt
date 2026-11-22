package club.dwdc.keymaster.avatar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * At most one reconnect loop per (realm, connector), and nothing else.
 *
 * Before 0.6.1 the loops were bare coroutines guarded by a flag keyed on
 * `"$realmId:$connectorId"`. Realm ids change (a restore or a detach-all plus
 * re-attach makes a new one for the same connector), so a loop started under
 * the old id did not block a new loop under the new id. The old loop then
 * slept for 60 s, mapped its realm to the live one, and **detached the
 * working connector** there, which started yet another loop. On the primary
 * phone that became a detach every minute (mission 53). Nothing could cancel
 * a loop either: `Thread.sleep` ignores cancellation, and no `Job` was kept.
 *
 * Here:
 * - [start] does nothing when a loop for the key is already running.
 * - Before every attempt the loop asks [Ops.liveRealmFor] where its connector
 *   lives now. If the realm moved, the loop moves its key there, and ends if
 *   another loop already owns that key.
 * - If the connector is connected in its live realm, the loop ends **without
 *   detaching anything**.
 * - Waiting is a coroutine [delay], so [cancel], [cancelRealm] and
 *   [cancelAll] stop a loop at once, even in slow mode.
 */
class ReconnectSupervisor(
    private val scope: CoroutineScope,
    private val ops: Ops,
    private val fastDelaysMs: LongArray = longArrayOf(0, 3000, 5000, 10000, 20000),
    private val slowDelayMs: Long = 60_000L,
    private val log: (String) -> Unit = {}
) {
    /** What a loop needs from the service, injectable for JVM tests. */
    interface Ops {
        /**
         * The realm that holds [connectorId] now, starting from [realmId].
         * Throws when the connector is no longer stored, which ends the loop.
         */
        fun liveRealmFor(realmId: Int, connectorId: Int): Int

        /** Whether [connectorId] is attached and connected in [realmId]. */
        fun isConnected(realmId: Int, connectorId: Int): Boolean

        /** Whether the store still lists [connectorId] anywhere. */
        fun stillWanted(connectorId: Int): Boolean

        /**
         * Remove a dead [connectorId] from [realmId] so it can be attached
         * again. Called only when the connector is not connected.
         */
        fun closeStale(realmId: Int, connectorId: Int)

        /** Attach [connectorId] to [realmId]. Throws on failure. */
        fun attach(realmId: Int, connectorId: Int, descriptorJson: String)

        /** Attempt [attempt] (1-based) is about to run. */
        fun onAttempt(realmId: Int, connectorId: Int, attempt: Int) {}

        /** The connector is back. */
        fun onReattached(realmId: Int, connectorId: Int, attempt: Int) {}

        /** The fast attempts are used up; the loop now waits [slowDelayMs]. */
        fun onSlow(realmId: Int, connectorId: Int) {}
    }

    data class Key(val realmId: Int, val connectorId: Int)

    private val lock = Any()
    private val jobs = HashMap<Key, Job>()

    /** Start a loop for this connector, unless one is already running. */
    fun start(realmId: Int, connectorId: Int, descriptorJson: String): Boolean {
        val key = Key(realmId, connectorId)
        synchronized(lock) {
            if (jobs[key]?.isActive == true) return false
            // LAZY: the job must be in the map before its body can look for it.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                run(key, descriptorJson)
            }
            jobs[key] = job
            job.invokeOnCompletion { synchronized(lock) { jobs.entries.removeIf { it.value === job } } }
            job.start()
            return true
        }
    }

    fun isRunning(realmId: Int, connectorId: Int): Boolean =
        synchronized(lock) { jobs[Key(realmId, connectorId)]?.isActive == true }

    /** The keys of the loops running now. */
    fun running(): Set<Key> = synchronized(lock) { jobs.filterValues { it.isActive }.keys.toSet() }

    fun cancel(realmId: Int, connectorId: Int) {
        synchronized(lock) { jobs.remove(Key(realmId, connectorId)) }?.cancel()
    }

    fun cancelRealm(realmId: Int) {
        val gone = synchronized(lock) {
            val keys = jobs.keys.filter { it.realmId == realmId }
            keys.mapNotNull { jobs.remove(it) }
        }
        gone.forEach { it.cancel() }
    }

    fun cancelAll() {
        val gone = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        gone.forEach { it.cancel() }
    }

    private suspend fun run(startKey: Key, descriptorJson: String) {
        val connectorId = startKey.connectorId
        var key = startKey
        var attempt = 0
        while (true) {
            val wait = fastDelaysMs.getOrElse(attempt) { slowDelayMs }
            if (attempt >= fastDelaysMs.size && !ops.stillWanted(connectorId)) {
                log("Connector $connectorId is no longer stored; giving up")
                return
            }
            ops.onAttempt(key.realmId, connectorId, attempt + 1)
            if (wait > 0) {
                log("Connector $connectorId retry ${attempt + 1} in ${wait}ms")
                delay(wait)
            }

            val live = try {
                ops.liveRealmFor(key.realmId, connectorId)
            } catch (e: Exception) {
                log("Connector $connectorId: ${e.message}; giving up")
                return
            }
            if (live != key.realmId) {
                if (!rekey(key, Key(live, connectorId))) {
                    log("Connector $connectorId: realm ${key.realmId} is now $live, " +
                        "where another loop already runs; ending this one")
                    return
                }
                log("Connector $connectorId: following realm ${key.realmId} to $live")
                key = Key(live, connectorId)
            }
            if (ops.isConnected(live, connectorId)) {
                log("Connector $connectorId is already connected in realm $live; nothing to do")
                return
            }

            try {
                ops.closeStale(live, connectorId)
                ops.attach(live, connectorId, descriptorJson)
                ops.onReattached(live, connectorId, attempt + 1)
                return
            } catch (e: Exception) {
                log("Re-attach ${attempt + 1} failed: ${e.message}")
            }
            attempt++
            if (attempt == fastDelaysMs.size) {
                log("Fast attempts exhausted for connector $connectorId; " +
                    "retrying every ${slowDelayMs / 1000}s")
                ops.onSlow(key.realmId, connectorId)
            }
        }
    }

    /** Move the calling loop from [from] to [to]; false if [to] is taken. */
    private fun rekey(from: Key, to: Key): Boolean = synchronized(lock) {
        val mine = jobs[from]
        val other = jobs[to]
        if (other != null && other !== mine && other.isActive) {
            jobs.remove(from)
            return false
        }
        jobs.remove(from)
        if (mine != null) jobs[to] = mine
        true
    }
}
