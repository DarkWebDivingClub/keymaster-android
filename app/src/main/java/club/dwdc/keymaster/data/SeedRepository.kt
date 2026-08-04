package club.dwdc.keymaster.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Shared EncryptedSharedPreferences instance for seed and account data.
 * Shared EncryptedSharedPreferences for seed data.
 */
internal object SeedPrefs {
    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "keymaster_seed",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

class SeedRepository(context: Context) {

    private val prefs = SeedPrefs.get(context)

    fun hasSeed(): Boolean = prefs.contains("mnemonic")

    fun storeMnemonic(mnemonic: String) {
        prefs.edit().putString("mnemonic", mnemonic).apply()
    }

    /** Store mnemonic and passphrase atomically. */
    fun storeSeed(mnemonic: String, passphrase: String) {
        prefs.edit()
            .putString("mnemonic", mnemonic)
            .putString("passphrase", passphrase)
            .apply()
    }

    fun getMnemonic(): String? = prefs.getString("mnemonic", null)

    /** Returns the stored BIP-39 passphrase, or empty string if none was set. */
    fun getPassphrase(): String = prefs.getString("passphrase", null) ?: ""

    fun clear() {
        prefs.edit().clear().apply()
    }
}
