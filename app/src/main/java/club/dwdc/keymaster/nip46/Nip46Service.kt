package club.dwdc.keymaster.nip46

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import club.dwdc.keymaster.R
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keymaster.crypto.NostrKeyService
import club.dwdc.keymaster.crypto.toHexString
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.Nip46Session
import club.dwdc.keymaster.data.Nip46SessionRepository
import club.dwdc.keymaster.data.SeedRepository
import club.dwdc.keymaster.data.SignerKeyRepository
import club.dwdc.keymaster.nip46.relay.RelayPool
import club.dwdc.keymaster.nip46.relay.RelayPoolListener
import club.dwdc.keymaster.ui.MainActivity
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps NIP-46 relay connections alive.
 * Handles incoming kind 24133 requests and sends responses.
 */
class Nip46Service : Service(), RelayPoolListener {

    private lateinit var relayPool: RelayPool
    private lateinit var requestHandler: Nip46RequestHandler
    private lateinit var sessionRepo: Nip46SessionRepository
    private lateinit var signerKeyRepo: SignerKeyRepository
    private var signerPubHex: String = ""
    private var subscriptionId: String = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)     // no read timeout for WebSocket
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        relayPool = RelayPool(client)
        relayPool.setListener(this)
        requestHandler = Nip46RequestHandler(this)
        sessionRepo = Nip46SessionRepository(this)
        signerKeyRepo = SignerKeyRepository(this)
        signerPubHex = signerKeyRepo.getPublicKeyHex()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted the service (START_STICKY) — restore persisted sessions
            restoreSessions()
        } else when (intent.action) {
            ACTION_CONNECT -> handleConnect(intent)
            ACTION_DISCONNECT -> handleDisconnect(intent)
            ACTION_STOP -> {
                relayPool.disconnectAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        unregisterNetworkCallback()
        relayPool.disconnectAll()
        super.onDestroy()
    }

    // --- RelayPoolListener ---

    override fun onRelayConnected(relayUrl: String) {
        Log.d(TAG, "Relay connected: $relayUrl")
    }

    override fun onRelayMessage(relayUrl: String, message: String) {
        try {
            val arr = JsonParser.parseString(message).asJsonArray
            val type = arr.get(0).asString

            when (type) {
                "EVENT" -> {
                    val event = arr.get(2).asJsonObject
                    val kind = event.get("kind").asInt
                    if (kind != 24133) return

                    val senderPub = event.get("pubkey").asString
                    val content = event.get("content").asString
                    val eventId = event.get("id")?.asString ?: ""

                    Log.d(TAG, "Received kind 24133 from $senderPub (event=$eventId)")

                    val responseJson = requestHandler.handleEvent(senderPub, content)
                    if (responseJson != null) {
                        relayPool.sendEventToAll(responseJson)
                    }
                }
                "EOSE" -> Log.d(TAG, "EOSE from $relayUrl")
                "OK" -> {
                    val ok = arr.get(2).asBoolean
                    val msg = if (arr.size() > 3) arr.get(3).asString else ""
                    Log.d(TAG, "OK from $relayUrl: $ok $msg")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing relay message from $relayUrl", e)
        }
    }

    // --- Private ---

    private fun handleConnect(intent: Intent) {
        val connectUrlStr = intent.getStringExtra(EXTRA_CONNECT_URL) ?: return
        val accountIdentity = intent.getStringExtra(EXTRA_ACCOUNT_IDENTITY) ?: return

        val connectUrl = try {
            NostrConnectUrl.parse(connectUrlStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid nostrconnect URL", e)
            return
        }

        // Resolve account pubkey from KVMetaStore
        val store = KeyMasterProvider.getMetaStore(this)
        val nostrEntry = store.byIdentity(accountIdentity)
            .filterIsInstance<NostrKeyEntry>()
            .firstOrNull()
        val accountPubkey = if (nostrEntry != null) {
            nostrEntry.pubkey()
        } else {
            val seedRepo = SeedRepository(this)
            val mnemonic = seedRepo.getMnemonic() ?: return
            val passphrase = seedRepo.getPassphrase()
            NostrKeyService(mnemonic, passphrase, accountIdentity).getPublicKeyHex()
        }

        // Create and persist session
        val session = Nip46Session(
            clientPubkey = connectUrl.clientPubkey,
            clientName = connectUrl.name,
            accountPubkey = accountPubkey,
            accountIdentity = accountIdentity,
            relays = connectUrl.relays,
            permissions = connectUrl.perms,
            createdAt = System.currentTimeMillis() / 1000
        )
        sessionRepo.addSession(session)

        // Connect to relays
        for (relay in connectUrl.relays) {
            relayPool.addRelay(relay)
        }

        // Subscribe for kind 24133 events addressed to our signer pubkey
        ensureSubscription()

        // Send connect response with the secret (anti-hijacking per NIP-46)
        val ackJson = requestHandler.buildConnectAck(connectUrl)
        relayPool.sendEventToAll(ackJson)

        updateNotification()
        Log.d(TAG, "Connected to client ${connectUrl.clientPubkey}, " +
                "relays=${connectUrl.relays}, account=$accountIdentity")
    }

    private fun handleDisconnect(intent: Intent) {
        val clientPubkey = intent.getStringExtra(EXTRA_CLIENT_PUBKEY) ?: return
        val session = sessionRepo.findSession(clientPubkey) ?: return

        sessionRepo.removeSession(clientPubkey)

        // Remove relays that are no longer needed by any session
        val allRelays = sessionRepo.getSessions().flatMap { it.relays }.toSet()
        for (relay in session.relays) {
            if (relay !in allRelays) {
                relayPool.removeRelay(relay)
            }
        }

        val remaining = sessionRepo.getSessions().size
        if (remaining == 0) {
            relayPool.disconnectAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }

        Log.d(TAG, "Disconnected client $clientPubkey, $remaining sessions remaining")
    }

    private fun restoreSessions() {
        val sessions = sessionRepo.getSessions()
        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        for (session in sessions) {
            for (relay in session.relays) {
                relayPool.addRelay(relay)
            }
        }
        ensureSubscription()
        updateNotification()
        Log.d(TAG, "Restored ${sessions.size} sessions")
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                    else -> "UNKNOWN"
                }
                Log.i(TAG, "Network available: transport=$transport, triggering relay reconnect")
                relayPool.reconnectAll()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
            }
        }

        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
        Log.d(TAG, "NetworkCallback registered for BT/WiFi/cellular")
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NetworkCallback", e)
        }
        networkCallback = null
    }

    private fun ensureSubscription() {
        if (subscriptionId.isNotEmpty()) {
            relayPool.unsubscribeAll(subscriptionId)
        }
        subscriptionId = "nip46-${System.currentTimeMillis()}"
        val filter = """{"kinds":[24133],"#p":["$signerPubHex"],"since":${System.currentTimeMillis() / 1000}}"""
        relayPool.subscribeAll(subscriptionId, filter)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NIP-46 Remote Signer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the NIP-46 remote signer connection alive"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (sessionCount == 0) "Starting..."
        else "$sessionCount session${if (sessionCount != 1) "s" else ""} active"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KeyMaster NIP-46")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val count = sessionRepo.getSessions().size
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(count))
    }

    companion object {
        private const val TAG = "Nip46Service"
        private const val CHANNEL_ID = "nip46_service"
        private const val NOTIFICATION_ID = 4601

        private const val ACTION_CONNECT = "club.dwdc.keymaster.nip46.CONNECT"
        private const val ACTION_DISCONNECT = "club.dwdc.keymaster.nip46.DISCONNECT"
        private const val ACTION_STOP = "club.dwdc.keymaster.nip46.STOP"

        private const val EXTRA_CONNECT_URL = "connect_url"
        private const val EXTRA_ACCOUNT_IDENTITY = "account_identity"
        private const val EXTRA_CLIENT_PUBKEY = "client_pubkey"

        fun startConnect(context: Context, connectUrl: String, accountIdentity: String) {
            val intent = Intent(context, Nip46Service::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONNECT_URL, connectUrl)
                putExtra(EXTRA_ACCOUNT_IDENTITY, accountIdentity)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context, clientPubkey: String) {
            val intent = Intent(context, Nip46Service::class.java).apply {
                action = ACTION_DISCONNECT
                putExtra(EXTRA_CLIENT_PUBKEY, clientPubkey)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, Nip46Service::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
