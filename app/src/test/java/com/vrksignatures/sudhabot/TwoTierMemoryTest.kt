package com.vrksignatures.sudhabot

import com.vrksignatures.sudhabot.memory.DailyLedgerMemory
import com.vrksignatures.sudhabot.memory.KeyValueStore
import com.vrksignatures.sudhabot.memory.PermanentDistributorMemory
import com.vrksignatures.sudhabot.model.DistributorRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, Any?>()
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
    override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String): Set<String> = (map[key] as? Set<String>)?.toSet() ?: emptySet()
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putString(key: String, value: String?) { map[key] = value }
    override fun putStringSet(key: String, value: Set<String>) { map[key] = value.toSet() }
    override fun clearAll() { map.clear() }
}

class TwoTierMemoryTest {

    @Test
    fun midnightRolloverResetsOnlyTier2AndPreservesTier1PermanentBlueprint() {
        var fakeDate = LocalDate.of(2026, 11, 20)
        val tier1 = PermanentDistributorMemory(InMemoryKeyValueStore())
        val tier2 = DailyLedgerMemory(InMemoryKeyValueStore()) { fakeDate }

        // Learn Tier 1 Permanent Blueprint
        tier1.saveBlueprint(
            distributors = listOf(DistributorRecord("RANJANA YADVENDU", "SM65")),
            blacklistIds = setOf("SM65", "65"),
            blacklistSignatures = setOf("ranjana yadvendu|dist=true|adj=false"),
            permDistributorScrolls = 0
        )

        // Record Tier 2 Daily Progress on 2026-11-20
        tier2.checkAndResetAtMidnight()
        tier2.recordRetailerSuccess("SMR2255", "MUSHKAN KIRANA", 4)
        assertEquals("SMR2255", tier2.lastHandledCustomerId)
        assertEquals(4, tier2.tempRetailerScrollCount)
        assertEquals(1, tier2.successCountToday)

        // Same day check -> NO reset
        assertFalse(tier2.checkAndResetAtMidnight())
        assertEquals("SMR2255", tier2.lastHandledCustomerId)

        // Advance calendar date past 00:00 midnight to 2026-11-21
        fakeDate = LocalDate.of(2026, 11, 21)
        assertTrue(tier2.checkAndResetAtMidnight())

        // Tier 2 is wiped clean
        assertNull(tier2.lastHandledCustomerId)
        assertEquals(0, tier2.tempRetailerScrollCount)
        assertEquals(0, tier2.successCountToday)
        assertTrue(tier2.processedIdsToday.isEmpty())
        assertEquals("2026-11-21", tier2.savedCalendarDate)

        // Tier 1 is 100% intact!
        assertTrue(tier1.blueprintLearned)
        assertTrue("SM65" in tier1.permanentBlacklistIds)
        assertTrue("65" in tier1.permanentBlacklistIds)

        // Explicit Reset Distributor History clears Tier 1
        tier1.resetDistributorHistory()
        assertFalse(tier1.blueprintLearned)
        assertTrue(tier1.permanentBlacklistIds.isEmpty())
    }
}
