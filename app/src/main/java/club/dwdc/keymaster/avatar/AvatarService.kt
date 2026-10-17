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
import club.dwdc.keymaster.AvatarDescriptor
import club.dwdc.keymaster.Identity
import club.dwdc.keymaster.IdentitySelection
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.NostrTransport
import club.dwdc.keymaster.R
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.RealmRepository
import club.dwdc.keymaster.data.StoredConnector
import club.dwdc.keymaster.data.StoredRealm
import club.dwdc.keymaster.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the phone's avatar connections alive.
 *
 * One phone serves several desktops at once: a realm holds one or more
 * connectors, and the phone holds one or more realms. Each connector is an
 * independent relay connection with its own state and its own backoff — one
 * can be retrying while another serves, which is why the core reports state
 * per connector rather than per realm (`setConnectorStateListener`).
 *
 * Two transport modes, chosen per instance because a KM is either a BT KM or
 * a WS KM:
 * - WebSocket (PAN): reconnects via ConnectivityManager.NetworkCallback
 * - RFCOMM: reconnects on DISCONNECTED and on the ACL_CONNECTED broadcast
 */
class AvatarService : Service() {

    private lateinit var realmRepo: RealmRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var rfcommMode = false

    /** Per connector, so one avatar's backoff cannot block another's. */
    private val reconnecting = ConcurrentHashMap<Int, AtomicBoolean>()
    private val previousState = ConcurrentHashMap<Int, NostrTransport.ConnectionState>()

    /** Realms that already have a state listener installed. */
    private val listening = ConcurrentHashMap.newKeySet<Int>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        running = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        realmRepo = RealmRepository(this)
        // Show what is stored immediately, all connectors disconnected until
        // each attach says otherwise.
        _realms.value = realmRepo.getRealms().map { it.toUi() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every branch either goes foreground or stops. A start that falls
        // through doing nothing leaves a service the platform kills for
        // missing its foreground window, which is why restore used to be
        // reachable only via the platform's own null intent.
        when (intent?.action) {
            ACTION_ATTACH -> handleAttach(intent)
            ACTION_DETACH -> handleDetachAll()
            ACTION_DETACH_CONNECTOR -> handleDetachConnector(
                intent.getIntExtra(EXTRA_REALM_ID, NO_REALM),
                intent.getIntExtra(EXTRA_CONNECTOR_ID, 0)
            )
            ACTION_DETACH_REALM -> handleDetachRealm(intent.getIntExtra(EXTRA_REALM_ID, NO_REALM))
            ACTION_RENAME_REALM -> handleRenameRealm(
                intent.getIntExtra(EXTRA_REALM_ID, NO_REALM),
                intent.getStringExtra(EXTRA_REALM_NAME).orEmpty()
            )
            ACTION_STOP -> {
                cleanupController()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // null covers both the START_STICKY restart and a bare start
            null, ACTION_RESTORE -> restoreRealms()
            else -> {
                Log.w(TAG, "Unknown action ${intent.action}, treating as restore")
                restoreRealms()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        running = false
        unregisterReconnectListeners()
        cleanupController()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Attach ---

    private fun handleAttach(intent: Intent) {
        val descriptorJson = intent.getStringExtra(EXTRA_DESCRIPTOR_JSON) ?: return
        val identity = intent.getStringExtra(EXTRA_IDENTITY) ?: return
        val additionalIdentities = intent.getStringArrayExtra(EXTRA_ADDITIONAL_IDENTITIES)
            ?.toList() ?: emptyList()
        val joinRealmId = intent.getIntExtra(EXTRA_REALM_ID, NO_REALM)
        val realmName = intent.getStringExtra(EXTRA_REALM_NAME)

        val descriptor = try {
            AvatarDescriptor.fromJson(descriptorJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid avatar descriptor", e)
            updateNotification("Error: invalid descriptor")
            stopIfIdle()
            return
        }

        rfcommMode = descriptor.isRfcomm
        registerReconnectListeners()

        val label = AvatarLabels.resolve(this, descriptor)
        updateNotification("Connecting to $label...")

        serviceScope.launch {
            try {
                val controller = requireController()
                // Approval behaviour is deliberately unchanged by this mission.
                controller.setApprovalHandler { _, _ -> true }

                val realmId = resolveRealm(controller, joinRealmId, realmName, identity,
                    additionalIdentities)
                watchConnectors(controller, realmId)

                val result = controller.attachToRealm(realmId, descriptor)
                persistAttach(realmId, controller, identity, additionalIdentities, label,
                    descriptorJson)
                publish(controller)
                AvatarWatchdog.schedule(this@AvatarService)
                Log.i(TAG, "Attached $label (realm=$realmId connector=${result.connectorId()}) " +
                    "identity=$identity additional=$additionalIdentities")
            } catch (e: Exception) {
                Log.e(TAG, "Attach failed", e)
                val duplicate = e.message?.contains("already has a connector") == true
                updateNotification(
                    if (duplicate) "$label is already attached to this realm"
                    else "Attach failed: ${e.message}"
                )
                // Deliberately not clearing the store: a failed attach is not
                // a reason to forget realms that were already working.
                stopIfIdle()
            }
        }
    }

    /**
     * Which realm this avatar joins.
     *
     * The UI can name a realm the core no longer has: the service may have
     * been killed since the card was drawn, and realm keys do not survive a
     * restart. When the store still describes that realm, rebuild it and let
     * the stored record follow the new id — otherwise the attach would create
     * a second record for a realm the user believes they already have.
     */
    private fun resolveRealm(
        controller: KeyMasterController,
        joinRealmId: Int,
        realmName: String?,
        identity: String,
        additionalIdentities: List<String>
    ): Int {
        if (joinRealmId == NO_REALM) {
            return createRealm(controller, realmName, identity, additionalIdentities)
        }
        if (controller.getRealm(joinRealmId) != null) return joinRealmId

        val stored = realmRepo.getRealms().firstOrNull { it.id == joinRealmId }
        if (stored == null) {
            Log.w(TAG, "Realm $joinRealmId is unknown; creating one instead")
            return createRealm(controller, realmName, identity, additionalIdentities)
        }
        Log.i(TAG, "Realm ${stored.name} was not live; rebuilding it to join")
        val rebuilt = createRealm(controller, stored.name, stored.primaryIdentity,
            stored.alsoServe)
        realmRepo.reassignId(stored.id, rebuilt)
        return rebuilt
    }

    private fun createRealm(
        controller: KeyMasterController,
        realmName: String?,
        identity: String,
        additionalIdentities: List<String>
    ): Int {
        val primary = Identity(identity)
        val selections = buildList {
            add(IdentitySelection(primary))
            additionalIdentities.filter { it != identity }
                .forEach { add(IdentitySelection(Identity(it))) }
        }
        return controller.createRealm(realmName?.takeIf { it.isNotBlank() }, primary, selections)
            .id()
    }

    /**
     * Write the realm and this connector to the store. The realm's own name
     * comes from the core, so a default name picked there is what the UI shows.
     */
    private fun persistAttach(
        realmId: Int,
        controller: KeyMasterController,
        identity: String,
        additionalIdentities: List<String>,
        label: String,
        descriptorJson: String
    ) {
        val realm = controller.getRealm(realmId) ?: return
        val existing = realmRepo.getRealms().firstOrNull { it.id == realmId }
        val connectors = (existing?.connectors.orEmpty()
            .filter { it.descriptorJson != descriptorJson }) +
            StoredConnector(descriptorJson, label, System.currentTimeMillis() / 1000)
        realmRepo.upsert(
            StoredRealm(
                id = realmId,
                name = realm.name(),
                // Joining must not rewrite the realm's definition: the
                // identities it serves were chosen when it was created, and
                // this attach was one tap that said nothing about them.
                primaryIdentity = existing?.primaryIdentity ?: identity,
                alsoServe = existing?.alsoServe
                    ?: additionalIdentities.filter { it != identity },
                connectors = connectors
            )
        )
    }

    // --- Restore ---

    /**
     * Re-attach every stored realm. A realm that fails to come back keeps its
     * record: the watchdog or the next resume will try again, and forgetting
     * it here would turn a flat avatar into lost configuration.
     */
    private fun restoreRealms() {
        val stored = realmRepo.getRealms()
        if (stored.isEmpty()) {
            Log.d(TAG, "Restore requested with nothing persisted; stopping")
            AvatarWatchdog.cancel(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        _realms.value = stored.map { it.toUi() }
        rfcommMode = stored.any { realm ->
            realm.connectors.any { runCatching { AvatarDescriptor.fromJson(it.descriptorJson).isRfcomm }
                .getOrDefault(false) }
        }
        registerReconnectListeners()
        updateNotification("Reconnecting...")

        serviceScope.launch {
            val controller = try {
                requireController()
            } catch (e: Exception) {
                Log.e(TAG, "Restore impossible: ${e.message}")
                stopIfIdle()
                return@launch
            }
            for (realm in stored) {
                try {
                    restoreRealm(controller, realm)
                } catch (e: Exception) {
                    Log.e(TAG, "Restore of realm ${realm.name} failed: ${e.message}")
                }
            }
            publish(controller)
            AvatarWatchdog.schedule(this@AvatarService)
        }
    }

    private fun restoreRealm(controller: KeyMasterController, stored: StoredRealm) {
        controller.setApprovalHandler { _, _ -> true }
        val realmId = createRealm(controller, stored.name, stored.primaryIdentity, stored.alsoServe)
        // The core draws a fresh id; the stored record follows it, since a
        // rebuilt realm is a new realm cryptographically either way.
        realmRepo.reassignId(stored.id, realmId)
        watchConnectors(controller, realmId)

        for (connector in stored.connectors) {
            try {
                val descriptor = AvatarDescriptor.fromJson(connector.descriptorJson)
                controller.attachToRealm(realmId, descriptor)
                val label = AvatarLabels.resolve(this, descriptor, connector.label)
                refreshLabel(realmId, connector.descriptorJson, label)
                Log.i(TAG, "Restored $label in realm ${stored.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore ${connector.label}: ${e.message}")
            }
        }
    }

    /** Keep the stored name current whenever a live lookup succeeds. */
    private fun refreshLabel(realmId: Int, descriptorJson: String, label: String) {
        val realm = realmRepo.getRealms().firstOrNull { it.id == realmId } ?: return
        if (realm.connectors.none { it.descriptorJson == descriptorJson && it.label != label }) return
        realmRepo.upsert(realm.copy(connectors = realm.connectors.map {
            if (it.descriptorJson == descriptorJson) it.copy(label = label) else it
        }))
    }

    // --- Detach ---

    private fun handleDetachAll() {
        serviceScope.launch {
            cleanupController()
            AvatarWatchdog.cancel(this@AvatarService)
            realmRepo.clear()
            _realms.value = emptyList()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "Detached everything")
        }
    }

    private fun handleDetachConnector(realmId: Int, connectorId: Int) {
        serviceScope.launch {
            val controller = KeyMasterProvider.getController(this@AvatarService)
            val descriptorJson = descriptorOf(realmId, connectorId)
            try {
                controller?.detach(realmId, connectorId)
            } catch (e: Exception) {
                Log.w(TAG, "Detach of connector $connectorId failed: ${e.message}")
            }
            val stored = realmRepo.getRealms().firstOrNull { it.id == realmId }
            if (stored != null) {
                val left = stored.connectors.filter { it.descriptorJson != descriptorJson }
                // The core removes a realm whose last connector went; the store
                // follows, so the two cannot disagree about what exists.
                if (left.isEmpty()) realmRepo.remove(realmId)
                else realmRepo.upsert(stored.copy(connectors = left))
            }
            reconnecting.remove(connectorId)
            previousState.remove(connectorId)
            publish(controller)
            Log.i(TAG, "Detached connector $connectorId from realm $realmId")
            stopIfIdle()
        }
    }

    private fun handleDetachRealm(realmId: Int) {
        serviceScope.launch {
            val controller = KeyMasterProvider.getController(this@AvatarService)
            try {
                controller?.detachRealm(realmId)
            } catch (e: Exception) {
                Log.w(TAG, "Detach of realm $realmId failed: ${e.message}")
            }
            realmRepo.remove(realmId)
            listening.remove(realmId)
            publish(controller)
            Log.i(TAG, "Detached realm $realmId")
            stopIfIdle()
        }
    }

    private fun handleRenameRealm(realmId: Int, name: String) {
        if (name.isBlank()) return
        serviceScope.launch {
            val controller = KeyMasterProvider.getController(this@AvatarService)
            // Renaming never re-keys anything: derivation uses the realm id.
            controller?.getRealm(realmId)?.setName(name)
            realmRepo.getRealms().firstOrNull { it.id == realmId }?.let {
                realmRepo.upsert(it.copy(name = name))
            }
            publish(controller)
        }
    }

    /** Nothing left to serve: stop rather than sit in the notification tray. */
    private fun stopIfIdle() {
        if (!realmRepo.hasRealms()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- Connection state, per connector ---

    private fun watchConnectors(controller: KeyMasterController, realmId: Int) {
        if (!listening.add(realmId)) return
        controller.setConnectorStateListener(realmId) { connectorId, state ->
            onConnectorState(controller, realmId, connectorId, state)
        }
    }

    private fun onConnectorState(
        controller: KeyMasterController,
        realmId: Int,
        connectorId: Int,
        state: NostrTransport.ConnectionState
    ) {
        Log.d(TAG, "Realm $realmId connector $connectorId: $state")
        _realms.value = _realms.value.map { realm ->
            if (realm.id != realmId) realm else realm.copy(
                connectors = realm.connectors.map {
                    if (it.id == connectorId) it.copy(
                        state = state,
                        retryingSince = if (state == NostrTransport.ConnectionState.RETRYING)
                            it.retryingSince ?: System.currentTimeMillis() else null,
                        retryAttempt = if (state == NostrTransport.ConnectionState.CONNECTED)
                            null else it.retryAttempt,
                        retryTotal = if (state == NostrTransport.ConnectionState.CONNECTED)
                            null else it.retryTotal
                    ) else it
                }
            )
        }
        updateNotification(notificationText())

        val previous = previousState.put(connectorId, state)
        // Vibrate on RETRYING -> CONNECTED: the signal that a sleeping desktop
        // came back, per connector rather than per phone.
        if (state == NostrTransport.ConnectionState.CONNECTED &&
            previous == NostrTransport.ConnectionState.RETRYING
        ) {
            vibrate(200)
            cancelBtReconnectNotification()
        }

        if (rfcommMode && state == NostrTransport.ConnectionState.DISCONNECTED) {
            val descriptorJson = descriptorOf(realmId, connectorId)
            if (descriptorJson != null && guard(connectorId).compareAndSet(false, true)) {
                Log.i(TAG, "Connector $connectorId dropped; reconnecting")
                serviceScope.launch {
                    reconnectWithBackoff(controller, realmId, connectorId, descriptorJson)
                }
            }
        }
    }

    /** Show the backoff on the row that is failing, and nowhere else. */
    private fun noteRetry(realmId: Int, connectorId: Int, attempt: Int, total: Int) {
        _realms.value = _realms.value.map { realm ->
            if (realm.id != realmId) realm else realm.copy(
                connectors = realm.connectors.map {
                    if (it.id == connectorId) it.copy(retryAttempt = attempt, retryTotal = total)
                    else it
                }
            )
        }
        updateNotification(notificationText())
    }

    private fun guard(connectorId: Int): AtomicBoolean =
        reconnecting.computeIfAbsent(connectorId) { AtomicBoolean(false) }

    /**
     * Re-attach one connector, leaving every other connector alone.
     *
     * The dead connector is closed through the realm rather than the
     * controller: the controller removes a realm that loses its last
     * connector, which would take the realm's handlers down with it and turn a
     * reconnect into a re-create.
     */
    private fun reconnectWithBackoff(
        controller: KeyMasterController,
        realmId: Int,
        connectorId: Int,
        descriptorJson: String
    ) {
        try {
            val delays = longArrayOf(0, 3000, 5000, 10000, 20000)
            for ((attempt, delay) in delays.withIndex()) {
                noteRetry(realmId, connectorId, attempt + 1, delays.size)
                if (delay > 0) {
                    Log.i(TAG, "Connector $connectorId retry ${attempt + 1}/${delays.size} " +
                        "in ${delay}ms")
                    Thread.sleep(delay)
                }
                try {
                    val realm = controller.getRealm(realmId)
                        ?: throw IllegalStateException("realm $realmId is gone")
                    try {
                        realm.detach(connectorId)
                    } catch (e: Exception) {
                        Log.d(TAG, "Stale connector would not close cleanly: ${e.message}")
                    }
                    controller.attachToRealm(realmId, AvatarDescriptor.fromJson(descriptorJson))
                    Log.i(TAG, "Connector $connectorId re-attached" +
                        if (attempt > 0) " (attempt ${attempt + 1})" else "")
                    cancelBtReconnectNotification()
                    publish(controller)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Re-attach ${attempt + 1}/${delays.size} failed: ${e.message}")
                }
            }
            Log.e(TAG, "All re-attach attempts failed for connector $connectorId")
            updateNotification(notificationText())
        } finally {
            guard(connectorId).set(false)
        }
    }

    // --- State published to the UI ---

    private fun publish(controller: KeyMasterController?) {
        val live = controller?.realms().orEmpty()
        _realms.value = realmRepo.getRealms().map { stored ->
            val realm = live[stored.id]
            val connectorIds = realm?.connectorIds().orEmpty()
            stored.toUi().copy(
                name = realm?.name() ?: stored.name,
                connectors = stored.connectors.map { connector ->
                    val id = connectorIdOf(connector.descriptorJson)
                    val known = _realms.value.firstOrNull { it.id == stored.id }
                        ?.connectors?.firstOrNull { it.id == id }
                    ConnectorUi(
                        id = id,
                        label = connector.label,
                        transport = transportOf(connector.descriptorJson),
                        descriptorJson = connector.descriptorJson,
                        state = when {
                            id in connectorIds -> known?.state
                                ?: NostrTransport.ConnectionState.CONNECTED
                            else -> NostrTransport.ConnectionState.DISCONNECTED
                        },
                        retryingSince = known?.retryingSince
                    )
                }
            )
        }
        updateNotification(notificationText())
    }

    private fun descriptorOf(realmId: Int, connectorId: Int): String? =
        _realms.value.firstOrNull { it.id == realmId }
            ?.connectors?.firstOrNull { it.id == connectorId }?.descriptorJson
            ?: realmRepo.getRealms().firstOrNull { it.id == realmId }
                ?.connectors?.firstOrNull { connectorIdOf(it.descriptorJson) == connectorId }
                ?.descriptorJson

    private fun requireController(): KeyMasterController =
        KeyMasterProvider.getController(this) ?: throw IllegalStateException("No seed available")

    private fun cleanupController() {
        try {
            KeyMasterProvider.getController(this)?.detachAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error during detach cleanup", e)
        }
        listening.clear()
        reconnecting.clear()
        previousState.clear()
        _realms.value = realmRepo.getRealms().map { it.toUi() }
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
                Log.i(TAG, "Network available: ${transportName(cm, network)}")
                reconnectEveryDownConnector("network restored")
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost: ${transportName(cm, network)}")
                if (realmRepo.hasRealms()) {
                    postBtReconnectNotification()
                    _showBtReconnectPrompt.value = true
                }
            }
        }

        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
        Log.d(TAG, "NetworkCallback registered for BT/WiFi/cellular")
    }

    private fun transportName(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network)
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "BLUETOOTH"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            else -> "UNKNOWN"
        }
    }

    /** Every connector that is not connected gets a retry; the rest are left alone. */
    private fun reconnectEveryDownConnector(reason: String) {
        val controller = KeyMasterProvider.getController(this) ?: return
        for (realm in _realms.value) {
            for (connector in realm.connectors) {
                if (connector.state == NostrTransport.ConnectionState.CONNECTED) continue
                if (!guard(connector.id).compareAndSet(false, true)) continue
                Log.i(TAG, "Reconnecting ${connector.label} after $reason")
                serviceScope.launch {
                    reconnectWithBackoff(controller, realm.id, connector.id,
                        connector.descriptorJson)
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun registerAclReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                val address = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE
                )?.address ?: return
                val controller = KeyMasterProvider.getController(this@AvatarService) ?: return

                // Only the connector on that device: another avatar's link
                // coming up says nothing about this one.
                for (realm in _realms.value) {
                    for (connector in realm.connectors) {
                        val btAddr = runCatching {
                            AvatarDescriptor.fromJson(connector.descriptorJson).btAddr()
                        }.getOrNull() ?: continue
                        if (!btAddr.equals(address, ignoreCase = true)) continue
                        if (connector.state == NostrTransport.ConnectionState.CONNECTED) continue
                        if (!guard(connector.id).compareAndSet(false, true)) continue
                        Log.i(TAG, "ACL up for ${connector.label}; reconnecting")
                        serviceScope.launch {
                            reconnectWithBackoff(controller, realm.id, connector.id,
                                connector.descriptorJson)
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
        aclReceiver = receiver
        Log.d(TAG, "ACL_CONNECTED receiver registered for RFCOMM reconnect")
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NetworkCallback", e)
        }
        networkCallback = null
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

    // --- Notification ---

    /**
     * Title counts avatars, and realms only when there is more than one, so a
     * single-realm user sees exactly what they saw before.
     */
    private fun notificationText(): String {
        val realms = _realms.value
        if (realms.isEmpty()) return "No avatars attached"
        val connectors = realms.flatMap { it.connectors }
        val connected = connectors.count {
            it.state == NostrTransport.ConnectionState.CONNECTED
        }
        // One avatar in one realm reads as it always did; anything plural
        // counts first and then lists one line per realm.
        if (realms.size == 1 && connectors.size == 1) {
            return connectors.first().let { "${it.label}: ${it.state.describe()}" }
        }
        val head = if (realms.size > 1) {
            "$connected/${connectors.size} avatars in ${realms.size} realms"
        } else {
            "$connected/${connectors.size} avatars attached"
        }
        val lines = realms.map { realm ->
            val devices = realm.connectors.joinToString(", ") {
                "${it.label} ${it.state.symbol()}"
            }
            if (realms.size > 1) "${realm.name}: $devices" else devices
        }
        return (listOf(head) + lines).joinToString("\n")
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
            .setStyle(Notification.BigTextStyle().bigText(text))
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

        getSystemService(NotificationManager::class.java)
            .notify(BT_RECONNECT_NOTIFICATION_ID, notification)
    }

    private fun cancelBtReconnectNotification() {
        getSystemService(NotificationManager::class.java).cancel(BT_RECONNECT_NOTIFICATION_ID)
        _showBtReconnectPrompt.value = false
    }

    private fun StoredRealm.toUi() = RealmUi(
        id = id,
        name = name,
        primaryIdentity = primaryIdentity,
        alsoServe = alsoServe,
        connectors = connectors.map {
            ConnectorUi(
                id = connectorIdOf(it.descriptorJson),
                label = it.label,
                transport = transportOf(it.descriptorJson),
                descriptorJson = it.descriptorJson,
                state = NostrTransport.ConnectionState.DISCONNECTED
            )
        }
    )

    companion object {
        private const val TAG = "AvatarService"
        private const val CHANNEL_ID = "avatar_service"
        private const val NOTIFICATION_ID = 4602
        private const val BT_RECONNECT_CHANNEL_ID = "bt_reconnect"
        private const val BT_RECONNECT_NOTIFICATION_ID = 4603

        /** No realm named in the intent: this attach creates one. */
        const val NO_REALM = -1
        private const val UNKNOWN_CONNECTOR = 0

        // Public so a watchdog, a boot receiver and `adb shell
        // start-foreground-service` can all name the path they want. The
        // service stays android:exported="false" in release; the debug
        // manifest opens it for the test script.
        const val ACTION_ATTACH = "club.dwdc.keymaster.avatar.ATTACH"
        const val ACTION_DETACH = "club.dwdc.keymaster.avatar.DETACH"
        const val ACTION_DETACH_CONNECTOR = "club.dwdc.keymaster.avatar.DETACH_CONNECTOR"
        const val ACTION_DETACH_REALM = "club.dwdc.keymaster.avatar.DETACH_REALM"
        const val ACTION_RENAME_REALM = "club.dwdc.keymaster.avatar.RENAME_REALM"
        const val ACTION_STOP = "club.dwdc.keymaster.avatar.STOP"
        const val ACTION_RESTORE = "club.dwdc.keymaster.avatar.RESTORE"

        const val EXTRA_DESCRIPTOR_JSON = "descriptor_json"
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_ADDITIONAL_IDENTITIES = "additional_identities"
        const val EXTRA_REALM_ID = "realm_id"
        const val EXTRA_REALM_NAME = "realm_name"
        const val EXTRA_CONNECTOR_ID = "connector_id"

        /**
         * Whether the service is alive. The watchdog needs this: a persisted
         * session with no running service is exactly the state
         * android-avatar-service-not-restarted-after-sleep leaves behind, and
         * it is not observable from outside the process otherwise.
         */
        @Volatile
        var running: Boolean = false
            private set

        private val _realms = MutableStateFlow<List<RealmUi>>(emptyList())

        /** Realms with their connectors, newest state first-hand from the core. */
        val realms: StateFlow<List<RealmUi>> = _realms

        private val _showBtReconnectPrompt = MutableStateFlow(false)
        val showBtReconnectPrompt: StateFlow<Boolean> = _showBtReconnectPrompt

        /**
         * The connector id the core will use for an avatar: the mangled login
         * xpub, which is deterministic, so a UI row keeps its identity across
         * reconnects and restarts.
         */
        fun connectorIdOf(descriptorJson: String): Int = try {
            club.dwdc.keyvault.core.Bip32KeyDerivator.mangle(
                AvatarDescriptor.fromJson(descriptorJson).loginXpub()
            )
        } catch (e: Exception) {
            UNKNOWN_CONNECTOR
        }

        fun transportOf(descriptorJson: String): String = try {
            if (AvatarDescriptor.fromJson(descriptorJson).isRfcomm) "RFCOMM" else "WS"
        } catch (e: Exception) {
            "?"
        }

        fun startAttach(
            context: Context,
            descriptorJson: String,
            identity: String,
            additionalIdentities: List<String> = emptyList(),
            realmId: Int = NO_REALM,
            realmName: String? = null
        ) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_ATTACH
                putExtra(EXTRA_DESCRIPTOR_JSON, descriptorJson)
                putExtra(EXTRA_IDENTITY, identity)
                putExtra(EXTRA_ADDITIONAL_IDENTITIES, additionalIdentities.toTypedArray())
                putExtra(EXTRA_REALM_ID, realmId)
                realmName?.let { putExtra(EXTRA_REALM_NAME, it) }
            }
            start(context, intent)
        }

        /**
         * Ask the service to re-attach whatever is persisted. Safe to call
         * when nothing is persisted (it stops again) and when already
         * attached.
         */
        fun restore(context: Context) {
            start(context, Intent(context, AvatarService::class.java).setAction(ACTION_RESTORE))
        }

        /** True when there is something for [restore] to re-attach. */
        fun hasPersistedState(context: Context): Boolean = RealmRepository(context).hasRealms()

        /** Detach one avatar, leaving the rest of its realm attached. */
        fun detachConnector(context: Context, realmId: Int, connectorId: Int) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_DETACH_CONNECTOR
                putExtra(EXTRA_REALM_ID, realmId)
                putExtra(EXTRA_CONNECTOR_ID, connectorId)
            }
            context.startService(intent)
        }

        fun detachRealm(context: Context, realmId: Int) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_DETACH_REALM
                putExtra(EXTRA_REALM_ID, realmId)
            }
            context.startService(intent)
        }

        fun renameRealm(context: Context, realmId: Int, name: String) {
            val intent = Intent(context, AvatarService::class.java).apply {
                action = ACTION_RENAME_REALM
                putExtra(EXTRA_REALM_ID, realmId)
                putExtra(EXTRA_REALM_NAME, name)
            }
            context.startService(intent)
        }

        /** Detach everything this phone serves. */
        fun detach(context: Context) {
            context.startService(
                Intent(context, AvatarService::class.java).setAction(ACTION_DETACH)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AvatarService::class.java).setAction(ACTION_STOP)
            )
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** One realm as the UI sees it. */
data class RealmUi(
    val id: Int,
    val name: String,
    val primaryIdentity: String,
    val alsoServe: List<String> = emptyList(),
    val connectors: List<ConnectorUi> = emptyList()
)

/** One avatar attached to a realm, with its own connection state. */
data class ConnectorUi(
    val id: Int,
    val label: String,
    val transport: String,
    val descriptorJson: String,
    val state: NostrTransport.ConnectionState,
    /** When RETRYING began, for the backoff sub-line on the failing row. */
    val retryingSince: Long? = null,
    /** Which backoff attempt is in flight, and out of how many. */
    val retryAttempt: Int? = null,
    val retryTotal: Int? = null
)

fun NostrTransport.ConnectionState.describe(): String = when (this) {
    NostrTransport.ConnectionState.CONNECTED -> "Connected"
    NostrTransport.ConnectionState.RETRYING -> "Reconnecting..."
    NostrTransport.ConnectionState.DISCONNECTED -> "Disconnected"
}

fun NostrTransport.ConnectionState.symbol(): String = when (this) {
    NostrTransport.ConnectionState.CONNECTED -> "●"
    NostrTransport.ConnectionState.RETRYING -> "◐"
    NostrTransport.ConnectionState.DISCONNECTED -> "○"
}
