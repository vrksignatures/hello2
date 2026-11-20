package com.vrksignatures.sudhabot.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vrksignatures.sudhabot.model.CustomerDemandRule
import com.vrksignatures.sudhabot.model.EmployeeChanceConfig
import com.vrksignatures.sudhabot.model.PaymentMode

class BotPreferences(private val store: KeyValueStore) {

    constructor(context: Context) : this(
        SharedPrefsKeyValueStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    private val gson = Gson()

    var targetAppPackage: String
        get() = store.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_TARGET_PACKAGE
        set(value) = store.putString(KEY_TARGET_PACKAGE, value.trim().ifBlank { DEFAULT_TARGET_PACKAGE })

    var expectedRetailersToday: Int
        get() = store.getInt(KEY_EXPECTED_RETAILERS, 125).coerceAtLeast(1)
        set(value) = store.putInt(KEY_EXPECTED_RETAILERS, value.coerceAtLeast(1))

    /**
     * Manual override field ("Last handled customer ID (optional)").
     * Once consumed (anchored and advanced), cleared automatically so subsequent resumes use live Tier 2 ID.
     */
    var manualLastHandledIdOverride: String?
        get() = store.getString(KEY_MANUAL_OVERRIDE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) = store.putString(KEY_MANUAL_OVERRIDE_ID, value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() })

    var fastMode: Boolean
        get() = store.getBoolean(KEY_FAST_MODE, false)
        set(value) = store.putBoolean(KEY_FAST_MODE, value)

    var paymentMode: PaymentMode
        get() {
            val raw = store.getString(KEY_PAYMENT_MODE, PaymentMode.MANUAL_PAUSE.name)
            return runCatching { PaymentMode.valueOf(raw ?: PaymentMode.MANUAL_PAUSE.name) }
                .getOrDefault(PaymentMode.MANUAL_PAUSE)
        }
        set(value) = store.putString(KEY_PAYMENT_MODE, value.name)

    fun getCustomerDemandRules(): List<CustomerDemandRule> {
        val raw = store.getString(KEY_DEMAND_RULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CustomerDemandRule>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomerDemandRules(rules: List<CustomerDemandRule>) {
        store.putString(KEY_DEMAND_RULES, gson.toJson(rules))
    }

    fun getEmployeeChanceConfig(): EmployeeChanceConfig {
        val raw = store.getString(KEY_EMPLOYEE_CHANCE, null) ?: return EmployeeChanceConfig()
        return try {
            gson.fromJson(raw, EmployeeChanceConfig::class.java) ?: EmployeeChanceConfig()
        } catch (_: Exception) {
            EmployeeChanceConfig()
        }
    }

    fun saveEmployeeChanceConfig(config: EmployeeChanceConfig) {
        store.putString(KEY_EMPLOYEE_CHANCE, gson.toJson(config))
    }

    companion object {
        const val PREFS_NAME = "sudha_bot_preferences"
        const val DEFAULT_TARGET_PACKAGE = "in.vasista.suprabhat"
        private const val KEY_TARGET_PACKAGE = "target_app_package"
        private const val KEY_EXPECTED_RETAILERS = "expected_retailers_today"
        private const val KEY_MANUAL_OVERRIDE_ID = "manual_last_handled_id_override"
        private const val KEY_FAST_MODE = "fast_mode"
        private const val KEY_PAYMENT_MODE = "payment_mode"
        private const val KEY_DEMAND_RULES = "customer_demand_rules_json"
        private const val KEY_EMPLOYEE_CHANCE = "employee_chance_config_json"
    }
}
