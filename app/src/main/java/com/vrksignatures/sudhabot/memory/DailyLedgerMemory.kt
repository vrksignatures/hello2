package com.vrksignatures.sudhabot.memory

import android.content.Context
import com.google.gson.Gson
import com.vrksignatures.sudhabot.model.EmployeeChancePlan
import java.time.LocalDate

/**
 * TIER 2: TEMPORARY (DAILY) MEMORY — "Today's Retailer Progress"
 *
 * Tracks today's exact retailer progress across pauses/restarts.
 * Auto-resets strictly at calendar midnight (00:00) whenever LocalDate advances (YYYY-MM-DD),
 * NEVER using a rolling 24-hour timer and NEVER touching Tier 1 Permanent Distributor Memory.
 */
class DailyLedgerMemory(
    private val store: KeyValueStore,
    private val todayProvider: () -> LocalDate = { LocalDate.now() }
) {

    constructor(context: Context) : this(
        SharedPrefsKeyValueStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    private val gson = Gson()

    val savedCalendarDate: String
        get() = store.getString(KEY_SAVED_DATE, "") ?: ""

    val lastHandledCustomerId: String?
        get() = store.getString(KEY_LAST_CUSTOMER_ID, null)?.takeIf { it.isNotBlank() }

    val lastHandledRetailerName: String?
        get() = store.getString(KEY_LAST_RETAILER_NAME, null)?.takeIf { it.isNotBlank() }

    val tempRetailerScrollCount: Int
        get() = store.getInt(KEY_TEMP_SCROLL_COUNT, 0).coerceAtLeast(0)

    val processedIdsToday: Set<String>
        get() = store.getStringSet(KEY_PROCESSED_IDS)

    val successCountToday: Int
        get() = store.getInt(KEY_SUCCESS_COUNT, 0)

    val skippedCountToday: Int
        get() = store.getInt(KEY_SKIPPED_COUNT, 0)

    val errorCountToday: Int
        get() = store.getInt(KEY_ERROR_COUNT, 0)

    val processedGenuineIndex: Int
        get() = processedIdsToday.size

    /**
     * Strict Calendar Midnight (00:00) Auto-Reset check.
     * Must be invoked on EVERY app open and EVERY ▶ START press.
     *
     * @return true if a midnight rollover occurred and Tier 2 was wiped.
     */
    fun checkAndResetAtMidnight(): Boolean {
        val todayStr = todayProvider().toString() // Strict ISO YYYY-MM-DD
        val saved = savedCalendarDate
        if (saved.isBlank()) {
            store.putString(KEY_SAVED_DATE, todayStr)
            return false
        }
        if (saved != todayStr) {
            resetTodaysLedger()
            return true
        }
        return false
    }

    /**
     * Clears ONLY Tier 2 Temporary Daily Memory and sets savedCalendarDate to today.
     * NEVER touches Tier 1 Permanent Distributor Memory.
     */
    fun resetTodaysLedger() {
        val todayStr = todayProvider().toString()
        store.clearAll()
        store.putString(KEY_SAVED_DATE, todayStr)
    }

    fun recordRetailerSuccess(
        customerId: String,
        retailerName: String,
        auditedTempScrollCount: Int
    ) {
        val id = customerId.trim().uppercase()
        val updated = processedIdsToday.toMutableSet().apply { add(id) }
        store.putString(KEY_SAVED_DATE, todayProvider().toString())
        store.putString(KEY_LAST_CUSTOMER_ID, id)
        store.putString(KEY_LAST_RETAILER_NAME, retailerName.trim())
        store.putInt(KEY_TEMP_SCROLL_COUNT, auditedTempScrollCount.coerceAtLeast(0))
        store.putStringSet(KEY_PROCESSED_IDS, updated)
        store.putInt(KEY_SUCCESS_COUNT, successCountToday + 1)
    }

    fun recordRetailerSkipped(
        customerId: String,
        retailerName: String,
        auditedTempScrollCount: Int
    ) {
        val id = customerId.trim().uppercase()
        val updated = processedIdsToday.toMutableSet().apply { add(id) }
        store.putString(KEY_SAVED_DATE, todayProvider().toString())
        store.putString(KEY_LAST_CUSTOMER_ID, id)
        store.putString(KEY_LAST_RETAILER_NAME, retailerName.trim())
        store.putInt(KEY_TEMP_SCROLL_COUNT, auditedTempScrollCount.coerceAtLeast(0))
        store.putStringSet(KEY_PROCESSED_IDS, updated)
        store.putInt(KEY_SKIPPED_COUNT, skippedCountToday + 1)
    }

    fun incrementErrorCount() {
        store.putInt(KEY_ERROR_COUNT, errorCountToday + 1)
    }

    fun updateAuditedTempScrollCount(newScrollCount: Int) {
        store.putInt(KEY_TEMP_SCROLL_COUNT, newScrollCount.coerceAtLeast(0))
    }

    fun markManualOverrideAnchorFound(
        customerId: String,
        retailerName: String,
        foundTempScrollCount: Int
    ) {
        val id = customerId.trim().uppercase()
        val updated = processedIdsToday.toMutableSet().apply { add(id) }
        store.putString(KEY_SAVED_DATE, todayProvider().toString())
        store.putString(KEY_LAST_CUSTOMER_ID, id)
        store.putString(KEY_LAST_RETAILER_NAME, retailerName.trim())
        store.putInt(KEY_TEMP_SCROLL_COUNT, foundTempScrollCount.coerceAtLeast(0))
        store.putStringSet(KEY_PROCESSED_IDS, updated)
    }

    fun isProcessedToday(customerId: String): Boolean {
        val id = customerId.trim().uppercase()
        return id.isNotEmpty() && processedIdsToday.contains(id)
    }

    fun getEmployeeChancePlan(): EmployeeChancePlan? {
        val raw = store.getString(KEY_EMPLOYEE_CHANCE_PLAN, null) ?: return null
        return try {
            val plan = gson.fromJson(raw, EmployeeChancePlan::class.java)
            if (plan != null && plan.planDate == todayProvider().toString()) plan else null
        } catch (_: Exception) {
            null
        }
    }

    fun saveEmployeeChancePlan(plan: EmployeeChancePlan) {
        store.putString(KEY_EMPLOYEE_CHANCE_PLAN, gson.toJson(plan))
    }

    fun statusSummary(): String {
        val dateDisplay = savedCalendarDate.ifBlank { todayProvider().toString() }
        val lastId = lastHandledCustomerId ?: "None"
        return "Tier 2 Daily Ledger ($dateDisplay): Last ID=$lastId | " +
            "TempScroll=$tempRetailerScrollCount | OK=$successCountToday, Skip=$skippedCountToday, Err=$errorCountToday"
    }

    companion object {
        const val PREFS_NAME = "sudha_tier2_daily_ledger"
        private const val KEY_SAVED_DATE = "saved_calendar_date"
        private const val KEY_LAST_CUSTOMER_ID = "last_handled_customer_id"
        private const val KEY_LAST_RETAILER_NAME = "last_handled_retailer_name"
        private const val KEY_TEMP_SCROLL_COUNT = "temp_retailer_scroll_count"
        private const val KEY_PROCESSED_IDS = "processed_ids_today"
        private const val KEY_SUCCESS_COUNT = "success_count_today"
        private const val KEY_SKIPPED_COUNT = "skipped_count_today"
        private const val KEY_ERROR_COUNT = "error_count_today"
        private const val KEY_EMPLOYEE_CHANCE_PLAN = "employee_chance_plan_json"
    }
}
