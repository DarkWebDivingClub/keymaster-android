package club.dwdc.keymaster.data

import android.content.Context
import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.KVMetaStore
import club.dwdc.keymaster.KeyMaster
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keyvault.core.Bip32KeyVault

/**
 * Singleton provider for [KeyMasterController] on Android.
 *
 * Creates the controller in-process (no daemon) from the stored seed.
 * Returns null if no seed is stored yet.
 */
object KeyMasterProvider {

    @Volatile
    private var controller: KeyMasterController? = null

    @Volatile
    private var metaStore: AndroidKVMetaStore? = null

    fun getController(context: Context): KeyMasterController? {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val seedRepo = SeedRepository(context)
            val mnemonic = seedRepo.getMnemonic() ?: return null
            val passphrase = seedRepo.getPassphrase()
            val vault = Bip32KeyVault(mnemonic, passphrase)
            val store = getMetaStore(context)
            val km = KeyMaster(vault, store)
            return KeyMasterController(km).also { controller = it }
        }
    }

    fun getMetaStore(context: Context): AndroidKVMetaStore {
        metaStore?.let { return it }
        synchronized(this) {
            metaStore?.let { return it }
            return AndroidKVMetaStore(context).also { metaStore = it }
        }
    }

    /** Call after seed import/generate to discard cached state. */
    fun reset() {
        synchronized(this) {
            controller = null
            metaStore = null
        }
    }

    /**
     * Update the display name for an identity by replacing its GPG key entries.
     */
    fun updateIdentityName(context: Context, identity: String, newName: String) {
        val store = getMetaStore(context)
        val gpgEntries = store.byIdentity(identity).filterIsInstance<GPGKeyEntry>()
        for (entry in gpgEntries) {
            store.remove(entry.path())
            store.put(GPGKeyEntry(entry.path(), identity, newName, entry.email(), entry.creationTime()))
        }
    }

    /**
     * Build an [Account] from a [NostrKeyEntry] for UI display.
     */
    fun toAccount(entry: NostrKeyEntry): Account =
        Account(entry.identity(), entry.pubkey())

    /**
     * List all identities as [Account] objects for UI display.
     */
    fun getAccounts(context: Context): List<Account> =
        getMetaStore(context).byType(NostrKeyEntry::class.java).map { toAccount(it) }

    /**
     * Find an account by its hex pubkey.
     */
    fun findAccountByPubkey(context: Context, pubkeyHex: String): Account? =
        getMetaStore(context).byType(NostrKeyEntry::class.java)
            .find { it.pubkey() == pubkeyHex }
            ?.let { toAccount(it) }
}
