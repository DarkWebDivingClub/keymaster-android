package club.dwdc.keymaster.avatar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import club.dwdc.keymaster.AbstractKeyEntry
import club.dwdc.keymaster.AvatarDescriptor
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.NostrTransport
import club.dwdc.keymaster.R
import club.dwdc.keymaster.data.AvatarSession
import club.dwdc.keymaster.data.AvatarSessionRepository
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Avatar relay connection alive.
 * Handles attach/detach lifecycle for NipxxConnector via KeyMasterController.
 *
 * Supports two transport modes:
 * - WebSocket (PAN): reconnects via ConnectivityManager.NetworkCallback
 * - RFCOMM: reconnects on connection state DISCONNECTED and ACL_CONNECTED broadcast
 */
class AvatarService : Service() {

    private lateinit var sessionRepo: AvatarSessionRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previousConnectionState: NostrTransport.ConnectionState =
        NostrTransport.ConnectionState.DISCONNECTED
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var aclReceiver: BroadcastReceiver? = null
    private val reconnecting = AtomicBoolean(false)
    private var rfcommMode = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        sessionRepo = AvatarSessionRepository(this)
        _avatarSession.value = sessionRepo.getSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted the service (START_STICKY) — restore persisted session
            restoreSession()
        } else when (intent.action) {
            ACTION_ATTACH -> handleAttach(intent)
            ACTION_DETACH -> handleDetach()
            ACTION_STOP -> {
                cleanupController()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        unregisterReconnectListeners()
        cleanupController()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Private ---

    private fun handleAttach(intent: Intent) {
        val descriptorJson = intent.getStringExtra(EXTRA_DESCRIPTOR_JSON) ?: return
        val identity = intent.getStringExtra(EXTRA_IDENTITY) ?: return
        val additionalIdentities = intent.getStringArrayExtra(EXTRA_ADDITIONAL_IDENTITIES)
            ?.toList() ?: emptyList()

        val descriptor = try {
            AvatarDescriptor.fromJson(descriptorJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid avatar descriptor", e)
            updateNotification("Error: invalid descriptor")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        rfcommMode = descriptor.isRfcomm
        registerReconnectListeners()

        val target = if (rfcommMode) "RFCOMM ${descriptor.btAddr()}" else descriptor.relay()
        updateNotification("Connecting to $target...")

        serviceScope.launch {
            try {
                val controller = KeyMasterProvider.getController(this@AvatarService)
                    ?: throw IllegalStateException("No seed available")

                // Auto-approve all requests (first pass — proper UI in later mission)
                controller.setApprovalHandler { _, _ -> true }
                registerConnectionStateListener(controller)

                val attachId = if (additionalIdentities.isEmpty()) {
                    controller.attach(descriptor, identity)
                } else {
                    val predicates = buildKeyPredicates(identity, additionalIdentities)
                    controller.attach(descriptor, identity, predicates)
                }

                val session = AvatarSession(
                    descriptorJson = descriptorJson,
                    identity = identity,
                    sessionId = attachId,
                    relayUrl = descriptor.relay(),
                    attachedAt = System.currentTimeMillis() / 1000,
                    additionalIdentities = additionalIdentities
                )
                sessionRepo.saveSession(session)
                _avatarSession.value = session
                updateNotification("Attached via ${if (rfcommMode) "RFCOMM" else descriptor.relay()}")
                Log.d(TAG, "Attached via ${if (rfcommMode) "RFCOMM" else descriptor.relay()}, " +
                    "identity=$identity, additional=$additionalIdentities, id=$attachId")
            } catch (e: Exception) {
                Log.e(TAG, "Attach failed", e)
                updateNotification("Attach failed: ${e.message}")
                sessionRepo.clearSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleDetach() {
        serviceScope.launch {
            cleanupController()
            sessionRepo.clearSession()
            _avatarSession.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "Detached")
        }
    }

    private fun restoreSession() {
        val session = sessionRepo.getSession()
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val descriptor = try {
            AvatarDescriptor.fromJson(session.descriptorJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid stored descriptor", e)
            sessionRepo.clearSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        rfcommMode = descriptor.isRfcomm
        registerReconnectListeners()

        val target = if (rfcommMode) "RFCOMM" else session.relayUrl
        Log.d(TAG, "Restoring session: $target, identity=${session.identity}")
        updateNotification("Reconnecting to $target...")

        serviceScope.launch {
            try {
                doAttachSession(session)
                Log.d(TAG, "Restored session to $target")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                sessionRepo.clearSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Core attach logic shared by restoreSession and reconnect.
     */
    private fun doAttachSession(session: AvatarSession) {
        val controller = KeyMasterProvider.getController(this@AvatarService)
            ?: throw IllegalStateException("No seed available")

        controller.setApprovalHandler { _, _ -> true }
        registerConnectionStateListener(controller)

        val descriptor = AvatarDescriptor.fromJson(session.descriptorJson)
        val attachId = if (session.additionalIdentities.isEmpty()) {
            controller.attach(descriptor, session.identity)
        } else {
            val predicates = buildKeyPredicates(
                session.identity, session.additionalIdentities
            )
            controller.attach(descriptor, session.identity, predicates)
        }

        val restoredSession = session.copy(sessionId = attachId)
        sessionRepo.saveSession(restoredSession)
        _avatarSession.value = restoredSession
        updateNotification("Attached via ${if (rfcommMode) "RFCOMM" else session.relayUrl}")
    }

    private fun buildKeyPredicates(
        identity: String,
        additionalIdentities: List<String>
    ): List<Predicate<AbstractKeyEntry>> {
        val allIdentities = listOf(identity) + additionalIdentities
        return allIdentities.map { id ->
            Predicate<AbstractKeyEntry> { entry -> entry.identity() == id }
        }
    }

    private fun registerConnectionStateListener(controller: KeyMasterController) {
        controller.setConnectionStateListener { state ->
            Log.d(TAG, "Connection state: $state")
            _connectionState.value = state

            val text = when (state) {
                NostrTransport.ConnectionState.CONNECTED -> {
                    val session = sessionRepo.getSession()
                    "Attached via ${if (rfcommMode) "RFCOMM" else (session?.relayUrl ?: "relay")}"
                }
                NostrTransport.ConnectionState.RETRYING -> "Reconnecting..."
                NostrTransport.ConnectionState.DISCONNECTED -> "Disconnected"
            }
            updateNotification(text)

            // Vibrate on RETRYING -> CONNECTED transition (reconnected after sleep)
            if (state == NostrTransport.ConnectionState.CONNECTED &&
                previousConnectionState == NostrTransport.ConnectionState.RETRYING
            ) {
                vibrate(200)
            }

            // RFCOMM: trigger reconnect when connection drops
            if (rfcommMode && state == NostrTransport.ConnectionState.DISCONNECTED) {
                val session = sessionRepo.getSession()
                if (session != null && reconnecting.compareAndSet(false, true)) {
                    Log.i(TAG, "RFCOMM disconnected, triggering reconnect")
                    serviceScope.launch { reconnectWithBackoff(session) }
                }
            }

            previousConnectionState = state
        }
    }

    private fun vibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    // --- Reconnect listeners ---

    private fun registerReconnectListeners() {
        unregisterReconnectListeners()
        if (rfcommMode) {
            registerAclReceiver()
        } else {
            registerNetworkCallback()
        }
    }

    private fun unregisterReconnectListeners() {
        unregisterNetworkCallback()
        unregisterAclReceiver()
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
                Log.i(TAG, "Network available: transport=$transport")

                if (_connectionState.value != NostrTransport.ConnectionState.CONNECTED) {
                    val session = sessionRepo.getSession()
                    if (session != null && reconnecting.compareAndSet(false, true)) {
                        Log.i(TAG, "Connection is down, triggering re-attach " +
                            "after $transport network restored")
                        serviceScope.launch { reconnectWithBackoff(session) }
                    }
                }
            }

            override fun onLost(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                    else -> "UNKNOWN"
                }
                Log.i(TAG, "Network lost: transport=$transport")

                if (sessionRepo.getSession() != null) {
                    Log.i(TAG, "Active session exists, prompting BT reconnect")
                    postBtReconnectNotification()
                    _showBtReconnectPrompt.value = true
                }
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

    @Suppress("MissingPermission")
    private fun registerAclReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val session = sessionRepo.getSession() ?: return
                val descriptor = try {
                    AvatarDescriptor.fromJson(session.descriptorJson)
                } catch (_: Exception) { return }

                val deviceAddr = device?.address
                if (deviceAddr != null && deviceAddr.equals(descriptor.btAddr(), ignoreCase = true)) {
                    Log.i(TAG, "ACL connected to avatar BT device $deviceAddr")
                    if (_connectionState.value != NostrTransport.ConnectionState.CONNECTED &&
                        reconnecting.compareAndSet(false, true)
                    ) {
                        Log.i(TAG, "Triggering RFCOMM reconnect after ACL restore")
                        serviceScope.launch { reconnectWithBackoff(session) }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        registerReceiver(receiver, filter)
        aclReceiver = receiver
        Log.d(TAG, "ACL_CONNECTED receiver registered for RFCOMM reconnect")
    }

    private fun unregisterAclReceiver() {
        val receiver = aclReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering ACL receiver", e)
        }
        aclReceiver = null
    }

    // --- Reconnect with backoff ---

    private fun reconnectWithBackoff(session: AvatarSession) {
        try {
            val delays = longArrayOf(0, 3000, 5000, 10000, 20000)
            for ((attempt, delay) in delays.withIndex()) {
                if (delay > 0) {
                    Log.i(TAG, "Retry ${attempt + 1}/${delays.size} in ${delay}ms...")
                    Thread.sleep(delay)
                }
                try {
                    doAttachSession(session)
                    val mode = if (rfcommMode) "RFCOMM" else session.relayUrl
                    Log.i(TAG, "Re-attached to $mode" +
                        if (attempt > 0) " (attempt ${attempt + 1})" else "")
                    cancelBtReconnectNotification()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Re-attach attempt ${attempt + 1}/${delays.size} failed: ${e.message}")
                }
            }
            Log.e(TAG, "All re-attach attempts failed")
            updateNotification("Reconnect failed")
        } finally {
            reconnecting.set(false)
        }
    }

    private fun cleanupController() {
        try {
            KeyMasterProvider.getController(this)?.detach()
        } catch (e: Exception) {
            Log.w(TAG, "Error during detach cleanup", e)
        }
        _connectionState.value = NostrTransport.ConnectionState.DISCONNECTED
        _avatarSession.value = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val foregroundChannel = NotificationChannel(
                CHANNEL_ID,
                "Avatar Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Avatar relay connection alive"
            }
            nm.createNotificationChannel(foregroundChannel)

            val reconnectChannel = NotificationChannel(
                BT_RECONNECT_CHANNEL_ID,
                "Bluetooth Reconnect",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Bluetooth needs manual reconnection"
            }
            nm.createNotificationChannel(reconnectChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KeyMaster Avatar")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun postBtReconnectNotification() {
        val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            this, 1, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, BT_RECONNECT_CHANNEL_ID)
            .setContentTitle("Bluetooth connection lost")
            .setContentText("Toggle Internet access in Bluetooth settings to reconnect")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(BT_RECONNECT_NOTIFICATION_ID, notification)
    }

    private fun cancelBtReconnectNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(BT_RECONNECT_NOTIFICATION_ID)
        _showBtReconnectPrompt.value = false
    }

    companion object {
        private const val TAG = "AvatarService"
        private const val CHANNEL_ID = "avatar_service"
        private const val NOTIFICATION_ID = 4602
        private const val BT_RECONNECT_CHANNEL_ID = "bt_reconnect"
        private const val BT_RECONNECT_NOTIFICATION_ID = 4603

        private const val ACTION_ATTACH = "club.dwdc.keymaster.avatar.ATTACH"
        private const val ACTION_DETACH = "club.dwdc.keymaster.avatar.DETACH"
        private const val ACTION_STOP = "club.dwdc.keymaster.avatar.STOP"

        private const val EXTRA_DESCRIPTOR_JSON = "descriptor_json"
        private const val EXTRA_IDENTITY = "identity"
        private const val EXTRA_ADDITIONAL_IDENTITIES = "additional_identities"

        private val _connectionState = MutableStateFlow(NostrTransport.ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<NostrTransport.ConnectionState> = _connectionState

        private val _avatarSession = MutableStateFlow<AvatarSession?>(null)
        val avatarSession: StateFlow<AvatarSession?> = _avatarSession

        private val _showBtReconnectPrompt = MutableStateFlow(false)
        val showBtReconnectPrompt: StateFlow<Boolean> = _showBtReconnectPrompt

        fun startAttach(
            context: Context,
            descriptorJson: String,
            identity: String,
            additionalIdentities: List<String> = emptyList()
        ) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_ATTACH
                putExtra(EXTRA_DESCRIPTOR_JSON, descriptorJson)
                putExtra(EXTRA_IDENTITY, identity)
                putExtra(EXTRA_ADDITIONAL_IDENTITIES, additionalIdentities.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun detach(context: Context) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_DETACH
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
