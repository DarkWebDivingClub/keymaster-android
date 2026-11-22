package club.dwdc.keymaster.data

import club.dwdc.keymaster.Identity
import club.dwdc.keymaster.IdentityPaths
import club.dwdc.keymaster.KeyMaster
import club.dwdc.keymaster.KeyMasterController
import club.dwdc.keymaster.SSHKeyEntry
import club.dwdc.keymaster.X509ImportRequest
import club.dwdc.keymaster.X509KeyEntry
import club.dwdc.keymaster.X509Role
import club.dwdc.keymaster.X509ServiceHandler
import club.dwdc.keyvault.core.Bip32KeyDerivator
import club.dwdc.keyvault.core.Bip32KeyVault
import club.dwdc.keyvault.core.Protocol
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit

/** Mission 50: X.509 entries and certificates in the Android typed store. */
class AndroidKVMetaStoreX509Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vault = Bip32KeyVault(MNEMONIC, "")
    private lateinit var storeFile: File

    private val aliceTls = IdentityPaths.hdPath(IdentityPaths.x509(Identity(ALICE), X509Role.TLS_CLIENT))
    private val aliceTlsString = IdentityPaths.pathString(IdentityPaths.x509(Identity(ALICE), X509Role.TLS_CLIENT))

    @Before
    fun setUp() {
        storeFile = File(tmp.root, AndroidKVMetaStore.FILE_NAME)
    }

    @Test
    fun roundTripsX509EntryWithoutCertificate() {
        AndroidKVMetaStore(storeFile).put(X509KeyEntry(aliceTls, ALICE))

        val entry = AndroidKVMetaStore(storeFile).get(aliceTls) as X509KeyEntry
        assertEquals(ALICE, entry.identity())
        assertFalse(entry.certificate().isPresent)
        assertFalse("no cert field without a certificate", storeFile.readText().contains("\"cert\""))
    }

    @Test
    fun roundTripsX509EntryWithCertificate() {
        val der = der("tls-client-ok.pem")
        AndroidKVMetaStore(storeFile).put(X509KeyEntry(aliceTls, ALICE, der))

        val entry = AndroidKVMetaStore(storeFile).get(aliceTls) as X509KeyEntry
        assertArrayEquals(der, entry.certificate().orElseThrow())
    }

    @Test
    fun exportAndRestoreKeepTheCertificate() {
        val der = der("tls-client-ok.pem")
        val source = AndroidKVMetaStore(storeFile)
        source.put(X509KeyEntry(aliceTls, ALICE, der))

        val target = AndroidKVMetaStore(File(tmp.root, "other.json"))
        target.replaceFromJson(source.toJson())

        assertArrayEquals(der, (target.get(aliceTls) as X509KeyEntry).certificate().orElseThrow())
    }

    @Test
    fun x509EntryWrittenAsSshBy061ReadsAsX509() {
        // 0.6.1 serialized an X.509 entry it had read as SSH: identity and comment.
        storeFile.writeText("""{"$aliceTlsString": {"identity": "$ALICE", "comment": "$ALICE"}}""")

        val entry = AndroidKVMetaStore(storeFile).get(aliceTls)
        assertTrue(entry is X509KeyEntry)
        assertFalse((entry as X509KeyEntry).certificate().isPresent)
    }

    @Test
    fun unknownCoinTypeRoundTripsUnchangedAndIsNotSsh() {
        val m = Bip32KeyDerivator.mangle(ALICE)
        val mlsPath = "m/44'/${Protocol.MLS.coinType()}'/$m'/0'/0'"
        storeFile.writeText("""{"$mlsPath": {"identity": "$ALICE", "something": "kept", "n": 7}}""")

        val store = AndroidKVMetaStore(storeFile)
        val entry = store.all().single()
        assertTrue(entry is OpaqueKeyEntry)
        assertTrue(store.byType(SSHKeyEntry::class.java).isEmpty())

        store.put(X509KeyEntry(aliceTls, ALICE)) // forces a save
        val saved = JsonParser.parseString(storeFile.readText()).asJsonObject.getAsJsonObject(mlsPath)
        assertEquals("kept", saved.get("something").asString)
        assertEquals(7, saved.get("n").asInt)
        assertFalse(saved.has("comment"))
    }

    @Test
    fun createIdentityRegistersX509AndRepairKeepsIt() {
        val store = AndroidKVMetaStore(storeFile)
        val controller = KeyMasterController(KeyMaster(vault, store))
        controller.createIdentity(ALICE, "Alice", ALICE, 1700000000L)
        store.put((store.get(aliceTls) as X509KeyEntry).withCertificate(der("tls-client-ok.pem")))

        val repair = StoreRepair(store, vault,
            createIdentity = { id, name, email, time -> controller.createIdentity(id, name, email, time) })
        assertTrue("the vault serves X.509 as P-256", repair.refused().isEmpty())
        assertEquals(0, repair.run())
        assertTrue((AndroidKVMetaStore(storeFile).get(aliceTls) as X509KeyEntry).certificate().isPresent)
    }

    @Test
    fun handlerImportsIntoTheAndroidStore() {
        val store = AndroidKVMetaStore(storeFile)
        val controller = KeyMasterController(KeyMaster(vault, store))
        controller.createIdentity(ALICE, "Alice", ALICE, 1700000000L)
        val realm = controller.createRealm("phone", Identity(ALICE))
        val handler = realm.getHandler(Protocol.X509.coinType()) as X509ServiceHandler
        handler.setApprovalUI { _, _ -> true }

        val der = der("tls-client-ok.pem")
        assertTrue(handler.importCert(X509ImportRequest(der)).stored())

        // A new store object reads the file: the certificate survived "a restart".
        assertArrayEquals(der, (AndroidKVMetaStore(storeFile).get(aliceTls) as X509KeyEntry).certificate().orElseThrow())
    }

    @Test
    fun rowsListTheKeyWithAndWithoutCertificate() {
        val store = AndroidKVMetaStore(storeFile)

        val bare = x509Rows(ALICE, store, vault).single()
        assertEquals("$ALICE tls-client", bare.label)
        assertEquals(64, bare.spkiSha256.length)
        assertNull("listed without any stored entry", bare.cert)

        store.put(X509KeyEntry(aliceTls, ALICE, der("tls-client-ok.pem")))
        val row = x509Rows(ALICE, store, vault).single()
        assertEquals(bare.spkiSha256, row.spkiSha256)
        assertNotNull(row.cert)
        assertTrue(row.cert!!.subject.isNotEmpty())
        assertTrue(row.cert!!.issuer.isNotEmpty())
    }

    @Test
    fun expiryWarning() {
        val expired = summarize(der("expired.pem"))!!
        assertTrue(expired.expiresSoon(System.currentTimeMillis()))

        val ok = summarize(der("tls-client-ok.pem"))!!
        assertFalse(ok.expiresSoon(System.currentTimeMillis()))
        assertTrue(ok.expiresSoon(ok.notAfterMillis - TimeUnit.DAYS.toMillis(29)))
        assertFalse(ok.expiresSoon(ok.notAfterMillis - TimeUnit.DAYS.toMillis(31)))
    }

    private fun der(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/x509/$name") ?: error("missing fixture $name")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it).encoded
        }
    }

    companion object {
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val ALICE = "alice@atlanta.com"
    }
}
