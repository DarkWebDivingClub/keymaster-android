package club.dwdc.keymaster.data

import android.content.Context
import android.util.Log
import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.KVMetaStore
import club.dwdc.keymaster.KeyMaster
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keymaster.NostrRelayClient
import club.dwdc.keymaster.rfcomm.RfcommRelayClient
import club.dwdc.keyvault.core.Bip32KeyVault
import club.dwdc.keyvault.core.KeyVault

/**
 * Singleton provider for [KeyMasterController] on Android.
 *
 * Creates the controller in-process (no daemon) from the stored seed.
 * Returns null if no seed is stored yet.
 */
object KeyMasterProvider {

    private const val TAG = "KeyMasterProvider"

    /** Relay links shared per host by every connector (mission 53). */
    private val relayLinks = RelayLinkPool { Log.i("RelayLinkPool", it) }

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
            val created = KeyMasterController(km).apply {
                // One link per host, shared by every realm that reaches it
                // (mission 53): a second RFCOMM connection to the same channel
                // fails and takes the first one down.
                setRelayClientFactory { descriptor ->
                    relayLinks.handle(
                        RelayLinkPool.keyOf(
                            descriptor.isRfcomm,
                            if (descriptor.isRfcomm) descriptor.btAddr() else null,
                            if (descriptor.isRfcomm) descriptor.rfcommChannel() else 0,
                            if (descriptor.isRfcomm) null else descriptor.relay()
                        )
                    ) {
                        if (descriptor.isRfcomm) {
                            RfcommRelayClient(descriptor.btAddr(), descriptor.rfcommChannel())
                        } else {
                            NostrRelayClient(descriptor.relay())
                        }
                    }
                }
            }
            // Before anything reads the stored paths (realms, UI).
            repair(store, vault, created)
            this.vault = vault
            return created.also { controller = it }
        }
    }

    @Volatile
    private var vault: KeyVault? = null

    private fun repair(store: AndroidKVMetaStore, vault: KeyVault, controller: KeyMasterController) {
        val removed = StoreRepair(store, vault,
            createIdentity = { identity, name, email, time ->
                controller.createIdentity(identity, name, email, time)
            },
            log = { Log.i(TAG, "store repair: $it") },
        ).run()
        if (removed > 0) Log.i(TAG, "store repair: replaced $removed entries the vault refuses")
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
            vault = null
        }
    }

    /**
     * Re-derive keys for all existing identities from the current seed.
     *
     * Preserves GPG name/email/creationTime from the certification role entry
     * so identity metadata survives seed changes.
     */
    fun reDeriveAllIdentities(context: Context) {
        val store = getMetaStore(context)
        val controller = getController(context) ?: return
        // An imported export may hold paths the vault refuses: replace them
        // first, so the loop below rewrites entries in place.
        vault?.let { repair(store, it, controller) }
        for (identity in store.identities()) {
            val gpgEntries = store.byIdentity(identity)
                .filterIsInstance<GPGKeyEntry>()
            val cert = gpgEntries.firstOrNull { it.role() == 0 }
            controller.createIdentity(
                identity,
                cert?.name() ?: identity,
                cert?.email() ?: identity,
                cert?.creationTime() ?: java.time.Instant.now().epochSecond
            )
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
