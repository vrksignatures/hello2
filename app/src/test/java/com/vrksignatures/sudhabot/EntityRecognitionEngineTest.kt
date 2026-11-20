package com.vrksignatures.sudhabot

import com.vrksignatures.sudhabot.engine.DatePickerEngine
import com.vrksignatures.sudhabot.engine.EntityRecognitionEngine
import com.vrksignatures.sudhabot.model.CardType
import com.vrksignatures.sudhabot.model.CustomerCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EntityRecognitionEngineTest {

    @Test
    fun excludesDistAndAdjacentSameNameRetailerBelow_startsAtTrueRetailer1() {
        val cards = listOf(
            CustomerCard("SM65", "RANJANA YADVENDU", CardType.DISTRIBUTOR),
            CustomerCard("65", "RANJANA YADVENDU", CardType.RETAILER),
            CustomerCard("SMR2255", "MUSHKAN KIRANA", CardType.RETAILER),
            CustomerCard("SMR2254", "OM KIRANA", CardType.RETAILER)
        )

        val result = EntityRecognitionEngine.classifyOrderedCards(cards)

        assertTrue("SM65" in result.blacklistIds)
        assertTrue("65" in result.blacklistIds)
        assertFalse("SMR2255" in result.blacklistIds)
        assertEquals(listOf("SMR2255", "SMR2254"), result.genuineRetailers.map { it.customerId })
    }

    @Test
    fun excludesAdjacentSameNameRetailerAboveDist() {
        val cards = listOf(
            CustomerCard("41", "TUNTUN KUMAR", CardType.RETAILER),
            CustomerCard("SM41", "TUNTUN KUMAR", CardType.DISTRIBUTOR),
            CustomerCard("ABC99", "SHREE GANESH MILK", CardType.RETAILER)
        )

        val result = EntityRecognitionEngine.classifyOrderedCards(cards)
        assertTrue("41" in result.blacklistIds)
        assertTrue("SM41" in result.blacklistIds)
        assertEquals(listOf("ABC99"), result.genuineRetailers.map { it.customerId })
    }

    @Test
    fun edgeCaseA_sameNameRetailerElsewhereNotAdjacentIsNotSkipped() {
        val cards = listOf(
            CustomerCard("SM41", "TUNTUN KUMAR", CardType.DISTRIBUTOR),
            CustomerCard("41", "TUNTUN KUMAR", CardType.RETAILER),
            CustomerCard("M23", "OM KIRANA", CardType.RETAILER),
            CustomerCard("900", "TUNTUN KUMAR", CardType.RETAILER) // Non-adjacent genuine retailer!
        )

        val result = EntityRecognitionEngine.classifyOrderedCards(cards)
        assertTrue("41" in result.blacklistIds)
        assertFalse("900" in result.blacklistIds)
        assertEquals(listOf("M23", "900"), result.genuineRetailers.map { it.customerId })
    }

    @Test
    fun edgeCaseB_zeroDistributorListStartsImmediatelyAtCard1() {
        val cards = listOf(
            CustomerCard("101", "ALPHA DAIRY", CardType.RETAILER),
            CustomerCard("102", "BETA KIRANA", CardType.RETAILER)
        )

        val result = EntityRecognitionEngine.classifyOrderedCards(cards)
        assertTrue(result.distributors.isEmpty())
        assertTrue(result.blacklistIds.isEmpty())
        assertEquals(listOf("101", "102"), result.genuineRetailers.map { it.customerId })
    }

    @Test
    fun datePickerHeaderVerificationMatchesExactTargetDate() {
        val target = LocalDate.of(2026, 11, 19)
        assertTrue(DatePickerEngine.headerMatchesTargetDate("Thu, 19 Nov", target))
        assertTrue(DatePickerEngine.headerMatchesTargetDate("Thu, Nov 19", target))
        assertFalse(DatePickerEngine.headerMatchesTargetDate("Fri, 20 Nov", target))
        assertFalse(DatePickerEngine.headerMatchesTargetDate("Mon, 19 Oct", target))
    }
}
