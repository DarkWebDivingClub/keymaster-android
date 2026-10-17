package club.dwdc.keymaster.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.bitcoinj.wallet.DeterministicSeed

@Composable
fun ImportSeedScreen(
    onSeedImported: (mnemonic: String, passphrase: String) -> Unit,
    onBack: () -> Unit
) {
    var mnemonic by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showMnemonic by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

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
            text = "Import Seed Phrase",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Enter your BIP-39 seed phrase to derive Nostr keys.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = mnemonic,
            onValueChange = {
                mnemonic = it
                error = null
            },
            label = { Text("Seed phrase (24 words)") },
            visualTransformation = if (showMnemonic) VisualTransformation.None
                else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = showMnemonic,
                onCheckedChange = { showMnemonic = it }
            )
            Text("Show seed phrase", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val clip = clipboardManager.getText()
                if (clip != null) {
                    mnemonic = clip.text
                    error = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Paste from Clipboard")
        }

        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

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
                val trimmed = mnemonic.trim().lowercase()
                    .replace(Regex("\\s+"), " ")

                if (trimmed.isEmpty()) {
                    error = "Seed phrase is required"
                    return@Button
                }

                try {
                    DeterministicSeed.ofMnemonic(trimmed, "")
                } catch (e: Exception) {
                    error = "Invalid seed phrase: ${e.message}"
                    return@Button
                }

                onSeedImported(trimmed, passphrase)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Import Seed", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
