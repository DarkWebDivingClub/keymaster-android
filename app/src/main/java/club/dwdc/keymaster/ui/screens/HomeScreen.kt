package club.dwdc.keymaster.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import club.dwdc.keymaster.avatar.ConnectorUi
import club.dwdc.keymaster.avatar.RealmUi
import club.dwdc.keymaster.avatar.describe
import club.dwdc.keymaster.data.Account
import club.dwdc.keymaster.data.AppPermission
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
    onNavigateToRestoreScan: () -> Unit = {},
    onNavigateToSeedManage: () -> Unit = {}
) {
    val context = LocalContext.current
    val seedRepo = SeedRepository(context)
    val permRepo = PermissionRepository(context)
    val sessionRepo = Nip46SessionRepository(context)
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

        val allRealms by AvatarService.realms.collectAsState()
        Text(
            text = headerLine(allRealms),
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
                    onRestore = onNavigateToRestoreScan,
                    onManageSeed = onNavigateToSeedManage
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
    val realms by AvatarService.realms.collectAsState()
    // A realm appears on its primary identity's page and nowhere else: that is
    // the same rule that lets "+ Attach" imply the primary, and it means no
    // realm is ever rendered twice.
    val myRealms = realms.filter { it.primaryIdentity == account.identity }
    val btReconnectPrompt by AvatarService.showBtReconnectPrompt.collectAsState()
    var btDialogDismissed by remember { mutableStateOf(false) }
    // Reset local dismiss when the prompt goes away (connection restored)
    LaunchedEffect(btReconnectPrompt) {
        if (!btReconnectPrompt) btDialogDismissed = false
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Refresh permissions, sessions, and avatar state when lifecycle resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, account.pubkeyHex) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = permRepo.getPermissionsForAccount(account.pubkeyHex)
                nip46Sessions = sessionRepo.getSessionsForAccount(account.pubkeyHex)
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
        AvatarsCard(
            realms = myRealms,
            onAttach = onNavigateToAvatarScan,
            onDetachAll = { AvatarService.detach(context) },
            onDetachConnector = { realmId, connectorId ->
                AvatarService.detachConnector(context, realmId, connectorId)
            },
            onRenameRealm = { realmId, name ->
                AvatarService.renameRealm(context, realmId, name)
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

    if (btReconnectPrompt && !btDialogDismissed) {
        AlertDialog(
            onDismissRequest = { btDialogDismissed = true },
            title = { Text("Bluetooth connection lost") },
            text = {
                Text("Open Bluetooth settings and turn Internet access off " +
                     "then on for the paired laptop.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        btDialogDismissed = true
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                ) {
                    Text("Open Bluetooth Settings")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { btDialogDismissed = true }) {
                    Text("Dismiss")
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
private fun AvatarsCard(
    realms: List<RealmUi>,
    onAttach: () -> Unit,
    onDetachAll: () -> Unit,
    onDetachConnector: (realmId: Int, connectorId: Int) -> Unit,
    onRenameRealm: (realmId: Int, name: String) -> Unit
) {
    val connectors = realms.flatMap { it.connectors }
    var renaming by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Avatars",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = countLabel(connectors),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (connectors.isEmpty()) {
                Text(
                    text = "Nothing attached. Scan an avatar QR code on a desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                realms.forEach { realm ->
                    // With one realm the heading is hidden: no point labelling
                    // a group that is the whole list.
                    if (realms.size > 1) {
                        RealmHeading(
                            realm = realm,
                            renaming = renaming == realm.id,
                            onStartRename = { renaming = realm.id },
                            onRename = { name ->
                                renaming = null
                                onRenameRealm(realm.id, name)
                            }
                        )
                    }
                    // Two avatars on one desktop share its Bluetooth name;
                    // show what tells them apart only when it is needed.
                    val ambiguous = realm.connectors.groupBy { it.label }
                        .filterValues { it.size > 1 }.keys
                    realm.connectors.forEach { connector ->
                        ConnectorRow(
                            connector = connector,
                            disambiguate = connector.label in ambiguous,
                            onDetach = { onDetachConnector(realm.id, connector.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAttach,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Attach")
            }

            if (connectors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDetachAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    // "Detach" only while it can mean one thing.
                    Text(if (connectors.size == 1) "Detach" else "Detach all")
                }
            }
        }
    }
}

/**
 * A realm is a heading inside the card, not a card of its own: one bold line
 * with a rename pencil, and no per-realm controls.
 */
@Composable
private fun RealmHeading(
    realm: RealmUi,
    renaming: Boolean,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (renaming) {
            var draft by remember { mutableStateOf(realm.name) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Realm name") },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onRename(draft) }) { Text("Save") }
        } else {
            Text(
                text = realm.name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onStartRename, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Rename realm",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One avatar: state dot, label, transport badge, eject — and backoff when failing. */
@Composable
private fun ConnectorRow(
    connector: ConnectorUi,
    disambiguate: Boolean = false,
    onDetach: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor(connector.state))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (disambiguate && connector.detail.isNotBlank())
                    "${connector.label} · ${connector.detail}" else connector.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = connector.transport,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDetach, modifier = Modifier.size(28.dp)) {
                Text("⏏", style = MaterialTheme.typography.bodyMedium)
            }
        }
        // The sub-line belongs to the row that is failing, not to the card.
        if (connector.state != NostrTransport.ConnectionState.CONNECTED) {
            Text(
                text = subLine(connector),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
    }
}

private fun stateColor(state: NostrTransport.ConnectionState): Color = when (state) {
    NostrTransport.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
    NostrTransport.ConnectionState.RETRYING -> Color(0xFFFFC107)
    NostrTransport.ConnectionState.DISCONNECTED -> Color(0xFFF44336)
}

private fun subLine(connector: ConnectorUi): String {
    val attempt = connector.retryAttempt ?: return connector.state.describe()
    val total = connector.retryTotal
    // Past the fast attempts there is no total to count towards: it retries
    // for as long as the avatar is still wanted.
    return if (total != null) "Reconnecting · attempt $attempt of $total"
    else "Reconnecting · attempt $attempt, retrying every 60s"
}

/** "none", "1 connected", or "1 of 2" while one is down. */
private fun countLabel(connectors: List<ConnectorUi>): String {
    if (connectors.isEmpty()) return "none"
    val connected = connectors.count {
        it.state == NostrTransport.ConnectionState.CONNECTED
    }
    return if (connected == connectors.size) "$connected connected"
    else "$connected of ${connectors.size}"
}

/**
 * The line under "KeyMaster". Counts realms only when there is more than one,
 * so a single-realm user reads exactly what phase B showed.
 */
private fun headerLine(realms: List<RealmUi>): String {
    val connectors = realms.flatMap { it.connectors }
    return when {
        connectors.isEmpty() -> "Nostr Signer Ready"
        realms.size > 1 -> "${connectors.size} avatars in ${realms.size} realms"
        connectors.size == 1 -> "1 avatar attached"
        else -> "${connectors.size} avatars attached"
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
private fun AddAccountPage(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onManageSeed: () -> Unit
) {
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
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onManageSeed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Seed")
            }
        }
    }
}
