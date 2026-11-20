package com.vrksignatures.sudhabot.memory

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vrksignatures.sudhabot.model.CustomerCard
import com.vrksignatures.sudhabot.model.DistributorRecord

/**
 * Abstract key-value backing store so Tier 1 & Tier 2 memory can be unit-tested on JVM without Robolectric.
 */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String): Set<String>
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String?)
    fun putStringSet(key: String, value: Set<String>)
    fun clearAll()
}

class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value.toSet()).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * TIER 1: PERMANENT MEMORY — "Distributor Blueprint"
 *
 * Strictly isolated from Tier 2 Daily Memory.
 * NEVER auto-resets at midnight, on run completion, on pause, or on app restart.
 * Clears ONLY when the user explicitly taps "Reset Distributor History".
 */
class PermanentDistributorMemory(private val store: KeyValueStore) {

    constructor(context: Context) : this(
        SharedPrefsKeyValueStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    private val gson = Gson()

    val blueprintLearned: Boolean
        get() = store.getBoolean(KEY_BLUEPRINT_LEARNED, false)

    val memorizedDistributors: List<DistributorRecord>
        get() {
            val raw = store.getString(KEY_DISTRIBUTORS_JSON, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<DistributorRecord>>() {}.type
                gson.fromJson(raw, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    val permanentBlacklistIds: Set<String>
        get() = store.getStringSet(KEY_BLACKLIST_IDS)

    val permanentBlacklistSignatures: Set<String>
        get() = store.getStringSet(KEY_BLACKLIST_SIGNATURES)

    val permDistributorScrolls: Int
        get() = store.getInt(KEY_PERM_DISTRIBUTOR_SCROLLS, 0).coerceAtLeast(0)

    fun saveBlueprint(
        distributors: List<DistributorRecord>,
        blacklistIds: Set<String>,
        blacklistSignatures: Set<String>,
        permDistributorScrolls: Int
    ) {
        store.putString(KEY_DISTRIBUTORS_JSON, gson.toJson(distributors))
        store.putStringSet(KEY_BLACKLIST_IDS, blacklistIds.map { it.trim().uppercase() }.toSet())
        store.putStringSet(KEY_BLACKLIST_SIGNATURES, blacklistSignatures)
        store.putInt(KEY_PERM_DISTRIBUTOR_SCROLLS, permDistributorScrolls.coerceAtLeast(0))
        store.putBoolean(KEY_BLUEPRINT_LEARNED, true)
    }

    fun isPermanentlyBlacklisted(card: CustomerCard): Boolean {
        val normalizedId = card.customerId.trim().uppercase()
        if (normalizedId.isNotEmpty() && permanentBlacklistIds.contains(normalizedId)) {
            return true
        }
        val distSig = buildSignature(card.normalizedName, isDist = true, adjacentToDist = false)
        if (card.cardType == com.vrksignatures.sudhabot.model.CardType.DISTRIBUTOR &&
            permanentBlacklistSignatures.contains(distSig)
        ) {
            return true
        }
        return false
    }

    /**
     * Explicit user-triggered reset ONLY ("Reset Distributor History").
     */
    fun resetDistributorHistory() {
        store.clearAll()
    }

    fun statusSummary(): String {
        return if (!blueprintLearned) {
            "Tier 1 Permanent Blueprint: Not learned (will learn on next ▶ START)"
        } else {
            "Tier 1 Permanent Blueprint: LEARNED | Distributors: ${memorizedDistributors.size} | " +
                "Blacklisted IDs: ${permanentBlacklistIds.size} | Top Scrolls: $permDistributorScrolls"
        }
    }

    companion object {
        const val PREFS_NAME = "sudha_tier1_permanent_blueprint"
        private const val KEY_BLUEPRINT_LEARNED = "blueprint_learned"
        private const val KEY_DISTRIBUTORS_JSON = "memorized_distributors_json"
        private const val KEY_BLACKLIST_IDS = "permanent_blacklist_ids"
        private const val KEY_BLACKLIST_SIGNATURES = "permanent_blacklist_signatures"
        private const val KEY_PERM_DISTRIBUTOR_SCROLLS = "perm_distributor_scrolls"

        fun buildSignature(normalizedName: String, isDist: Boolean, adjacentToDist: Boolean): String =
            "$normalizedName|dist=$isDist|adj=$adjacentToDist"
    }
}
