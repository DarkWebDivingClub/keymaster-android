package club.dwdc.keymaster.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * Persists the active Avatar session in SharedPreferences.
 * Single session at a time — matches desktop km-daemon behavior.
 */
class AvatarSessionRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSession(session: AvatarSession) {
        prefs.edit().putString(KEY_SESSION, gson.toJson(session)).apply()
    }

    fun getSession(): AvatarSession? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try { gson.fromJson(json, AvatarSession::class.java) } catch (_: Exception) { null }
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun hasSession(): Boolean = prefs.contains(KEY_SESSION)

    companion object {
        private const val PREFS_NAME = "keymaster_avatar_session"
        private const val KEY_SESSION = "avatar_session"
    }
}

data class AvatarSession(
    val descriptorJson: String,
    val identity: String,
    val sessionId: String,
    val relayUrl: String,
    val attachedAt: Long,
    val additionalIdentities: List<String> = emptyList()
)
