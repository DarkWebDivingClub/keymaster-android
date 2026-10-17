package club.dwdc.keymaster.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.SeedRepository

private enum class SetupStep {
    CHOICE, GENERATE, IMPORT, QR_SCAN, NFC_BACKUP, NFC_RESTORE
}

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    hasSeed: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(SetupStep.CHOICE) }

    val seedCallback: (String, String) -> Unit = remember(context) {
        { mnemonic: String, passphrase: String ->
            SeedRepository(context).storeSeed(mnemonic, passphrase)
            KeyMasterProvider.reset()

            val metaStore = KeyMasterProvider.getMetaStore(context)
            if (hasSeed && metaStore.identities().isNotEmpty()) {
                KeyMasterProvider.reDeriveAllIdentities(context)
            }
            onSetupComplete()
        }
    }

    when (step) {
        SetupStep.CHOICE -> ChoiceContent(
            onGenerate = { step = SetupStep.GENERATE },
            onImport = { step = SetupStep.IMPORT },
            onScanQR = { step = SetupStep.QR_SCAN },
            onNfcBackup = { step = SetupStep.NFC_BACKUP },
            onNfcRestore = { step = SetupStep.NFC_RESTORE },
            hasSeed = hasSeed,
            onBack = onBack
        )
        SetupStep.GENERATE -> GenerateSeedScreen(
            onSeedConfirmed = seedCallback,
            onBack = { step = SetupStep.CHOICE }
        )
        SetupStep.IMPORT -> ImportSeedScreen(
            onSeedImported = seedCallback,
            onBack = { step = SetupStep.CHOICE }
        )
        SetupStep.QR_SCAN -> QRScanScreen(
            onSeedScanned = seedCallback,
            onBack = { step = SetupStep.CHOICE }
        )
        SetupStep.NFC_BACKUP -> {
            val seedRepo = SeedRepository(context)
            val mnemonic = seedRepo.getMnemonic()
            if (mnemonic != null) {
                NfcBackupScreen(
                    mnemonic = mnemonic,
                    onComplete = { step = SetupStep.CHOICE },
                    onBack = { step = SetupStep.CHOICE }
                )
            } else {
                // No seed to back up — return to choice
                step = SetupStep.CHOICE
            }
        }
        SetupStep.NFC_RESTORE -> NfcRestoreScreen(
            onSeedRestored = seedCallback,
            onBack = { step = SetupStep.CHOICE }
        )
    }
}

@Composable
private fun ChoiceContent(
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onScanQR: () -> Unit,
    onNfcBackup: () -> Unit,
    onNfcRestore: () -> Unit,
    hasSeed: Boolean,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // SAF launcher: export metadata
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val metaStore = KeyMasterProvider.getMetaStore(context)
                val json = metaStore.toJson()
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray())
                }
                statusMessage = "Metadata exported successfully"
            } catch (e: Exception) {
                statusMessage = "Export failed: ${e.message}"
            }
        }
    }

    // SAF launcher: import metadata
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (json != null) {
                    val metaStore = KeyMasterProvider.getMetaStore(context)
                    metaStore.replaceFromJson(json)
                    KeyMasterProvider.reDeriveAllIdentities(context)
                    statusMessage = "Metadata imported successfully"
                }
            } catch (e: Exception) {
                statusMessage = "Import failed: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        if (hasSeed) {
            Text(
                text = "Seed Management",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Primary NFC actions
            Button(
                onClick = onNfcBackup,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Backup Seed to NFC", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onNfcRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore Seed from NFC", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Metadata export/import
            OutlinedButton(
                onClick = { exportLauncher.launch("kv-metadata.json") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Identity Metadata", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Identity Metadata", modifier = Modifier.padding(8.dp))
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(24.dp))

            // Secondary actions
            OutlinedButton(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate New Seed", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Seed Phrase", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onScanQR,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan QR Code", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("Back")
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            // First-run setup
            Text(
                text = "KeyMaster",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "NIP-55 Nostr Signer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Set up your BIP-39 seed phrase. You will create your first identity next.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Generate New Seed", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Seed Phrase", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onScanQR,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan QR Code", modifier = Modifier.padding(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onNfcRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore from NFC", modifier = Modifier.padding(8.dp))
            }
        }
    }
}
