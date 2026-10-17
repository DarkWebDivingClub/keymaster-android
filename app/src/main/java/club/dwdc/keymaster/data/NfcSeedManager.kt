package club.dwdc.keymaster.data

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import org.bouncycastle.crypto.generators.SCrypt
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts/decrypts BIP-39 mnemonics for NFC tag backup.
 *
 * Binary format (fits NTAG215's 504 usable bytes):
 * [1 byte: version = 0x01]
 * [16 bytes: scrypt salt]
 * [12 bytes: AES-GCM nonce]
 * [N bytes: AES-GCM ciphertext + 16-byte auth tag]
 *
 * NDEF record: MIME type "application/x-keymaster-seed".
 */
object NfcSeedManager {

    private const val VERSION: Byte = 0x01
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val MIME_TYPE = "application/x-keymaster-seed"

    // scrypt parameters: N=16384, r=8, p=1, dkLen=32
    private const val SCRYPT_N = 16384
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val KEY_LEN = 32

    fun encrypt(mnemonic: String, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))

        val buf = ByteBuffer.allocate(1 + SALT_LEN + NONCE_LEN + ciphertext.size)
        buf.put(VERSION)
        buf.put(salt)
        buf.put(nonce)
        buf.put(ciphertext)
        return buf.array()
    }

    fun decrypt(payload: ByteArray, passphrase: String): String {
        val buf = ByteBuffer.wrap(payload)
        val version = buf.get()
        require(version == VERSION) { "Unsupported payload version: $version" }

        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val nonce = ByteArray(NONCE_LEN).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    fun writeToTag(tag: Tag, payload: ByteArray): Result<Unit> = runCatching {
        val record = NdefRecord.createMime(MIME_TYPE, payload)
        val message = NdefMessage(arrayOf(record))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) {
                    throw IllegalStateException("NFC tag is read-only")
                }
                if (ndef.maxSize < message.toByteArray().size) {
                    throw IllegalStateException(
                        "NFC tag too small: need ${message.toByteArray().size} bytes, " +
                            "tag has ${ndef.maxSize} bytes"
                    )
                }
                ndef.writeNdefMessage(message)
            } finally {
                ndef.close()
            }
        } else {
            val formatable = NdefFormatable.get(tag)
                ?: throw IllegalStateException("NFC tag does not support NDEF")
            formatable.connect()
            try {
                formatable.format(message)
            } finally {
                formatable.close()
            }
        }
    }

    fun readFromTag(tag: Tag): Result<ByteArray> = runCatching {
        val ndef = Ndef.get(tag)
            ?: throw IllegalStateException("NFC tag does not contain NDEF data")
        ndef.connect()
        try {
            val message = ndef.ndefMessage
                ?: throw IllegalStateException("NFC tag is empty")
            val record = message.records.firstOrNull { it.toMimeType() == MIME_TYPE }
                ?: throw IllegalStateException("NFC tag does not contain a KeyMaster seed backup")
            record.payload
        } finally {
            ndef.close()
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        return SCrypt.generate(
            passphrase.toByteArray(Charsets.UTF_8),
            salt,
            SCRYPT_N,
            SCRYPT_R,
            SCRYPT_P,
            KEY_LEN
        )
    }
}
