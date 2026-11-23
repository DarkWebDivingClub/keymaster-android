package club.dwdc.keymaster.data

import club.dwdc.keymaster.Identity
import club.dwdc.keymaster.IdentityPaths
import club.dwdc.keymaster.KVMetaStore
import club.dwdc.keymaster.X509KeyEntry
import club.dwdc.keymaster.X509Role
import club.dwdc.keyvault.core.Alg
import club.dwdc.keyvault.core.KeyVault
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * One X.509 key of an identity, as the home screen shows it (mission 50).
 *
 * @param label      the generated label, e.g. `alice@atlanta.com tls-client`
 * @param spkiSha256 SHA-256 of the key's SubjectPublicKeyInfo, hex; the
 *                   PKCS#11 module's `CKA_ID`
 * @param cert       the stored certificate, or null
 */
data class X509Row(
    val label: String,
    val spkiSha256: String,
    val cert: CertSummary?,
)

/** What the home screen shows of a stored certificate. */
data class CertSummary(
    val subject: String,
    val issuer: String,
    val notAfterMillis: Long,
) {
    /** True when the certificate expires within [EXPIRY_WARNING_DAYS] of [nowMillis], or has expired. */
    fun expiresSoon(nowMillis: Long): Boolean =
        notAfterMillis - nowMillis < TimeUnit.DAYS.toMillis(EXPIRY_WARNING_DAYS)

    companion object {
        const val EXPIRY_WARNING_DAYS = 30L
    }
}

/**
 * The X.509 rows of [identity]: one per template role. The keys are computed
 * from the identity, as the x509 service computes them, so an identity created
 * before mission 50 (no stored X.509 entry) is listed too. The certificate is
 * read from the store.
 */
fun x509Rows(identity: String, store: KVMetaStore, vault: KeyVault): List<X509Row> {
    val id = Identity(identity)
    return X509Role.TEMPLATE.map { role ->
        val path = IdentityPaths.x509(id, role)
        val entry = store.get(IdentityPaths.hdPath(path)) as? X509KeyEntry
        X509Row(
            label = IdentityPaths.x509Label(id, role),
            spkiSha256 = spkiSha256(vault, path),
            cert = entry?.certificate()?.orElse(null)?.let(::summarize),
        )
    }
}

/** Subject, issuer and expiry of [der]; null if it does not parse. */
fun summarize(der: ByteArray): CertSummary? = try {
    val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    CertSummary(
        subject = cert.subjectX500Principal.name,
        issuer = cert.issuerX500Principal.name,
        notAfterMillis = cert.notAfter.time,
    )
} catch (e: Exception) {
    null
}

private fun spkiSha256(vault: KeyVault, path: IntArray): String {
    val result = vault.execute(Alg.P256, KeyVault.FN_GET_PUBLIC_KEY, KeyVault.OPT_DEFAULT, null, *path)
    val point = result.data()
    if (!result.isOk || point == null || point.size < POINT_LENGTH || point[0] != 0x04.toByte()) {
        return "?"
    }
    val spki = P256_SPKI_PREFIX + point.copyOf(POINT_LENGTH)
    return MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
}

private const val POINT_LENGTH = 65

/** DER of SubjectPublicKeyInfo up to the BIT STRING contents: id-ecPublicKey, prime256v1. */
private val P256_SPKI_PREFIX = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
)
