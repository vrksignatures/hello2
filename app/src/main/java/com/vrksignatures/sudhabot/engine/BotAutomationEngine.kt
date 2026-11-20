package com.vrksignatures.sudhabot.engine

import com.vrksignatures.sudhabot.memory.BotPreferences
import com.vrksignatures.sudhabot.memory.DailyLedgerMemory
import com.vrksignatures.sudhabot.memory.DiagnosticLogStore
import com.vrksignatures.sudhabot.memory.PermanentDistributorMemory
import com.vrksignatures.sudhabot.model.BotRuntimeSnapshot
import com.vrksignatures.sudhabot.model.BotState
import com.vrksignatures.sudhabot.model.CardType
import com.vrksignatures.sudhabot.model.CustomerCard
import com.vrksignatures.sudhabot.model.PaymentMode
import com.vrksignatures.sudhabot.service.SudhaAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * MASTER STATE MACHINE ENGINE — SudhaAutoBot v2.0
 *
 * Implements Sections 4–10 end-to-end:
 * - Strict midnight (00:00) Tier 2 reset on every ▶ START (never touches Tier 1).
 * - Direct Customers screen detection (Bug 5).
 * - Filter dropdown "All Distributors" check + warning log (Section 5 Rule 2).
 * - Single-tap Show Retailers expansion (Bug 1).
 * - Tier 1 Learning Mode vs Fast Execution Boycott Mode (Section 6).
 * - Manual "Last handled customer ID" override + Smart Scroll Jump + UP/DOWN Anchor Recovery + Audit (Section 7).
 * - Complete order & payment loop with T-1..T-5 verified DatePicker (Bug 2), Dialog-First Guard (Bug 3),
 *   waitUntilIdle() barrier (Bug 4), Customer Demand & Employee's Chance (Section 10),
 *   and 5-retry High-Volume Alarm fail-safe (Section 9).
 */
class BotAutomationEngine(
    private val service: SudhaAccessibilityService,
    private val prefs: BotPreferences,
    private val tier1Memory: PermanentDistributorMemory,
    private val tier2Memory: DailyLedgerMemory,
    private val logs: DiagnosticLogStore,
    private val alarmController: AlarmController,
    private val onSnapshotChanged: (BotRuntimeSnapshot) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inspector = ScreenInspector(service)
    private val datePicker = DatePickerEngine(service, inspector, logs)

    private var runJob: Job? = null
    private var currentState: BotState = BotState.IDLE
    private var currentRetailerId: String? = null
    private var currentRetailerName: String? = null
    private var currentStatus: String = "Open Customers and tap ▶ START"

    // Track latest toast reported by AccessibilityService
    @Volatile
    private var lastToastText: String = ""
    @Volatile
    private var lastToastTimestamp: Long = 0L

    private var currentTempScrollDepth: Int = 0
    private var genuineRetailerIndexCounter: Int = 0

    fun onToastObserved(text: String) {
        lastToastText = text
        lastToastTimestamp = System.currentTimeMillis()
        if (text.contains("Failed to connect", true) ||
            text.contains("Internal error", true)
        ) {
            logs.warn("Toast", "Observed target app toast: $text")
        }
    }

    fun getSnapshot(): BotRuntimeSnapshot {
        return BotRuntimeSnapshot(
            state = currentState,
            fastMode = prefs.fastMode,
            successCount = tier2Memory.successCountToday,
            skippedCount = tier2Memory.skippedCountToday,
            errorCount = tier2Memory.errorCountToday,
            processedCount = tier2Memory.processedIdsToday.size,
            expectedRetailers = prefs.expectedRetailersToday,
            currentRetailerId = currentRetailerId,
            currentRetailerName = currentRetailerName,
            statusMessage = currentStatus,
            alarmActive = alarmController.isRinging
        )
    }

    fun startOrResume() {
        if (currentState == BotState.ALARM_HALTED) {
            stopAlarmAndReturnIdle()
            return
        }
        if (runJob?.isActive == true) return

        // Strict Calendar Midnight (00:00) Auto-Reset Check on EVERY ▶ START
        if (tier2Memory.checkAndResetAtMidnight()) {
            logs.info("MidnightReset", "Calendar date advanced to ${LocalDate.now()}; Tier 2 Daily Memory auto-reset (Tier 1 preserved).")
        }

        // Initialize or load Employee's Chance scattered plan for today
        ensureTodaysEmployeeChancePlan()

        runJob = scope.launch {
            try {
                executeMainAutomationFlow()
            } catch (_: CancellationException) {
                if (currentState != BotState.ALARM_HALTED && currentState != BotState.MANUAL_PAYMENT) {
                    updateState(BotState.PAUSED, "Paused by user")
                }
            } catch (e: Exception) {
                triggerAlarmHalt("Unhandled exception: ${e.message}", "MAIN_LOOP")
            }
        }
    }

    fun pause() {
        runJob?.cancel()
        runJob = null
        if (currentState != BotState.ALARM_HALTED) {
            updateState(BotState.PAUSED, "Paused — safe to resume")
        }
    }

    fun stopAlarmAndReturnIdle() {
        alarmController.stopAlarm()
        runJob?.cancel()
        runJob = null
        updateState(BotState.IDLE, "Alarm stopped. Review screen then tap ▶ START")
    }

    fun resetTodaysLedgerFromOverlay() {
        pause()
        tier2Memory.resetTodaysLedger()
        currentTempScrollDepth = 0
        logs.info("Tier2Reset", "Today's ledger reset from floating panel (Tier 1 Permanent Blueprint untouched).")
        updateState(BotState.IDLE, "Today's ledger cleared")
    }

    private suspend fun executeMainAutomationFlow() {
        val fast = prefs.fastMode
        inspector.waitUntilIdle(fast) { !isRunningJobActive() }

        // BUG 5 FIX: Directly detect Customers screen without ever searching for Home screen
        val screen = inspector.detectScreen()
        if (screen == ScreenInspector.DetectedScreen.PAYMENT_SCREEN && currentState == BotState.MANUAL_PAYMENT) {
            updateState(BotState.RUNNING, "Resuming after manual payment...")
            inspector.safeNavigateBack(fast)
        } else if (screen != ScreenInspector.DetectedScreen.CUSTOMERS_LIST) {
            updateState(BotState.ERROR, "Open Customers screen in Sudha Daily Orders first, then tap ▶ START")
            logs.warn("StartCheck", "Start tapped on non-Customers screen: $screen")
            return
        }

        // SECTION 5 RULE 2: Verify filter dropdown is "All Distributors" before touching Show Retailers
        ensureAllDistributorsFilter(fast)

        // TIER 1 LEARNING vs FAST EXECUTION MODE
        if (!tier1Memory.blueprintLearned) {
            runDistributorLearningMode(fast)
        } else {
            // Fast Execution Mode: expand Show Retailers ONCE (if unchecked)
            ensureShowRetailersExpandedOnce(fast)
        }

        // Determine Starting Position: Manual Override vs Same-Day Smart Scroll Jump vs Retailer #1
        val manualOverride = prefs.manualLastHandledIdOverride
        val savedLastId = tier2Memory.lastHandledCustomerId
        var anchorAfterId: String? = null

        if (!manualOverride.isNullOrBlank()) {
            // Manual Override Rule (e.g., user typed "1142" even after Reset today's ledger)
            updateState(BotState.JUMPING_SCROLL, "Finding manual resume ID: $manualOverride...")
            val foundDepth = scrollToFindManualOverrideId(manualOverride, fast)
            if (foundDepth == null) {
                triggerAlarmHalt("Manual Last handled customer ID '$manualOverride' not found in list", "MANUAL_RESUME")
                return
            }
            val foundCard = inspector.parseVisibleCustomerCards()
                .firstOrNull { it.customerId.equals(manualOverride, ignoreCase = true) }
            val tempDepth = (foundDepth - tier1Memory.permDistributorScrolls).coerceAtLeast(0)
            currentTempScrollDepth = tempDepth
            tier2Memory.markManualOverrideAnchorFound(
                customerId = manualOverride,
                retailerName = foundCard?.boldOrangeName ?: "",
                foundTempScrollCount = tempDepth
            )
            // Clear manual override once anchored so future pauses resume from live Tier 2 ID
            prefs.manualLastHandledIdOverride = null
            anchorAfterId = manualOverride
            logs.audit("ManualOverride", "Anchored on $manualOverride at scroll #$tempDepth; starting from next retailer below.")
        } else if (!savedLastId.isNullOrBlank()) {
            // Same-Day Smart Scroll Jump + Anchor Recovery + Scroll Count Audit
            updateState(BotState.JUMPING_SCROLL, "Jumping to anchor $savedLastId...")
            val navigator = object : ScrollAnchorEngine.ScrollNavigator {
                override suspend fun readVisibleCards(): List<CustomerCard> =
                    inspector.parseVisibleCustomerCards()

                override suspend fun scrollDownOnePage(): Boolean =
                    inspector.scrollCustomersList(down = true, fastMode = fast)

                override suspend fun scrollUpOnePage(): Boolean =
                    inspector.scrollCustomersList(down = false, fastMode = fast)
            }

            val resumeResult = ScrollAnchorEngine.jumpAndRecoverAnchor(
                navigator = navigator,
                permDistributorScrolls = tier1Memory.permDistributorScrolls,
                expectedTempScrollCount = tier2Memory.tempRetailerScrollCount,
                targetCustomerId = savedLastId,
                targetRetailerName = tier2Memory.lastHandledRetailerName
            )
            logs.audit("ScrollAudit", resumeResult.auditMessage)

            if (!resumeResult.found) {
                triggerAlarmHalt(resumeResult.auditMessage, "ANCHOR_RECOVERY")
                return
            }
            currentTempScrollDepth = resumeResult.auditedTempScrollCount
            tier2Memory.updateAuditedTempScrollCount(currentTempScrollDepth)
            anchorAfterId = savedLastId
        } else {
            // Fresh start from True Retailer #1
            currentTempScrollDepth = 0
            val permScrolls = tier1Memory.permDistributorScrolls
            if (permScrolls > 0) {
                updateState(BotState.JUMPING_SCROLL, "Bypassing $permScrolls distributor scroll(s)...")
                repeat(permScrolls) {
                    inspector.scrollCustomersList(down = true, fastMode = fast)
                }
            }
        }

        // MAIN RETAILER PROCESSING LOOP
        updateState(BotState.RUNNING, "Scanning genuine retailers...")
        genuineRetailerIndexCounter = tier2Memory.processedIdsToday.size
        var consecutiveEmptyScrolls = 0

        while (isRunningJobActive()) {
            val visibleCards = inspector.parseVisibleCustomerCards()

            // Passive guard: log diagnostic note if unexpected Dist appears mid-list in Fast Mode
            if (tier1Memory.blueprintLearned && currentTempScrollDepth > 0) {
                val unexpectedDist = visibleCards.firstOrNull {
                    it.cardType == CardType.DISTRIBUTOR && !tier1Memory.isPermanentlyBlacklisted(it)
                }
                if (unexpectedDist != null) {
                    logs.warn(
                        "PassiveGuard",
                        "Unexpected distributor '${unexpectedDist.boldOrangeName}' (${unexpectedDist.customerId}) mid-list. Skipping; consider tapping 'Reset Distributor History'."
                    )
                }
            }

            val nextCard = EntityRecognitionEngine.findNextEligibleRetailer(
                visibleCards = visibleCards,
                permanentBlacklistIds = tier1Memory.permanentBlacklistIds,
                processedIdsToday = tier2Memory.processedIdsToday,
                afterCustomerId = anchorAfterId
            )

            if (nextCard != null) {
                anchorAfterId = null // Anchor consumed once we select the retailer immediately below it
                consecutiveEmptyScrolls = 0
                genuineRetailerIndexCounter++

                val proceed = processSingleRetailer(nextCard, genuineRetailerIndexCounter, fast)
                if (!proceed) return
            } else {
                // Scroll down controlled 68% distance (Bug 6 fix)
                val moved = inspector.scrollCustomersList(down = true, fastMode = fast)
                if (moved) {
                    currentTempScrollDepth++
                    // If anchor was at bottom of previous viewport, clear it after moving down 1 page
                    anchorAfterId = null
                    consecutiveEmptyScrolls = 0
                } else {
                    consecutiveEmptyScrolls++
                    if (consecutiveEmptyScrolls >= 2) {
                        logs.info(
                            "RunComplete",
                            "Reached end of Customers list. Processed=${tier2Memory.processedIdsToday.size}, " +
                                "OK=${tier2Memory.successCountToday}, Skip=${tier2Memory.skippedCountToday}"
                        )
                        updateState(BotState.IDLE, "Completed all visible retailers for today!")
                        return
                    }
                }
            }
        }
    }

    /**
     * SECTION 5 RULE 2: Verify dropdown says "All Distributors" before expanding Show Retailers.
     */
    private suspend fun ensureAllDistributorsFilter(fast: Boolean) {
        val (dropdownNode, label) = inspector.findDistributorFilterDropdown()
        if (dropdownNode != null && label != null && !label.equals("All Distributors", ignoreCase = true)) {
            logs.warn(
                "FilterGuard",
                "Filter dropdown was '$label' instead of 'All Distributors' — possible app update or filter change"
            )
            updateState(BotState.RUNNING, "Resetting filter to All Distributors...")
            inspector.clickNodeOrCenter(dropdownNode)
            delay(if (fast) 300L else 550L)
            val allDistOption = inspector.findNodeByExactText("All Distributors")
            if (allDistOption != null) {
                inspector.clickNodeOrCenter(allDistOption)
                delay(if (fast) 350L else 600L)
                inspector.waitUntilIdle(fast)
            }
        }
    }

    /**
     * BUG 1 FIX: Inspect isChecked BEFORE tapping. Tap Show Retailers AT MOST ONCE when unchecked.
     * Treat list as EXPANDED immediately on ANY true signal:
     * (a) isChecked == true, (b) Total Customers jumped > preTapCount, or (c) Retailer card visible.
     */
    private suspend fun ensureShowRetailersExpandedOnce(fast: Boolean): Boolean {
        val preTapTotal = inspector.readTotalCustomersCount()
        if (inspector.isRetailerListExpanded(preTapTotal)) {
            return true
        }

        val checkbox = inspector.findShowRetailersCheckbox()
        if (checkbox == null) {
            logs.warn("ShowRetailers", "Show Retailers checkbox not found")
            return false
        }

        // Strict guard: tap AT MOST ONCE when unchecked
        if (!inspector.isShowRetailersChecked()) {
            logs.info("ShowRetailers", "Tapping Show Retailers ONCE (preTapTotal=$preTapTotal)")
            inspector.clickNodeOrCenter(checkbox)
        }

        // Wait for ANY true expansion signal (NEVER wait for ~125 RecyclerView rows)
        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline && isRunningJobActive()) {
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            if (inspector.isRetailerListExpanded(preTapTotal)) {
                val postTotal = inspector.readTotalCustomersCount()
                logs.info("ShowRetailers", "Expanded cleanly (Total Customers: $preTapTotal -> $postTotal)")
                return true
            }
            delay(200L)
        }
        return inspector.isRetailerListExpanded(preTapTotal)
    }

    /**
     * TIER 1 LEARNING MODE (First run or after "Reset Distributor History").
     */
    private suspend fun runDistributorLearningMode(fast: Boolean) {
        updateState(BotState.LEARNING_DISTRIBUTORS, "Learning permanent distributor blueprint...")
        val uncheckedDistCards = if (!inspector.isShowRetailersChecked()) {
            inspector.parseVisibleCustomerCards().filter { it.cardType == CardType.DISTRIBUTOR }
        } else {
            emptyList()
        }

        val expanded = ensureShowRetailersExpandedOnce(fast)
        if (!expanded) {
            triggerAlarmHalt("Failed to expand Show Retailers during distributor learning", "LEARNING_DISTRIBUTORS")
            return
        }

        val accumulatedTopCards = mutableListOf<CustomerCard>()
        var permScrolls = 0

        // Inspect top of expanded list until True Retailer #1 appears
        while (isRunningJobActive() && permScrolls < 15) {
            val pageCards = inspector.parseVisibleCustomerCards()
            for (c in pageCards) {
                if (accumulatedTopCards.none { it.customerId.equals(c.customerId, true) && it.cardType == c.cardType }) {
                    accumulatedTopCards.add(c)
                }
            }

            // Edge Case B: Zero-distributor list -> immediately Card #1 is True Retailer #1, permScrolls = 0
            if (permScrolls == 0 && pageCards.none { it.cardType == CardType.DISTRIBUTOR } &&
                pageCards.any { it.cardType == CardType.RETAILER }
            ) {
                permScrolls = 0
                break
            }

            val classification = EntityRecognitionEngine.classifyOrderedCards(accumulatedTopCards)
            if (classification.genuineRetailers.isNotEmpty()) {
                // True Retailer #1 is visible on this page!
                break
            }

            val moved = inspector.scrollCustomersList(down = true, fastMode = fast)
            if (!moved) break
            permScrolls++
        }

        val finalClass = EntityRecognitionEngine.classifyOrderedCards(accumulatedTopCards)
        val mergedDistributors = (finalClass.distributors + uncheckedDistCards.map {
            com.vrksignatures.sudhabot.model.DistributorRecord(it.boldOrangeName, it.customerId.uppercase())
        }).distinctBy { it.customerId.uppercase() }

        val mergedBlacklist = finalClass.blacklistIds.toMutableSet().apply {
            uncheckedDistCards.forEach { add(it.customerId.trim().uppercase()) }
        }

        tier1Memory.saveBlueprint(
            distributors = mergedDistributors,
            blacklistIds = mergedBlacklist,
            blacklistSignatures = finalClass.blacklistSignatures,
            permDistributorScrolls = permScrolls
        )

        logs.info(
            "Tier1Blueprint",
            "Learned blueprint: distributors=${mergedDistributors.size}, " +
                "blacklistedIds=$mergedBlacklist, permDistributorScrolls=$permScrolls"
        )
    }

    private suspend fun scrollToFindManualOverrideId(targetId: String, fast: Boolean): Int? {
        var depth = 0
        repeat(80) {
            if (!isRunningJobActive()) return null
            val cards = inspector.parseVisibleCustomerCards()
            if (cards.any { it.customerId.equals(targetId, ignoreCase = true) }) {
                return depth
            }
            if (!inspector.scrollCustomersList(down = true, fastMode = fast)) {
                return null
            }
            depth++
        }
        return null
    }

    /**
     * SECTION 8: Complete Screen-by-Screen Order & Payment Loop for one genuine retailer.
     */
    private suspend fun processSingleRetailer(
        card: CustomerCard,
        retailerIndex: Int,
        fast: Boolean
    ): Boolean {
        currentRetailerId = card.customerId
        currentRetailerName = card.boldOrangeName
        updateState(BotState.RUNNING, "Opening ${card.boldOrangeName} (${card.customerId})")

        // Step 1: Tap Retailer Card
        val tappedCard = executeWith5Retry("TAP_RETAILER_${card.customerId}", fast) {
            service.dispatchTap(card.bounds.centerX().toFloat(), card.bounds.centerY().toFloat())
            delay(if (fast) 350L else 600L)
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            inspector.detectScreen() == ScreenInspector.DetectedScreen.CUSTOMER_DETAILS
        }
        if (!tappedCard) return false

        // Step 2: Customer Details -> Tap blue "Milk" icon (NEVER tap PROD)
        updateState(BotState.RUNNING, "Opening Milk orders for ${card.customerId}...")
        val openedMilk = executeWith5Retry("TAP_MILK_ICON_${card.customerId}", fast) {
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            val milkNode = inspector.findNodeByExactText("Milk") ?: return@executeWith5Retry false
            inspector.clickNodeOrCenter(milkNode)
            delay(if (fast) 350L else 650L)
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            inspector.detectScreen() == ScreenInspector.DetectedScreen.ORDER_HISTORY
        }
        if (!openedMilk) return false

        // Step 3: Order History -> Ignore "Orders list is empty. Retry?" snackbar -> Tap orange '+' FAB
        updateState(BotState.RUNNING, "Creating new Milk order for ${card.customerId}...")
        val openedNewOrder = executeWith5Retry("TAP_ADD_FAB_${card.customerId}", fast) {
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            val fab = inspector.findAddOrderFab() ?: return@executeWith5Retry false
            inspector.clickNodeOrCenter(fab)
            delay(if (fast) 350L else 650L)
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }
            inspector.detectScreen() == ScreenInspector.DetectedScreen.ORDER_ENTRY
        }
        if (!openedNewOrder) return false

        // Step 4: Copy Order with T-1..T-5 Verified DatePicker Rollback
        val copied = runCopyOrderRollbackT1ToT5(card, fast)
        if (!copied) {
            // All 5 days had no order -> SKIPPED (unless alarm halted)
            if (currentState == BotState.ALARM_HALTED) return false
            tier2Memory.recordRetailerSkipped(card.customerId, card.boldOrangeName, currentTempScrollDepth)
            logs.info("Skipped", "No order found T-1..T-5 for ${card.customerId} (${card.boldOrangeName}); marked SKIPPED.")
            return navigateBackToCustomersList(fast)
        }

        // Step 5: Apply Quantity Manipulation Modes (Customer Demand -> Employee's Chance)
        applyQuantityModesIfNeeded(card, retailerIndex, fast)

        // Step 6: Tap PLACE ORDER and wait for In Progress / Loading
        updateState(BotState.RUNNING, "Placing order for ${card.customerId}...")
        val placed = executeWith5Retry("PLACE_ORDER_${card.customerId}", fast) {
            val placeBtn = inspector.findNodeByExactText("PLACE ORDER") ?: return@executeWith5Retry false
            inspector.clickNodeOrCenter(placeBtn)
            delay(if (fast) 400L else 700L)
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }

            if (hadRecentNetworkFailure()) {
                inspector.dismissOpenDialogsFirst(fast)
                return@executeWith5Retry false
            }

            val nextScreen = inspector.detectScreen()
            nextScreen == ScreenInspector.DetectedScreen.PAYMENT_SCREEN ||
                nextScreen == ScreenInspector.DetectedScreen.ORDER_HISTORY ||
                nextScreen == ScreenInspector.DetectedScreen.CUSTOMERS_LIST
        }
        if (!placed) return false

        // Step 7: Payment Screen Handling
        if (inspector.detectScreen() == ScreenInspector.DetectedScreen.PAYMENT_SCREEN) {
            when (prefs.paymentMode) {
                PaymentMode.MANUAL_PAUSE -> {
                    updateState(
                        BotState.MANUAL_PAYMENT,
                        "Manual Payment: complete payment for ${card.customerId}, then tap ▶ START"
                    )
                    logs.info("ManualPayment", "Paused at Payment screen for ${card.customerId} (${card.boldOrangeName}).")
                    tier2Memory.recordRetailerSuccess(card.customerId, card.boldOrangeName, currentTempScrollDepth)
                    return false
                }

                PaymentMode.AUTO_PAY_CASH -> {
                    val paid = executeAutoPayCash(card, fast)
                    if (!paid) return false
                }
            }
        }

        // Step 8: Return cleanly to Customers & Commit Tier 2 Daily Memory
        val returned = navigateBackToCustomersList(fast)
        if (!returned) return false

        tier2Memory.recordRetailerSuccess(card.customerId, card.boldOrangeName, currentTempScrollDepth)
        logs.info("OrderPlaced", "Completed ${card.customerId} (${card.boldOrangeName}) at scroll #$currentTempScrollDepth.")
        updateState(BotState.RUNNING, "Done ${card.customerId}; moving to next...")
        return true
    }

    /**
     * Step 4: T-1 through T-5 Date Rollback with verified DatePicker & per-date network retry.
     */
    private suspend fun runCopyOrderRollbackT1ToT5(card: CustomerCard, fast: Boolean): Boolean {
        val today = LocalDate.now()

        for (dayOffset in 1..5) {
            val targetDate = today.minusDays(dayOffset.toLong())
            updateState(BotState.RUNNING, "Copy Order T-$dayOffset ($targetDate) for ${card.customerId}")

            var dateRetries = 0
            while (dateRetries < 5 && isRunningJobActive()) {
                val copyLink = inspector.findNodeByExactText("Copy Order")
                if (copyLink == null) {
                    dateRetries++
                    delay(400L)
                    continue
                }
                inspector.clickNodeOrCenter(copyLink)

                val selectedOk = datePicker.selectDateAndConfirm(today, targetDate, fast)
                if (!selectedOk) {
                    dateRetries++
                    continue
                }

                inspector.waitUntilIdle(fast) { !isRunningJobActive() }
                val modal = inspector.detectModalDialog()
                if (modal != null) {
                    if (modal.isNoOrderToCopy) {
                        // Dismiss OK (NEVER press Back) and advance to T-(offset+1)
                        inspector.dismissOpenDialogsFirst(fast)
                        break
                    }
                    if (modal.isNetworkError) {
                        inspector.dismissOpenDialogsFirst(fast)
                        dateRetries++
                        delay(1800L)
                        continue
                    }
                    // Unknown dialog on Copy Order
                    inspector.dismissOpenDialogsFirst(fast)
                    dateRetries++
                    delay(1200L)
                    continue
                }

                if (hadRecentNetworkFailure()) {
                    dateRetries++
                    delay(1800L)
                    continue
                }

                // Verify at least one Milk row has MQty > 0 or EQty > 0
                val rows = inspector.parseVisibleMilkTableRows()
                if (rows.any { (data, _) -> data.mQty > 0 || data.eQty > 0 }) {
                    logs.info("CopyOrder", "Copied order from T-$dayOffset ($targetDate) for ${card.customerId}")
                    return true
                }

                // Empty table without popup -> try next date
                break
            }

            if (dateRetries >= 5) {
                triggerAlarmHalt("Failed Copy Order T-$dayOffset after 5 retries for ${card.customerId}", "COPY_ORDER")
                return false
            }
        }
        return false
    }

    /**
     * Step 5: Applies Customer Demand overrides (Mode A) or Employee's Chance (Mode B) to MQty cells.
     */
    private suspend fun applyQuantityModesIfNeeded(
        card: CustomerCard,
        retailerIndex: Int,
        fast: Boolean
    ) {
        val visibleRows = inspector.parseVisibleMilkTableRows()
        val rowData = visibleRows.map { it.first }
        val plan = tier2Memory.getEmployeeChancePlan()

        val (edits, appliedChanceMl) = QuantityManipulationEngine.computeEditsForRetailer(
            customerId = card.customerId,
            genuineRetailerIndex = retailerIndex,
            tableRows = rowData,
            demandRules = prefs.getCustomerDemandRules(),
            employeeChanceConfig = prefs.getEmployeeChanceConfig(),
            employeeChancePlan = plan
        )

        if (edits.isEmpty()) return

        for (edit in edits) {
            var targetNode = inspector.parseVisibleMilkTableRows()
                .firstOrNull { it.first.sku == edit.sku }?.second

            // Scroll table slightly if target SKU row is below the fold
            if (targetNode == null) {
                val rv = inspector.findCustomersRecyclerViewRect()
                if (rv != null) {
                    service.dispatchSwipe(
                        rv.centerX().toFloat(),
                        rv.top + rv.height() * 0.78f,
                        rv.centerX().toFloat(),
                        rv.top + rv.height() * 0.28f,
                        320L
                    )
                    delay(350L)
                    targetNode = inspector.parseVisibleMilkTableRows()
                        .firstOrNull { it.first.sku == edit.sku }?.second
                }
            }

            if (targetNode != null) {
                val ok = inspector.setMQtyValue(targetNode, edit.newQty, fast)
                if (ok) {
                    logs.audit("QtyEdit", "${card.customerId}: ${edit.reason}")
                } else {
                    logs.warn("QtyEdit", "Failed to verify MQty edit for ${card.customerId}: ${edit.reason}")
                }
            } else {
                logs.warn("QtyEdit", "SKU row '${edit.sku.exactLabel}' not found for ${card.customerId}")
            }
        }

        if (appliedChanceMl > 0 && plan != null) {
            val updatedPlan = plan.copy(
                appliedRetailerIndices = plan.appliedRetailerIndices + retailerIndex,
                distributedMlSoFar = plan.distributedMlSoFar + appliedChanceMl
            )
            tier2Memory.saveEmployeeChancePlan(updatedPlan)
        }
    }

    private suspend fun executeAutoPayCash(card: CustomerCard, fast: Boolean): Boolean {
        updateState(BotState.RUNNING, "Auto-Pay CASH for ${card.customerId}...")
        return executeWith5Retry("AUTO_PAY_CASH_${card.customerId}", fast) {
            val payBtn = inspector.findNodeByExactText("Pay") ?: return@executeWith5Retry false
            inspector.clickNodeOrCenter(payBtn)
            delay(if (fast) 300L else 550L)

            val proceedBtn = inspector.findNodeByExactText("PROCEED")
            if (proceedBtn != null) {
                inspector.clickNodeOrCenter(proceedBtn)
            }

            delay(if (fast) 400L else 700L)
            inspector.waitUntilIdle(fast) { !isRunningJobActive() }

            if (hadRecentNetworkFailure()) {
                inspector.dismissOpenDialogsFirst(fast)
                return@executeWith5Retry false
            }

            inspector.findNodeByExactText("Done")?.let { inspector.clickNodeOrCenter(it) }
            delay(if (fast) 250L else 450L)
            inspector.detectScreen() != ScreenInspector.DetectedScreen.PAYMENT_SCREEN
        }
    }

    /**
     * BUG 3 FIX: Navigates cleanly back to Customers list using Dialog-First Guard on every hop.
     */
    private suspend fun navigateBackToCustomersList(fast: Boolean): Boolean {
        repeat(6) {
            if (inspector.detectScreen() == ScreenInspector.DetectedScreen.CUSTOMERS_LIST) {
                return true
            }
            inspector.safeNavigateBack(fast)
        }
        if (inspector.detectScreen() == ScreenInspector.DetectedScreen.CUSTOMERS_LIST) {
            return true
        }
        triggerAlarmHalt("Could not return to Customers list cleanly", "NAVIGATE_BACK")
        return false
    }

    /**
     * SECTION 9: 5-Retry Protocol -> Immediate ALARM_HALTED on 5th consecutive failure.
     */
    private suspend fun executeWith5Retry(
        stepName: String,
        fast: Boolean,
        action: suspend () -> Boolean
    ): Boolean {
        for (attempt in 1..5) {
            if (!isRunningJobActive()) return false
            // If an error modal is open, tap OK first (never blind Back)
            inspector.dismissOpenDialogsFirst(fast)

            val success = try {
                action()
            } catch (e: Exception) {
                logs.warn("Retry", "$stepName attempt $attempt/5 exception: ${e.message}")
                false
            }

            if (success) return true

            logs.warn("Retry", "$stepName failed (attempt $attempt/5). Retrying...")
            delay(1800L)
        }

        triggerAlarmHalt("Step '$stepName' failed after 5 consecutive attempts", stepName)
        return false
    }

    private fun triggerAlarmHalt(reason: String, stepName: String) {
        tier2Memory.incrementErrorCount()
        val dump = inspector.dumpScreenSummary()
        logs.alarm(
            "ALARM_HALTED",
            "Step=$stepName | Customer=$currentRetailerId ($currentRetailerName) | Reason=$reason | $dump"
        )
        alarmController.startHighVolumeAlarm()
        updateState(BotState.ALARM_HALTED, "ALARM: $reason")
        runJob?.cancel()
    }

    private fun hadRecentNetworkFailure(): Boolean {
        val recent = System.currentTimeMillis() - lastToastTimestamp < 3500L
        if (!recent) return false
        return lastToastText.contains("Failed to connect", ignoreCase = true) ||
            lastToastText.contains("Internal error", ignoreCase = true)
    }

    private fun ensureTodaysEmployeeChancePlan() {
        val config = prefs.getEmployeeChanceConfig()
        if (!config.enabled) return
        val existing = tier2Memory.getEmployeeChancePlan()
        if (existing != null &&
            existing.totalExtraVolumeMl == config.totalExtraVolumeMl &&
            existing.targetRetailerCount == config.targetRetailerCount
        ) {
            return
        }
        val plan = QuantityManipulationEngine.generateEmployeeChancePlan(
            planDate = LocalDate.now().toString(),
            config = config,
            expectedRetailers = prefs.expectedRetailersToday
        )
        if (plan != null) {
            tier2Memory.saveEmployeeChancePlan(plan)
            logs.info(
                "EmployeeChance",
                "Generated scattered plan for ${plan.planDate}: ${plan.totalExtraVolumeMl}ml across ${plan.targetRetailerCount} non-consecutive retailers."
            )
        }
    }

    private fun isRunningJobActive(): Boolean = runJob?.isActive == true

    private fun updateState(newState: BotState, message: String) {
        currentState = newState
        currentStatus = message
        onSnapshotChanged(getSnapshot())
    }
}
