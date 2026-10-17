package club.dwdc.keymaster.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.AvatarDescriptor
import club.dwdc.keymaster.avatar.AvatarLabels
import club.dwdc.keymaster.avatar.AvatarService
import club.dwdc.keymaster.avatar.RealmUi
import club.dwdc.keymaster.data.KeyMasterProvider

/**
 * What happens after a QR scan.
 *
 * Three modes, and which one appears is decided by what already exists — the
 * user is never asked a question the page they came from has already answered:
 *
 * - **First attach** (this identity has no realm): state the primary identity,
 *   ask only which *additional* identities the realm should serve.
 * - **Join** (realms exist): one radio list, "Add to", plus *New realm…*.
 * - **Create** (*New realm…* picked): the same sheet as the first attach, with
 *   a name field.
 *
 * The primary identity is stated and never picked: the card sits on an
 * identity page, so pressing *+ Attach* already chose it.
 */
@Composable
fun AvatarConfirmDialog(
    descriptorJson: String,
    defaultIdentity: String,
    onConfirm: (identity: String, additionalIdentities: List<String>,
                realmId: Int, realmName: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val descriptor = remember { AvatarDescriptor.fromJson(descriptorJson) }
    val accounts = remember { KeyMasterProvider.getAccounts(context) }
    val realms by AvatarService.realms.collectAsState()
    val myRealms = realms.filter { it.primaryIdentity == defaultIdentity }

    // NO_REALM means "create one"; that is also the only option on a first attach.
    var chosenRealm by remember(myRealms.size) {
        mutableStateOf(myRealms.firstOrNull()?.id ?: AvatarService.NO_REALM)
    }
    var creating by remember { mutableStateOf(myRealms.isEmpty()) }
    var realmName by remember { mutableStateOf("") }
    val alsoServe: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }

    val avatarLabel = remember(descriptorJson) {
        AvatarLabels.resolve(context, descriptor)
    }
    val avatarLine = remember(descriptorJson) {
        val transport = if (descriptor.isRfcomm) "BT ${descriptor.btAddr()}" else descriptor.relay()
        "$avatarLabel · $transport"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    creating && myRealms.isEmpty() -> "Attach this avatar"
                    creating -> "New realm"
                    else -> "Attach this avatar"
                }
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = avatarLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                if (descriptor.services().isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = descriptor.services().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!creating) {
                    // Joining is one tap: pick a realm, press Add. The avatar
                    // counts are there so the choice can be made without
                    // remembering which realm is which.
                    Label("Add to")
                    myRealms.forEach { realm ->
                        RealmChoice(
                            title = realm.name,
                            subtitle = avatarCount(realm),
                            selected = chosenRealm == realm.id,
                            onSelect = { chosenRealm = realm.id }
                        )
                    }
                    RealmChoice(
                        title = "New realm…",
                        subtitle = null,
                        selected = false,
                        onSelect = {
                            creating = true
                            chosenRealm = AvatarService.NO_REALM
                        }
                    )
                } else {
                    if (myRealms.isNotEmpty()) {
                        OutlinedTextField(
                            value = realmName,
                            onValueChange = { realmName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Label("Primary identity")
                    Text(
                        text = defaultIdentity,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "the page you attached from · signs the login",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val others = accounts.filter { it.identity != defaultIdentity }
                    if (others.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Label("Also serve")
                        others.forEach { account ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = alsoServe[account.identity] == true,
                                    onCheckedChange = { alsoServe[account.identity] = it }
                                )
                                Text(
                                    text = account.identity,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val additional = alsoServe.filter { it.value }.map { it.key }
                onConfirm(
                    defaultIdentity,
                    if (creating) additional else emptyList(),
                    if (creating) AvatarService.NO_REALM else chosenRealm,
                    realmName.takeIf { creating && it.isNotBlank() }
                )
            }) {
                Text(
                    when {
                        creating && myRealms.isEmpty() -> "Attach"
                        creating -> "Create"
                        else -> "Add"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                // "Back" returns to the realm list; "Cancel" leaves.
                if (creating && myRealms.isNotEmpty()) creating = false else onDismiss()
            }) {
                Text(if (creating && myRealms.isNotEmpty()) "Back" else "Cancel")
            }
        }
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun RealmChoice(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun avatarCount(realm: RealmUi): String =
    if (realm.connectors.size == 1) "1 avatar" else "${realm.connectors.size} avatars"
