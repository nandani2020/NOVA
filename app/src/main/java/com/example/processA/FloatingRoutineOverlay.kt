package com.example.processA

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.example.data.ConditionType
import com.example.data.ElseBehavior
import com.example.data.RecordedRoutineStore
import com.example.data.RoutineCondition
import com.example.data.RoutineScope
import com.example.engine.RoutineExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Floating Routine Control Window hosted by NovaAccessibilityService via TYPE_ACCESSIBILITY_OVERLAY
 * (no SYSTEM_ALERT_WINDOW permission needed).
 *
 * Provides a fully draggable pill on top of any app to:
 * 1) Start/Pause/Save routine recording (global or per-app),
 * 2) Start / play a recorded or selected routine directly from the floating bar,
 * 3) Capture exact Tap or Swipe coordinates + timing via a z-ordered pass-through capture scrim
 *    while keeping the floating control bar interactive and never recording itself,
 * 4) Insert IF Conditional statements directly into the routine timeline.
 */
class FloatingRoutineOverlay(
    private val service: NovaAccessibilityService
) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootView: DraggableOverlayContainer? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var captureScrimView: View? = null
    private var captureScrimParams: WindowManager.LayoutParams? = null
    private var continuousTouchCapture: Boolean = false

    private var statusBadge: TextView? = null
    private var recBtn: TextView? = null
    private var playBtn: TextView? = null
    private var tapBtn: TextView? = null
    private var swipeBtn: TextView? = null
    private var ifBtn: TextView? = null

    private var stateObserverJob: Job? = null
    private var playbackJob: Job? = null
    private var dispatchForwardJob: Job? = null

    fun attach() {
        if (rootView != null) {
            renderState(RoutineRecorderManager.state.value)
            return
        }
        mainHandler.post {
            if (rootView != null) return@post
            buildAndAddFloatingBar()
            stateObserverJob?.cancel()
            stateObserverJob = uiScope.launch {
                RoutineRecorderManager.state.collectLatest { st ->
                    renderState(st)
                }
            }
        }
    }

    fun detach() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        dispatchForwardJob?.cancel()
        dispatchForwardJob = null
        continuousTouchCapture = false
        RoutineRecorderManager.setFloatingOverlayFocused(false, releaseCooldownMs = 250L)
        mainHandler.post {
            removeCaptureScrimImmediate()
            rootView?.let { v ->
                runCatching { windowManager.removeView(v) }
            }
            rootView = null
            layoutParams = null
        }
    }

    fun destroy() {
        detach()
        playbackJob?.cancel()
        uiScope.cancel()
    }

    /**
     * Temporarily toggles FLAG_NOT_TOUCHABLE so replayed or forwarded taps/swipes underneath
     * the floating bar hit the target app instead of colliding with the floating window.
     */
    fun setTouchableForPlayback(touchable: Boolean) {
        val v = rootView ?: return
        val lp = layoutParams ?: return
        val apply = {
            if (v.isAttachedToWindow) {
                lp.flags = if (touchable) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }
                v.alpha = if (touchable) 1f else 0.42f
                runCatching { windowManager.updateViewLayout(v, lp) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post(apply)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAndAddFloatingBar() {
        val dm = service.resources.displayMetrics
        fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            dm
        ).toInt()

        val barWidthPx = dp(264).coerceAtMost((dm.widthPixels - dp(24)).coerceAtLeast(dp(200)))
        val params = WindowManager.LayoutParams(
            barWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - barWidthPx - dp(12)).coerceAtLeast(dp(8))
            y = dp(96)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        val container = DraggableOverlayContainer(
            context = service,
            touchSlop = touchSlop,
            onFocusChangedSignal = { focused ->
                RoutineRecorderManager.setFloatingOverlayFocused(focused)
            },
            onDragStart = {
                RoutineRecorderManager.setFloatingOverlayFocused(true)
            },
            onDragDelta = { dx, dy, initialX, initialY, viewW, viewH ->
                val maxX = (dm.widthPixels - viewW.coerceAtLeast(barWidthPx)).coerceAtLeast(0)
                val maxY = (dm.heightPixels - viewH.coerceAtLeast(dp(96))).coerceAtLeast(0)
                params.x = (initialX + dx.toInt()).coerceIn(0, maxX)
                params.y = (initialY + dy.toInt()).coerceIn(0, maxY)
                runCatching {
                    rootView?.let { rv ->
                        if (rv.isAttachedToWindow) windowManager.updateViewLayout(rv, params)
                    }
                }
            },
            onDragEnd = {
                RoutineRecorderManager.setFloatingOverlayFocused(false)
            },
            currentWindowX = { params.x },
            currentWindowY = { params.y }
        ).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB17162E"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#997C4DFF"))
            }
            elevation = dp(10).toFloat()
        }

        // Row 1: Drag header + Scope + Close
        val headerRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragGrip = TextView(service).apply {
            text = "⋮⋮ NOVA"
            textSize = 10.5f
            setTextColor(Color.parseColor("#C8D0F0"))
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        val statusTxt = TextView(service).apply {
            text = "GLOBAL • 0 steps"
            textSize = 10.5f
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(dp(4), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { statusBadge = it }

        val closeTxt = TextView(service).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#FF7A90"))
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener {
                RoutineRecorderManager.markFloatingOverlayInteraction()
                RoutineRecorderManager.hideFloatingControl()
            }
        }

        headerRow.addView(dragGrip)
        headerRow.addView(statusTxt)
        headerRow.addView(closeTxt)
        container.addView(headerRow)

        // Row 2: Start Routine + Record/Save controls
        val primaryRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }

        val playControl = makeChipButton("▶ Start", "#00E676", "#1600E676", dp(10), dp(7)) {
            RoutineRecorderManager.markFloatingOverlayInteraction()
            onStartRoutineClicked()
        }.also { playBtn = it }

        val recControl = makeChipButton("● Record", "#FF5252", "#26FF5252", dp(10), dp(7)) {
            RoutineRecorderManager.markFloatingOverlayInteraction()
            onRecordToggleClicked()
        }.also { recBtn = it }

        primaryRow.addView(playControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        primaryRow.addView(recControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(primaryRow)

        // Row 3: Precision Touch/Swipe capture & conditional insertion buttons
        val captureRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }

        val tapControl = makeChipButton("+ Tap", "#E8EAFF", "#1EFFFFFF", dp(8), dp(6)) {
            RoutineRecorderManager.markFloatingOverlayInteraction()
            if (RoutineRecorderManager.state.value.isCapturingTouch) {
                stopTouchCaptureMode()
            } else {
                launchTouchCaptureOverlay(isSwipeMode = false, continuous = true)
            }
        }.also { tapBtn = it }

        val swipeControl = makeChipButton("+ Swipe", "#E8EAFF", "#1EFFFFFF", dp(8), dp(6)) {
            RoutineRecorderManager.markFloatingOverlayInteraction()
            if (RoutineRecorderManager.state.value.isCapturingTouch) {
                stopTouchCaptureMode()
            } else {
                launchTouchCaptureOverlay(isSwipeMode = true, continuous = false)
            }
        }.also { swipeBtn = it }

        val ifControl = makeChipButton("+ IF", "#FFD166", "#22FFD166", dp(8), dp(6)) { anchor ->
            RoutineRecorderManager.markFloatingOverlayInteraction()
            showConditionQuickPicker(anchor)
        }.also { ifBtn = it }

        captureRow.addView(tapControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })
        captureRow.addView(swipeControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })
        captureRow.addView(ifControl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(captureRow)

        layoutParams = params
        rootView = container
        runCatching { windowManager.addView(container, params) }
        renderState(RoutineRecorderManager.state.value)
    }

    private fun makeChipButton(
        label: String,
        textColorHex: String,
        bgColorHex: String,
        padH: Int,
        padV: Int,
        onClick: (View) -> Unit
    ): TextView {
        return TextView(service).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textColorHex))
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColorHex))
                cornerRadius = 999f
                setStroke(1, Color.parseColor("#38FFFFFF"))
            }
            setOnClickListener { onClick(this) }
        }
    }

    private fun renderState(st: com.example.data.RoutineRecorderState) {
        val scopePrefix = if (st.scope == RoutineScope.PER_APP && !st.targetAppName.isNullOrBlank()) {
            st.targetAppName
        } else {
            "GLOBAL"
        }
        statusBadge?.text = when {
            st.isPlaying -> "RUNNING..."
            st.isCapturingTouch -> "TAP SCREEN • ${st.capturedSteps.size} steps"
            else -> "$scopePrefix • ${st.capturedSteps.size} step${if (st.capturedSteps.size == 1) "" else "s"}"
        }
        recBtn?.text = if (st.isRecording) "■ Save (${st.capturedSteps.size})" else "● Record"
        playBtn?.text = if (st.isPlaying) "■ Stop Run" else "▶ Start"
        tapBtn?.text = if (st.isCapturingTouch) "✓ Done" else "+ Tap"
        tapBtn?.setTextColor(Color.parseColor(if (st.isCapturingTouch) "#00E5FF" else "#E8EAFF"))
        tapBtn?.alpha = if (st.isRecording || st.isCapturingTouch) 1f else 0.72f
        swipeBtn?.alpha = if (st.isRecording) 1f else 0.72f
        ifBtn?.alpha = if (st.isRecording) 1f else 0.72f
    }

    private fun onRecordToggleClicked() {
        val st = RoutineRecorderManager.state.value
        if (st.isRecording) {
            stopTouchCaptureMode()
            playbackJob?.cancel()
            uiScope.launch(Dispatchers.IO) {
                RoutineRecorderManager.stopAndSaveRecording(service)
            }
        } else {
            val fgPkg = service.resolveCurrentForegroundPackage()
            RoutineRecorderManager.startRecording(
                scope = st.scope,
                targetAppName = st.targetAppName,
                targetPackage = st.targetPackage ?: fgPkg
            )
        }
    }

    private fun onStartRoutineClicked() {
        val st = RoutineRecorderManager.state.value
        if (st.isPlaying) {
            playbackJob?.cancel()
            NovaAccessibilityService.abortAutomation("Routine stopped from floating control")
            RoutineRecorderManager.setPlayingState(false, "Stopped")
            return
        }
        stopTouchCaptureMode()

        playbackJob = uiScope.launch(Dispatchers.IO) {
            val store = RecordedRoutineStore(service)
            val all = store.getAll()
            val targetRoutine = when {
                !st.selectedPlayRoutineId.isNullOrBlank() ->
                    store.findById(st.selectedPlayRoutineId) ?: all.firstOrNull()
                st.capturedSteps.isNotEmpty() -> {
                    RoutineRecorderManager.stopAndSaveRecording(service) ?: all.firstOrNull()
                }
                else -> all.firstOrNull()
            }
            store.close()

            if (targetRoutine == null) {
                RoutineRecorderManager.setPlayingState(false, "Record steps first or pick a routine")
                return@launch
            }

            RoutineRecorderManager.setPlayingState(true, "Running '${targetRoutine.name}'...")
            val engine = RoutineExecutionEngine(service)
            val result = engine.executeRoutine(targetRoutine)
            RoutineRecorderManager.setPlayingState(false, result.summary)
        }
    }

    private fun showConditionQuickPicker(anchor: View) {
        if (!RoutineRecorderManager.isRecording()) {
            val st = RoutineRecorderManager.state.value
            RoutineRecorderManager.startRecording(st.scope, st.targetAppName, st.targetPackage)
        }
        RoutineRecorderManager.setFloatingOverlayFocused(true, releaseCooldownMs = 700L)
        val popup = PopupMenu(service, anchor)
        popup.menu.add(0, 0, 0, "IF screen contains 'Skip' -> else Skip next")
        popup.menu.add(0, 1, 1, "IF screen missing 'Done' -> else Stop")
        popup.menu.add(0, 2, 2, "IF battery >= 20% -> else Stop")
        popup.menu.add(0, 3, 3, "IF Wi-Fi is ON -> else Continue")
        popup.setOnMenuItemClickListener { item ->
            RoutineRecorderManager.markFloatingOverlayInteraction(700L)
            val cond = when (item.itemId) {
                0 -> RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "Skip", "", ElseBehavior.SKIP_NEXT)
                1 -> RoutineCondition(ConditionType.SCREEN_MISSING_TEXT, "Done", "", ElseBehavior.STOP_ROUTINE)
                2 -> RoutineCondition(ConditionType.BATTERY_AT_LEAST, "20", "", ElseBehavior.STOP_ROUTINE)
                else -> RoutineCondition(ConditionType.WIFI_IS_ON, "true", "", ElseBehavior.CONTINUE)
            }
            RoutineRecorderManager.recordConditionStep(cond)
            true
        }
        popup.setOnDismissListener {
            RoutineRecorderManager.setFloatingOverlayFocused(false, releaseCooldownMs = 550L)
        }
        runCatching { popup.show() }.onFailure {
            RoutineRecorderManager.setFloatingOverlayFocused(false, releaseCooldownMs = 400L)
        }
    }

    private fun stopTouchCaptureMode() {
        continuousTouchCapture = false
        dispatchForwardJob?.cancel()
        dispatchForwardJob = null
        removeCaptureScrimImmediate()
        RoutineRecorderManager.setTouchCaptureMode(false)
        RoutineRecorderManager.setManualDispatchInProgress(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun launchTouchCaptureOverlay(isSwipeMode: Boolean, continuous: Boolean) {
        if (!RoutineRecorderManager.isRecording()) {
            val st = RoutineRecorderManager.state.value
            RoutineRecorderManager.startRecording(st.scope, st.targetAppName, st.targetPackage)
        }
        removeCaptureScrimImmediate()
        continuousTouchCapture = continuous
        RoutineRecorderManager.setTouchCaptureMode(true)

        var downX = 0f
        var downY = 0f
        var downTime = 0L

        val scrim = FrameTouchCaptureView(service, isSwipeMode, continuous).apply {
            setBackgroundColor(Color.parseColor("#180B0A18"))
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        downTime = ev.eventTime
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val upX = ev.rawX
                        val upY = ev.rawY
                        val dur = (ev.eventTime - downTime).coerceAtLeast(55L)
                        val dist = hypot(upX - downX, upY - downY)

                        // Guard against trailing focus/click Accessibility events during forwarding
                        RoutineRecorderManager.setManualDispatchInProgress(true)
                        val fgPkg = service.resolveCurrentForegroundPackage()

                        if (continuousTouchCapture) {
                            setScrimPassThrough(true)
                        } else {
                            removeCaptureScrimImmediate()
                            RoutineRecorderManager.setTouchCaptureMode(false)
                        }

                        if (isSwipeMode || dist >= 42f) {
                            RoutineRecorderManager.recordManualSwipe(
                                startX = downX,
                                startY = downY,
                                endX = upX,
                                endY = upY,
                                durationMs = dur.coerceIn(140L, 1800L),
                                packageHint = fgPkg
                            )
                            forwardCapturedGesture(
                                isSwipe = true,
                                startX = downX,
                                startY = downY,
                                endX = upX,
                                endY = upY,
                                durationMs = dur.coerceIn(140L, 1200L),
                                keepCaptureActive = continuousTouchCapture
                            )
                        } else {
                            val labelHint = service.findLabelAtPoint(upX, upY)
                            RoutineRecorderManager.recordManualTap(
                                x = upX,
                                y = upY,
                                holdDurationMs = dur.coerceIn(55L, 1500L),
                                labelHint = labelHint,
                                packageHint = fgPkg
                            )
                            forwardCapturedGesture(
                                isSwipe = false,
                                startX = upX,
                                startY = upY,
                                endX = upX,
                                endY = upY,
                                durationMs = dur.coerceIn(55L, 600L),
                                keepCaptureActive = continuousTouchCapture
                            )
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        captureScrimParams = params
        captureScrimView = scrim
        runCatching { windowManager.addView(scrim, params) }
        // CRITICAL: keep floating bar strictly ABOVE capture scrim so user can tap Done / Stop / drag
        ensureFloatingBarOnTop()
    }

    private fun forwardCapturedGesture(
        isSwipe: Boolean,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        keepCaptureActive: Boolean
    ) {
        dispatchForwardJob?.cancel()
        dispatchForwardJob = uiScope.launch {
            try {
                // Allow WindowManager input surface update to commit before injecting synthetic touch
                delay(120L)
                service.withFloatingOverlayPassthrough {
                    if (isSwipe) {
                        service.dispatchSwipe(startX, startY, endX, endY, durationMs)
                    } else {
                        service.dispatchTap(startX, startY, durationMs)
                    }
                }
                delay(140L)
            } finally {
                RoutineRecorderManager.setManualDispatchInProgress(false)
                if (keepCaptureActive && continuousTouchCapture && RoutineRecorderManager.state.value.isCapturingTouch) {
                    setScrimPassThrough(false)
                    ensureFloatingBarOnTop()
                }
            }
        }
    }

    private fun setScrimPassThrough(passThrough: Boolean) {
        val scrim = captureScrimView ?: return
        val lp = captureScrimParams ?: return
        if (!scrim.isAttachedToWindow) return
        lp.flags = if (passThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        scrim.visibility = if (passThrough) View.GONE else View.VISIBLE
        runCatching { windowManager.updateViewLayout(scrim, lp) }
    }

    private fun ensureFloatingBarOnTop() {
        val bar = rootView ?: return
        val lp = layoutParams ?: return
        if (bar.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(bar) }
            runCatching { windowManager.addView(bar, lp) }
        }
    }

    private fun removeCaptureScrimImmediate() {
        captureScrimView?.let { v ->
            runCatching { windowManager.removeViewImmediate(v) }
                .onFailure { runCatching { windowManager.removeView(v) } }
        }
        captureScrimView = null
        captureScrimParams = null
    }

    private class DraggableOverlayContainer(
        context: Context,
        private val touchSlop: Int,
        private val onFocusChangedSignal: (Boolean) -> Unit,
        private val onDragStart: () -> Unit,
        private val onDragDelta: (Float, Float, Int, Int, Int, Int) -> Unit,
        private val onDragEnd: () -> Unit,
        private val currentWindowX: () -> Int,
        private val currentWindowY: () -> Int
    ) : LinearLayout(context) {

        private var downRawX = 0f
        private var downRawY = 0f
        private var initialWinX = 0
        private var initialWinY = 0
        private var isDragging = false

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            onFocusChangedSignal(hasWindowFocus)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onFocusChangedSignal(true)
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    initialWinX = currentWindowX()
                    initialWinY = currentWindowY()
                    isDragging = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isDragging && hypot(dx, dy) >= touchSlop) {
                        isDragging = true
                        onDragStart()
                        return true
                    }
                    return isDragging
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    onFocusChangedSignal(false)
                    val wasDragging = isDragging
                    isDragging = false
                    return wasDragging
                }
                else -> return isDragging
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onFocusChangedSignal(true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialWinX = currentWindowX()
                    initialWinY = currentWindowY()
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && hypot(dx, dy) >= touchSlop) {
                        isDragging = true
                        onDragStart()
                    }
                    if (isDragging) {
                        onDragDelta(dx, dy, initialWinX, initialWinY, width, height)
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        onDragEnd()
                    } else {
                        onFocusChangedSignal(false)
                    }
                    isDragging = false
                    return true
                }
                else -> return super.onTouchEvent(event)
            }
        }
    }

    private class FrameTouchCaptureView(
        context: Context,
        isSwipe: Boolean,
        continuous: Boolean
    ) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 72)
            val banner = TextView(context).apply {
                text = when {
                    isSwipe -> "Swipe across screen to capture gesture..."
                    continuous -> "Tap or swipe anywhere on screen • Tap ✓ Done when finished"
                    else -> "Tap anywhere on screen..."
                }
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#DD17162E"))
                    cornerRadius = 999f
                    setStroke(2, Color.parseColor("#00E5FF"))
                }
            }
            addView(banner)
        }
    }
}
