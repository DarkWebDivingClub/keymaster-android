package club.dwdc.keymaster.nip55

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import club.dwdc.keymaster.data.Account
import club.dwdc.keymaster.data.AppPermission
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.ui.components.CreateAccountDialog
import club.dwdc.keymaster.ui.theme.KeyMasterTheme

class AccountPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "requesting_package"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_SELECTED_PUBKEY = "selected_pubkey"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestingPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (requestingPackage == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: resolveAppLabel(requestingPackage)

        setContent {
            KeyMasterTheme {
                AccountPickerScreen(
                    appLabel = appLabel,
                    packageName = requestingPackage,
                    onAccountSelected = { account ->
                        PermissionRepository(this).setPermission(
                            requestingPackage, account.pubkeyHex, AppPermission.ALLOWED
                        )
                        setResult(Activity.RESULT_OK, android.content.Intent().apply {
                            putExtra(EXTRA_SELECTED_PUBKEY, account.pubkeyHex)
                        })
                        finish()
                    },
                    onNewAccount = { identity, name, email ->
                        val controller = KeyMasterProvider.getController(this) ?: return@AccountPickerScreen
                        controller.createIdentity(identity, name, email)
                    },
                    onDeny = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = this.packageManager.getApplicationInfo(packageName, 0)
            this.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}

@Composable
private fun AccountPickerScreen(
    appLabel: String,
    packageName: String,
    onAccountSelected: (Account) -> Unit,
    onNewAccount: (identity: String, name: String, email: String) -> Unit,
    onDeny: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var accounts by remember { mutableStateOf(KeyMasterProvider.getAccounts(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Choose Account",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "$appLabel wants to read your public key",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(accounts) { account ->
                            AccountRow(account = account, onClick = { onAccountSelected(account) })
                            if (account != accounts.last()) {
                                HorizontalDivider()
                            }
                        }
                        item {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCreateDialog = true }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Text(
                                    text = "Create new account",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Deny")
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAccountDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { identity, name, email ->
                onNewAccount(identity, name, email)
                accounts = KeyMasterProvider.getAccounts(context)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = account.identity,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = account.pubkeyHex.take(16) + "..." + account.pubkeyHex.takeLast(8),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
