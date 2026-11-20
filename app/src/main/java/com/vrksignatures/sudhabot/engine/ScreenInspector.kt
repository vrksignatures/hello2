package com.vrksignatures.sudhabot.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.vrksignatures.sudhabot.model.CardType
import com.vrksignatures.sudhabot.model.CustomerCard
import com.vrksignatures.sudhabot.model.MilkSku
import com.vrksignatures.sudhabot.model.MilkTableRowData
import com.vrksignatures.sudhabot.service.SudhaAccessibilityService
import kotlinx.coroutines.delay

/**
 * Centralized Accessibility Tree Inspector & UI Guards:
 * - Bug 1: Accurate Show Retailers expansion signals (isChecked, Total Customers jump, Retailer card visible).
 * - Bug 3: Dialog-First Guard before any navigation or Back press.
 * - Bug 4: Centralized waitUntilIdle() barrier (never bypassed by Fast Mode).
 * - Bug 5: Direct Customers screen detection without Home screen hunt.
 * - Section 3 & 10: Customer Card & 13-SKU Milk Table parser/editor.
 */
class ScreenInspector(private val service: SudhaAccessibilityService) {

    enum class DetectedScreen {
        CUSTOMERS_LIST,
        CUSTOMER_DETAILS,
        ORDER_HISTORY,
        ORDER_ENTRY,
        PAYMENT_SCREEN,
        HOME_SCREEN,
        MODAL_DIALOG,
        UNKNOWN
    }

    data class ModalDialogInfo(
        val title: String,
        val body: String,
        val okButton: AccessibilityNodeInfo?,
        val cancelButton: AccessibilityNodeInfo?,
        val proceedButton: AccessibilityNodeInfo?,
        val isNoOrderToCopy: Boolean,
        val isNetworkError: Boolean
    )

    fun getActiveRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    // Exclude our own floating overlay window
                    if (pkg != service.packageName) {
                        roots.add(root)
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            service.rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() != service.packageName) {
                    roots.add(root)
                }
            }
        }
        return roots
    }

    fun getAllNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        getActiveRoots().forEach { queue.add(it) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    fun getAllVisibleTexts(): List<String> {
        return getAllNodes().mapNotNull { n ->
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            when {
                !t.isNullOrEmpty() -> t
                !d.isNullOrEmpty() -> d
                else -> null
            }
        }
    }

    /**
     * BUG 4 FIX: Centralized waitUntilIdle() barrier.
     * Actively pauses whenever Loading, Getting placed orders..., Making Payment...,
     * In Progress, or any active ProgressBar is visible.
     * Fast Mode ONLY shortens the post-idle settle debounce — NEVER bypasses this barrier.
     */
    suspend fun waitUntilIdle(
        fastMode: Boolean,
        timeoutMs: Long = 20_000L,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isCancelled()) return false
            if (!isLoadingOverlayVisible()) {
                delay(if (fastMode) 160L else 380L)
                if (!isLoadingOverlayVisible()) {
                    return true
                }
            }
            delay(180L)
        }
        return !isLoadingOverlayVisible()
    }

    fun isLoadingOverlayVisible(): Boolean {
        val nodes = getAllNodes()
        for (node in nodes) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("ProgressBar", ignoreCase = true) && node.isVisibleToUser) {
                return true
            }
            val text = node.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty() && LOADING_LABELS.any { text.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    /**
     * BUG 5 FIX: Immediately detects current screen without ever hunting for HOME when on Customers.
     */
    fun detectScreen(): DetectedScreen {
        val texts = getAllVisibleTexts()
        if (detectModalDialog() != null) return DetectedScreen.MODAL_DIALOG

        val hasShowRetailers = texts.any { it.equals("Show Retailers", ignoreCase = true) }
        val hasTotalCustomers = texts.any { it.startsWith("Total Customers:", ignoreCase = true) }
        val hasDeliveryPoint = texts.any { it.contains("Delivery Point (", ignoreCase = true) }
        if (hasShowRetailers || hasTotalCustomers || hasDeliveryPoint) {
            return DetectedScreen.CUSTOMERS_LIST
        }

        if (texts.any { it.equals("PLACE ORDER", ignoreCase = true) } ||
            texts.any { it.equals("Copy Order", ignoreCase = true) } ||
            texts.any { it.contains("Item Group", ignoreCase = true) }
        ) {
            return DetectedScreen.ORDER_ENTRY
        }

        if (texts.any { it.equals("Paid Amount", ignoreCase = true) } ||
            (texts.any { it.equals("Payment Mode", ignoreCase = true) } &&
                texts.any { it.equals("Pay", ignoreCase = true) })
        ) {
            return DetectedScreen.PAYMENT_SCREEN
        }

        if (texts.any { it.equals("View Orders", ignoreCase = true) } &&
            texts.any { it.equals("Milk", ignoreCase = true) }
        ) {
            return DetectedScreen.CUSTOMER_DETAILS
        }

        if (texts.any { it.equals("New Order Milk", ignoreCase = true) } &&
            findAddOrderFab() != null
        ) {
            return DetectedScreen.ORDER_HISTORY
        }

        if (texts.any { it.equals("Daily Orders", ignoreCase = true) } &&
            texts.any { it.equals("Welcome!", ignoreCase = true) }
        ) {
            return DetectedScreen.HOME_SCREEN
        }

        return DetectedScreen.UNKNOWN
    }

    /**
     * BUG 3 FIX: Dialog-First Guard.
     * Detects modal Error / Confirmation dialogs before any navigation or Back press.
     */
    fun detectModalDialog(): ModalDialogInfo? {
        val nodes = getAllNodes()
        var okBtn: AccessibilityNodeInfo? = null
        var cancelBtn: AccessibilityNodeInfo? = null
        var proceedBtn: AccessibilityNodeInfo? = null
        val texts = mutableListOf<String>()

        for (node in nodes) {
            val t = node.text?.toString()?.trim() ?: ""
            if (t.isNotEmpty()) texts.add(t)

            if (node.isClickable || (node.className?.toString()?.contains("Button", true) == true)) {
                when {
                    t.equals("OK", ignoreCase = true) -> okBtn = node
                    t.equals("CANCEL", ignoreCase = true) -> cancelBtn = node
                    t.equals("PROCEED", ignoreCase = true) -> proceedBtn = node
                }
            }
        }

        val combined = texts.joinToString(" | ")
        val isErrorDialog = texts.any { it.equals("Error", ignoreCase = true) } ||
            combined.contains("No order to copy", ignoreCase = true) ||
            combined.contains("Failed to connect", ignoreCase = true) ||
            combined.contains("Are you sure you want to Proceed Pay", ignoreCase = true)

        // Exclude Material DatePicker (which has OK + CANCEL + calendar day grid)
        val isDatePicker = texts.any { it.equals("SELECT DATE", ignoreCase = true) } ||
            nodes.any { (it.className?.toString() ?: "").contains("DatePicker", ignoreCase = true) } ||
            nodes.any { (it.viewIdResourceName ?: "").contains("date_picker", ignoreCase = true) }

        if (!isErrorDialog || isDatePicker) return null

        val isNoOrder = combined.contains("No order to copy", ignoreCase = true)
        val isNetwork = combined.contains("Failed to connect", ignoreCase = true) ||
            combined.contains("live.dailyordersapp.in", ignoreCase = true) ||
            combined.contains("Internal error", ignoreCase = true)

        return ModalDialogInfo(
            title = texts.firstOrNull { it.equals("Error", ignoreCase = true) } ?: "Dialog",
            body = combined.take(300),
            okButton = okBtn,
            cancelButton = cancelBtn,
            proceedButton = proceedBtn,
            isNoOrderToCopy = isNoOrder,
            isNetworkError = isNetwork
        )
    }

    /**
     * Dismisses any open modal dialog by tapping OK (or CANCEL) BEFORE issuing Back.
     */
    suspend fun dismissOpenDialogsFirst(fastMode: Boolean): Boolean {
        var dismissedAny = false
        repeat(3) {
            val dialog = detectModalDialog() ?: return dismissedAny
            val target = dialog.okButton ?: dialog.cancelButton
            if (target != null) {
                clickNodeOrCenter(target)
                dismissedAny = true
                delay(if (fastMode) 220L else 420L)
            } else {
                return dismissedAny
            }
        }
        return dismissedAny
    }

    /**
     * Safe Back navigation: ALWAYS dismisses open dialogs before calling GLOBAL_ACTION_BACK.
     */
    suspend fun safeNavigateBack(fastMode: Boolean) {
        dismissOpenDialogsFirst(fastMode)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(if (fastMode) 320L else 600L)
        waitUntilIdle(fastMode)
    }

    /**
     * BUG 1 FIX: Reads Show Retailers checkbox node & Total Customers counter.
     */
    fun findShowRetailersCheckbox(): AccessibilityNodeInfo? {
        val nodes = getAllNodes()
        for (node in nodes) {
            val t = node.text?.toString()?.trim() ?: ""
            if (t.equals("Show Retailers", ignoreCase = true)) {
                if (node.isCheckable) return node
                // Check sibling CheckBox inside same parent row
                val parent = node.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        if (child.isCheckable) return child
                    }
                    if (parent.isClickable) return parent
                }
                return node
            }
        }
        return null
    }

    fun isShowRetailersChecked(): Boolean {
        val cb = findShowRetailersCheckbox() ?: return false
        if (cb.isChecked) return true
        val parent = cb.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                if (parent.getChild(i)?.isChecked == true) return true
            }
        }
        return false
    }

    fun readTotalCustomersCount(): Int? {
        val regex = Regex("""Total Customers:\s*(\d+)""", RegexOption.IGNORE_CASE)
        for (text in getAllVisibleTexts()) {
            val match = regex.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    /**
     * BUG 1 FIX: Returns true IMMEDIATELY when ANY true expansion signal is present:
     * (a) Show Retailers checkbox isChecked == true, OR
     * (b) Total Customers: <N> jumped strictly greater than preTapCount, OR
     * (c) At least one visible card contains "Delivery Point (Retailer)".
     */
    fun isRetailerListExpanded(preTapTotalCustomers: Int?): Boolean {
        if (isShowRetailersChecked()) return true
        val currentTotal = readTotalCustomersCount()
        if (preTapTotalCustomers != null && currentTotal != null && currentTotal > preTapTotalCustomers) {
            return true
        }
        if (getAllVisibleTexts().any { it.contains("Delivery Point (Retailer)", ignoreCase = true) }) {
            return true
        }
        return false
    }

    /**
     * SECTION 5 RULE 2: Reads the distributor filter dropdown to the left of Show Retailers.
     */
    fun findDistributorFilterDropdown(): Pair<AccessibilityNodeInfo?, String?> {
        val showRetailersNode = findShowRetailersCheckbox()
        val srRect = Rect().also { showRetailersNode?.getBoundsInScreen(it) }
        val nodes = getAllNodes()

        // First look for explicit "All Distributors" node
        nodes.firstOrNull { (it.text?.toString()?.trim() ?: "").equals("All Distributors", ignoreCase = true) }
            ?.let { return Pair(it, "All Distributors") }

        // Otherwise locate clickable Spinner / TextView positioned directly to the left of Show Retailers
        if (showRetailersNode != null && !srRect.isEmpty) {
            val candidates = nodes.filter { node ->
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val verticallyAligned = kotlin.math.abs(rect.centerY() - srRect.centerY()) < 110
                val toTheLeft = rect.right <= srRect.left + 40 && rect.width() > 60
                val text = node.text?.toString()?.trim() ?: ""
                verticallyAligned && toTheLeft && (node.isClickable || text.isNotEmpty())
            }.sortedByDescending { node ->
                val cls = node.className?.toString() ?: ""
                if (cls.contains("Spinner", ignoreCase = true)) 2 else if (node.isClickable) 1 else 0
            }

            val best = candidates.firstOrNull()
            if (best != null) {
                val label = extractFirstText(best)
                return Pair(best, label)
            }
        }
        return Pair(null, null)
    }

    /**
     * Parses all currently visible Customer Cards in vertical top-to-bottom order.
     * Extracts exact (CustomerId, BoldOrangeName, CardType) without any ID prefix assumptions.
     */
    fun parseVisibleCustomerCards(): List<CustomerCard> {
        val nodes = getAllNodes()
        val cards = mutableListOf<CustomerCard>()
        val seenIds = mutableSetOf<String>()

        // Find every node containing "Delivery Point (Dist)" or "Delivery Point (Retailer)"
        val anchorNodes = nodes.filter { n ->
            val t = n.text?.toString()?.trim() ?: ""
            t.contains("Delivery Point (Dist)", ignoreCase = true) ||
                t.contains("Delivery Point (Retailer)", ignoreCase = true)
        }

        for (anchor in anchorNodes) {
            val cardContainer = findCardContainer(anchor) ?: continue
            val rect = Rect().also { cardContainer.getBoundsInScreen(it) }
            if (rect.height() < 30 || rect.width() < 100) continue

            val cardTexts = collectTextsInSubtree(cardContainer)
            val isDist = cardTexts.any { it.contains("Delivery Point (Dist)", ignoreCase = true) }
            val isRetailer = cardTexts.any { it.contains("Delivery Point (Retailer)", ignoreCase = true) }
            val cardType = when {
                isDist -> CardType.DISTRIBUTOR
                isRetailer -> CardType.RETAILER
                else -> CardType.UNKNOWN
            }

            val customerId = extractCustomerId(cardTexts) ?: continue
            val normId = customerId.trim().uppercase()
            if (!seenIds.add("$normId|${cardType.name}")) continue

            val boldName = extractBoldOrangeName(cardTexts)
            cards.add(
                CustomerCard(
                    customerId = customerId.trim(),
                    boldOrangeName = boldName,
                    cardType = cardType,
                    bounds = rect,
                    verticalOrder = rect.top
                )
            )
        }

        return cards.sortedBy { it.bounds.top }
    }

    private fun findCardContainer(anchor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = anchor
        var best: AccessibilityNodeInfo? = null
        var depth = 0
        while (current != null && depth < 6) {
            val texts = collectTextsInSubtree(current)
            val hasId = texts.any { it.contains("Customer Id", ignoreCase = true) }
            val deliveryPointCount = texts.count { it.contains("Delivery Point (", ignoreCase = true) }
            if (deliveryPointCount > 1) {
                // Went too high (into RecyclerView)
                break
            }
            if (hasId && deliveryPointCount == 1) {
                best = current
                if (current.isClickable) return current
            }
            current = current.parent
            depth++
        }
        return best
    }

    private fun extractCustomerId(texts: List<String>): String? {
        val inlineRegex = Regex("""Customer\s*Id\s*:?\s*([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        for (i in texts.indices) {
            val t = texts[i].trim()
            val m = inlineRegex.find(t)
            if (m != null && m.groupValues[1].isNotBlank()) {
                return m.groupValues[1].trim()
            }
            if (t.equals("Customer Id:", ignoreCase = true) || t.equals("Customer Id", ignoreCase = true)) {
                val next = texts.getOrNull(i + 1)?.trim()
                if (!next.isNullOrBlank()) return next
            }
        }
        return null
    }

    private fun extractBoldOrangeName(texts: List<String>): String {
        val filtered = texts.map { it.trim() }.filter { t ->
            t.isNotEmpty() &&
                !t.contains("Delivery Point", ignoreCase = true) &&
                !t.contains("Customer Id", ignoreCase = true) &&
                !t.startsWith("Total Customers", ignoreCase = true) &&
                !t.matches(Regex("""^\+?\d{10,13}$""")) // skip phone numbers
        }
        // The Bold Orange Name in Sudha Daily Orders is uppercase shop/person name
        return filtered.firstOrNull { it == it.uppercase() && it.any { ch -> ch.isLetter() } }
            ?: filtered.firstOrNull()
            ?: ""
    }

    fun findCustomersRecyclerViewRect(): Rect? {
        val nodes = getAllNodes()
        val rv = nodes.firstOrNull { n ->
            val cls = n.className?.toString() ?: ""
            (cls.contains("RecyclerView", ignoreCase = true) || cls.contains("ListView", ignoreCase = true)) &&
                n.isScrollable && n.isVisibleToUser
        } ?: return null
        return Rect().also { rv.getBoundsInScreen(it) }
    }

    /**
     * BUG 6 FIX: Controlled 68% RecyclerView swipe distance so 1-2 cards always overlap
     * across consecutive scrolls, preventing boundary drops.
     */
    suspend fun scrollCustomersList(down: Boolean, fastMode: Boolean): Boolean {
        val rect = findCustomersRecyclerViewRect() ?: return false
        val x = rect.centerX().toFloat()
        val height = rect.height().toFloat()
        val topY = rect.top + height * 0.16f
        val bottomY = rect.top + height * 0.84f // 68% controlled travel

        val beforeIds = parseVisibleCustomerCards().map { it.customerId }
        val ok = if (down) {
            service.dispatchSwipe(x, bottomY, x, topY, if (fastMode) 280L else 420L)
        } else {
            service.dispatchSwipe(x, topY, x, bottomY, if (fastMode) 280L else 420L)
        }
        if (!ok) return false
        delay(if (fastMode) 260L else 480L)
        val afterIds = parseVisibleCustomerCards().map { it.customerId }
        return afterIds != beforeIds
    }

    fun findNodeByExactText(text: String, ignoreCase: Boolean = true): AccessibilityNodeInfo? {
        return getAllNodes().firstOrNull { n ->
            val t = n.text?.toString()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.trim() ?: ""
            t.equals(text, ignoreCase = ignoreCase) || d.equals(text, ignoreCase = ignoreCase)
        }
    }

    fun findAddOrderFab(): AccessibilityNodeInfo? {
        val nodes = getAllNodes()
        // Look for FloatingActionButton / ImageButton at bottom-right
        return nodes.filter { n ->
            val cls = n.className?.toString() ?: ""
            val t = n.text?.toString()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.trim() ?: ""
            val rect = Rect().also { n.getBoundsInScreen(it) }
            val isFabLike = cls.contains("FloatingActionButton", true) ||
                cls.contains("ImageButton", true) ||
                t == "+" || d.contains("add", true)
            isFabLike && n.isClickable && rect.width() in 60..280 && rect.height() in 60..280
        }.maxByOrNull { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            r.bottom + r.right
        }
    }

    /**
     * Parses visible rows of the New Order Milk table (Item, Rate, MQty, EQty, Total)
     * across all 13 exact SKUs.
     */
    fun parseVisibleMilkTableRows(): List<Pair<MilkTableRowData, AccessibilityNodeInfo?>> {
        val nodes = getAllNodes()
        val result = mutableListOf<Pair<MilkTableRowData, AccessibilityNodeInfo?>>()
        val seenSkus = mutableSetOf<MilkSku>()

        for (node in nodes) {
            val label = node.text?.toString()?.trim() ?: continue
            val sku = MilkSku.fromTableLabel(label) ?: continue
            if (!seenSkus.add(sku)) continue

            val rowParent = findTableRowContainer(node) ?: continue
            val editTexts = collectEditTextsInSubtree(rowParent)
                .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.left }

            // Table columns: Item | Rate | MQty | EQty | Total -> first EditText is MQty, second is EQty
            val mQtyNode = editTexts.getOrNull(0)
            val eQtyNode = editTexts.getOrNull(1)
            val mQty = mQtyNode?.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val eQty = eQtyNode?.text?.toString()?.trim()?.toIntOrNull() ?: 0

            val texts = collectTextsInSubtree(rowParent)
            val rate = texts.getOrNull(1) ?: ""
            val total = texts.lastOrNull() ?: ""

            result.add(
                Pair(
                    MilkTableRowData(sku = sku, rate = rate, mQty = mQty, eQty = eQty, total = total),
                    mQtyNode
                )
            )
        }
        return result
    }

    /**
     * Edits an MQty EditText cleanly: focus -> ACTION_SET_TEXT -> verify persisted value.
     */
    suspend fun setMQtyValue(editTextNode: AccessibilityNodeInfo, newQty: Int, fastMode: Boolean): Boolean {
        val targetStr = newQty.coerceAtLeast(0).toString()
        clickNodeOrCenter(editTextNode)
        delay(if (fastMode) 120L else 220L)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetStr)
        }
        val setOk = editTextNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(if (fastMode) 150L else 280L)
        editTextNode.refresh()
        val current = editTextNode.text?.toString()?.trim()
        return setOk && current == targetStr
    }

    suspend fun clickNodeOrCenter(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 4) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            target = target.parent
            depth++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (!rect.isEmpty) {
            return service.dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun dumpScreenSummary(): String {
        val texts = getAllVisibleTexts().distinct().take(40)
        return "Screen=${detectScreen()} | Texts=${texts.joinToString(" ; ")}"
    }

    private fun findTableRowContainer(skuNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = skuNode.parent
        var depth = 0
        while (cur != null && depth < 5) {
            val edits = collectEditTextsInSubtree(cur)
            if (edits.size in 1..3) return cur
            cur = cur.parent
            depth++
        }
        return skuNode.parent
    }

    private fun collectEditTextsInSubtree(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val cls = n.className?.toString() ?: ""
            if (n.isEditable || cls.contains("EditText", ignoreCase = true)) {
                out.add(n)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return out
    }

    private fun collectTextsInSubtree(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return out
    }

    private fun extractFirstText(root: AccessibilityNodeInfo): String? {
        return collectTextsInSubtree(root).firstOrNull()
    }

    companion object {
        private val LOADING_LABELS = listOf(
            "Loading",
            "Getting placed orders",
            "Making Payment",
            "In Progress"
        )
    }
}
