package club.dwdc.keymaster.avatar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * The exemption the avatar connection depends on.
 *
 * Two things stop working without it: the system is free to kill the
 * foreground service under doze or a vendor app-management policy, and a
 * background component — [AvatarWatchdog], [AvatarBootReceiver] — may not
 * start a foreground service at all. So the watchdog that exists to survive
 * the kill is itself blocked by the same restriction.
 *
 * Asking is a system dialog, not a screen of ours, and it is asked once per
 * install at the first attach rather than on launch.
 */
object BatteryOptimization {

    private const val TAG = "BatteryOptimization"

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Show the system exemption dialog. Caller must be an Activity context —
     * this cannot be driven from the service, which is the whole problem it
     * exists to solve.
     *
     * @return false if the dialog could not be shown, e.g. on a build where
     *         the intent is unavailable
     */
    fun requestExemption(context: Context): Boolean {
        if (isExempt(context)) return true
        return try {
            @Suppress("BatteryLife")  // not a Play app; the connection is the product
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Exemption dialog unavailable: ${e.message}")
            false
        }
    }
}
