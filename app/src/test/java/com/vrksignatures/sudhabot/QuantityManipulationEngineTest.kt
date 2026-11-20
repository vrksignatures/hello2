package com.vrksignatures.sudhabot

import com.vrksignatures.sudhabot.engine.QuantityManipulationEngine
import com.vrksignatures.sudhabot.model.CustomerDemandRule
import com.vrksignatures.sudhabot.model.DemandRuleType
import com.vrksignatures.sudhabot.model.EmployeeChanceConfig
import com.vrksignatures.sudhabot.model.MilkFamily
import com.vrksignatures.sudhabot.model.MilkSku
import com.vrksignatures.sudhabot.model.MilkTableRowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityManipulationEngineTest {

    @Test
    fun employeeChanceScattersOrganicallyAndSumsToExactRequestedVolume() {
        val config = EmployeeChanceConfig(
            enabled = true,
            totalExtraVolumeMl = 10_000,
            targetRetailerCount = 15,
            eligibleFamilies = setOf(MilkFamily.COW.name, MilkFamily.SM.name)
        )

        val plan = QuantityManipulationEngine.generateEmployeeChancePlan(
            planDate = "2026-11-20",
            config = config,
            expectedRetailers = 125
        )

        assertNotNull(plan)
        plan!!
        assertEquals(10_000, plan.totalExtraVolumeMl)
        assertEquals(15, plan.allocationsByRetailerIndex.size)
        assertEquals(10_000, plan.allocationsByRetailerIndex.values.sum())

        // Anti-bot guarantee: must NOT be consecutive 1..15
        val sortedKeys = plan.allocationsByRetailerIndex.keys.sorted()
        assertNotEquals((1..15).toList(), sortedKeys)
        assertTrue(sortedKeys.last() > 30)
    }

    @Test
    fun customerDemandVolumeDeltaUsesExistingCow500PackMath() {
        val rows = listOf(
            MilkTableRowData(MilkSku.COW_500, "26", mQty = 4, eQty = 0, total = "104"),
            MilkTableRowData(MilkSku.COW_1000, "52", mQty = 0, eQty = 0, total = "0")
        )
        val rule = CustomerDemandRule(
            id = "1",
            customerId = "SMR2246",
            ruleType = DemandRuleType.VOLUME_DELTA_ML,
            familyName = MilkFamily.COW.name,
            value = 1000
        )

        val (edits, _) = QuantityManipulationEngine.computeEditsForRetailer(
            customerId = "SMR2246",
            genuineRetailerIndex = 10,
            tableRows = rows,
            demandRules = listOf(rule),
            employeeChanceConfig = EmployeeChanceConfig(enabled = false),
            employeeChancePlan = null
        )

        assertEquals(1, edits.size)
        assertEquals(MilkSku.COW_500, edits[0].sku)
        assertEquals(6, edits[0].newQty) // +1000 ml -> +2 x 500 ml packs
    }
}
