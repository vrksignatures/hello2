package com.vrksignatures.sudhabot.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.vrksignatures.sudhabot.memory.DiagnosticLogStore
import com.vrksignatures.sudhabot.service.SudhaAccessibilityService
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * BUG 2 FIX: Verified Material DatePicker Engine for T-1..T-5 Order Rollback.
 *
 * Guarantees the orange selection circle ACTUALLY moves off today's date onto the intended
 * target date before tapping OK:
 * 1. Handles cross-month rollback by tapping the `<` previous-month navigation arrow first.
 * 2. Locates the exact clickable day cell in the DatePicker grid.
 * 3. MANDATORY PRE-OK VERIFICATION: Reads the orange DatePicker header text (e.g. "Thu, 19 Nov")
 *    and asserts it matches targetDate (and differs from today's date). Retries via node click
 *    and direct coordinate gesture dispatch on the cell's Rect center until verified.
 */
class DatePickerEngine(
    private val service: SudhaAccessibilityService,
    private val inspector: ScreenInspector,
    private val logs: DiagnosticLogStore
) {

    /**
     * Selects targetDate inside an already-open Material DatePicker and taps OK ONLY after
     * confirming the orange header reflects targetDate.
     */
    suspend fun selectDateAndConfirm(
        today: LocalDate,
        targetDate: LocalDate,
        fastMode: Boolean
    ): Boolean {
        delay(if (fastMode) 220L else 420L)

        // Step 1: If targetDate is in the previous calendar month, tap `<` previous month arrow
        if (targetDate.monthValue != today.monthValue || targetDate.year != today.year) {
            val prevBtn = findPreviousMonthButton()
            if (prevBtn == null) {
                logs.warn("DatePicker", "Previous month button '<' not found for $targetDate")
                return false
            }
            inspector.clickNodeOrCenter(prevBtn)
            delay(if (fastMode) 350L else 600L)
        }

        // Step 2: Select day cell + MANDATORY PRE-OK HEADER VERIFICATION (up to 4 attempts)
        val dayStr = targetDate.dayOfMonth.toString()
        var headerVerified = false

        for (attempt in 1..4) {
            val dayNode = findDayCellNode(dayStr)
            if (dayNode == null) {
                logs.warn("DatePicker", "Day cell '$dayStr' not found (attempt $attempt/4)")
                delay(250L)
                continue
            }

            // First 2 attempts: standard node click; attempts 3-4: direct coordinate gesture on Rect center
            if (attempt <= 2) {
                inspector.clickNodeOrCenter(dayNode)
            } else {
                val rect = Rect().also { dayNode.getBoundsInScreen(it) }
                if (!rect.isEmpty) {
                    service.dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }

            delay(if (fastMode) 260L else 450L)

            val headerText = readDatePickerHeaderText()
            if (headerMatchesTargetDate(headerText, targetDate)) {
                headerVerified = true
                logs.info("DatePicker", "Header verified '$headerText' for target $targetDate")
                break
            } else {
                logs.warn(
                    "DatePicker",
                    "Pre-OK check failed (attempt $attempt/4): header='$headerText', expected=$targetDate"
                )
            }
        }

        if (!headerVerified) {
            logs.error("DatePicker", "Refusing to tap OK: DatePicker header never moved to $targetDate")
            // Cleanly cancel picker rather than submitting today's date
            inspector.findNodeByExactText("CANCEL")?.let { inspector.clickNodeOrCenter(it) }
            return false
        }

        // Step 3: Tap OK now that orange header is verified
        val okNode = inspector.findNodeByExactText("OK")
        if (okNode == null) {
            logs.error("DatePicker", "OK button not found in DatePicker")
            return false
        }
        inspector.clickNodeOrCenter(okNode)
        delay(if (fastMode) 250L else 450L)
        inspector.waitUntilIdle(fastMode)
        return true
    }

    private fun findPreviousMonthButton(): AccessibilityNodeInfo? {
        val nodes = inspector.getAllNodes()
        return nodes.firstOrNull { n ->
            val desc = n.contentDescription?.toString()?.trim() ?: ""
            val id = n.viewIdResourceName ?: ""
            desc.contains("Previous month", ignoreCase = true) ||
                desc.contains("previous", ignoreCase = true) ||
                id.contains("prev", ignoreCase = true)
        }
    }

    private fun findDayCellNode(dayStr: String): AccessibilityNodeInfo? {
        val nodes = inspector.getAllNodes()
        // Material DatePicker day cells are inside the calendar grid (below header, above OK/CANCEL)
        val candidates = nodes.filter { n ->
            val t = n.text?.toString()?.trim() ?: ""
            val d = n.contentDescription?.toString()?.trim() ?: ""
            val rect = Rect().also { n.getBoundsInScreen(it) }
            val matchesDay = t == dayStr || d.startsWith("$dayStr ")
            matchesDay && !rect.isEmpty && rect.width() in 30..240 && rect.height() in 30..240
        }
        // Prefer clickable/checkable cell inside the middle calendar region
        return candidates.firstOrNull { it.isClickable || it.isCheckable }
            ?: candidates.firstOrNull()
    }

    private fun readDatePickerHeaderText(): String {
        val nodes = inspector.getAllNodes()
        // Standard Android Material DatePicker header resource IDs or formatted date string like "Thu, 19 Nov" / "Nov 19"
        nodes.firstOrNull { n ->
            val id = n.viewIdResourceName ?: ""
            (id.contains("date_picker_header_date", ignoreCase = true) ||
                id.contains("mtrl_picker_header_selection_text", ignoreCase = true)) &&
                !n.text.isNullOrBlank()
        }?.let { return it.text.toString().trim() }

        // Fallback: match day-of-week + month header pattern
        val headerRegex = Regex("""([A-Za-z]{3},?\s+\d{1,2}\s+[A-Za-z]{3}|[A-Za-z]{3}\s+\d{1,2})""")
        return nodes.mapNotNull { it.text?.toString()?.trim() }
            .firstOrNull { headerRegex.containsMatchIn(it) }
            ?: ""
    }

    companion object {
        /**
         * Pure helper (also unit-testable) verifying that the DatePicker orange header
         * matches targetDate (supports "Thu, 19 Nov", "Thu, Nov 19", "19 Nov", "Nov 19").
         */
        fun headerMatchesTargetDate(headerText: String, targetDate: LocalDate): Boolean {
            if (headerText.isBlank()) return false
            val day = targetDate.dayOfMonth.toString()
            val shortMonth = targetDate.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
            val norm = headerText.trim().lowercase(Locale.ENGLISH)
            val hasMonth = norm.contains(shortMonth.lowercase(Locale.ENGLISH))
            val hasExactDay = Regex("""\b$day\b""").containsMatchIn(norm)
            return hasMonth && hasExactDay
        }
    }
}
