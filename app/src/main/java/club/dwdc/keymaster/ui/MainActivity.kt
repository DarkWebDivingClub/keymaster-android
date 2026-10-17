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
import club.dwdc.keymaster.avatar.AvatarService
import club.dwdc.keymaster.avatar.AvatarWatchdog
import club.dwdc.keymaster.avatar.BatteryOptimization
import club.dwdc.keymaster.data.AccountRepository
import club.dwdc.keymaster.data.KeyMasterProvider
import club.dwdc.keymaster.data.PermissionRepository
import club.dwdc.keymaster.data.SeedRepository
import club.dwdc.keymaster.nip46.Nip46Service
import club.dwdc.keymaster.nip46.NostrConnectUrl
import club.dwdc.keymaster.ui.components.AvatarConfirmDialog
import club.dwdc.keymaster.ui.components.Nip46ConfirmDialog
import club.dwdc.keymaster.ui.screens.AvatarScanScreen
import club.dwdc.keymaster.ui.screens.CreateFirstIdentityScreen
import club.dwdc.keymaster.ui.screens.HomeScreen
import club.dwdc.keymaster.ui.screens.Nip46ScanScreen
import club.dwdc.keymaster.ui.screens.RestoreGpgScanScreen
import club.dwdc.keymaster.ui.screens.SetupScreen
import club.dwdc.keymaster.ui.theme.KeyMasterTheme
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "keymaster_migration"
        private const val KEY_ACCOUNTS_MIGRATED = "accounts_migrated_to_kvmetastore"
    }

    /**
     * Last line of defence for the service lifecycle: opening the app
     * re-attaches whatever is persisted if the service is gone.
     *
     * The watchdog alarm and the boot receiver cover the cases where nobody
     * touches the phone; this covers the case where somebody does, and it is
     * the one trigger that needs no exemption, because a foreground service
     * started from a visible activity is always allowed.
     */
    override fun onResume() {
        super.onResume()
        if (AvatarWatchdog.needsRestore(this)) {
            Log.i(TAG, "Session persisted but service not running — restoring on resume")
            AvatarService.restore(this)
            AvatarWatchdog.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val seedRepo = SeedRepository(this)
        if (seedRepo.hasSeed()) {
            migrateAccountsToKVMetaStore()
        }

        setContent {
            KeyMasterTheme {
                val navController = rememberNavController()
                val startDest = when {
                    !seedRepo.hasSeed() -> "setup"
                    KeyMasterProvider.getMetaStore(this@MainActivity).identities().isEmpty() -> "firstidentity"
                    else -> "home"
                }

                // State for the NIP-46 confirmation dialog
                var pendingConnectUrl by remember { mutableStateOf<NostrConnectUrl?>(null) }
                var pendingConnectRawUrl by remember { mutableStateOf("") }
                var pendingConnectIdentity by remember { mutableStateOf("") }

                // State for the Avatar confirmation dialog
                var pendingAvatarDescriptor by remember { mutableStateOf<String?>(null) }
                var pendingAvatarIdentity by remember { mutableStateOf("") }

                NavHost(navController, startDestination = startDest) {
                    composable("setup") {
                        SetupScreen(
                            onSetupComplete = {
                                navController.navigate("firstidentity") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("firstidentity") {
                        CreateFirstIdentityScreen(
                            onIdentityCreated = {
                                navController.navigate("home") {
                                    popUpTo("firstidentity") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            onNavigateToNip46Scan = { identity ->
                                navController.navigate("nip46scan/$identity")
                            },
                            onNavigateToAvatarScan = { identity ->
                                navController.navigate("avatarscan/$identity")
                            },
                            onNavigateToRestoreScan = {
                                navController.navigate("restoregpgscan")
                            },
                            onNavigateToSeedManage = {
                                navController.navigate("seedmanage")
                            }
                        )
                    }
                    composable("seedmanage") {
                        SetupScreen(
                            hasSeed = true,
                            onSetupComplete = {
                                navController.popBackStack("home", inclusive = false)
                            },
                            onBack = { navController.popBackStack() }
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
                    composable(
                        "avatarscan/{identity}",
                        arguments = listOf(navArgument("identity") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val identity = backStackEntry.arguments?.getString("identity") ?: "default"
                        AvatarScanScreen(
                            onDescriptorScanned = { descriptorJson ->
                                Log.d("MainActivity", "Avatar descriptor scanned, len=${descriptorJson.length}")
                                pendingAvatarDescriptor = descriptorJson
                                pendingAvatarIdentity = identity
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("restoregpgscan") {
                        RestoreGpgScanScreen(
                            onRestored = {
                                navController.popBackStack("home", inclusive = false)
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

                // Avatar confirmation dialog (shown on top of any screen)
                pendingAvatarDescriptor?.let { descriptorJson ->
                    AvatarConfirmDialog(
                        descriptorJson = descriptorJson,
                        defaultIdentity = pendingAvatarIdentity,
                        onConfirm = { identity, additionalIdentities ->
                            // Asked here, not on launch: at the moment the
                            // user attaches, "keep this connection alive" is
                            // self-explanatory. Without the exemption the
                            // service is killed under doze and no background
                            // trigger may restart it.
                            if (!BatteryOptimization.isExempt(this@MainActivity)) {
                                BatteryOptimization.requestExemption(this@MainActivity)
                            }
                            AvatarService.startAttach(
                                this@MainActivity,
                                descriptorJson,
                                identity,
                                additionalIdentities
                            )
                            pendingAvatarDescriptor = null
                            navController.popBackStack("home", inclusive = false)
                        },
                        onDismiss = {
                            pendingAvatarDescriptor = null
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

}
