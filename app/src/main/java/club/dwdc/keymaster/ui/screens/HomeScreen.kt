package club.dwdc.keymaster.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import club.dwdc.keymaster.AbstractKeyEntry
import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keymaster.SSHKeyEntry
import club.dwdc.keymaster.NostrTransport
import club.dwdc.keymaster.crypto.NostrKeyService
import club.dwdc.keymaster.avatar.AvatarService
import club.dwdc.keymaster.data.Account
import club.dwdc.keymaster.data.AppPermission
import club.dwdc.keymaster.data.AvatarSession
import club.dwdc.keymaster.data.AvatarSessionRepository
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.Nip46Session
import club.dwdc.keymaster.data.Nip46SessionRepository
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.data.SeedRepository
import club.dwdc.keymaster.nip46.Nip46Service
import club.dwdc.keymaster.ui.components.CreateAccountDialog

@Composable
fun HomeScreen(
    onNavigateToNip46Scan: (accountIdentity: String) -> Unit = {},
    onNavigateToAvatarScan: (identity: String) -> Unit = {},
    onNavigateToRestoreScan: () -> Unit = {}
) {
    val context = LocalContext.current
    val seedRepo = SeedRepository(context)
    val permRepo = PermissionRepository(context)
    val sessionRepo = Nip46SessionRepository(context)
    val avatarSessionRepo = AvatarSessionRepository(context)
    val mnemonic = seedRepo.getMnemonic()
    val passphrase = seedRepo.getPassphrase()

    var accounts by remember { mutableStateOf(KeyMasterProvider.getAccounts(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { accounts.size + 1 })

    // Refresh accounts when the screen resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accounts = KeyMasterProvider.getAccounts(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
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
            text = "Nostr Signer Ready",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Page indicator dots
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(accounts.size + 1) { index ->
                val color = if (pagerState.currentPage == index)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outlineVariant
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = color,
                        modifier = Modifier.fillMaxSize()
                    ) {}
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            if (page < accounts.size) {
                AccountPage(
                    account = accounts[page],
                    mnemonic = mnemonic,
                    passphrase = passphrase,
                    permRepo = permRepo,
                    sessionRepo = sessionRepo,
                    avatarSessionRepo = avatarSessionRepo,
                    onConnectNip46 = { onNavigateToNip46Scan(accounts[page].identity) },
                    onNavigateToAvatarScan = { onNavigateToAvatarScan(accounts[page].identity) },
                    onDeleteAccount = {
                        KeyMasterProvider.getMetaStore(context)
                            .removeByIdentity(accounts[page].identity)
                        accounts = KeyMasterProvider.getAccounts(context)
                    }
                )
            } else {
                AddAccountPage(
                    onCreate = { showCreateDialog = true },
                    onRestore = onNavigateToRestoreScan
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateAccountDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { identity, name, email ->
                val controller = KeyMasterProvider.getController(context)
                if (controller != null) {
                    controller.createIdentity(identity, name, email)
                    accounts = KeyMasterProvider.getAccounts(context)
                }
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun AccountPage(
    account: Account,
    mnemonic: String?,
    passphrase: String,
    permRepo: PermissionRepository,
    sessionRepo: Nip46SessionRepository,
    avatarSessionRepo: AvatarSessionRepository,
    onConnectNip46: () -> Unit,
    onNavigateToAvatarScan: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val keyService = remember(mnemonic, passphrase, account.identity) {
        if (mnemonic != null) {
            try { NostrKeyService(mnemonic, passphrase, account.identity) } catch (_: Exception) { null }
        } else null
    }
    val npub = remember(keyService) {
        try { keyService?.getNpub() } catch (_: Exception) { null }
    }

    var copiedHex by remember { mutableStateOf(false) }
    var copiedNpub by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(permRepo.getPermissionsForAccount(account.pubkeyHex)) }
    var nip46Sessions by remember { mutableStateOf(sessionRepo.getSessionsForAccount(account.pubkeyHex)) }
    var avatarSession by remember { mutableStateOf(avatarSessionRepo.getSession()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Refresh permissions, sessions, and avatar state when lifecycle resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, account.pubkeyHex) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = permRepo.getPermissionsForAccount(account.pubkeyHex)
                nip46Sessions = sessionRepo.getSessionsForAccount(account.pubkeyHex)
                avatarSession = avatarSessionRepo.getSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Identity",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = account.identity,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (npub != null) {
                    Text(
                        text = "Public Key (npub)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = npub,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(npub))
                            copiedNpub = true
                            copiedHex = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (copiedNpub) "Copied!" else "Copy npub")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "Public Key (hex)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = account.pubkeyHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(account.pubkeyHex))
                        copiedHex = true
                        copiedNpub = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (copiedHex) "Copied!" else "Copy hex")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Derived Keys card
        KeyEntriesCard(account.identity)

        Spacer(modifier = Modifier.height(24.dp))

        // Avatar card
        AvatarCard(
            avatarSession = avatarSession,
            accountIdentity = account.identity,
            onAttach = onNavigateToAvatarScan,
            onDetach = {
                AvatarService.detach(context)
                avatarSession = null
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // App Permissions card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "App Permissions",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (permissions.isEmpty()) {
                    Text(
                        text = "No apps have requested access yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    permissions.entries.forEachIndexed { index, (pkg, perm) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (perm == AppPermission.ALLOWED) "Allowed" else "Denied",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (perm == AppPermission.ALLOWED)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }

                            TextButton(
                                onClick = {
                                    permRepo.removePermission(pkg, account.pubkeyHex)
                                    permissions = permRepo.getPermissionsForAccount(account.pubkeyHex)
                                }
                            ) {
                                Text(
                                    text = "Revoke",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (index < permissions.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // NIP-46 Remote Sessions card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "NIP-46 Remote Sessions",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (nip46Sessions.isEmpty()) {
                    Text(
                        text = "No remote clients connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    nip46Sessions.forEachIndexed { index, session ->
                        Nip46SessionRow(
                            session = session,
                            onDisconnect = {
                                Nip46Service.disconnect(context, session.clientPubkey)
                                nip46Sessions = sessionRepo.getSessionsForAccount(account.pubkeyHex)
                            }
                        )
                        if (index < nip46Sessions.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onConnectNip46,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect New Client")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Delete identity button
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Delete Identity")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How to use",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "NIP-55: Open a Nostr client app on this device and choose " +
                           "\"Login with Signer\".\n\n" +
                           "NIP-46: Tap \"Connect New Client\" above and scan the " +
                           "nostrconnect:// QR code from a remote Nostr client.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Identity") },
            text = {
                Text("Delete identity \"${account.identity}\"? " +
                     "The keys can be re-derived from your seed phrase.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun KeyEntriesCard(identity: String) {
    val context = LocalContext.current
    val entries = remember(identity) {
        KeyMasterProvider.getMetaStore(context).byIdentity(identity)
    }

    val sshCount = entries.count { it is SSHKeyEntry }
    val gpgEntries = entries.filterIsInstance<GPGKeyEntry>()
    val nostrEntries = entries.filterIsInstance<NostrKeyEntry>()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Derived Keys",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Text(
                    text = "No keys derived for this identity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // SSH
                if (sshCount > 0) {
                    KeyTypeRow(type = "SSH", detail = "ED25519")
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // GPG
                for (gpg in gpgEntries) {
                    val role = if (gpg.role() == 0) "certification" else "signing"
                    KeyTypeRow(
                        type = "GPG ($role)",
                        detail = "${gpg.name()} <${gpg.email()}>"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Nostr
                for (nostr in nostrEntries) {
                    KeyTypeRow(
                        type = "Nostr",
                        detail = nostr.pubkey().take(16) + "..." + nostr.pubkey().takeLast(8)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "${entries.size} key${if (entries.size != 1) "s" else ""} total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AvatarCard(
    avatarSession: AvatarSession?,
    accountIdentity: String,
    onAttach: () -> Unit,
    onDetach: () -> Unit
) {
    val connState by AvatarService.connectionState.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Avatar",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (avatarSession != null && avatarSession.identity == accountIdentity) {
                Text(
                    text = avatarSession.relayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Identity: ${avatarSession.identity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Connection state indicator
                ConnectionStateRow(connState)

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onDetach,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Detach")
                }
            } else {
                Text(
                    text = "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onAttach,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Attach to Avatar")
                }
            }
        }
    }
}

@Composable
private fun ConnectionStateRow(state: NostrTransport.ConnectionState) {
    val (color, label) = when (state) {
        NostrTransport.ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
        NostrTransport.ConnectionState.RETRYING -> Color(0xFFFFC107) to "Reconnecting..."
        NostrTransport.ConnectionState.DISCONNECTED -> Color(0xFFF44336) to "Disconnected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KeyTypeRow(type: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = type,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Nip46SessionRow(
    session: Nip46Session,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.clientName
                    ?: (session.clientPubkey.take(8) + "..." + session.clientPubkey.takeLast(8)),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${session.relays.size} relay${if (session.relays.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TextButton(onClick = onDisconnect) {
            Text(
                text = "Disconnect",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AddAccountPage(onCreate: () -> Unit, onRestore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "+",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create New Account")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore from QR")
            }
        }
    }
}
