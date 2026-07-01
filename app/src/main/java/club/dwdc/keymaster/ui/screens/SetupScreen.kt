package club.dwdc.keymaster.ui.screens

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
    CHOICE, GENERATE, IMPORT, QR_SCAN
}

@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(SetupStep.CHOICE) }

    val storeSeedAndFinish: (String, String) -> Unit = remember(context) {
        { mnemonic: String, passphrase: String ->
            val seedRepo = SeedRepository(context)
            seedRepo.storeSeed(mnemonic, passphrase)

            // Reset cached state so controller picks up the new seed
            KeyMasterProvider.reset()
            val controller = KeyMasterProvider.getController(context)
            controller?.createIdentity("default", "default", "default")

            onSetupComplete()
        }
    }

    when (step) {
        SetupStep.CHOICE -> ChoiceContent(
            onGenerate = { step = SetupStep.GENERATE },
            onImport = { step = SetupStep.IMPORT },
            onScanQR = { step = SetupStep.QR_SCAN }
        )
        SetupStep.GENERATE -> GenerateSeedScreen(
            onSeedConfirmed = storeSeedAndFinish,
            onBack = { step = SetupStep.CHOICE }
        )
        SetupStep.IMPORT -> ImportSeedScreen(
            onSeedImported = storeSeedAndFinish,
            onBack = { step = SetupStep.CHOICE }
        )
        SetupStep.QR_SCAN -> QRScanScreen(
            onSeedScanned = storeSeedAndFinish,
            onBack = { step = SetupStep.CHOICE }
        )
    }
}

@Composable
private fun ChoiceContent(
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onScanQR: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

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
            text = "Set up your BIP-39 seed phrase to derive Nostr keys.",
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

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "A default Nostr account will be created automatically. " +
                   "You can add more accounts later from the home screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
