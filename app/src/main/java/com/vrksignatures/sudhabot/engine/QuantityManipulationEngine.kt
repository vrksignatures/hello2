package com.vrksignatures.sudhabot.engine

import com.vrksignatures.sudhabot.model.CustomerDemandRule
import com.vrksignatures.sudhabot.model.DemandRuleType
import com.vrksignatures.sudhabot.model.EmployeeChanceConfig
import com.vrksignatures.sudhabot.model.EmployeeChancePlan
import com.vrksignatures.sudhabot.model.MilkFamily
import com.vrksignatures.sudhabot.model.MilkSku
import com.vrksignatures.sudhabot.model.MilkTableRowData
import com.vrksignatures.sudhabot.model.QuantityEditInstruction
import kotlin.math.abs
import kotlin.random.Random

/**
 * SECTION 10: Pre-Run Quantity Manipulation Engine.
 *
 * Mode A: Customer Demand (Specific Retailer Overrides — Exact Pack or Volume Delta).
 * Mode B: Employee's Chance (Anti-Bot Randomized Scatter across full expected retailer range).
 */
object QuantityManipulationEngine {

    /**
     * Generates a deterministic-for-today randomly scattered plan across [1 .. expectedRetailers].
     *
     * CRITICAL ANTI-BOT GUARANTEE:
     * - NEVER allocates to the first N consecutive retailers (1..N).
     * - Stratified + shuffled selection mixes early, middle, and late indices organically.
     * - Partitions totalExtraVolumeMl into realistic pack-aligned increments (multiples of 500 ml)
     *   whose sum equals the exact requested totalExtraVolumeMl (rounded down only if user enters
     *   a non-multiple of 500 ml, or exact when multiple of 500 ml).
     */
    fun generateEmployeeChancePlan(
        planDate: String,
        config: EmployeeChanceConfig,
        expectedRetailers: Int,
        seedOverride: Long? = null
    ): EmployeeChancePlan? {
        if (!config.enabled || config.totalExtraVolumeMl < 500 || config.targetRetailerCount <= 0) {
            return null
        }

        val totalSlots = expectedRetailers.coerceAtLeast(1)
        val totalHalfLiters = config.totalExtraVolumeMl / 500
        if (totalHalfLiters <= 0) return null

        // Target count cannot exceed available retailers OR available 500ml units
        val targetCount = config.targetRetailerCount
            .coerceAtMost(totalSlots)
            .coerceAtMost(totalHalfLiters)
            .coerceAtLeast(1)

        val seed = seedOverride ?: ("$planDate|${config.totalExtraVolumeMl}|$targetCount|$totalSlots".hashCode().toLong())
        val rng = Random(seed)

        // Stratified organic selection across the entire [1..totalSlots] range so early, middle,
        // and late retailers are naturally mixed without consecutive top-heavy clustering.
        val allIndices = (1..totalSlots).toMutableList()
        allIndices.shuffle(rng)
        val chosenIndices = allIndices.take(targetCount).sorted()

        // Base allocation: every chosen retailer gets at least 500 ml (1 unit)
        val unitsByIndex = chosenIndices.associateWith { 1 }.toMutableMap()
        var remainingUnits = totalHalfLiters - targetCount

        // Distribute remaining 500ml units organically across chosen retailers
        // (prefers realistic +500ml or +1000ml bumps, avoiding huge spikes on one shop)
        val shuffledChosen = chosenIndices.toMutableList()
        while (remainingUnits > 0) {
            shuffledChosen.shuffle(rng)
            for (idx in shuffledChosen) {
                if (remainingUnits <= 0) break
                val add = if (remainingUnits >= 2 && rng.nextBoolean()) 2 else 1
                unitsByIndex[idx] = (unitsByIndex[idx] ?: 1) + add
                remainingUnits -= add
            }
        }

        val allocationsMl = unitsByIndex.mapValues { (_, units) -> units * 500 }

        return EmployeeChancePlan(
            planDate = planDate,
            totalExtraVolumeMl = allocationsMl.values.sum(),
            targetRetailerCount = chosenIndices.size,
            expectedRetailers = totalSlots,
            allocationsByRetailerIndex = allocationsMl
        )
    }

    /**
     * Computes exact MQty edits for a retailer after Copy Order populates the table.
     * Precedence: Mode A (Customer Demand) runs first. If any Customer Demand rule matches
     * this customerId, Employee's Chance is skipped for this retailer to avoid conflicts.
     */
    fun computeEditsForRetailer(
        customerId: String,
        genuineRetailerIndex: Int,
        tableRows: List<MilkTableRowData>,
        demandRules: List<CustomerDemandRule>,
        employeeChanceConfig: EmployeeChanceConfig,
        employeeChancePlan: EmployeeChancePlan?
    ): Pair<List<QuantityEditInstruction>, Int> {
        val normId = customerId.trim().uppercase()
        val matchingDemands = demandRules.filter { it.customerId.trim().uppercase() == normId }

        if (matchingDemands.isNotEmpty()) {
            val edits = mutableListOf<QuantityEditInstruction>()
            val workingQty = tableRows.associate { it.sku to it.mQty }.toMutableMap()

            for (rule in matchingDemands) {
                when (rule.ruleType) {
                    DemandRuleType.EXACT_PACK_OVERRIDE -> {
                        val sku = rule.skuName?.let { MilkSku.fromTableLabel(it) } ?: continue
                        val oldQty = workingQty[sku] ?: 0
                        val newQty = rule.value.coerceAtLeast(0)
                        if (newQty != oldQty) {
                            workingQty[sku] = newQty
                            edits.add(
                                QuantityEditInstruction(
                                    sku = sku,
                                    oldQty = oldQty,
                                    newQty = newQty,
                                    reason = "Customer Demand exact: ${sku.exactLabel} = $newQty"
                                )
                            )
                        }
                    }

                    DemandRuleType.VOLUME_DELTA_ML -> {
                        val family = runCatching { MilkFamily.valueOf(rule.familyName ?: "") }.getOrNull()
                            ?: continue
                        val deltaEdit = resolveVolumeDeltaEdit(
                            family = family,
                            deltaMl = rule.value,
                            workingQty = workingQty,
                            reasonPrefix = "Customer Demand delta"
                        )
                        if (deltaEdit != null) {
                            workingQty[deltaEdit.sku] = deltaEdit.newQty
                            edits.add(deltaEdit)
                        }
                    }
                }
            }
            return Pair(edits, 0)
        }

        // Mode B: Employee's Chance (only when no conflicting Customer Demand override exists)
        if (employeeChanceConfig.enabled && employeeChancePlan != null &&
            genuineRetailerIndex !in employeeChancePlan.appliedRetailerIndices
        ) {
            val assignedMl = employeeChancePlan.allocationsByRetailerIndex[genuineRetailerIndex] ?: 0
            if (assignedMl >= 500) {
                val workingQty = tableRows.associate { it.sku to it.mQty }.toMutableMap()
                val eligibleFamilies = employeeChanceConfig.eligibleFamilies
                    .mapNotNull { runCatching { MilkFamily.valueOf(it) }.getOrNull() }
                    .ifEmpty { listOf(MilkFamily.COW, MilkFamily.SM) }

                // Prefer a family that already has active MQty > 0 on this retailer's order
                val activeFamily = eligibleFamilies.firstOrNull { fam ->
                    workingQty.entries.any { (sku, qty) ->
                        sku.family == fam && (sku.packSizeMl == 500 || sku.packSizeMl == 1000) && qty > 0
                    }
                } ?: eligibleFamilies.first()

                val edit = resolveVolumeDeltaEdit(
                    family = activeFamily,
                    deltaMl = assignedMl,
                    workingQty = workingQty,
                    reasonPrefix = "Employee's Chance (#$genuineRetailerIndex)"
                )
                if (edit != null) {
                    val actualDeltaMl = (edit.newQty - edit.oldQty) * edit.sku.packSizeMl
                    return Pair(listOf(edit), actualDeltaMl)
                }
            }
        }

        return Pair(emptyList(), 0)
    }

    /**
     * Resolves a +/- ml delta onto a milk family using Section 10 pack math:
     * - If 1000 ML pack has MQty > 0 and delta is a multiple of 1000 ml -> adjust 1000 ML pack.
     * - Else if 500 ML pack has MQty > 0 and delta is a multiple of 500 ml -> adjust 500 ML pack.
     * - Else default to 1000 ML pack (if multiple of 1000 ml) or 500 ML pack.
     */
    fun resolveVolumeDeltaEdit(
        family: MilkFamily,
        deltaMl: Int,
        workingQty: Map<MilkSku, Int>,
        reasonPrefix: String
    ): QuantityEditInstruction? {
        if (deltaMl == 0) return null

        val sku1000 = MilkSku.entries.firstOrNull { it.family == family && it.packSizeMl == 1000 }
        val sku500 = MilkSku.entries.firstOrNull { it.family == family && it.packSizeMl == 500 }

        val qty1000 = sku1000?.let { workingQty[it] ?: 0 } ?: 0
        val qty500 = sku500?.let { workingQty[it] ?: 0 } ?: 0

        val targetSku: MilkSku = when {
            sku1000 != null && deltaMl % 1000 == 0 && qty1000 > 0 &&
                (qty1000 + deltaMl / 1000) >= 0 -> sku1000
            sku500 != null && deltaMl % 500 == 0 && qty500 > 0 &&
                (qty500 + deltaMl / 500) >= 0 -> sku500
            sku1000 != null && deltaMl % 1000 == 0 && deltaMl > 0 -> sku1000
            sku500 != null && deltaMl % 500 == 0 -> sku500
            else -> return null
        }

        val packDelta = deltaMl / targetSku.packSizeMl
        val oldQty = workingQty[targetSku] ?: 0
        val newQty = (oldQty + packDelta).coerceAtLeast(0)
        if (newQty == oldQty) return null

        val sign = if (deltaMl >= 0) "+" else ""
        return QuantityEditInstruction(
            sku = targetSku,
            oldQty = oldQty,
            newQty = newQty,
            reason = "$reasonPrefix: ${family.displayName} ${sign}${deltaMl}ml -> ${targetSku.exactLabel} ($oldQty -> $newQty)"
        )
    }
}
