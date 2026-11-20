package com.vrksignatures.sudhabot.model

import android.graphics.Rect

enum class BotState {
    IDLE,
    LEARNING_DISTRIBUTORS,
    JUMPING_SCROLL,
    RUNNING,
    PAUSED,
    MANUAL_PAYMENT,
    ALARM_HALTED,
    ERROR
}

enum class PaymentMode {
    MANUAL_PAUSE,
    AUTO_PAY_CASH
}

enum class CardType {
    DISTRIBUTOR,
    RETAILER,
    UNKNOWN
}

enum class MilkFamily(val displayName: String) {
    DTM("DTM"),
    TM("TM"),
    COW("COW"),
    SM("SM"),
    FCM("FCM"),
    SUDHA_DELIGHT("SUDHA DELIGHT")
}

/**
 * Exact 13 Milk SKUs verified in Sudha Daily Orders (in.vasista.suprabhat) New Order Milk table.
 */
enum class MilkSku(
    val exactLabel: String,
    val family: MilkFamily,
    val packSizeMl: Int
) {
    DTM_180("DTM 180 ML", MilkFamily.DTM, 180),
    TM_500("TM 500 ML", MilkFamily.TM, 500),
    TM_1000("TM 1000 ML", MilkFamily.TM, 1000),
    COW_500("COW 500 ML", MilkFamily.COW, 500),
    COW_1000("COW 1000 ML", MilkFamily.COW, 1000),
    COW_6000("COW 6000 ML", MilkFamily.COW, 6000),
    SM_500("SM 500 ML", MilkFamily.SM, 500),
    SM_1000("SM 1000 ML", MilkFamily.SM, 1000),
    SM_6000("SM 6000 ML", MilkFamily.SM, 6000),
    FCM_500("FCM 500 ML", MilkFamily.FCM, 500),
    FCM_1000("FCM 1000 ML", MilkFamily.FCM, 1000),
    FCM_6000("FCM 6000 ML", MilkFamily.FCM, 6000),
    SUDHA_DELIGHT_1000("SUDHA DELIGHT 1000 ML", MilkFamily.SUDHA_DELIGHT, 1000);

    companion object {
        fun fromTableLabel(raw: String): MilkSku? {
            val normalized = raw.trim().uppercase().replace(Regex("\\s+"), " ")
            return entries.firstOrNull { it.exactLabel == normalized }
                ?: entries.firstOrNull { normalized.contains(it.exactLabel) }
        }
    }
}

data class DistributorRecord(
    val name: String,
    val customerId: String
)

data class CustomerCard(
    val customerId: String,
    val boldOrangeName: String,
    val cardType: CardType,
    val bounds: Rect = Rect(),
    val verticalOrder: Int = 0
) {
    val normalizedName: String
        get() = normalizeName(boldOrangeName)

    companion object {
        fun normalizeName(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

enum class DemandRuleType {
    EXACT_PACK_OVERRIDE,
    VOLUME_DELTA_ML
}

data class CustomerDemandRule(
    val id: String,
    val customerId: String,
    val ruleType: DemandRuleType,
    val skuName: String? = null,      // Used for EXACT_PACK_OVERRIDE (MilkSku.exactLabel)
    val familyName: String? = null,   // Used for VOLUME_DELTA_ML (MilkFamily.name)
    val value: Int                    // Exact MQty count OR signed ml delta (+1000, -500)
) {
    fun displaySummary(): String {
        return when (ruleType) {
            DemandRuleType.EXACT_PACK_OVERRIDE ->
                "${customerId.uppercase()}: ${skuName ?: "?"} = $value packs"
            DemandRuleType.VOLUME_DELTA_ML -> {
                val sign = if (value >= 0) "+" else ""
                "${customerId.uppercase()}: ${familyName ?: "?"} ${sign}${value} ml"
            }
        }
    }
}

data class EmployeeChanceConfig(
    val enabled: Boolean = false,
    val totalExtraVolumeMl: Int = 0,
    val targetRetailerCount: Int = 0,
    val eligibleFamilies: Set<String> = setOf(MilkFamily.COW.name, MilkFamily.SM.name)
)

/**
 * Deterministic-for-today scattered allocation mapping 1-based genuine retailer index -> extra ml.
 */
data class EmployeeChancePlan(
    val planDate: String,
    val totalExtraVolumeMl: Int,
    val targetRetailerCount: Int,
    val expectedRetailers: Int,
    val allocationsByRetailerIndex: Map<Int, Int>,
    val appliedRetailerIndices: Set<Int> = emptySet(),
    val distributedMlSoFar: Int = 0
) {
    val remainingMl: Int
        get() = (totalExtraVolumeMl - distributedMlSoFar).coerceAtLeast(0)
}

data class MilkTableRowData(
    val sku: MilkSku,
    val rate: String,
    val mQty: Int,
    val eQty: Int,
    val total: String
)

data class QuantityEditInstruction(
    val sku: MilkSku,
    val oldQty: Int,
    val newQty: Int,
    val reason: String
)

data class BotRuntimeSnapshot(
    val state: BotState = BotState.IDLE,
    val fastMode: Boolean = false,
    val successCount: Int = 0,
    val skippedCount: Int = 0,
    val errorCount: Int = 0,
    val processedCount: Int = 0,
    val expectedRetailers: Int = 125,
    val currentRetailerId: String? = null,
    val currentRetailerName: String? = null,
    val statusMessage: String = "Open Customers and tap ▶ START",
    val alarmActive: Boolean = false
)
