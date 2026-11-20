package com.vrksignatures.sudhabot.engine

import com.vrksignatures.sudhabot.memory.PermanentDistributorMemory
import com.vrksignatures.sudhabot.model.CardType
import com.vrksignatures.sudhabot.model.CustomerCard
import com.vrksignatures.sudhabot.model.DistributorRecord

/**
 * SECTION 5: Zero-Hardcoding Distributor vs. Retailer Identification Engine.
 *
 * Strictly uses UI structural labels ("Delivery Point (Dist)" vs "Delivery Point (Retailer)")
 * and immediate vertical adjacency rules — NEVER checks "SM" or "SMR" ID prefixes.
 */
object EntityRecognitionEngine {

    data class ClassificationResult(
        val distributors: List<DistributorRecord>,
        val distributorOwnedRetailerIds: Set<String>,
        val blacklistIds: Set<String>,
        val blacklistSignatures: Set<String>,
        val genuineRetailers: List<CustomerCard>
    )

    /**
     * Classifies an ordered list of visible (or accumulated top-of-list) cards.
     *
     * Rules:
     * 1. Every Delivery Point (Dist) card is ALWAYS excluded.
     * 2. Whenever a (Dist) card is encountered, inspect BOTH index - 1 (immediately above)
     *    and index + 1 (immediately below). If an adjacent Delivery Point (Retailer) card
     *    shares the exact same normalized Bold Orange Name, classify it as a
     *    Distributor-Owned Retailer ID and exclude/boycott it.
     * 3. Edge Case A: A Delivery Point (Retailer) card with the SAME name appearing LATER
     *    (NOT immediately above or below a (Dist) card) is NOT excluded — it is genuine.
     * 4. Edge Case B: If zero (Dist) cards appear, every Delivery Point (Retailer) card
     *    is genuine starting immediately at index 0.
     */
    fun classifyOrderedCards(cards: List<CustomerCard>): ClassificationResult {
        val distributors = mutableListOf<DistributorRecord>()
        val distributorOwnedRetailerIds = mutableSetOf<String>()
        val blacklistIds = mutableSetOf<String>()
        val blacklistSignatures = mutableSetOf<String>()
        val excludedIndices = mutableSetOf<Int>()

        for (i in cards.indices) {
            val card = cards[i]
            if (card.cardType == CardType.DISTRIBUTOR) {
                excludedIndices.add(i)
                val normalizedId = card.customerId.trim().uppercase()
                if (normalizedId.isNotEmpty()) {
                    blacklistIds.add(normalizedId)
                }
                distributors.add(DistributorRecord(card.boldOrangeName.trim(), normalizedId))
                blacklistSignatures.add(
                    PermanentDistributorMemory.buildSignature(
                        card.normalizedName,
                        isDist = true,
                        adjacentToDist = false
                    )
                )

                // Check immediately ABOVE (i - 1) and immediately BELOW (i + 1)
                val adjacentIndices = listOf(i - 1, i + 1)
                for (adjIdx in adjacentIndices) {
                    if (adjIdx in cards.indices) {
                        val adjCard = cards[adjIdx]
                        if (adjCard.cardType == CardType.RETAILER &&
                            adjCard.normalizedName.isNotEmpty() &&
                            adjCard.normalizedName == card.normalizedName
                        ) {
                            excludedIndices.add(adjIdx)
                            val adjId = adjCard.customerId.trim().uppercase()
                            if (adjId.isNotEmpty()) {
                                distributorOwnedRetailerIds.add(adjId)
                                blacklistIds.add(adjId)
                            }
                            blacklistSignatures.add(
                                PermanentDistributorMemory.buildSignature(
                                    adjCard.normalizedName,
                                    isDist = false,
                                    adjacentToDist = true
                                )
                            )
                        }
                    }
                }
            }
        }

        val genuineRetailers = cards.filterIndexed { idx, card ->
            idx !in excludedIndices && card.cardType == CardType.RETAILER
        }

        return ClassificationResult(
            distributors = distributors.distinctBy { "${it.customerId}|${it.name.lowercase()}" },
            distributorOwnedRetailerIds = distributorOwnedRetailerIds,
            blacklistIds = blacklistIds,
            blacklistSignatures = blacklistSignatures,
            genuineRetailers = genuineRetailers
        )
    }

    /**
     * Picks the next eligible genuine retailer on the currently visible page during normal execution.
     * Also enforces the live passive guard against any unexpected (Dist) card or adjacent same-name pair.
     */
    fun findNextEligibleRetailer(
        visibleCards: List<CustomerCard>,
        permanentBlacklistIds: Set<String>,
        processedIdsToday: Set<String>,
        afterCustomerId: String? = null
    ): CustomerCard? {
        val liveClassification = classifyOrderedCards(visibleCards)
        val liveExcludedIds = liveClassification.blacklistIds

        val normalizedPermBlacklist = permanentBlacklistIds.map { it.trim().uppercase() }.toSet()
        val normalizedProcessed = processedIdsToday.map { it.trim().uppercase() }.toSet()
        val normalizedAfter = afterCustomerId?.trim()?.uppercase()

        var passedAnchor = (normalizedAfter == null)

        for (card in visibleCards) {
            val id = card.customerId.trim().uppercase()
            if (!passedAnchor) {
                if (id == normalizedAfter) {
                    passedAnchor = true
                }
                continue
            }
            if (card.cardType != CardType.RETAILER) continue
            if (id in normalizedPermBlacklist) continue
            if (id in liveExcludedIds) continue
            if (id in normalizedProcessed) continue
            return card
        }
        return null
    }
}
