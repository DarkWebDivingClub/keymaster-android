package club.dwdc.keymaster.nip55

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import club.dwdc.keymaster.crypto.EventSigner
import club.dwdc.keymaster.crypto.NostrKeyService
import club.dwdc.keymaster.data.AppPermission
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.data.SeedRepository

/**
 * NIP-55 Signer Activity.
 *
 * Handles nostrsigner: intents from Nostr client apps.
 * Uses a translucent theme so it appears as an overlay.
 *
 * For get_public_key: always launches AccountPickerActivity so the user
 * can choose which account to expose.
 *
 * For sign/encrypt/decrypt: resolves the account via the current_user
 * extra and checks per-(caller, pubkey) permissions.
 */
class SignerActivity : Activity() {

    companion object {
        private const val TAG = "KeyMasterSigner"
        private const val REQUEST_ACCOUNT_PICKER = 1001
    }

    private var stashedIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val type = intent.getStringExtra("type")
        val caller = callingPackage
        Log.d(TAG, "Received NIP-55 request: type=$type caller=[$caller]")

        if (caller == null) {
            Log.w(TAG, "No callingPackage — client must use startActivityForResult()")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val seedRepo = SeedRepository(this)
        if (!seedRepo.hasSeed()) {
            Log.w(TAG, "[$caller] No seed configured, rejecting request")
            finishWithError("No seed configured")
            return
        }

        when (type) {
            "get_public_key" -> {
                // Always launch account picker for get_public_key
                stashedIntent = intent
                val appLabel = resolveAppLabel(caller)
                val pickerIntent = Intent(this, AccountPickerActivity::class.java).apply {
                    putExtra(AccountPickerActivity.EXTRA_PACKAGE_NAME, caller)
                    putExtra(AccountPickerActivity.EXTRA_APP_LABEL, appLabel)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(pickerIntent, REQUEST_ACCOUNT_PICKER)
            }
            "sign_event", "nip44_encrypt", "nip44_decrypt" -> {
                resolveAndProcess(intent, caller)
            }
            else -> {
                Log.w(TAG, "[$caller] Unsupported NIP-55 type: $type")
                finishWithError("Unsupported type: $type")
            }
        }
    }

    /**
     * Resolve the account for signing/encrypting operations and check permissions.
     */
    private fun resolveAndProcess(intent: Intent, caller: String) {
        val permRepo = PermissionRepository(this)
        val seedRepo = SeedRepository(this)
        val mnemonic = seedRepo.getMnemonic()!!
        val passphrase = seedRepo.getPassphrase()

        val currentUser = intent.getStringExtra("current_user")
        val account = if (!currentUser.isNullOrEmpty()) {
            KeyMasterProvider.findAccountByPubkey(this, currentUser)
        } else {
            // Fall back to first (default) account
            KeyMasterProvider.getAccounts(this).firstOrNull()
        }

        if (account == null) {
            Log.w(TAG, "[$caller] Unknown account: $currentUser")
            finishWithError("Unknown account")
            return
        }

        when (permRepo.getPermission(caller, account.pubkeyHex)) {
            AppPermission.ALLOWED -> {
                Log.d(TAG, "[$caller] permission=ALLOWED for ${account.identity}, proceeding")
                val keyService = NostrKeyService(mnemonic, passphrase, account.identity)
                processSigningRequest(intent, keyService, caller)
            }
            AppPermission.DENIED -> {
                Log.d(TAG, "[$caller] permission=DENIED for ${account.identity}")
                finishWithError("Permission denied")
            }
            null -> {
                Log.d(TAG, "[$caller] no permission for ${account.identity}")
                finishWithError("Use get_public_key first")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT_PICKER) {
            val caller = callingPackage
            if (resultCode == RESULT_OK && data != null && caller != null) {
                val selectedPubkey = data.getStringExtra(AccountPickerActivity.EXTRA_SELECTED_PUBKEY)
                if (selectedPubkey != null) {
                    Log.d(TAG, "[$caller] user selected account $selectedPubkey")
                    handleGetPublicKey(selectedPubkey, caller)
                } else {
                    finishWithError("No account selected")
                }
            } else {
                Log.d(TAG, "[$caller] user denied account selection")
                setResult(RESULT_CANCELED)
                finish()
            }
            stashedIntent = null
        }
    }

    private fun handleGetPublicKey(pubkeyHex: String, caller: String) {
        Log.d(TAG, "[$caller] Returning public key: $pubkeyHex")
        val resultIntent = Intent().apply {
            putExtra("result", pubkeyHex)
            putExtra("package", packageName)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun processSigningRequest(intent: Intent, keyService: NostrKeyService, caller: String) {
        when (intent.getStringExtra("type")) {
            "sign_event" -> handleSignEvent(intent, keyService, caller)
            "nip44_encrypt" -> handleNip44Encrypt(intent, keyService, caller)
            "nip44_decrypt" -> handleNip44Decrypt(intent, keyService, caller)
        }
    }

    private fun handleSignEvent(intent: Intent, keyService: NostrKeyService, caller: String) {
        val eventJson = intent.data?.schemeSpecificPart
        if (eventJson.isNullOrEmpty()) {
            Log.e(TAG, "[$caller] No event JSON in intent data")
            finishWithError("No event JSON")
            return
        }

        val id = intent.getStringExtra("id") ?: ""
        Log.d(TAG, "[$caller] Signing event, request id=$id")

        try {
            val (eventIdHex, signatureHex, signedEventJson) =
                EventSigner.signEvent(eventJson, keyService)

            Log.d(TAG, "[$caller] Event signed successfully, eventId=$eventIdHex")

            val resultIntent = Intent().apply {
                putExtra("result", signatureHex)
                putExtra("event", signedEventJson)
                putExtra("id", id)
            }
            setResult(RESULT_OK, resultIntent)
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] Failed to sign event", e)
            setResult(RESULT_CANCELED)
        }

        finish()
    }

    private fun handleNip44Encrypt(intent: Intent, keyService: NostrKeyService, caller: String) {
        val plaintext = intent.data?.schemeSpecificPart
        val pubkey = intent.getStringExtra("pubkey")
        val id = intent.getStringExtra("id") ?: ""

        if (plaintext.isNullOrEmpty() || pubkey.isNullOrEmpty()) {
            Log.e(TAG, "[$caller] Missing plaintext or pubkey for nip44_encrypt")
            finishWithError("Missing plaintext or pubkey")
            return
        }

        try {
            val ciphertext = keyService.encrypt(plaintext, pubkey)
            Log.d(TAG, "[$caller] NIP-44 encrypt successful")

            val resultIntent = Intent().apply {
                putExtra("result", ciphertext)
                putExtra("id", id)
            }
            setResult(RESULT_OK, resultIntent)
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] NIP-44 encrypt failed", e)
            setResult(RESULT_CANCELED)
        }

        finish()
    }

    private fun handleNip44Decrypt(intent: Intent, keyService: NostrKeyService, caller: String) {
        val ciphertext = intent.data?.schemeSpecificPart
        val pubkey = intent.getStringExtra("pubkey")
        val id = intent.getStringExtra("id") ?: ""

        if (ciphertext.isNullOrEmpty() || pubkey.isNullOrEmpty()) {
            Log.e(TAG, "[$caller] Missing ciphertext or pubkey for nip44_decrypt")
            finishWithError("Missing ciphertext or pubkey")
            return
        }

        try {
            val decrypted = keyService.decrypt(ciphertext, pubkey)
            Log.d(TAG, "[$caller] NIP-44 decrypt successful")

            val resultIntent = Intent().apply {
                putExtra("result", decrypted)
                putExtra("id", id)
            }
            setResult(RESULT_OK, resultIntent)
        } catch (e: Exception) {
            Log.e(TAG, "[$caller] NIP-44 decrypt failed", e)
            setResult(RESULT_CANCELED)
        }

        finish()
    }

    private fun finishWithError(message: String) {
        val resultIntent = Intent().apply {
            putExtra("error", message)
        }
        setResult(RESULT_CANCELED, resultIntent)
        finish()
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = this.packageManager.getApplicationInfo(packageName, 0)
            this.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
