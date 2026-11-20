package com.vrksignatures.sudhabot.engine

import com.vrksignatures.sudhabot.model.CustomerCard

/**
 * SECTION 7: Smart Scroll Jump, Anchor Recovery & Scroll Count Audit Engine.
 *
 * Pure deterministic navigation & audit algorithm decoupled from Android Accessibility gestures
 * via an interface so it can be thoroughly unit-tested on JVM and executed reliably on device.
 */
object ScrollAnchorEngine {

    interface ScrollNavigator {
        suspend fun readVisibleCards(): List<CustomerCard>
        suspend fun scrollDownOnePage(): Boolean
        suspend fun scrollUpOnePage(): Boolean
    }

    data class ResumeAnchorResult(
        val found: Boolean,
        val anchorCard: CustomerCard?,
        val expectedTempScrollCount: Int,
        val auditedTempScrollCount: Int,
        val delta: Int,
        val auditMessage: String
    )

    /**
     * Computes exact total scrolls to jump from top of expanded retailer list.
     */
    fun computeTotalJumpScrolls(permDistributorScrolls: Int, tempRetailerScrollCount: Int): Int =
        permDistributorScrolls.coerceAtLeast(0) + tempRetailerScrollCount.coerceAtLeast(0)

    /**
     * Computes audited scroll count after anchor recovery finds the target at signed offset `delta`.
     */
    fun computeAuditedScrollCount(expectedTempScrollCount: Int, delta: Int): Int =
        (expectedTempScrollCount + delta).coerceAtLeast(0)

    /**
     * Executes:
     * 1. Direct Scroll Jump (`perm + temp` uniform scrolls down).
     * 2. Anchor Page Verification (match by `targetCustomerId`, fallback `targetRetailerName`).
     * 3. Expanding UP/DOWN Anchor Recovery (1 UP, 1 DOWN, 2 UP, 2 DOWN, ...) if list shifted.
     * 4. Mandatory Scroll Count Audit returning recalibrated `auditedTempScrollCount`.
     */
    suspend fun jumpAndRecoverAnchor(
        navigator: ScrollNavigator,
        permDistributorScrolls: Int,
        expectedTempScrollCount: Int,
        targetCustomerId: String,
        targetRetailerName: String?,
        maxSearchRadius: Int = 15
    ): ResumeAnchorResult {
        val totalJump = computeTotalJumpScrolls(permDistributorScrolls, expectedTempScrollCount)
        var actualDepthFromTop = 0

        // Step 1: Direct Scroll Jump
        repeat(totalJump) {
            if (navigator.scrollDownOnePage()) {
                actualDepthFromTop++
            }
        }

        // Step 2: Anchor Page Verification (delta = 0)
        val anchorCards = navigator.readVisibleCards()
        findMatchingCard(anchorCards, targetCustomerId, targetRetailerName)?.let { match ->
            val audited = (actualDepthFromTop - permDistributorScrolls).coerceAtLeast(0)
            val delta = audited - expectedTempScrollCount
            return ResumeAnchorResult(
                found = true,
                anchorCard = match,
                expectedTempScrollCount = expectedTempScrollCount,
                auditedTempScrollCount = audited,
                delta = delta,
                auditMessage = "Scroll audit: Anchor $targetCustomerId verified on expected page #$audited (delta $delta)"
            )
        }

        // Step 3: Alternating UP / DOWN Expanding Anchor Recovery Search
        // Track exact signed offset relative to the Anchor Page we landed on.
        var currentOffset = 0
        var hitTop = false
        var hitBottom = false

        for (radius in 1..maxSearchRadius) {
            // Try -radius (UP from Anchor Page)
            if (!hitTop) {
                val targetOffset = -radius
                val moved = moveToOffset(navigator, currentOffset, targetOffset)
                currentOffset = moved.newOffset
                if (moved.hitBoundary) hitTop = true

                findMatchingCard(navigator.readVisibleCards(), targetCustomerId, targetRetailerName)?.let { match ->
                    val audited = computeAuditedScrollCount(expectedTempScrollCount, currentOffset)
                    return ResumeAnchorResult(
                        found = true,
                        anchorCard = match,
                        expectedTempScrollCount = expectedTempScrollCount,
                        auditedTempScrollCount = audited,
                        delta = currentOffset,
                        auditMessage = "Scroll audit: expected #$expectedTempScrollCount, found at #$audited (delta $currentOffset); anchor updated"
                    )
                }
            }

            // Try +radius (DOWN from Anchor Page)
            if (!hitBottom) {
                val targetOffset = +radius
                val moved = moveToOffset(navigator, currentOffset, targetOffset)
                currentOffset = moved.newOffset
                if (moved.hitBoundary) hitBottom = true

                findMatchingCard(navigator.readVisibleCards(), targetCustomerId, targetRetailerName)?.let { match ->
                    val audited = computeAuditedScrollCount(expectedTempScrollCount, currentOffset)
                    return ResumeAnchorResult(
                        found = true,
                        anchorCard = match,
                        expectedTempScrollCount = expectedTempScrollCount,
                        auditedTempScrollCount = audited,
                        delta = currentOffset,
                        auditMessage = "Scroll audit: expected #$expectedTempScrollCount, found at #$audited (delta +$currentOffset); anchor updated"
                    )
                }
            }

            if (hitTop && hitBottom) break
        }

        return ResumeAnchorResult(
            found = false,
            anchorCard = null,
            expectedTempScrollCount = expectedTempScrollCount,
            auditedTempScrollCount = expectedTempScrollCount,
            delta = 0,
            auditMessage = "Scroll audit: Anchor $targetCustomerId NOT found within radius ±$maxSearchRadius"
        )
    }

    private data class MoveResult(val newOffset: Int, val hitBoundary: Boolean)

    private suspend fun moveToOffset(
        navigator: ScrollNavigator,
        currentOffset: Int,
        targetOffset: Int
    ): MoveResult {
        var offset = currentOffset
        var hitBoundary = false
        while (offset < targetOffset) {
            if (navigator.scrollDownOnePage()) {
                offset++
            } else {
                hitBoundary = true
                break
            }
        }
        while (offset > targetOffset) {
            if (navigator.scrollUpOnePage()) {
                offset--
            } else {
                hitBoundary = true
                break
            }
        }
        return MoveResult(offset, hitBoundary)
    }

    fun findMatchingCard(
        cards: List<CustomerCard>,
        targetCustomerId: String,
        targetRetailerName: String?
    ): CustomerCard? {
        val normId = targetCustomerId.trim().uppercase()
        cards.firstOrNull { it.customerId.trim().uppercase() == normId }?.let { return it }

        val normName = targetRetailerName?.let { CustomerCard.normalizeName(it) }
        if (!normName.isNullOrEmpty()) {
            cards.firstOrNull { it.normalizedName == normName }?.let { return it }
        }
        return null
    }
}
