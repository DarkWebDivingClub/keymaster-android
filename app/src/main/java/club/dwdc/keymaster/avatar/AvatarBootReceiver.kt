package club.dwdc.keymaster.avatar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-attaches after a reboot, so the phone serves its desktops again without
 * the app being opened by hand.
 *
 * Also re-arms [AvatarWatchdog]: alarms do not survive a reboot, so without
 * this the watchdog would be armed only until the next restart.
 */
class AvatarBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!AvatarService.hasPersistedState(context)) {
            Log.d(TAG, "Boot with nothing persisted; staying out of the way")
            return
        }

        Log.i(TAG, "Boot completed with a persisted session — restoring")
        AvatarWatchdog.schedule(context)
        try {
            AvatarService.restore(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore after boot: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "AvatarBootReceiver"
    }
}
