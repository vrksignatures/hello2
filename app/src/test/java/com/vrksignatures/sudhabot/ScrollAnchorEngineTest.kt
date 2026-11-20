package com.vrksignatures.sudhabot

import com.vrksignatures.sudhabot.engine.ScrollAnchorEngine
import com.vrksignatures.sudhabot.model.CardType
import com.vrksignatures.sudhabot.model.CustomerCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAnchorEngineTest {

    @Test
    fun anchorRecoveryFindsShiftedRetailerUp2PagesAndAuditsScrollCount() = runTest {
        // Simulate 10 pages; target SMR2248 was saved at tempScroll=7, but live list shifted UP to page 5
        val pages = (0..10).map { pageIdx ->
            listOf(
                CustomerCard("ID_${pageIdx}_A", "SHOP ${pageIdx}A", CardType.RETAILER),
                CustomerCard(
                    if (pageIdx == 5) "SMR2248" else "ID_${pageIdx}_B",
                    if (pageIdx == 5) "SAHDEV RAI" else "SHOP ${pageIdx}B",
                    CardType.RETAILER
                )
            )
        }

        var currentPage = 0
        val fakeNav = object : ScrollAnchorEngine.ScrollNavigator {
            override suspend fun readVisibleCards(): List<CustomerCard> = pages[currentPage]
            override suspend fun scrollDownOnePage(): Boolean {
                if (currentPage >= pages.lastIndex) return false
                currentPage++
                return true
            }
            override suspend fun scrollUpOnePage(): Boolean {
                if (currentPage <= 0) return false
                currentPage--
                return true
            }
        }

        val result = ScrollAnchorEngine.jumpAndRecoverAnchor(
            navigator = fakeNav,
            permDistributorScrolls = 0,
            expectedTempScrollCount = 7,
            targetCustomerId = "SMR2248",
            targetRetailerName = "SAHDEV RAI"
        )

        assertTrue(result.found)
        assertEquals("SMR2248", result.anchorCard?.customerId)
        assertEquals(-2, result.delta)
        assertEquals(5, result.auditedTempScrollCount)
    }
}
