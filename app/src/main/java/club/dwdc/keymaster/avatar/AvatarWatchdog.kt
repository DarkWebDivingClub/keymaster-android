package club.dwdc.keymaster.avatar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Restarts [AvatarService] when it has been killed with a session still
 * persisted.
 *
 * `START_STICKY` is a request, not a guarantee: vendor builds drop it, and on
 * a Samsung SM-S908E the service was gone after a minute with the screen off
 * while the app process lived on, with nothing bringing it back
 * (`plan/bugs/android-avatar-service-not-restarted-after-sleep.md`). An
 * inexact repeating alarm is the cheapest trigger that survives the process
 * being killed, since it lives in the system's alarm table rather than in the
 * app.
 *
 * The alarm is deliberately inexact: it only has to notice a dead service
 * eventually, and an exact alarm would need `SCHEDULE_EXACT_ALARM` and wake
 * the device on a schedule nobody asked for.
 */
object AvatarWatchdog {

    private const val TAG = "AvatarWatchdog"
    private const val ACTION_CHECK = "club.dwdc.keymaster.avatar.WATCHDOG_CHECK"
    private const val REQUEST_CODE = 4604

    /** How often to look. Inexact, so the system may batch it later than this. */
    val intervalMs: Long = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pendingIntent(context)
        )
        Log.d(TAG, "Watchdog scheduled every ${intervalMs / 60_000} min")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context))
        Log.d(TAG, "Watchdog cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, Receiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Decides whether a restore is needed, without starting anything it does
     * not have to. Kept separate from [Receiver] so the rule is one readable
     * expression: a session is persisted and the service is not alive.
     */
    fun needsRestore(context: Context): Boolean =
        !AvatarService.running && AvatarService.hasPersistedState(context)

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CHECK) return
            if (!AvatarService.hasPersistedState(context)) {
                Log.d(TAG, "Nothing persisted; cancelling watchdog")
                cancel(context)
                return
            }
            if (AvatarService.running) {
                // Alive, but a connector can still be down with its backoff
                // settled into the slow interval — nudge it rather than
                // restoring, which would rebuild realms that are working.
                Log.d(TAG, "Service alive; nudging any connector that is down")
                AvatarService.reconnect(context)
                return
            }
            Log.w(TAG, "Session persisted but service is gone — restoring")
            try {
                AvatarService.restore(context)
            } catch (e: Exception) {
                // Starting a foreground service from the background is only
                // allowed for a battery-optimisation-exempt app; say so
                // instead of dying silently.
                Log.e(TAG, "Could not restart the service: ${e.message}", e)
            }
        }
    }
}
