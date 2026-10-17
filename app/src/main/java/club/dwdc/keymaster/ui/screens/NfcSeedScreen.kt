package club.dwdc.keymaster.ui.screens

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.data.NfcSeedManager
import org.bitcoinj.wallet.DeterministicSeed

// ── Backup Screen ──────────────────────────────────────────────────

private enum class BackupStep { PASSPHRASE, NFC_TAP, RESULT }

@Composable
fun NfcBackupScreen(
    mnemonic: String,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    var step by remember { mutableStateOf(BackupStep.PASSPHRASE) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }
    var encryptedPayload by remember { mutableStateOf<ByteArray?>(null) }
    var resultMessage by remember { mutableStateOf("") }
    var resultIsError by remember { mutableStateOf(false) }

    when (step) {
        BackupStep.PASSPHRASE -> BackupPassphraseContent(
            passphrase = passphrase,
            passphraseConfirm = passphraseConfirm,
            onPassphraseChange = { passphrase = it },
            onPassphraseConfirmChange = { passphraseConfirm = it },
            onEncrypt = {
                encryptedPayload = NfcSeedManager.encrypt(mnemonic, passphrase)
                step = BackupStep.NFC_TAP
            },
            onBack = onBack
        )
        BackupStep.NFC_TAP -> NfcTapContent(
            instruction = "Hold NFC tag near phone to write backup",
            onTagDiscovered = { tag ->
                val payload = encryptedPayload ?: return@NfcTapContent
                val result = NfcSeedManager.writeToTag(tag, payload)
                result.fold(
                    onSuccess = {
                        resultMessage = "Seed phrase backup written successfully."
                        resultIsError = false
                    },
                    onFailure = { e ->
                        resultMessage = "Write failed: ${e.message}"
                        resultIsError = true
                    }
                )
                step = BackupStep.RESULT
            },
            onBack = { step = BackupStep.PASSPHRASE }
        )
        BackupStep.RESULT -> ResultContent(
            message = resultMessage,
            isError = resultIsError,
            onDone = {
                if (resultIsError) {
                    step = BackupStep.NFC_TAP
                } else {
                    onComplete()
                }
            },
            doneLabel = if (resultIsError) "Retry" else "Done",
            onBack = onBack
        )
    }
}

// ── Restore Screen ─────────────────────────────────────────────────

private enum class RestoreStep { NFC_TAP, DECRYPT, BIP39_PASSPHRASE, CONFIRM }

@Composable
fun NfcRestoreScreen(
    onSeedRestored: (mnemonic: String, passphrase: String) -> Unit,
    onBack: () -> Unit
) {
    var step by remember { mutableStateOf(RestoreStep.NFC_TAP) }
    var encryptedPayload by remember { mutableStateOf<ByteArray?>(null) }
    var decryptedMnemonic by remember { mutableStateOf("") }
    var nfcError by remember { mutableStateOf<String?>(null) }

    when (step) {
        RestoreStep.NFC_TAP -> {
            if (nfcError != null) {
                ResultContent(
                    message = nfcError!!,
                    isError = true,
                    onDone = {
                        nfcError = null
                    },
                    doneLabel = "Retry",
                    onBack = onBack
                )
            } else {
                NfcTapContent(
                    instruction = "Hold NFC tag near phone to read backup",
                    onTagDiscovered = { tag ->
                        val result = NfcSeedManager.readFromTag(tag)
                        result.fold(
                            onSuccess = { payload ->
                                encryptedPayload = payload
                                step = RestoreStep.DECRYPT
                            },
                            onFailure = { e ->
                                nfcError = "Read failed: ${e.message}"
                            }
                        )
                    },
                    onBack = onBack
                )
            }
        }
        RestoreStep.DECRYPT -> DecryptContent(
            encryptedPayload = encryptedPayload!!,
            onDecrypted = { mnemonic ->
                decryptedMnemonic = mnemonic
                step = RestoreStep.BIP39_PASSPHRASE
            },
            onBack = {
                encryptedPayload = null
                nfcError = null
                step = RestoreStep.NFC_TAP
            }
        )
        RestoreStep.BIP39_PASSPHRASE -> Bip39PassphraseContent(
            onConfirm = { passphrase -> onSeedRestored(decryptedMnemonic, passphrase) },
            onBack = { step = RestoreStep.DECRYPT }
        )
        RestoreStep.CONFIRM -> {
            // Handled by onSeedRestored callback
        }
    }
}

// ── Shared Sub-Composables ─────────────────────────────────────────

@Composable
private fun BackupPassphraseContent(
    passphrase: String,
    passphraseConfirm: String,
    onPassphraseChange: (String) -> Unit,
    onPassphraseConfirmChange: (String) -> Unit,
    onEncrypt: () -> Unit,
    onBack: () -> Unit
) {
    var showPassphrase by remember { mutableStateOf(false) }
    val mismatch = passphraseConfirm.isNotEmpty() && passphrase != passphraseConfirm

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Backup to NFC",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose a passphrase to encrypt your seed phrase. " +
                "You will need this passphrase to restore from the NFC tag.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = onPassphraseChange,
            label = { Text("Encryption passphrase") },
            visualTransformation = if (showPassphrase) VisualTransformation.None
                else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = passphraseConfirm,
            onValueChange = onPassphraseConfirmChange,
            label = { Text("Confirm passphrase") },
            visualTransformation = if (showPassphrase) VisualTransformation.None
                else PasswordVisualTransformation(),
            isError = mismatch,
            supportingText = if (mismatch) {{ Text("Passphrases do not match") }} else null,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showPassphrase,
                onCheckedChange = { showPassphrase = it }
            )
            Text("Show passphrase", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onEncrypt,
            modifier = Modifier.fillMaxWidth(),
            enabled = passphrase.isNotEmpty() && passphrase == passphraseConfirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Encrypt & Write", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun NfcTapContent(
    instruction: String,
    onTagDiscovered: (Tag) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    // Wrap callback in a stable reference to avoid stale captures
    val tagCallback = rememberUpdatedState(onTagDiscovered)

    DisposableEffect(nfcAdapter, activity) {
        if (nfcAdapter != null && activity != null) {
            nfcAdapter.enableReaderMode(
                activity,
                { tag ->
                    // Runs on binder thread — NFC I/O must happen here
                    // before the tag goes out of scope.  Compose snapshot
                    // state writes are thread-safe; recomposition is
                    // scheduled on main automatically.
                    tagCallback.value(tag)
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B,
                null
            )
        }
        onDispose {
            if (nfcAdapter != null && activity != null) {
                nfcAdapter.disableReaderMode(activity)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (nfcAdapter == null) {
            Text(
                text = "NFC is not available on this device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        } else if (!nfcAdapter.isEnabled) {
            Text(
                text = "NFC is disabled. Please enable NFC in system settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = instruction,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Waiting for NFC tag...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
private fun ResultContent(
    message: String,
    isError: Boolean,
    onDone: () -> Unit,
    doneLabel: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isError) "Error" else "Success",
            style = MaterialTheme.typography.headlineSmall,
            color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(doneLabel, modifier = Modifier.padding(8.dp))
        }

        if (isError) {
            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun DecryptContent(
    encryptedPayload: ByteArray,
    onDecrypted: (mnemonic: String) -> Unit,
    onBack: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Decrypt Backup",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter the passphrase used when creating this NFC backup.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                error = null
            },
            label = { Text("Encryption passphrase") },
            visualTransformation = if (showPassphrase) VisualTransformation.None
                else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showPassphrase,
                onCheckedChange = { showPassphrase = it }
            )
            Text("Show passphrase", style = MaterialTheme.typography.bodySmall)
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                try {
                    val mnemonic = NfcSeedManager.decrypt(encryptedPayload, passphrase)
                    // Validate as BIP-39
                    val trimmed = mnemonic.trim().lowercase().replace(Regex("\\s+"), " ")
                    DeterministicSeed.ofMnemonic(trimmed, "")
                    onDecrypted(trimmed)
                } catch (e: Exception) {
                    error = "Decryption failed: wrong passphrase or corrupted data."
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = passphrase.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Decrypt", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun Bip39PassphraseContent(
    onConfirm: (passphrase: String) -> Unit,
    onBack: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "BIP-39 Passphrase",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Seed phrase restored from NFC. " +
                "You may optionally enter a BIP-39 passphrase (25th word) " +
                "for key derivation. This is separate from the NFC encryption passphrase.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase (optional)") },
            visualTransformation = if (showPassphrase) VisualTransformation.None
                else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showPassphrase,
                onCheckedChange = { showPassphrase = it }
            )
            Text("Show passphrase", style = MaterialTheme.typography.bodySmall)
        }

        if (passphrase.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "If you set a passphrase, you MUST remember it. " +
                        "There is no way to recover it. " +
                        "A different passphrase produces completely different keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onConfirm(passphrase) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Continue", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
