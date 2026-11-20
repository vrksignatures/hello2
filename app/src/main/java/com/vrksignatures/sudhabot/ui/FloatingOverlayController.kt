package com.vrksignatures.sudhabot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.vrksignatures.sudhabot.R
import com.vrksignatures.sudhabot.model.BotRuntimeSnapshot
import com.vrksignatures.sudhabot.model.BotState
import kotlin.math.abs

/**
 * SECTION 11: Compact, Draggable, Minimizable Floating Control Panel Overlay.
 *
 * Displays all 8 state badges, live counters, current target retailer, FAST indicator,
 * ▶ START / ⏸ PAUSE, RESET (today's ledger), minimize (• pill), close (✕),
 * and the unmistakable bright-red STOP ALARM button whenever ALARM_HALTED is active.
 */
class FloatingOverlayController(
    private val context: Context,
    private val onStartPause: () -> Unit,
    private val onResetToday: () -> Unit,
    private val onStopAlarm: () -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMinimized = false

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_control_panel, null)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

        val dragListener = createDragTouchListener(params)
        view.findViewById<View>(R.id.dragHeader).setOnTouchListener(dragListener)
        view.findViewById<View>(R.id.pillContainer).setOnTouchListener(dragListener)

        view.findViewById<View>(R.id.btnMinimize).setOnClickListener {
            toggleMinimized(true)
        }
        view.findViewById<View>(R.id.pillContainer).setOnClickListener {
            toggleMinimized(false)
        }
        view.findViewById<View>(R.id.btnCloseOverlay).setOnClickListener {
            onClose()
        }
        view.findViewById<Button>(R.id.btnStartPause).setOnClickListener {
            onStartPause()
        }
        view.findViewById<Button>(R.id.btnResetTodayOverlay).setOnClickListener {
            onResetToday()
        }
        view.findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            onStopAlarm()
        }

        rootView = view
        layoutParams = params
        windowManager.addView(view, params)
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        rootView = null
        layoutParams = null
    }

    fun render(snapshot: BotRuntimeSnapshot) {
        val view = rootView ?: return

        val tvBadge = view.findViewById<TextView>(R.id.tvStateBadge)
        val tvPill = view.findViewById<TextView>(R.id.tvPillState)
        val tvFast = view.findViewById<TextView>(R.id.tvFastBadge)
        val tvSuccess = view.findViewById<TextView>(R.id.tvCountSuccess)
        val tvSkipped = view.findViewById<TextView>(R.id.tvCountSkipped)
        val tvErrors = view.findViewById<TextView>(R.id.tvCountErrors)
        val tvProgress = view.findViewById<TextView>(R.id.tvProgressSummary)
        val tvTarget = view.findViewById<TextView>(R.id.tvCurrentRetailer)
        val tvLiveStatus = view.findViewById<TextView>(R.id.tvLiveStatus)
        val btnStartPause = view.findViewById<Button>(R.id.btnStartPause)
        val btnStopAlarm = view.findViewById<Button>(R.id.btnStopAlarm)

        tvBadge.text = snapshot.state.name
        tvPill.text = "• ${snapshot.state.name} (${snapshot.processedCount}/${snapshot.expectedRetailers})"

        val badgeColorRes = when (snapshot.state) {
            BotState.IDLE -> R.color.badge_idle
            BotState.LEARNING_DISTRIBUTORS -> R.color.badge_learning
            BotState.JUMPING_SCROLL -> R.color.badge_jumping
            BotState.RUNNING -> R.color.badge_running
            BotState.PAUSED -> R.color.badge_paused
            BotState.MANUAL_PAYMENT -> R.color.badge_manual_pay
            BotState.ALARM_HALTED -> R.color.badge_alarm
            BotState.ERROR -> R.color.badge_error
        }
        (tvBadge.background?.mutate() as? GradientDrawable)
            ?.setColor(ContextCompat.getColor(context, badgeColorRes))

        tvFast.text = if (snapshot.fastMode) "FAST: ON" else "FAST: OFF"
        tvFast.setTextColor(
            ContextCompat.getColor(
                context,
                if (snapshot.fastMode) R.color.brand_accent else R.color.text_secondary
            )
        )

        tvSuccess.text = "SUCCESS: ${snapshot.successCount}"
        tvSkipped.text = "SKIPPED: ${snapshot.skippedCount}"
        tvErrors.text = "ERRORS: ${snapshot.errorCount}"
        tvProgress.text = "Processed ${snapshot.processedCount} / ${snapshot.expectedRetailers}"

        val targetText = if (!snapshot.currentRetailerId.isNullOrBlank()) {
            "Target: ${snapshot.currentRetailerId} — ${snapshot.currentRetailerName.orEmpty()}"
        } else {
            "Target: --"
        }
        tvTarget.text = targetText
        tvLiveStatus.text = snapshot.statusMessage

        val isActive = snapshot.state == BotState.RUNNING ||
            snapshot.state == BotState.LEARNING_DISTRIBUTORS ||
            snapshot.state == BotState.JUMPING_SCROLL
        btnStartPause.text = if (isActive) "⏸ PAUSE" else "▶ START"

        // Always expand overlay and show bright-red STOP ALARM button when ALARM_HALTED
        if (snapshot.state == BotState.ALARM_HALTED || snapshot.alarmActive) {
            if (isMinimized) toggleMinimized(false)
            btnStopAlarm.visibility = View.VISIBLE
        } else {
            btnStopAlarm.visibility = View.GONE
        }
    }

    private fun toggleMinimized(minimize: Boolean) {
        isMinimized = minimize
        val view = rootView ?: return
        view.findViewById<View>(R.id.expandedContainer).visibility =
            if (minimize) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.pillContainer).visibility =
            if (minimize) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragTouchListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 6 || abs(dy) > 6) moved = true
                        params.x = initX + dx
                        params.y = initY + dy
                        rootView?.let { windowManager.updateViewLayout(it, params) }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return false
            }
        }
    }
}
