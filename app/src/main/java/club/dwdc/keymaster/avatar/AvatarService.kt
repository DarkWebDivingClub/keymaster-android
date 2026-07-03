package club.dwdc.keymaster.avatar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import club.dwdc.keymaster.AbstractKeyEntry
import club.dwdc.keymaster.AvatarDescriptor
import club.dwdc.keymaster.R
import club.dwdc.keymaster.data.AvatarSession
import club.dwdc.keymaster.data.AvatarSessionRepository
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.ui.MainActivity
import java.util.function.Predicate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Avatar relay connection alive.
 * Handles attach/detach lifecycle for NipxxConnector via KeyMasterController.
 */
class AvatarService : Service() {

    private lateinit var sessionRepo: AvatarSessionRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        sessionRepo = AvatarSessionRepository(this)
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

        updateNotification("Connecting to ${descriptor.relay()}...")

        serviceScope.launch {
            try {
                val controller = KeyMasterProvider.getController(this@AvatarService)
                    ?: throw IllegalStateException("No seed available")

                // Auto-approve all requests (first pass — proper UI in later mission)
                controller.setApprovalHandler { _, _ -> true }

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
                updateNotification("Attached to ${descriptor.relay()}")
                Log.d(TAG, "Attached to ${descriptor.relay()}, identity=$identity, " +
                    "additional=$additionalIdentities, id=$attachId")
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

        Log.d(TAG, "Restoring session: relay=${session.relayUrl}, identity=${session.identity}")
        updateNotification("Reconnecting to ${session.relayUrl}...")

        serviceScope.launch {
            try {
                val controller = KeyMasterProvider.getController(this@AvatarService)
                    ?: throw IllegalStateException("No seed available")

                controller.setApprovalHandler { _, _ -> true }

                val descriptor = AvatarDescriptor.fromJson(session.descriptorJson)
                val attachId = if (session.additionalIdentities.isEmpty()) {
                    controller.attach(descriptor, session.identity)
                } else {
                    val predicates = buildKeyPredicates(
                        session.identity, session.additionalIdentities
                    )
                    controller.attach(descriptor, session.identity, predicates)
                }

                // Update session with new attach ID
                sessionRepo.saveSession(session.copy(sessionId = attachId))
                updateNotification("Attached to ${session.relayUrl}")
                Log.d(TAG, "Restored session to ${session.relayUrl}")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                sessionRepo.clearSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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

    private fun cleanupController() {
        try {
            KeyMasterProvider.getController(this)?.detach()
        } catch (e: Exception) {
            Log.w(TAG, "Error during detach cleanup", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Avatar Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Avatar relay connection alive"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
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

    companion object {
        private const val TAG = "AvatarService"
        private const val CHANNEL_ID = "avatar_service"
        private const val NOTIFICATION_ID = 4602

        private const val ACTION_ATTACH = "club.dwdc.keymaster.avatar.ATTACH"
        private const val ACTION_DETACH = "club.dwdc.keymaster.avatar.DETACH"
        private const val ACTION_STOP = "club.dwdc.keymaster.avatar.STOP"

        private const val EXTRA_DESCRIPTOR_JSON = "descriptor_json"
        private const val EXTRA_IDENTITY = "identity"
        private const val EXTRA_ADDITIONAL_IDENTITIES = "additional_identities"

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
