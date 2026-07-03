package club.dwdc.keymaster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.AvatarDescriptor
import club.dwdc.keymaster.data.Account
import club.dwdc.keymaster.data.KeyMasterProvider

/**
 * Confirmation dialog shown after scanning/pasting an avatar descriptor.
 * Displays relay URL, services list, and identity selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarConfirmDialog(
    descriptorJson: String,
    defaultIdentity: String,
    onConfirm: (identity: String, additionalIdentities: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val descriptor = remember { AvatarDescriptor.fromJson(descriptorJson) }
    val accounts = remember { KeyMasterProvider.getAccounts(context) }
    var selectedIdentity by remember { mutableStateOf(defaultIdentity) }
    var expanded by remember { mutableStateOf(false) }
    val alsoIdentities: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach to Avatar") },
        text = {
            Column {
                Text(
                    text = "Relay",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = descriptor.relay(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                if (descriptor.services().isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Services",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = descriptor.services().joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (accounts.size >= 2) "Primary identity" else "Identity",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedIdentity,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.identity) },
                                onClick = {
                                    selectedIdentity = account.identity
                                    alsoIdentities.remove(account.identity)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                if (accounts.size >= 2) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Also attach",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    accounts
                        .filter { it.identity != selectedIdentity }
                        .forEach { account ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = alsoIdentities[account.identity] == true,
                                    onCheckedChange = { checked ->
                                        alsoIdentities[account.identity] = checked
                                    }
                                )
                                Text(
                                    text = account.identity,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val additional = alsoIdentities
                    .filter { it.value }
                    .map { it.key }
                onConfirm(selectedIdentity, additional)
            }) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
