package club.dwdc.keymaster.avatar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ReconnectSupervisorTest {

    /** The service as the loops see it; every call is recorded. */
    private class FakeOps : ReconnectSupervisor.Ops {
        /** connector → the realm that holds it now. */
        val liveRealm = ConcurrentHashMap<Int, Int>()
        val connected = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
        val wanted = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var attachFails = true

        val closed = CopyOnWriteArrayList<Pair<Int, Int>>()
        val attached = CopyOnWriteArrayList<Pair<Int, Int>>()
        val attempts = CopyOnWriteArrayList<Pair<Int, Int>>()

        override fun liveRealmFor(realmId: Int, connectorId: Int): Int =
            liveRealm[connectorId] ?: realmId

        override fun isConnected(realmId: Int, connectorId: Int) =
            (realmId to connectorId) in connected

        override fun stillWanted(connectorId: Int) = connectorId in wanted

        override fun closeStale(realmId: Int, connectorId: Int) {
            closed += realmId to connectorId
        }

        override fun attach(realmId: Int, connectorId: Int, descriptorJson: String) {
            if (attachFails) throw IllegalStateException("read failed")
            attached += realmId to connectorId
            connected += realmId to connectorId
        }

        override fun onAttempt(realmId: Int, connectorId: Int, attempt: Int) {
            attempts += realmId to attempt
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = FakeOps()

    /** Fast delays in milliseconds so a test runs in well under a second. */
    private fun supervisor(slowMs: Long = 50) =
        ReconnectSupervisor(scope, ops, longArrayOf(0, 5, 5), slowMs)

    @After
    fun tearDown() = scope.cancel()

    private fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in time")
            Thread.sleep(5)
        }
    }

    @Test
    fun aSecondStartForARunningLoopIsIgnored() {
        ops.wanted += 7
        val s = supervisor()
        assertTrue(s.start(1, 7, "{}"))
        assertFalse(s.start(1, 7, "{}"))
        assertEquals(setOf(ReconnectSupervisor.Key(1, 7)), s.running())
        s.cancelAll()
    }

    @Test
    fun aReconnectClosesTheStaleConnectorThenAttachesOnce() {
        ops.attachFails = false
        val s = supervisor()
        s.start(1, 7, "{}")
        waitUntil { ops.attached.isNotEmpty() && s.running().isEmpty() }
        assertEquals(listOf(1 to 7), ops.closed)
        assertEquals(listOf(1 to 7), ops.attached)
    }

    @Test
    fun aConnectedConnectorIsNeverDetached() {
        ops.connected += 1 to 7
        val s = supervisor()
        s.start(1, 7, "{}")
        waitUntil { s.running().isEmpty() }
        assertTrue("no detach of a working connector", ops.closed.isEmpty())
        assertTrue(ops.attached.isEmpty())
    }

    /**
     * The 0.6.0 bug: a loop started under an old realm id wakes up, finds its
     * connector renumbered into a realm where another loop already runs, and
     * must leave rather than detach anything.
     */
    @Test
    fun aRenumberedLoopEndsWhenTheNewKeyIsTaken() {
        ops.wanted += 7
        val s = supervisor(slowMs = 10_000)
        assertTrue(s.start(2, 7, "{}")) // the live realm's own loop
        waitUntil { ops.attempts.count { it.first == 2 } >= 4 } // now in slow mode

        ops.liveRealm[7] = 2 // realm 1 was renumbered to 2
        val closedBefore = ops.closed.size
        assertTrue(s.start(1, 7, "{}")) // the orphan from realm 1
        waitUntil { !s.isRunning(1, 7) }

        assertEquals(setOf(ReconnectSupervisor.Key(2, 7)), s.running())
        assertEquals("the orphan detached nothing", closedBefore, ops.closed.size)
        s.cancelAll()
    }

    @Test
    fun aRenumberedLoopFollowsItsRealmWhenTheKeyIsFree() {
        ops.liveRealm[7] = 2
        ops.attachFails = false
        val s = supervisor()
        s.start(1, 7, "{}")
        waitUntil { ops.attached.isNotEmpty() }
        assertEquals(listOf(2 to 7), ops.attached)
    }

    @Test
    fun aLoopEndsWhenItsConnectorComesBackWhileItWaits() {
        ops.wanted += 7
        val s = supervisor(slowMs = 30)
        s.start(1, 7, "{}")
        waitUntil { ops.attempts.size >= 4 }
        ops.connected += 1 to 7 // another path re-attached it
        waitUntil { s.running().isEmpty() }
        val closedThen = ops.closed.size
        Thread.sleep(100)
        assertEquals("no detach after it came back", closedThen, ops.closed.size)
    }

    @Test
    fun detachAllCancelsALoopSleepingInSlowMode() {
        ops.wanted += 7
        val s = supervisor(slowMs = 60_000)
        s.start(1, 7, "{}")
        waitUntil { ops.attempts.size >= 4 } // sleeping 60 s now
        val before = System.currentTimeMillis()
        s.cancelAll()
        waitUntil { s.running().isEmpty() }
        assertTrue("stopped at once", System.currentTimeMillis() - before < 1000)
    }

    @Test
    fun cancelRealmStopsOnlyThatRealmsLoops() {
        ops.wanted += 7
        val s = supervisor(slowMs = 60_000)
        s.start(1, 7, "{}")
        s.start(2, 7, "{}")
        s.cancelRealm(1)
        waitUntil { !s.isRunning(1, 7) }
        assertTrue(s.isRunning(2, 7))
        s.cancelAll()
    }

    @Test
    fun theSameConnectorInTwoRealmsGetsTwoLoops() {
        ops.wanted += 7
        val s = supervisor(slowMs = 60_000)
        assertTrue(s.start(1, 7, "{}"))
        assertTrue(s.start(2, 7, "{}"))
        assertEquals(
            setOf(ReconnectSupervisor.Key(1, 7), ReconnectSupervisor.Key(2, 7)),
            s.running()
        )
        s.cancelAll()
    }

    @Test
    fun aLoopGivesUpWhenItsConnectorIsNoLongerStored() {
        val s = supervisor() // 7 is not wanted
        s.start(1, 7, "{}")
        waitUntil { s.running().isEmpty() }
        assertEquals("only the fast attempts ran", 3, ops.attempts.size)
    }
}
