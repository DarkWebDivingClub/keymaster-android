package club.dwdc.keymaster.avatar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import club.dwdc.keymaster.AvatarDescriptor

/**
 * What an avatar is called in the list.
 *
 * Pairing exchanges device names, so the phone's own bonded-device record
 * already holds `F4:6D:3F:28:64:4F : tintin` — the desktops are called
 * whatever they call themselves, with nothing for the user to type and no
 * change to the avatar or the protocol (mission 36.3.6, answered question 10).
 *
 * The name is snapshotted into the stored connector at attach rather than
 * resolved per render, so the list stays readable after the device is
 * unbonded; a later successful lookup refreshes the snapshot.
 */
object AvatarLabels {

    private const val TAG = "AvatarLabels"

    /**
     * @param previous the label stored at attach, kept when no live lookup
     *                 succeeds
     */
    fun resolve(context: Context, descriptor: AvatarDescriptor, previous: String? = null): String {
        if (!descriptor.isRfcomm) return relayHost(descriptor.relay())
        val address = descriptor.btAddr() ?: return previous ?: relayHost(descriptor.relay())
        return bluetoothName(context, address) ?: previous ?: address
    }

    /**
     * The bonded device's name, or null when it cannot be had: the device is
     * not bonded, the name cache is cold right after boot, or the
     * BLUETOOTH_CONNECT grant was revoked since the scan.
     */
    private fun bluetoothName(context: Context, address: String): String? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "No BLUETOOTH_CONNECT; falling back to the address")
            return null
        }
        return try {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
            adapter.bondedDevices
                ?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.name
                ?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            Log.d(TAG, "Bluetooth name lookup refused: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth name lookup failed: ${e.message}")
            null
        }
    }

    /** `ws://tintin.h3:7777` becomes `tintin.h3` — enough to tell two apart. */
    fun relayHost(relay: String?): String {
        if (relay.isNullOrBlank()) return "avatar"
        return try {
            java.net.URI(relay).host ?: relay
        } catch (_: Exception) {
            relay
        }
    }
}
