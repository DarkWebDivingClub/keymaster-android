package club.dwdc.keymaster.data

import android.content.Context
import club.dwdc.keymaster.AbstractKeyEntry
import club.dwdc.keymaster.GPGKeyEntry
import club.dwdc.keymaster.KVMetaStore
import club.dwdc.keymaster.NostrKeyEntry
import club.dwdc.keymaster.SSHKeyEntry
import club.dwdc.keyvault.core.Protocol
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDPath
import java.io.File

/**
 * Android implementation of [KVMetaStore].
 *
 * Stores all key entries as a JSON map in app-private storage
 * (`filesDir/kv-metadata.json`). Same JSON format as the desktop
 * FileKVMetaStore — a `Map<String, JsonObject>` keyed by HD path string.
 */
class AndroidKVMetaStore(context: Context) : KVMetaStore {

    private val storeFile: File = File(context.filesDir, "kv-metadata.json")
    private val entries: MutableMap<HDPath, AbstractKeyEntry> = load()

    override fun put(entry: AbstractKeyEntry) {
        entries[entry.path()] = entry
        save()
    }

    override fun remove(path: HDPath) {
        if (entries.remove(path) != null) {
            save()
        }
    }

    override fun get(path: HDPath): AbstractKeyEntry? = entries[path]

    override fun all(): List<AbstractKeyEntry> = entries.values.toList()

    override fun byIdentity(identity: String): List<AbstractKeyEntry> =
        entries.values.filter { it.identity() == identity }

    @Suppress("UNCHECKED_CAST")
    override fun <T : AbstractKeyEntry> byType(type: Class<T>): List<T> =
        entries.values.filter { type.isInstance(it) }.map { it as T }

    override fun identities(): List<String> =
        entries.values.map { it.identity() }.distinct()

    // --- persistence ---

    private fun load(): MutableMap<HDPath, AbstractKeyEntry> {
        if (!storeFile.exists()) return LinkedHashMap()
        val json = storeFile.readText()
        val rawMap: LinkedHashMap<String, JsonObject> =
            GSON.fromJson(json, MAP_TYPE) ?: return LinkedHashMap()

        val result = LinkedHashMap<HDPath, AbstractKeyEntry>()
        for ((pathStr, obj) in rawMap) {
            val path = HDPath.parsePath(pathStr)
            result[path] = deserializeEntry(path, obj)
        }
        return result
    }

    private fun save() {
        val rawMap = LinkedHashMap<String, JsonObject>()
        for ((path, entry) in entries) {
            rawMap[hdPathToString(path)] = serializeEntry(entry)
        }
        storeFile.writeText(GSON.toJson(rawMap, MAP_TYPE))
    }

    companion object {
        private val GSON = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()

        private val MAP_TYPE =
            object : TypeToken<LinkedHashMap<String, JsonObject>>() {}.type

        private fun serializeEntry(entry: AbstractKeyEntry): JsonObject {
            val obj = JsonObject()
            obj.addProperty("identity", entry.identity())
            when (entry) {
                is SSHKeyEntry -> obj.addProperty("comment", entry.comment())
                is GPGKeyEntry -> {
                    obj.addProperty("name", entry.name())
                    obj.addProperty("email", entry.email())
                    obj.addProperty("creation_time", entry.creationTime().toString())
                }
                is NostrKeyEntry -> obj.addProperty("pubkey", entry.pubkey())
            }
            return obj
        }

        private fun deserializeEntry(path: HDPath, obj: JsonObject): AbstractKeyEntry {
            val identity = obj.get("identity").asString
            val coinType = path.get(1).num()

            return when (coinType) {
                Protocol.SSH.coinType() -> {
                    val comment = if (obj.has("comment")) obj.get("comment").asString else identity
                    SSHKeyEntry(path, identity, comment)
                }
                Protocol.OPENPGP.coinType() -> {
                    val name = if (obj.has("name")) obj.get("name").asString else identity
                    val email = if (obj.has("email")) obj.get("email").asString else identity
                    val creationTime = if (obj.has("creation_time"))
                        obj.get("creation_time").asString.toLong() else 0L
                    GPGKeyEntry(path, identity, name, email, creationTime)
                }
                Protocol.NOSTR.coinType() -> {
                    val pubkey = if (obj.has("pubkey")) obj.get("pubkey").asString else ""
                    NostrKeyEntry(path, identity, pubkey)
                }
                else -> {
                    val comment = if (obj.has("comment")) obj.get("comment").asString else identity
                    SSHKeyEntry(path, identity, comment)
                }
            }
        }

        private fun hdPathToString(path: HDPath): String {
            val sb = StringBuilder("m")
            for (child: ChildNumber in path.list()) {
                sb.append('/')
                sb.append(child.num())
                if (child.isHardened) sb.append('\'')
            }
            return sb.toString()
        }
    }
}
