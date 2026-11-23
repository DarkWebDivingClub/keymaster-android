package club.dwdc.keymaster.data

import club.dwdc.keymaster.AbstractKeyEntry
import com.google.gson.JsonObject
import org.bitcoinj.crypto.HDPath

/**
 * An entry of a coin type this app does not know (MLS, for example). Its JSON
 * is kept as read and written back unchanged, so a store round-trips without
 * losing or retyping it. Before mission 50 such entries were read as SSH.
 */
class OpaqueKeyEntry(path: HDPath, identity: String, raw: JsonObject) : AbstractKeyEntry(path, identity) {

    private val raw: JsonObject = raw.deepCopy()

    /** A copy of the entry's JSON as stored. */
    fun raw(): JsonObject = raw.deepCopy()
}
