package club.dwdc.keymaster.data

import club.dwdc.keymaster.AbstractKeyEntry
import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keyvault.core.Alg
import club.dwdc.keyvault.core.KeyVault
import java.io.File
import java.time.Instant

/**
 * Keeps the store usable by the vault it runs on.
 *
 * Rule: a stored entry whose path the vault refuses is removed, and its
 * identity is re-derived. The check asks the vault itself, so it covers an
 * in-place update from any older app (e.g. 0.5.0's `65536'` SSH and GPG
 * paths, refused by keyvault 0.6.0), an imported old export, and any later
 * path change, without a per-version migration table.
 *
 * Before the first change the store file is copied to `kv-metadata.json.bak`;
 * each removed entry is logged to `kv-metadata.repair.log`. Re-derived GPG
 * entries get a new creation time: they are new keys.
 */
class StoreRepair(
    private val store: AndroidKVMetaStore,
    private val vault: KeyVault,
    private val createIdentity: (identity: String, name: String, email: String, creationTime: Long) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { Instant.now().epochSecond },
) {

    /** Entries the vault refuses to serve. */
    fun refused(): List<AbstractKeyEntry> = store.all().filter { !vaultAccepts(it) }

    /** Repair the store; returns the number of entries removed (0 = nothing to do). */
    fun run(): Int {
        val refused = refused()
        if (refused.isEmpty()) return 0

        val dir = store.storeFile.parentFile
        store.copyFileTo(File(dir, BACKUP_NAME))
        val logFile = File(dir, LOG_NAME)
        val time = now()

        for (identity in refused.map { it.identity() }.distinct()) {
            // Read name and e-mail before anything is removed.
            val gpg = store.byIdentity(identity).filterIsInstance<GPGKeyEntry>()
            val cert = gpg.firstOrNull { it.role() == 0 } ?: gpg.firstOrNull()
            val name = cert?.name() ?: identity
            val email = cert?.email() ?: identity

            for (entry in refused.filter { it.identity() == identity }) {
                store.remove(entry.path())
                line(logFile, "$time removed ${entry.javaClass.simpleName} ${entry.path()} identity=$identity")
            }
            createIdentity(identity, name, email, time)
            line(logFile, "$time re-derived identity=$identity")
        }
        return refused.size
    }

    private fun vaultAccepts(entry: AbstractKeyEntry): Boolean {
        val alg = if (entry is NostrKeyEntry) Alg.SECP256K1 else Alg.ED25519
        val path = entry.path().list().map { it.i() }.toIntArray()
        return try {
            vault.execute(alg, KeyVault.FN_GET_PUBLIC_KEY, KeyVault.OPT_DEFAULT, null, *path).isOk
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun line(logFile: File, text: String) {
        logFile.appendText(text + "\n")
        log(text)
    }

    companion object {
        const val BACKUP_NAME = "kv-metadata.json.bak"
        const val LOG_NAME = "kv-metadata.repair.log"
    }
}
