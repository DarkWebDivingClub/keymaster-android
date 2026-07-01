package club.dwdc.keymaster.nip55

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import club.dwdc.keymaster.crypto.EventSigner
import club.dwdc.keymaster.crypto.NostrKeyService
import club.dwdc.keymaster.data.AppPermission
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.data.SeedRepository

/**
 * NIP-55 Content Provider for background signing operations.
 *
 * Clients query this provider when they have previously been authorized
 * via Intent-based approval. Only ALLOWED apps may proceed; unknown apps
 * must request access via the nostrsigner: intent first.
 *
 * URIs:
 *   content://club.dwdc.keymaster.SIGN_EVENT
 *   content://club.dwdc.keymaster.NIP44_ENCRYPT
 *   content://club.dwdc.keymaster.NIP44_DECRYPT
 *
 * The projection array carries the request data:
 *   [0] = event JSON / plaintext / ciphertext
 *   [1] = pubkey (for encrypt/decrypt) or empty
 *   [2] = current_user hex pubkey
 */
class SignerContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "KeyMasterProvider"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val authority = uri.authority ?: return null
        val operation = authority.substringAfterLast(".")
        val caller = callingPackage

        Log.d(TAG, "Content resolver query: operation=$operation caller=[$caller]")

        if (caller == null) {
            Log.w(TAG, "No callingPackage available")
            return errorCursor("Unknown caller")
        }

        val seedRepo = SeedRepository(ctx)
        if (!seedRepo.hasSeed()) {
            Log.w(TAG, "[$caller] No seed configured")
            return errorCursor("No seed configured")
        }

        val mnemonic = seedRepo.getMnemonic()!!
        val passphrase = seedRepo.getPassphrase()
        val permRepo = PermissionRepository(ctx)

        // Resolve account from current_user (projection[2]) or fall back to default
        val currentUser = projection?.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val account = if (currentUser != null) {
            KeyMasterProvider.findAccountByPubkey(ctx, currentUser)
        } else {
            KeyMasterProvider.getAccounts(ctx).firstOrNull()
        }

        if (account == null) {
            Log.w(TAG, "[$caller] Unknown account: $currentUser")
            return errorCursor("Unknown account")
        }

        // Permission gate
        when (permRepo.getPermission(caller, account.pubkeyHex)) {
            AppPermission.ALLOWED -> {
                Log.d(TAG, "[$caller] permission=ALLOWED for ${account.identity}, proceeding")
            }
            AppPermission.DENIED -> {
                Log.d(TAG, "[$caller] permission=DENIED for ${account.identity}")
                return errorCursor("Permission denied")
            }
            null -> {
                Log.d(TAG, "[$caller] no permission for ${account.identity}")
                return errorCursor("Use get_public_key first")
            }
        }

        val keyService = NostrKeyService(mnemonic, passphrase, account.identity)

        return when (operation) {
            "SIGN_EVENT" -> handleSignEvent(projection, keyService, caller)
            "NIP44_ENCRYPT" -> handleNip44Encrypt(projection, keyService, caller)
            "NIP44_DECRYPT" -> handleNip44Decrypt(projection, keyService, caller)
            else -> {
                Log.w(TAG, "[$caller] Unsupported operation: $operation")
                null
            }
        }
    }

    private fun handleSignEvent(
        projection: Array<String>?,
        keyService: NostrKeyService,
        caller: String
    ): Cursor? {
        val eventJson = projection?.getOrNull(0) ?: return null

        return try {
            val (eventIdHex, signatureHex, signedEventJson) =
                EventSigner.signEvent(eventJson, keyService)

            Log.d(TAG, "[$caller] Event signed successfully, eventId=$eventIdHex")

            val cursor = MatrixCursor(arrayOf("result", "event"))
            cursor.addRow(arrayOf(signatureHex, signedEventJson))
            cursor
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] Failed to sign event via content provider", e)
            null
        }
    }

    private fun handleNip44Encrypt(
        projection: Array<String>?,
        keyService: NostrKeyService,
        caller: String
    ): Cursor? {
        val plaintext = projection?.getOrNull(0) ?: return null
        val pubKey = projection.getOrNull(1) ?: return null

        return try {
            val ciphertext = keyService.encrypt(plaintext, pubKey)
            Log.d(TAG, "[$caller] NIP-44 encrypt successful via content provider")

            val cursor = MatrixCursor(arrayOf("result"))
            cursor.addRow(arrayOf(ciphertext))
            cursor
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] NIP-44 encrypt failed via content provider", e)
            null
        }
    }

    private fun handleNip44Decrypt(
        projection: Array<String>?,
        keyService: NostrKeyService,
        caller: String
    ): Cursor? {
        val ciphertext = projection?.getOrNull(0) ?: return null
        val pubKey = projection.getOrNull(1) ?: return null

        return try {
            val decrypted = keyService.decrypt(ciphertext, pubKey)
            Log.d(TAG, "[$caller] NIP-44 decrypt successful via content provider")

            val cursor = MatrixCursor(arrayOf("result"))
            cursor.addRow(arrayOf(decrypted))
            cursor
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] NIP-44 decrypt failed via content provider", e)
            null
        }
    }

    private fun errorCursor(message: String): MatrixCursor {
        val cursor = MatrixCursor(arrayOf("error"))
        cursor.addRow(arrayOf(message))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
