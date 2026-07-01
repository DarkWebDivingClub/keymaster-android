package club.dwdc.keymaster.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import club.dwdc.keymaster.data.AccountRepository
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.data.SeedRepository
import club.dwdc.keymaster.nip46.Nip46Service
import club.dwdc.keymaster.nip46.NostrConnectUrl
import club.dwdc.keymaster.ui.components.Nip46ConfirmDialog
import club.dwdc.keymaster.ui.screens.HomeScreen
import club.dwdc.keymaster.ui.screens.Nip46ScanScreen
import club.dwdc.keymaster.ui.screens.SetupScreen
import club.dwdc.keymaster.ui.theme.KeyMasterTheme
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "keymaster_migration"
        private const val KEY_ACCOUNTS_MIGRATED = "accounts_migrated_to_kvmetastore"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val seedRepo = SeedRepository(this)
        if (seedRepo.hasSeed()) {
            migrateAccountsToKVMetaStore()
            ensureDefaultIdentity()
        }

        setContent {
            KeyMasterTheme {
                val navController = rememberNavController()
                val startDest = if (seedRepo.hasSeed()) "home" else "setup"

                // State for the NIP-46 confirmation dialog
                var pendingConnectUrl by remember { mutableStateOf<NostrConnectUrl?>(null) }
                var pendingConnectRawUrl by remember { mutableStateOf("") }
                var pendingConnectIdentity by remember { mutableStateOf("") }

                NavHost(navController, startDestination = startDest) {
                    composable("setup") {
                        SetupScreen(
                            onSetupComplete = {
                                navController.navigate("home") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            onNavigateToNip46Scan = { identity ->
                                navController.navigate("nip46scan/$identity")
                            }
                        )
                    }
                    composable(
                        "nip46scan/{identity}",
                        arguments = listOf(navArgument("identity") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val identity = backStackEntry.arguments?.getString("identity") ?: "default"
                        Nip46ScanScreen(
                            onUrlScanned = { connectUrl, rawUrl ->
                                pendingConnectUrl = connectUrl
                                pendingConnectRawUrl = rawUrl
                                pendingConnectIdentity = identity
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                // NIP-46 confirmation dialog (shown on top of any screen)
                pendingConnectUrl?.let { connectUrl ->
                    Nip46ConfirmDialog(
                        connectUrl = connectUrl,
                        onConfirm = {
                            Nip46Service.startConnect(
                                this@MainActivity,
                                pendingConnectRawUrl,
                                pendingConnectIdentity
                            )
                            pendingConnectUrl = null
                            // Navigate back to home
                            navController.popBackStack("home", inclusive = false)
                        },
                        onDismiss = {
                            pendingConnectUrl = null
                        }
                    )
                }
            }
        }
    }

    /**
     * Migrate existing accounts from AccountRepository to KVMetaStore.
     * Runs once on upgrade from the old account model.
     */
    private fun migrateAccountsToKVMetaStore() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACCOUNTS_MIGRATED, false)) return

        val accountRepo = AccountRepository(this)
        val oldAccounts = accountRepo.getAccounts()
        if (oldAccounts.isEmpty()) {
            // No old accounts — mark as migrated (fresh install or already empty)
            prefs.edit().putBoolean(KEY_ACCOUNTS_MIGRATED, true).apply()
            return
        }

        val controller = KeyMasterProvider.getController(this) ?: return
        for (account in oldAccounts) {
            // Check if identity already exists in KVMetaStore
            val existing = KeyMasterProvider.getMetaStore(this).byIdentity(account.identity)
            if (existing.isEmpty()) {
                controller.createIdentity(account.identity, account.identity, account.identity)
                Log.d(TAG, "Migrated account: ${account.identity}")
            }
        }

        // Migrate permissions for the first account if needed
        if (oldAccounts.isNotEmpty()) {
            val firstOldPubkey = oldAccounts.first().pubkeyHex
            val firstNewAccount = KeyMasterProvider.findAccountByPubkey(this, firstOldPubkey)
            if (firstNewAccount == null) {
                // Pubkeys may differ (old used NostrKeyService, new uses KeyMasterController)
                // Migrate permissions to the new pubkey for the same identity
                val newAccounts = KeyMasterProvider.getAccounts(this)
                if (newAccounts.isNotEmpty()) {
                    PermissionRepository(this).migratePermissions(newAccounts.first().pubkeyHex)
                }
            }
        }

        prefs.edit().putBoolean(KEY_ACCOUNTS_MIGRATED, true).apply()
        Log.d(TAG, "Account migration complete")
    }

    /**
     * Ensure the "default" identity exists in KVMetaStore.
     */
    private fun ensureDefaultIdentity() {
        val store = KeyMasterProvider.getMetaStore(this)
        val existing = store.byIdentity("default")
        if (existing.isEmpty()) {
            val controller = KeyMasterProvider.getController(this) ?: return
            controller.createIdentity("default", "default", "default")
        }
    }
}
