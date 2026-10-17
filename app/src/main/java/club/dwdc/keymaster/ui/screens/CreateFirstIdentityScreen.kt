package club.dwdc.keymaster.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.data.KeyMasterProvider

@Composable
fun CreateFirstIdentityScreen(
    onIdentityCreated: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var useCustomIdentity by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val effectiveIdentity = if (useCustomIdentity) identity.trim() else email.trim()

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
                    onIdentityCreated()
                }
            } catch (e: Exception) {
                statusMessage = "Import failed: ${e.message}"
            }
        }
    }

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
            text = "Create Your First Identity",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "SSH, GPG, and Nostr keys will be derived for this identity.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "The identity string is permanent — it determines the key " +
                   "derivation path and cannot be changed later without " +
                   "generating different keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Name") },
            placeholder = { Text("Alice Smith") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            placeholder = { Text("alice@example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = useCustomIdentity,
                onCheckedChange = { useCustomIdentity = it; error = null }
            )
            Text(
                text = "Custom identity string",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (useCustomIdentity) {
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it; error = null },
                label = { Text("Identity") },
                placeholder = { Text("alice@example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Key derivation path identifier. " +
                         "Defaults to email if left unchecked.")
                }
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val trimmedName = name.trim()
                val trimmedEmail = email.trim()
                val trimmedIdentity = effectiveIdentity
                when {
                    trimmedName.isEmpty() -> error = "Name is required"
                    trimmedEmail.isEmpty() -> error = "Email is required"
                    trimmedIdentity.isEmpty() -> error = "Identity is required"
                    else -> {
                        val controller = KeyMasterProvider.getController(context)
                        controller?.createIdentity(trimmedIdentity, trimmedName, trimmedEmail)
                        onIdentityCreated()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Create", modifier = Modifier.padding(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Or restore identities from a backup:",
            style = MaterialTheme.typography.bodyMedium
        )

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
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
