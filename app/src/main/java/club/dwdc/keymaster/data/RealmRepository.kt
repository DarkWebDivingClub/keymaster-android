package club.dwdc.keymaster.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * What the phone remembers about its realms.
 *
 * A realm is a definition — a name, a primary identity, which other
 * identities it serves — plus the avatars attached to it. Keys are
 * deliberately absent: a realm rebuilt after a restart derives new channel
 * keys and the avatar evicts the stale session, so a realm's identity across
 * restarts is its definition, not its keys (mission 36.3.6, answered
 * question 8).
 *
 * The stored [StoredRealm.id] is whatever the core handed out when the realm
 * was last created, kept only so a UI row and a connector can agree on which
 * realm they belong to. It is rewritten on every restore.
 */
class RealmRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getRealms(): List<StoredRealm> {
        migrateIfNeeded()
        val json = prefs.getString(KEY_REALMS, null) ?: return emptyList()
        return parse(json)
    }

    fun saveRealms(realms: List<StoredRealm>) {
        prefs.edit().putString(KEY_REALMS, gson.toJson(realms)).apply()
    }

    /** Replace one realm's record, adding it if the id is new. */
    fun upsert(realm: StoredRealm) {
        val realms = getRealms().filter { it.id != realm.id } + realm
        saveRealms(realms)
    }

    fun remove(realmId: Int) {
        saveRealms(getRealms().filter { it.id != realmId })
    }

    /** Rewrite a realm's id after a restore gave it a new one. */
    fun reassignId(oldId: Int, newId: Int) {
        saveRealms(getRealms().map { if (it.id == oldId) it.copy(id = newId) else it })
    }

    fun clear() {
        prefs.edit().remove(KEY_REALMS).apply()
    }

    fun hasRealms(): Boolean = getRealms().isNotEmpty()

    /**
     * A truncated or hand-edited blob must leave the app empty, not crash it
     * on launch: this runs inside the foreground service's onCreate, and an
     * exception there is an app that cannot start.
     */
    private fun parse(json: String): List<StoredRealm> = try {
        val type = object : TypeToken<List<StoredRealm>>() {}.type
        gson.fromJson<List<StoredRealm>?>(json, type)
            ?.filter { it.primaryIdentity.isNotBlank() }
            ?.map { it.copy(connectors = it.connectors.filter { c -> c.descriptorJson.isNotBlank() }) }
            ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Unreadable realm store, starting empty: ${e.message}")
        emptyList()
    }

    /**
     * The one migration: the single-session blob written by every build up to
     * 0.3.0 becomes one realm, named after the primary identity's local part,
     * with its additional identities as [StoredRealm.alsoServe] and its avatar
     * as the one connector.
     *
     * Runs once and is marked done either way, so a user with nothing stored
     * does not pay for it on every read.
     */
    private fun migrateIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        try {
            val old = AvatarSessionRepository(appContext).getSession()
            if (old != null) {
                val realm = StoredRealm(
                    id = old.identity.hashCode() and 0x7FFFFFFF,
                    name = old.identity.substringBefore('@'),
                    primaryIdentity = old.identity,
                    alsoServe = old.additionalIdentities,
                    connectors = listOf(
                        StoredConnector(
                            descriptorJson = old.descriptorJson,
                            label = old.relayUrl,
                            attachedAt = old.attachedAt
                        )
                    )
                )
                prefs.edit().putString(KEY_REALMS, gson.toJson(listOf(realm))).apply()
                Log.i(TAG, "Migrated single session (${old.identity}) to one realm")
            }
        } catch (e: Exception) {
            // Losing the old session is bad; failing to start is worse. The
            // user re-attaches once instead of facing a crash loop.
            Log.e(TAG, "Session migration failed, starting empty: ${e.message}", e)
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    companion object {
        private const val TAG = "RealmRepository"
        private const val PREFS_NAME = "keymaster_realms"
        private const val KEY_REALMS = "realms"
        private const val KEY_MIGRATED = "migrated_from_single_session"
    }
}

data class StoredRealm(
    val id: Int,
    val name: String,
    val primaryIdentity: String,
    /** Additional identities this realm serves, all protocols. */
    val alsoServe: List<String> = emptyList(),
    val connectors: List<StoredConnector> = emptyList()
)

data class StoredConnector(
    val descriptorJson: String,
    /** Bluetooth name snapshotted at attach, or the relay host for WS. */
    val label: String,
    val attachedAt: Long
)
