package club.dwdc.keymaster.data

import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.Identity
import club.dwdc.keymaster.IdentityPaths
import club.dwdc.keymaster.KeyMaster
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keymaster.SSHKeyEntry
import club.dwdc.keyvault.core.Bip32KeyDerivator
import club.dwdc.keyvault.core.Bip32KeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vault = Bip32KeyVault(MNEMONIC, "")
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        storeFile = File(tmp.root, AndroidKVMetaStore.FILE_NAME)
    }

    /** The store as app 0.5.0 (keymaster.core 0.8.1) writes it. */
    private fun store050(vararg identities: Pair<String, String>): String {
        val rows = identities.flatMap { (id, pubkey) ->
            val m = Bip32KeyDerivator.mangle(id)
            listOf(
                """"m/44'/1237'/$m'/0'/0'": {"identity": "$id", "pubkey": "$pubkey"}""",
                """"m/44'/1238'/$m'/65536'/0'": {"identity": "$id", "comment": "$id"}""",
                """"m/44'/1239'/$m'/65536'/0'": {"identity": "$id", "name": "Name $id", "email": "$id", "creation_time": "1700000000"}""",
                """"m/44'/1239'/$m'/65537'/0'": {"identity": "$id", "name": "Name $id", "email": "$id", "creation_time": "1700000000"}""",
            )
        }
        return rows.joinToString(",\n", "{\n", "\n}\n")
    }

    private fun repairOf(store: AndroidKVMetaStore): StoreRepair {
        val controller = KeyMasterController(KeyMaster(vault, store))
        return StoreRepair(store, vault,
            createIdentity = { id, name, email, time -> controller.createIdentity(id, name, email, time) },
            now = { NOW })
    }

    @Test
    fun repairs050Store() {
        val original = store050(ALICE to ALICE_NPUB, BOB to BOB_NPUB)
        storeFile.writeText(original)
        val store = AndroidKVMetaStore(storeFile)

        assertEquals(6, repairOf(store).refused().size)
        assertEquals(6, repairOf(store).run())

        assertEquals(0, repairOf(AndroidKVMetaStore(storeFile)).refused().size)
        for ((id, npub) in listOf(ALICE to ALICE_NPUB, BOB to BOB_NPUB)) {
            val entries = AndroidKVMetaStore(storeFile).byIdentity(id)
            assertEquals("one Nostr, one SSH, two GPG for $id", 4, entries.size)
            val identity = Identity(id)

            val nostr = entries.filterIsInstance<NostrKeyEntry>().single()
            assertEquals(npub, nostr.pubkey())

            val ssh = entries.filterIsInstance<SSHKeyEntry>().single()
            assertEquals(IdentityPaths.hdPath(IdentityPaths.ssh(identity)), ssh.path())
            assertEquals(id, ssh.comment())

            val gpg = entries.filterIsInstance<GPGKeyEntry>().sortedBy { it.role() }
            assertEquals(2, gpg.size)
            for ((role, entry) in gpg.withIndex()) {
                assertEquals(IdentityPaths.hdPath(IdentityPaths.gpg(identity, role)), entry.path())
                assertEquals("Name $id", entry.name())
                assertEquals(id, entry.email())
                assertEquals("new key, new date", NOW, entry.creationTime())
            }
        }

        assertEquals(original, File(tmp.root, StoreRepair.BACKUP_NAME).readText())
        val log = File(tmp.root, StoreRepair.LOG_NAME).readLines()
        assertEquals(6, log.count { it.contains(" removed ") })
        assertEquals(2, log.count { it.contains(" re-derived ") })
    }

    @Test
    fun secondRunChangesNothing() {
        storeFile.writeText(store050(ALICE to ALICE_NPUB))
        repairOf(AndroidKVMetaStore(storeFile)).run()
        val after = storeFile.readText()
        File(tmp.root, StoreRepair.BACKUP_NAME).delete()

        assertEquals(0, repairOf(AndroidKVMetaStore(storeFile)).run())
        assertEquals(after, storeFile.readText())
        assertFalse(File(tmp.root, StoreRepair.BACKUP_NAME).exists())
    }

    @Test
    fun validStoreIsLeftAlone() {
        val store = AndroidKVMetaStore(storeFile)
        KeyMasterController(KeyMaster(vault, store)).createIdentity(ALICE, "Alice", ALICE, 1700000000L)
        val before = storeFile.readText()

        assertEquals(0, repairOf(AndroidKVMetaStore(storeFile)).run())
        assertEquals(before, storeFile.readText())
        assertFalse(File(tmp.root, StoreRepair.BACKUP_NAME).exists())
        assertFalse(File(tmp.root, StoreRepair.LOG_NAME).exists())
    }

    @Test
    fun importOf050ExportLeavesNoDuplicates() {
        val store = AndroidKVMetaStore(storeFile)
        KeyMasterController(KeyMaster(vault, store)).createIdentity(ALICE, "Alice", ALICE, 1700000000L)

        store.replaceFromJson(store050(ALICE to ALICE_NPUB))
        assertEquals(3, repairOf(store).run())

        val entries = AndroidKVMetaStore(storeFile).byIdentity(ALICE)
        assertEquals(4, entries.size)
        assertTrue(repairOf(AndroidKVMetaStore(storeFile)).refused().isEmpty())
    }

    companion object {
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val ALICE = "alice@atlanta.com"
        private const val BOB = "bob@biloxi.com"
        private const val NOW = 1763650000L

        /** Golden (48.3, 48.5): Alice's npub for the test mnemonic. */
        private const val ALICE_NPUB = "fa7851825be694a747ec9d93ea0b46be6f36a40af6ec3c5b2682d1dafe59dd12"
        private val BOB_NPUB: String by lazy {
            val store = AndroidKVMetaStore(File.createTempFile("bob", ".json").apply { delete(); deleteOnExit() })
            KeyMasterController(KeyMaster(Bip32KeyVault(MNEMONIC, ""), store)).createIdentity(BOB, BOB, BOB)
        }
    }
}
