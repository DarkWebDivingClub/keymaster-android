package club.dwdc.keymaster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * @param onDismiss called when the dialog is dismissed
 * @param onCreate called with (identity, name, email) when the user confirms
 */
@Composable
fun CreateAccountDialog(
    onDismiss: () -> Unit,
    onCreate: (identity: String, name: String, email: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var useCustomIdentity by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val effectiveIdentity = if (useCustomIdentity) identity.trim() else email.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Identity") },
        text = {
            Column {
                Text(
                    text = "SSH and GPG keys will be derived for this identity.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

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

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    val trimmedEmail = email.trim()
                    val trimmedIdentity = effectiveIdentity
                    when {
                        trimmedName.isEmpty() -> error = "Name is required"
                        trimmedEmail.isEmpty() -> error = "Email is required"
                        trimmedIdentity.isEmpty() -> error = "Identity is required"
                        else -> onCreate(trimmedIdentity, trimmedName, trimmedEmail)
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
