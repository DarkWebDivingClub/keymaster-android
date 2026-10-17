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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.bitcoinj.wallet.DeterministicSeed
import java.security.SecureRandom

@Composable
fun GenerateSeedScreen(
    onSeedConfirmed: (mnemonic: String, passphrase: String) -> Unit,
    onBack: () -> Unit
) {
    val words = remember {
        val seed = DeterministicSeed.ofRandom(SecureRandom(), 256, "")
        seed.mnemonicCode!!
    }
    val mnemonicString = remember { words.joinToString(" ") }

    var showWords by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
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
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Your New Seed Phrase",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Write down these 24 words and store them in a safe place. " +
                       "This is the ONLY way to recover your keys. " +
                       "Never share your seed phrase with anyone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2-column numbered grid
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                for (row in 0 until 12) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Left column: words 1-12
                        Row(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${row + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                                textAlign = TextAlign.End
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (showWords) words[row] else "****",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Right column: words 13-24
                        Row(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${row + 13}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                                textAlign = TextAlign.End
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (showWords) words[row + 12] else "****",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (row < 11) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showWords = !showWords },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (showWords) "Hide Words" else "Show Words")
            }

            OutlinedButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(mnemonicString))
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (copied) "Copied!" else "Copy to Clipboard")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Optional Passphrase (25th word)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onSeedConfirmed(mnemonicString, passphrase) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("I've Saved My Seed Phrase", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
