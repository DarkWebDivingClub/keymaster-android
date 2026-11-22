package club.dwdc.keymaster.data

import club.dwdc.keymaster.RelayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SharedRelayLinkTest {

    /** Stands in for an RFCOMM or WebSocket client. */
    private class FakeClient(private val connectDelayMs: Long = 0) : RelayClient {
        private lateinit var listener: RelayClient.Listener
        val sent = CopyOnWriteArrayList<String>()
        var connects = 0
        var closes = 0

        override fun setListener(listener: RelayClient.Listener) {
            this.listener = listener
        }

        override fun connect() {
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
            connects++
            listener.onConnected()
        }

        override fun send(message: String) {
            sent += message
        }

        override fun close() {
            closes++
            listener.onClosed() // as RfcommRelayClient does when its reader stops
        }

        fun receive(text: String) = listener.onMessage(text)
        fun drop() = listener.onClosed()
    }

    /** What a NostrTransport would see. */
    private class Recorder : RelayClient.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val connected = AtomicInteger()
        val closed = AtomicInteger()
        val errors = AtomicInteger()
        override fun onConnected() { connected.incrementAndGet() }
        override fun onMessage(text: String) { messages += text }
        override fun onError(error: String) { errors.incrementAndGet() }
        override fun onClosed() { closed.incrementAndGet() }
    }

    private val key = RelayLinkPool.keyOf(true, "f4:6d:3f:28:64:4f", 5, null)
    private val created = CopyOnWriteArrayList<FakeClient>()
    private val pool = RelayLinkPool()

    private fun handle(delayMs: Long = 0): Pair<RelayClient, Recorder> {
        val recorder = Recorder()
        val h = pool.handle(key) { FakeClient(delayMs).also { created += it } }
        h.setListener(recorder)
        return h to recorder
    }

    @Test
    fun keyIgnoresCaseOfTheAddress() {
        assertEquals("rfcomm:F4:6D:3F:28:64:4F:5", key)
        assertEquals("ws:ws://localhost:7777", RelayLinkPool.keyOf(false, null, 0, "ws://localhost:7777"))
    }

    @Test
    fun secondConnectToTheSameHostReusesTheOpenLink() {
        val (a, ra) = handle()
        val (b, rb) = handle()
        a.connect()
        b.connect()

        assertEquals("one underlying client", 1, created.size)
        assertEquals(1, created[0].connects)
        assertEquals(2, pool.users(key))
        assertEquals("each transport is told it is connected", 1, ra.connected.get())
        assertEquals(1, rb.connected.get())
    }

    @Test
    fun linkClosesOnlyWhenTheLastUserLeaves() {
        val (a, _) = handle()
        val (b, _) = handle()
        a.connect()
        b.connect()

        a.close()
        assertTrue(pool.isOpen(key))
        assertEquals(0, created[0].closes)

        b.close()
        assertFalse(pool.isOpen(key))
        assertEquals(1, created[0].closes)
    }

    @Test
    fun closingAHandleDoesNotCallItsOwnListener() {
        val (a, ra) = handle()
        val (b, _) = handle()
        a.connect()
        b.connect()
        a.close()
        b.close() // closes the client, whose reader then reports onClosed
        assertEquals(0, ra.closed.get())
    }

    @Test
    fun eventsGoOnlyToTheSubscriptionOwner() {
        val (a, ra) = handle()
        val (b, rb) = handle()
        a.connect()
        b.connect()
        a.send("""["REQ","km-1",{"kinds":[27235]}]""")
        b.send("""["REQ","km-2",{"kinds":[27235]}]""")

        created[0].receive("""["EVENT","km-1",{"id":"e1"}]""")
        created[0].receive("""["EOSE","km-2"]""")
        created[0].receive("""["EVENT","nobody",{"id":"e2"}]""")

        assertEquals(listOf("""["EVENT","km-1",{"id":"e1"}]"""), ra.messages)
        assertEquals(listOf("""["EOSE","km-2"]"""), rb.messages)
    }

    @Test
    fun okAndNoticeGoToEveryone() {
        val (a, ra) = handle()
        val (b, rb) = handle()
        a.connect()
        b.connect()
        created[0].receive("""["OK","abc",true,""]""")
        created[0].receive("""["NOTICE","hello"]""")
        assertEquals(2, ra.messages.size)
        assertEquals(2, rb.messages.size)
    }

    @Test
    fun leavingClosesOnlyTheLeaversSubscriptions() {
        val (a, _) = handle()
        val (b, rb) = handle()
        a.connect()
        b.connect()
        a.send("""["REQ","km-1",{}]""")
        b.send("""["REQ","km-2",{}]""")
        a.close()

        assertTrue(created[0].sent.contains("""["CLOSE","km-1"]"""))
        assertFalse(created[0].sent.contains("""["CLOSE","km-2"]"""))
        created[0].receive("""["EVENT","km-2",{}]""")
        assertEquals(1, rb.messages.size)
    }

    @Test
    fun aDroppedLinkClosesEveryUserAndTheNextConnectOpensANewOne() {
        val (a, ra) = handle()
        val (b, rb) = handle()
        a.connect()
        b.connect()

        created[0].drop()
        assertEquals(1, ra.closed.get())
        assertEquals(1, rb.closed.get())
        assertFalse(pool.isOpen(key))

        val (c, _) = handle()
        c.connect()
        assertEquals("a fresh link", 2, created.size)

        // The stale handles can't disturb it.
        a.send("""["REQ","stale",{}]""")
        a.close()
        assertTrue(pool.isOpen(key))
        assertTrue(created[1].sent.isEmpty())
        assertEquals(1, pool.users(key))
    }

    @Test
    fun concurrentConnectsOpenOneLink() {
        val ready = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            Thread {
                val (h, _) = handle(delayMs = 50)
                ready.await()
                h.connect()
                done.countDown()
            }.start()
        }
        ready.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, created.size)
        assertEquals(4, pool.users(key))
    }

    @Test
    fun differentHostsGetDifferentLinks() {
        val (a, _) = handle()
        val b = pool.handle(RelayLinkPool.keyOf(true, "28:6B:35:B4:BA:85", 3, null)) {
            FakeClient().also { created += it }
        }
        b.setListener(Recorder())
        a.connect()
        b.connect()
        assertEquals(2, created.size)
    }

    @Test
    fun subscriptionIdIsReadOnlyForItsType() {
        assertEquals("km-1", RelayLinkPool.subscriptionOf("""["REQ","km-1",{}]""", "REQ"))
        assertNull(RelayLinkPool.subscriptionOf("""["REQ","km-1",{}]""", "EVENT"))
        assertNull(RelayLinkPool.subscriptionOf("""["OK","abc",true,""]""", "EVENT"))
        assertNull(RelayLinkPool.subscriptionOf("not json", "EVENT"))
    }
}
