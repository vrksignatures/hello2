package com.vrksignatures.sudhabot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.vrksignatures.sudhabot.engine.AlarmController
import com.vrksignatures.sudhabot.engine.BotAutomationEngine
import com.vrksignatures.sudhabot.memory.BotPreferences
import com.vrksignatures.sudhabot.memory.DailyLedgerMemory
import com.vrksignatures.sudhabot.memory.DiagnosticLogStore
import com.vrksignatures.sudhabot.memory.PermanentDistributorMemory
import com.vrksignatures.sudhabot.ui.FloatingOverlayController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SudhaAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: BotPreferences
    private lateinit var tier1Memory: PermanentDistributorMemory
    private lateinit var tier2Memory: DailyLedgerMemory
    private lateinit var logs: DiagnosticLogStore
    private lateinit var alarmController: AlarmController
    private lateinit var engine: BotAutomationEngine
    private var overlayController: FloatingOverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        prefs = BotPreferences(this)
        tier1Memory = PermanentDistributorMemory(this)
        tier2Memory = DailyLedgerMemory(this)
        logs = DiagnosticLogStore(this)
        alarmController = AlarmController(this)

        engine = BotAutomationEngine(
            service = this,
            prefs = prefs,
            tier1Memory = tier1Memory,
            tier2Memory = tier2Memory,
            logs = logs,
            alarmController = alarmController,
            onSnapshotChanged = { snapshot ->
                mainHandler.post {
                    overlayController?.render(snapshot)
                }
            }
        )

        overlayController = FloatingOverlayController(
            context = this,
            onStartPause = {
                val snap = engine.getSnapshot()
                when (snap.state) {
                    com.vrksignatures.sudhabot.model.BotState.RUNNING,
                    com.vrksignatures.sudhabot.model.BotState.LEARNING_DISTRIBUTORS,
                    com.vrksignatures.sudhabot.model.BotState.JUMPING_SCROLL -> engine.pause()
                    else -> engine.startOrResume()
                }
            },
            onResetToday = { engine.resetTodaysLedgerFromOverlay() },
            onStopAlarm = { engine.stopAlarmAndReturnIdle() },
            onClose = {
                engine.pause()
                hideOverlay()
            }
        )

        logs.info("Service", "SudhaAccessibilityService connected (v2.0 rebuild)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Capture bottom grey toasts from target app ("Failed to connect...", "Internal error...")
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val toastText = event.text?.joinToString(" ")?.trim().orEmpty()
            if (toastText.isNotEmpty() && ::engine.isInitialized) {
                engine.onToastObserved(toastText)
            }
        }
    }

    override fun onInterrupt() {
        if (::engine.isInitialized) {
            engine.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::alarmController.isInitialized) {
            alarmController.stopAlarm()
        }
        hideOverlay()
        if (instance === this) {
            instance = null
        }
    }

    fun showOverlay() {
        mainHandler.post {
            overlayController?.show()
            if (::engine.isInitialized) {
                overlayController?.render(engine.getSnapshot())
            }
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            overlayController?.hide()
        }
    }

    suspend fun dispatchTap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 70L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    suspend fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(120L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    companion object {
        @Volatile
        var instance: SudhaAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
