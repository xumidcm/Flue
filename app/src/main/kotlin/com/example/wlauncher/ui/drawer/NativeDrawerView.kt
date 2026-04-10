package com.flue.launcher.ui.drawer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PointF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.EdgeEffect
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.input.tunedRotaryScrollDelta
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.util.fisheyeScale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val LIST_MENU_TRIGGER_MS = 410L
private const val LIST_MENU_DRAG_START_DP = 20f
private const val LIST_AUTO_SCROLL_EDGE_DP = 84f
private const val LIST_AUTO_SCROLL_MAX_PX = 30f
private const val LIST_EDGE_ITEM_BLUR_DP = 4f
private const val HONEYCOMB_MENU_TRIGGER_MS = 410L
private const val HONEYCOMB_MENU_DRAG_START_DP = 20f
private const val HONEYCOMB_PRESS_DURATION_MS = 200L
private const val HONEYCOMB_AUTO_SCROLL_EDGE_DP = 96f
private const val HONEYCOMB_AUTO_SCROLL_MAX_PX = 26f
private const val ROOT_SHORTCUT_BLUR_DP = 16f

internal class NativeDrawerView(context: Context) : FrameLayout(context) {

    private val recyclerView = RecyclerView(context)
    private val adapter = NativeDrawerAdapter(::handleItemTap)
    private val topFadeView = View(context)
    private val bottomFadeView = View(context)
    private val overlayView = DrawerOverlayView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var appClickListener: ((AppInfo, Offset) -> Unit)? = null
    private var reorderListener: ((Int, Int) -> Unit)? = null
    private var scrollToTopListener: (() -> Unit)? = null
    private var shortcutMenuListener: ((AppInfo?) -> Unit)? = null

    private var layoutMode: LayoutMode = LayoutMode.Honeycomb
    private var blurEnabled = true
    private var edgeBlurEnabled = false
    private var suppressHeavyEffects = false
    private var narrowCols = 4
    private var topBlurRadiusDp = 12
    private var bottomBlurRadiusDp = 12
    private var topFadeRangeDp = 56
    private var bottomFadeRangeDp = 56
    private var shortcutMenuApp: AppInfo? = null
    private var ignoreItemClicks = false
    private var glidePressedPosition: Int? = null
    private var dragState: DragState? = null
    private var settlingKey: String? = null
    private var settlingAnimator: ValueAnimator? = null
    private var pendingVisualRefresh = false

    private val honeycombLayoutManager = HoneycombLayoutManager(narrowCols)
    private val listLayoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downPosition: Int? = null
    private var longPressTriggered = false
    private var longPressOriginX = 0f
    private var longPressOriginY = 0f

    private val longPressRunnable = Runnable {
        val position = downPosition ?: return@Runnable
        val app = adapter.getApp(position) ?: return@Runnable
        longPressTriggered = true
        longPressOriginX = lastX
        longPressOriginY = lastY
        ignoreItemClicks = true
        shortcutMenuApp = app
        shortcutMenuListener?.invoke(app)
        vibrateHaptic(context)
        requestVisualRefresh()
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val state = dragState ?: return
            val edgePx = dp(
                if (layoutMode == LayoutMode.Honeycomb) HONEYCOMB_AUTO_SCROLL_EDGE_DP else LIST_AUTO_SCROLL_EDGE_DP
            ).toFloat()
            val maxStep = if (layoutMode == LayoutMode.Honeycomb) HONEYCOMB_AUTO_SCROLL_MAX_PX else LIST_AUTO_SCROLL_MAX_PX
            val velocity = when {
                state.pointerY < edgePx -> -maxStep * ((edgePx - state.pointerY) / edgePx).coerceIn(0f, 1.15f)
                state.pointerY > height.toFloat() - edgePx -> {
                    maxStep * ((state.pointerY - (height.toFloat() - edgePx)) / edgePx).coerceIn(0f, 1.15f)
                }
                else -> 0f
            }
            if (velocity != 0f) {
                recyclerView.scrollBy(0, velocity.roundToInt())
                updateDragTarget(state.pointerX, state.pointerY)
                requestVisualRefresh()
                mainHandler.postDelayed(this, 16L)
            }
        }
    }

    private val touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> handleActionDown(e)
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.x
                    lastY = e.y
                    if (dragState != null) {
                        updateDragFromMotion(e)
                        return true
                    }
                    val moved = hypot(e.x - downX, e.y - downY)
                    if (!longPressTriggered && moved > touchSlop) {
                        cancelLongPressDetection()
                    }
                    if (longPressTriggered) {
                        val dragThreshold = dp(
                            if (layoutMode == LayoutMode.Honeycomb) HONEYCOMB_MENU_DRAG_START_DP else LIST_MENU_DRAG_START_DP
                        )
                        if (hypot(e.x - longPressOriginX, e.y - longPressOriginY) > dragThreshold) {
                            beginDrag()
                            updateDragFromMotion(e)
                            return true
                        }
                    } else {
                        glidePressedPosition = findInteractivePosition(e.x, e.y)
                        requestVisualRefresh()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragState != null) return true
                    finishPointerSequence()
                }
            }
            return dragState != null
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDragFromMotion(e)
                MotionEvent.ACTION_UP -> finishDrag()
                MotionEvent.ACTION_CANCEL -> cancelDrag()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            cancelLongPressDetection()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false

        recyclerView.layoutManager = honeycombLayoutManager
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        recyclerView.overScrollMode = View.OVER_SCROLL_ALWAYS
        recyclerView.edgeEffectFactory = DrawerEdgeEffectFactory()
        recyclerView.setHasFixedSize(false)
        recyclerView.clipToPadding = false
        recyclerView.clipChildren = false
        recyclerView.addOnItemTouchListener(touchListener)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                requestVisualRefresh()
            }
        })
        recyclerView.setOnGenericMotionListener { _, event -> handleGenericMotion(event) }

        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topFadeView, LayoutParams(LayoutParams.MATCH_PARENT, dp(topFadeRangeDp)).apply { gravity = Gravity.TOP })
        addView(bottomFadeView, LayoutParams(LayoutParams.MATCH_PARENT, dp(bottomFadeRangeDp)).apply { gravity = Gravity.BOTTOM })
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        updateEdgeOverlays()
        requestVisualRefresh()
    }

    fun setAppClickListener(listener: (AppInfo, Offset) -> Unit) {
        appClickListener = listener
    }

    fun setReorderListener(listener: (Int, Int) -> Unit) {
        reorderListener = listener
    }

    fun setScrollToTopListener(listener: () -> Unit) {
        scrollToTopListener = listener
    }

    fun setShortcutMenuListener(listener: (AppInfo?) -> Unit) {
        shortcutMenuListener = listener
    }

    fun submitApps(apps: List<AppInfo>) {
        adapter.submitApps(apps)
        if ((dragState?.fromIndex ?: -1) >= apps.size) {
            dragState = null
        }
        requestVisualRefresh()
    }

    fun updateConfiguration(
        layoutMode: LayoutMode,
        blurEnabled: Boolean,
        edgeBlurEnabled: Boolean,
        suppressHeavyEffects: Boolean,
        narrowCols: Int,
        topBlurRadiusDp: Int,
        bottomBlurRadiusDp: Int,
        topFadeRangeDp: Int,
        bottomFadeRangeDp: Int
    ) {
        this.layoutMode = layoutMode
        this.blurEnabled = blurEnabled
        this.edgeBlurEnabled = edgeBlurEnabled
        this.suppressHeavyEffects = suppressHeavyEffects
        this.narrowCols = narrowCols
        this.topBlurRadiusDp = topBlurRadiusDp
        this.bottomBlurRadiusDp = bottomBlurRadiusDp
        this.topFadeRangeDp = topFadeRangeDp
        this.bottomFadeRangeDp = bottomFadeRangeDp

        adapter.setLayoutMode(layoutMode)
        honeycombLayoutManager.updateConfiguration(narrowCols)
        recyclerView.layoutManager = if (layoutMode == LayoutMode.Honeycomb) honeycombLayoutManager else listLayoutManager
        updateEdgeOverlays()
        requestVisualRefresh()
    }

    fun setShortcutMenuApp(app: AppInfo?) {
        shortcutMenuApp = app
        applyRootBlur()
        requestVisualRefresh()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(autoScrollRunnable)
        settlingAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun handleActionDown(event: MotionEvent) {
        activePointerId = event.getPointerId(0)
        downX = event.x
        downY = event.y
        lastX = event.x
        lastY = event.y
        downPosition = findInteractivePosition(event.x, event.y)
        longPressTriggered = false
        glidePressedPosition = downPosition
        mainHandler.removeCallbacks(longPressRunnable)
        if (downPosition != null) {
            mainHandler.postDelayed(
                longPressRunnable,
                if (layoutMode == LayoutMode.Honeycomb) HONEYCOMB_MENU_TRIGGER_MS else LIST_MENU_TRIGGER_MS
            )
        }
        requestVisualRefresh()
    }

    private fun cancelLongPressDetection() {
        mainHandler.removeCallbacks(longPressRunnable)
        if (!longPressTriggered) {
            glidePressedPosition = null
            requestVisualRefresh()
        }
    }

    private fun finishPointerSequence() {
        mainHandler.removeCallbacks(longPressRunnable)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        glidePressedPosition = null
        longPressTriggered = false
        downPosition = null
        if (ignoreItemClicks) {
            post { ignoreItemClicks = false }
        }
        requestVisualRefresh()
    }

    private fun beginDrag() {
        val fromIndex = downPosition ?: return
        val app = adapter.getApp(fromIndex) ?: return
        shortcutMenuApp = null
        shortcutMenuListener?.invoke(null)
        mainHandler.removeCallbacks(longPressRunnable)
        dragState = DragState(
            app = app,
            fromIndex = fromIndex,
            currentIndex = fromIndex,
            pointerX = lastX,
            pointerY = lastY
        )
        overlayView.show(app, layoutMode, recyclerView.width, currentHoneycombItemSize())
        updateDragTarget(lastX, lastY)
        if (layoutMode == LayoutMode.Honeycomb) {
            honeycombLayoutManager.updateDragState(fromIndex, fromIndex)
        }
        vibrateHaptic(context)
        mainHandler.removeCallbacks(autoScrollRunnable)
        mainHandler.post(autoScrollRunnable)
        requestVisualRefresh()
    }

    private fun updateDragFromMotion(event: MotionEvent) {
        val state = dragState ?: return
        state.pointerX = event.x
        state.pointerY = event.y
        if (!state.hasDragged) {
            val dragThreshold = dp(
                if (layoutMode == LayoutMode.Honeycomb) HONEYCOMB_MENU_DRAG_START_DP else LIST_MENU_DRAG_START_DP
            ) * 0.35f
            if (hypot(event.x - longPressOriginX, event.y - longPressOriginY) > dragThreshold) {
                state.hasDragged = true
            }
        }
        updateDragTarget(event.x, event.y)
        requestVisualRefresh()
    }

    private fun updateDragTarget(pointerX: Float, pointerY: Float) {
        val state = dragState ?: return
        state.currentIndex = when (layoutMode) {
            LayoutMode.Honeycomb -> honeycombLayoutManager.findNearestAdapterPosition(
                pointerX = pointerX,
                pointerY = pointerY
            ) ?: state.currentIndex
            LayoutMode.List -> findNearestListPosition(pointerY) ?: state.currentIndex
        }
        if (layoutMode == LayoutMode.Honeycomb) {
            honeycombLayoutManager.updateDragState(state.fromIndex, state.currentIndex)
        }
    }

    private fun finishDrag() {
        val state = dragState ?: run {
            finishPointerSequence()
            return
        }

        mainHandler.removeCallbacks(autoScrollRunnable)
        dragState = null
        if (layoutMode == LayoutMode.Honeycomb) {
            honeycombLayoutManager.updateDragState(null, null)
        }

        if (state.hasDragged && state.currentIndex != state.fromIndex) {
            val targetCenter = resolveDropTargetCenter(state.currentIndex)
            startSettling(state, targetCenter)
            reorderListener?.invoke(state.fromIndex, state.currentIndex)
        } else {
            overlayView.hide()
            settlingKey = null
        }

        finishPointerSequence()
    }

    private fun cancelDrag() {
        mainHandler.removeCallbacks(autoScrollRunnable)
        dragState = null
        settlingKey = null
        overlayView.hide()
        if (layoutMode == LayoutMode.Honeycomb) {
            honeycombLayoutManager.updateDragState(null, null)
        }
        finishPointerSequence()
    }

    private fun handleItemTap(position: Int, itemView: View) {
        if (ignoreItemClicks || dragState != null || shortcutMenuApp != null) return
        val app = adapter.getApp(position) ?: return
        val centerX = itemView.x + itemView.width / 2f
        val centerY = itemView.y + itemView.height / 2f
        val origin = Offset(
            (centerX / width).coerceIn(0f, 1f),
            (centerY / height).coerceIn(0f, 1f)
        )
        appClickListener?.invoke(app, origin)
    }

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val axis = when {
            event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER) ->
                event.getAxisValue(MotionEvent.AXIS_SCROLL)
            abs(event.getAxisValue(MotionEvent.AXIS_VSCROLL)) > 0f ->
                event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            else -> event.getAxisValue(MotionEvent.AXIS_SCROLL)
        }
        if (axis == 0f) return false
        val tuned = tunedRotaryScrollDelta(
            verticalScrollPixels = axis,
            multiplier = if (layoutMode == LayoutMode.Honeycomb) 0.9f else 0.95f
        )
        val dy = if (layoutMode == LayoutMode.Honeycomb) tuned.roundToInt() else (-tuned).roundToInt()
        recyclerView.scrollBy(0, dy)
        return true
    }

    private fun resolveDropTargetCenter(index: Int): PointF {
        if (layoutMode == LayoutMode.Honeycomb) {
            return honeycombLayoutManager.getSlotCenter(index, useVisualSlot = false)
                ?: PointF(width / 2f, height / 2f)
        }
        val child = recyclerView.findViewHolderForAdapterPosition(index)?.itemView
        return if (child != null) {
            PointF(child.x + child.width / 2f, child.y + child.height / 2f)
        } else {
            PointF(width / 2f, height / 2f)
        }
    }

    private fun startSettling(state: DragState, targetCenter: PointF) {
        settlingAnimator?.cancel()
        settlingKey = state.app.componentKey
        val startCenter = PointF(
            state.pointerX,
            state.pointerY.coerceIn(
                overlayView.overlayHalfHeight,
                height.toFloat() - overlayView.overlayHalfHeight
            )
        )
        overlayView.show(state.app, layoutMode, recyclerView.width, currentHoneycombItemSize())
        val duration = if (layoutMode == LayoutMode.Honeycomb) 170L else 190L
        settlingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = DecelerateInterpolator()
            setDuration(duration)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val currentX = lerp(startCenter.x, targetCenter.x, progress)
                val currentY = lerp(startCenter.y, targetCenter.y, progress)
                drawOverlayAt(state.app, currentX, currentY, settling = true)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settlingKey = null
                    overlayView.hide()
                    requestVisualRefresh()
                }
            })
            start()
        }
    }

    private fun findInteractivePosition(x: Float, y: Float): Int? {
        return when (layoutMode) {
            LayoutMode.Honeycomb -> honeycombLayoutManager.findNearestAdapterPosition(
                pointerX = x,
                pointerY = y,
                maxDistance = max(honeycombLayoutManager.currentItemSizePx * 0.7f, dp(24f).toFloat())
            )
            LayoutMode.List -> {
                val child = recyclerView.findChildViewUnder(x, y)
                child?.let { recyclerView.getChildAdapterPosition(it) }?.takeIf { it != RecyclerView.NO_POSITION }
            }
        }
    }

    private fun findNearestListPosition(pointerY: Float): Int? {
        var bestPosition: Int? = null
        var bestDistance = Float.MAX_VALUE
        recyclerView.children.forEach { child ->
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return@forEach
            val centerY = child.y + child.height / 2f
            val distance = abs(centerY - pointerY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestPosition = position
            }
        }
        return bestPosition
    }

    private fun requestVisualRefresh() {
        if (pendingVisualRefresh) return
        pendingVisualRefresh = true
        recyclerView.post {
            pendingVisualRefresh = false
            applyVisuals()
        }
    }

    private fun applyVisuals() {
        applyRootBlur()
        when (layoutMode) {
            LayoutMode.Honeycomb -> applyHoneycombVisuals()
            LayoutMode.List -> applyListVisuals()
        }
        dragState?.let { drawOverlayAt(it.app, it.pointerX, it.pointerY, settling = false) }
    }

    private fun applyHoneycombVisuals() {
        val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects
        val pressedAnchor = shortcutMenuApp?.let { app ->
            val index = adapter.indexOf(app.componentKey)
            if (index >= 0) honeycombLayoutManager.getSlotCenter(index) else null
        }
        recyclerView.children.forEach { child ->
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return@forEach
            val holder = recyclerView.getChildViewHolder(child) as? HoneycombViewHolder ?: return@forEach
            val app = adapter.getApp(position) ?: return@forEach
            val center = honeycombLayoutManager.getSlotCenter(position) ?: return@forEach
            val motion = neighborPressMotion(
                current = center,
                pressedAnchor = pressedAnchor,
                iconSizePx = honeycombLayoutManager.currentItemSizePx.toFloat(),
                cellSize = honeycombLayoutManager.currentCellSizePx
            )
            val actualCenterX = center.x + motion.shiftX
            val actualCenterY = center.y + motion.shiftY
            val dx = actualCenterX - honeycombLayoutManager.screenCenterX
            val dy = actualCenterY - honeycombLayoutManager.screenCenterY
            val scale = fisheyeScale(
                distance = hypot(dx, dy),
                maxDistance = honeycombLayoutManager.screenRadius * 1.65f,
                minScale = 0.58f
            ) * (1f - motion.scaleReduction)
            val itemBlur = computeHoneycombEdgeBlur(
                centerY = actualCenterY,
                screenHeight = height.toFloat(),
                topBlurZonePx = dp(topFadeRangeDp).toFloat(),
                bottomBlurZonePx = dp(bottomFadeRangeDp).toFloat(),
                topBlurDp = topBlurRadiusDp.toFloat(),
                bottomBlurDp = bottomBlurRadiusDp.toFloat()
            )
            val displayBitmap = if (
                blurEnabled && effectiveEdgeBlur && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && itemBlur > 0.5f
            ) app.cachedBlurredIcon else app.cachedIcon
            val isDragged = dragState?.fromIndex == position
            val isSettling = settlingKey == app.componentKey
            child.translationX = motion.shiftX
            child.translationY = motion.shiftY
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = when {
                isDragged || isSettling -> 0f
                else -> scale.coerceIn(0.24f, 1f)
            }
            holder.view.setDisplayBitmap(displayBitmap)
            holder.view.setForcePressed(
                pressed = glidePressedPosition == position || shortcutMenuApp?.componentKey == app.componentKey,
                targetScale = 0.9f,
                overlayAlpha = 0.16f,
                durationMs = HONEYCOMB_PRESS_DURATION_MS
            )
            holder.view.setRenderBlur(itemBlur, blurEnabled && effectiveEdgeBlur)
        }
    }

    private fun applyListVisuals() {
        val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects
        val screenCenterY = height / 2f
        val draggedHeight = recyclerView.findViewHolderForAdapterPosition(dragState?.fromIndex ?: -1)
            ?.itemView?.height?.toFloat()
            ?: dp(68f).toFloat()
        recyclerView.children.forEach { child ->
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return@forEach
            val holder = recyclerView.getChildViewHolder(child) as? ListViewHolder ?: return@forEach
            val app = adapter.getApp(position) ?: return@forEach
            val centerY = child.y + child.height / 2f
            val itemScale = computeListItemScale(centerY, screenCenterY.toFloat(), height.toFloat())
            val itemBlur = computeListEdgeBlur(
                centerY = centerY,
                screenHeight = height.toFloat(),
                topBlurZonePx = dp(topFadeRangeDp).toFloat(),
                bottomBlurZonePx = dp(bottomFadeRangeDp).toFloat(),
                maxBlurDp = LIST_EDGE_ITEM_BLUR_DP
            )
            val isDragged = dragState?.fromIndex == position
            val isSettling = settlingKey == app.componentKey
            child.translationY = if (isDragged) 0f else listDisplacementForIndex(
                index = position,
                dragFromIndex = dragState?.fromIndex,
                dragCurrentIndex = dragState?.currentIndex,
                dragRowShift = draggedHeight
            )
            child.scaleX = itemScale
            child.scaleY = itemScale
            child.alpha = if (isDragged || isSettling) 0f else itemScale.coerceIn(0.3f, 1f)
            holder.view.setDisplayBitmap(
                if (
                    blurEnabled && effectiveEdgeBlur && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && itemBlur > 0.5f
                ) app.cachedBlurredIcon else app.cachedIcon
            )
            holder.view.setForcePressed(
                pressed = glidePressedPosition == position || shortcutMenuApp?.componentKey == app.componentKey,
                targetScale = 0.992f,
                overlayAlpha = 0.08f,
                durationMs = 170L
            )
            holder.view.setRenderBlur(itemBlur, blurEnabled && effectiveEdgeBlur)
        }
    }

    private fun drawOverlayAt(app: AppInfo, pointerX: Float, pointerY: Float, settling: Boolean) {
        val effectiveEdgeBlur = edgeBlurEnabled && !suppressHeavyEffects
        when (layoutMode) {
            LayoutMode.Honeycomb -> {
                val clamped = clampHoneycombPointer(pointerX, pointerY, currentHoneycombItemSize().toFloat())
                val itemBlur = computeHoneycombEdgeBlur(
                    centerY = clamped.y,
                    screenHeight = height.toFloat(),
                    topBlurZonePx = dp(topFadeRangeDp).toFloat(),
                    bottomBlurZonePx = dp(bottomFadeRangeDp).toFloat(),
                    topBlurDp = topBlurRadiusDp.toFloat(),
                    bottomBlurDp = bottomBlurRadiusDp.toFloat()
                )
                val dx = clamped.x - honeycombLayoutManager.screenCenterX
                val dy = clamped.y - honeycombLayoutManager.screenCenterY
                val scale = fisheyeScale(
                    distance = hypot(dx, dy),
                    maxDistance = honeycombLayoutManager.screenRadius * 1.65f,
                    minScale = 0.58f
                )
                overlayView.show(app, layoutMode, recyclerView.width, currentHoneycombItemSize())
                overlayView.update(
                    app = app,
                    mode = layoutMode,
                    bitmap = if (blurEnabled && effectiveEdgeBlur && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && itemBlur > 0.5f) app.cachedBlurredIcon else app.cachedIcon,
                    centerX = clamped.x,
                    centerY = clamped.y,
                    scale = scale,
                    alpha = scale.coerceIn(0.24f, 1f),
                    blurRadiusDp = itemBlur,
                    blurEnabled = blurEnabled && effectiveEdgeBlur,
                    settling = settling
                )
            }
            LayoutMode.List -> {
                val centerY = pointerY.coerceIn(
                    overlayView.overlayHalfHeight,
                    height.toFloat() - overlayView.overlayHalfHeight
                )
                val itemBlur = computeListEdgeBlur(
                    centerY = centerY,
                    screenHeight = height.toFloat(),
                    topBlurZonePx = dp(topFadeRangeDp).toFloat(),
                    bottomBlurZonePx = dp(bottomFadeRangeDp).toFloat(),
                    maxBlurDp = LIST_EDGE_ITEM_BLUR_DP
                )
                overlayView.show(app, layoutMode, recyclerView.width, currentHoneycombItemSize())
                overlayView.update(
                    app = app,
                    mode = layoutMode,
                    bitmap = if (blurEnabled && effectiveEdgeBlur && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && itemBlur > 0.5f) app.cachedBlurredIcon else app.cachedIcon,
                    centerX = width / 2f,
                    centerY = centerY,
                    scale = 0.965f,
                    alpha = 0.98f,
                    blurRadiusDp = itemBlur,
                    blurEnabled = blurEnabled && effectiveEdgeBlur,
                    settling = settling
                )
            }
        }
    }

    private fun applyRootBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            recyclerView.setRenderEffect(
                if (shortcutMenuApp != null && blurEnabled && !suppressHeavyEffects) {
                    RenderEffect.createBlurEffect(
                        dp(ROOT_SHORTCUT_BLUR_DP).toFloat(),
                        dp(ROOT_SHORTCUT_BLUR_DP).toFloat(),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    null
                }
            )
        }
    }

    private fun updateEdgeOverlays() {
        topFadeView.layoutParams = (topFadeView.layoutParams as LayoutParams).apply {
            height = dp(topFadeRangeDp)
            gravity = Gravity.TOP
        }
        bottomFadeView.layoutParams = (bottomFadeView.layoutParams as LayoutParams).apply {
            height = dp(bottomFadeRangeDp)
            gravity = Gravity.BOTTOM
        }
        topFadeView.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.BLACK, Color.TRANSPARENT)
        )
        bottomFadeView.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.BLACK)
        )
        applyEdgeBlurToOverlay(
            topFadeView,
            if (layoutMode == LayoutMode.Honeycomb) topBlurRadiusDp.toFloat() else LIST_EDGE_ITEM_BLUR_DP
        )
        applyEdgeBlurToOverlay(
            bottomFadeView,
            if (layoutMode == LayoutMode.Honeycomb) bottomBlurRadiusDp.toFloat() else LIST_EDGE_ITEM_BLUR_DP
        )
    }

    private fun applyEdgeBlurToOverlay(view: View, radiusDp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                if (blurEnabled && edgeBlurEnabled && !suppressHeavyEffects && radiusDp > 0.5f) {
                    RenderEffect.createBlurEffect(
                        dp(radiusDp).toFloat(),
                        dp(radiusDp).toFloat(),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    null
                }
            )
        }
    }

    private fun currentHoneycombItemSize(): Int {
        return if (layoutMode == LayoutMode.Honeycomb && honeycombLayoutManager.currentItemSizePx > 0) {
            honeycombLayoutManager.currentItemSizePx
        } else {
            dp(52f)
        }
    }

    private fun clampHoneycombPointer(pointerX: Float, pointerY: Float, iconSizePx: Float): PointF {
        val horizontalOverflow = iconSizePx * 0.3f
        val verticalOverflow = iconSizePx * 1.15f
        return PointF(
            pointerX.coerceIn(-horizontalOverflow, width.toFloat() + horizontalOverflow),
            pointerY.coerceIn(-verticalOverflow, height.toFloat() + verticalOverflow)
        )
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    private inner class DrawerEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {
        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
            return object : EdgeEffect(context) {
                override fun onPull(deltaDistance: Float) {
                    handlePull(direction, deltaDistance)
                }

                override fun onPull(deltaDistance: Float, displacement: Float) {
                    handlePull(direction, deltaDistance)
                }

                override fun onRelease() {
                    if (direction == DIRECTION_TOP && recyclerView.translationY > dp(32f).toFloat()) {
                        scrollToTopListener?.invoke()
                    }
                    recyclerView.animate().translationY(0f).setDuration(180L).start()
                }

                override fun onAbsorb(velocity: Int) {
                    if (direction == DIRECTION_TOP && !recyclerView.canScrollVertically(-1) && velocity > 800) {
                        scrollToTopListener?.invoke()
                    }
                    recyclerView.animate().translationY(0f).setDuration(180L).start()
                }

                override fun draw(canvas: android.graphics.Canvas): Boolean = false

                override fun isFinished(): Boolean = true

                private fun handlePull(direction: Int, deltaDistance: Float) {
                    val delta = deltaDistance * height * 0.35f
                    recyclerView.translationY = when (direction) {
                        DIRECTION_TOP -> (recyclerView.translationY + delta).coerceAtMost(dp(140f).toFloat())
                        DIRECTION_BOTTOM -> (recyclerView.translationY - delta).coerceAtLeast(-dp(140f).toFloat())
                        else -> recyclerView.translationY
                    }
                }
            }
        }
    }

}

private class NativeDrawerAdapter(
    private val onItemTap: (Int, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var layoutMode: LayoutMode = LayoutMode.Honeycomb
    private var apps: List<AppInfo> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun setLayoutMode(layoutMode: LayoutMode) {
        if (this.layoutMode == layoutMode) return
        this.layoutMode = layoutMode
        notifyDataSetChanged()
    }

    fun submitApps(apps: List<AppInfo>) {
        this.apps = apps
        notifyDataSetChanged()
    }

    fun getApp(position: Int): AppInfo? = apps.getOrNull(position)

    fun indexOf(componentKey: String): Int = apps.indexOfFirst { it.componentKey == componentKey }

    override fun getItemCount(): Int = apps.size

    override fun getItemId(position: Int): Long = apps[position].componentKey.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = if (layoutMode == LayoutMode.Honeycomb) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            HoneycombViewHolder(NativeBubbleItemView(parent.context))
        } else {
            ListViewHolder(NativeListItemView(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val app = apps[position]
        when (holder) {
            is HoneycombViewHolder -> holder.view.bind(app)
            is ListViewHolder -> holder.view.bind(app)
        }
        holder.itemView.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                onItemTap(adapterPosition, holder.itemView)
            }
        }
    }
}

private class HoneycombViewHolder(val view: NativeBubbleItemView) : RecyclerView.ViewHolder(view)

private class ListViewHolder(val view: NativeListItemView) : RecyclerView.ViewHolder(view)

private class NativeBubbleItemView(context: Context) : FrameLayout(context) {
    private val imageView = ImageView(context)
    private val overlayView = View(context)
    private val contentView = FrameLayout(context)
    private var shownBitmap: android.graphics.Bitmap? = null

    init {
        clipChildren = false
        clipToPadding = false
        contentView.clipToOutline = true
        contentView.outlineProvider = CircleOutlineProvider
        addView(contentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.clipToOutline = true
        imageView.outlineProvider = CircleOutlineProvider
        contentView.addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overlayView.setBackgroundColor(Color.BLACK)
        overlayView.alpha = 0f
        contentView.addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(app: AppInfo) {
        setDisplayBitmap(app.cachedIcon)
    }

    fun setDisplayBitmap(bitmap: android.graphics.Bitmap) {
        if (shownBitmap === bitmap) return
        shownBitmap = bitmap
        imageView.setImageBitmap(bitmap)
    }

    fun setForcePressed(pressed: Boolean, targetScale: Float, overlayAlpha: Float, durationMs: Long) {
        contentView.animate().cancel()
        overlayView.animate().cancel()
        contentView.animate().scaleX(if (pressed) targetScale else 1f).scaleY(if (pressed) targetScale else 1f).setDuration(durationMs).start()
        overlayView.animate().alpha(if (pressed) overlayAlpha else 0f).setDuration(durationMs).start()
    }

    fun setRenderBlur(radiusDp: Float, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            contentView.setRenderEffect(
                if (enabled && radiusDp > 0.5f) {
                    RenderEffect.createBlurEffect(
                        radiusDp * resources.displayMetrics.density,
                        radiusDp * resources.displayMetrics.density,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    null
                }
            )
        }
    }
}

private class NativeListItemView(context: Context) : FrameLayout(context) {
    private val row = LinearLayout(context)
    private val iconContainer = FrameLayout(context)
    private val imageView = ImageView(context)
    private val titleView = TextView(context)
    private val overlayView = View(context)
    private var shownBitmap: android.graphics.Bitmap? = null

    init {
        setPadding(dp(context, 16f), dp(context, 10f), dp(context, 16f), dp(context, 10f))
        clipChildren = false
        clipToPadding = false
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(context, 18f).toFloat()
            setColor(ColorUtils.setAlphaComponent(Color.BLACK, 20))
        }

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val iconSize = dp(context, 48f)
        iconContainer.outlineProvider = CircleOutlineProvider
        iconContainer.clipToOutline = true
        row.addView(iconContainer, LinearLayout.LayoutParams(iconSize, iconSize))

        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.outlineProvider = CircleOutlineProvider
        imageView.clipToOutline = true
        iconContainer.addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        row.addView(
            titleView,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(context, 14f)
            }
        )

        overlayView.setBackgroundColor(Color.BLACK)
        overlayView.alpha = 0f
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(app: AppInfo) {
        titleView.text = app.label
        setDisplayBitmap(app.cachedIcon)
    }

    fun setDisplayBitmap(bitmap: android.graphics.Bitmap) {
        if (shownBitmap === bitmap) return
        shownBitmap = bitmap
        imageView.setImageBitmap(bitmap)
    }

    fun setForcePressed(pressed: Boolean, targetScale: Float, overlayAlpha: Float, durationMs: Long) {
        row.animate().cancel()
        overlayView.animate().cancel()
        row.animate().scaleX(if (pressed) targetScale else 1f).scaleY(if (pressed) targetScale else 1f).setDuration(durationMs).start()
        overlayView.animate().alpha(if (pressed) overlayAlpha else 0f).setDuration(durationMs).start()
    }

    fun setRenderBlur(radiusDp: Float, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                if (enabled && radiusDp > 0.5f) {
                    RenderEffect.createBlurEffect(
                        radiusDp * resources.displayMetrics.density,
                        radiusDp * resources.displayMetrics.density,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    null
                }
            )
        }
    }
}

private class DrawerOverlayView(context: Context) : FrameLayout(context) {
    private val bubbleView = NativeBubbleItemView(context)
    private val listView = NativeListItemView(context)
    var overlayHalfHeight: Float = 0f
        private set

    init {
        isClickable = false
        visibility = View.GONE
        addView(bubbleView, LayoutParams(dp(context, 56f), dp(context, 56f)))
        addView(listView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(context, 28f)
            rightMargin = dp(context, 28f)
        })
    }

    fun show(app: AppInfo, mode: LayoutMode, parentWidth: Int, honeycombItemSizePx: Int) {
        visibility = View.VISIBLE
        bubbleView.visibility = if (mode == LayoutMode.Honeycomb) View.VISIBLE else View.GONE
        listView.visibility = if (mode == LayoutMode.List) View.VISIBLE else View.GONE
        if (mode == LayoutMode.Honeycomb) {
            bubbleView.bind(app)
            bubbleView.layoutParams = (bubbleView.layoutParams as LayoutParams).apply {
                width = honeycombItemSizePx
                height = honeycombItemSizePx
            }
            overlayHalfHeight = honeycombItemSizePx / 2f
        } else {
            listView.bind(app)
            listView.layoutParams = (listView.layoutParams as LayoutParams).apply {
                width = max(parentWidth - dp(context, 56f), dp(context, 180f))
            }
            listView.measure(
                View.MeasureSpec.makeMeasureSpec(max(parentWidth - dp(context, 56f), dp(context, 180f)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            overlayHalfHeight = listView.measuredHeight / 2f
        }
    }

    fun update(
        app: AppInfo,
        mode: LayoutMode,
        bitmap: android.graphics.Bitmap,
        centerX: Float,
        centerY: Float,
        scale: Float,
        alpha: Float,
        blurRadiusDp: Float,
        blurEnabled: Boolean,
        settling: Boolean
    ) {
        visibility = View.VISIBLE
        if (mode == LayoutMode.Honeycomb) {
            bubbleView.visibility = View.VISIBLE
            listView.visibility = View.GONE
            bubbleView.setDisplayBitmap(bitmap)
            bubbleView.setRenderBlur(blurRadiusDp, blurEnabled)
            bubbleView.translationX = centerX - bubbleView.layoutParams.width / 2f
            bubbleView.translationY = centerY - bubbleView.layoutParams.height / 2f
            bubbleView.scaleX = scale
            bubbleView.scaleY = scale
            bubbleView.alpha = alpha
            if (!settling) bubbleView.setForcePressed(false, 1f, 0f, 0L)
        } else {
            listView.visibility = View.VISIBLE
            bubbleView.visibility = View.GONE
            listView.setDisplayBitmap(bitmap)
            listView.setRenderBlur(blurRadiusDp, blurEnabled)
            listView.translationX = centerX - (listView.layoutParams.width / 2f)
            listView.translationY = centerY - overlayHalfHeight
            listView.scaleX = scale
            listView.scaleY = scale
            listView.alpha = alpha
            if (!settling) listView.setForcePressed(false, 1f, 0f, 0L)
        }
    }

    fun hide() {
        visibility = View.GONE
        bubbleView.alpha = 1f
        listView.alpha = 1f
    }
}

private object CircleOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

private data class DragState(
    val app: AppInfo,
    val fromIndex: Int,
    var currentIndex: Int,
    var pointerX: Float,
    var pointerY: Float,
    var hasDragged: Boolean = false
)

private data class NeighborMotion(
    val scaleReduction: Float = 0f,
    val shiftX: Float = 0f,
    val shiftY: Float = 0f
)

private fun neighborPressMotion(
    current: PointF,
    pressedAnchor: PointF?,
    iconSizePx: Float,
    cellSize: Float
): NeighborMotion {
    if (pressedAnchor == null) return NeighborMotion()
    val dx = pressedAnchor.x - current.x
    val dy = pressedAnchor.y - current.y
    val distance = hypot(dx, dy)
    if (distance <= 0.001f) return NeighborMotion()
    val progress = (1f - distance / (cellSize * 1.9f)).coerceIn(0f, 1f)
    if (progress <= 0f) return NeighborMotion()
    val pullDistance = iconSizePx * 0.18f * progress
    val sinkDistance = iconSizePx * 0.11f * progress
    return NeighborMotion(
        scaleReduction = 0.08f * progress,
        shiftX = dx / distance * pullDistance,
        shiftY = dy / distance * pullDistance + sinkDistance
    )
}

private fun computeHoneycombEdgeBlur(
    centerY: Float,
    screenHeight: Float,
    topBlurZonePx: Float,
    bottomBlurZonePx: Float,
    topBlurDp: Float,
    bottomBlurDp: Float
): Float {
    val topStrength = if (topBlurZonePx <= 0f) 0f else (1f - (centerY / topBlurZonePx)).coerceIn(0f, 1f)
    val bottomStrength = if (bottomBlurZonePx <= 0f) 0f else (1f - ((screenHeight - centerY) / bottomBlurZonePx)).coerceIn(0f, 1f)
    return max(topStrength * topBlurDp, bottomStrength * bottomBlurDp)
}

private fun computeListItemScale(centerY: Float, screenCenterY: Float, screenHeight: Float): Float {
    val t = (abs(centerY - screenCenterY) / (screenHeight / 2f)).coerceIn(0f, 1f)
    return 1f - 0.2f * t
}

private fun computeListEdgeBlur(
    centerY: Float,
    screenHeight: Float,
    topBlurZonePx: Float,
    bottomBlurZonePx: Float,
    maxBlurDp: Float
): Float {
    val topStrength = if (topBlurZonePx <= 0f) 0f else (1f - (centerY / topBlurZonePx)).coerceIn(0f, 1f)
    val bottomStrength = if (bottomBlurZonePx <= 0f) 0f else (1f - ((screenHeight - centerY) / bottomBlurZonePx)).coerceIn(0f, 1f)
    return max(topStrength, bottomStrength) * maxBlurDp
}

private fun listDisplacementForIndex(
    index: Int,
    dragFromIndex: Int?,
    dragCurrentIndex: Int?,
    dragRowShift: Float
): Float {
    if (dragFromIndex == null || dragCurrentIndex == null || dragFromIndex == dragCurrentIndex) return 0f
    return when {
        dragCurrentIndex > dragFromIndex && index in (dragFromIndex + 1)..dragCurrentIndex -> -dragRowShift
        dragCurrentIndex < dragFromIndex && index in dragCurrentIndex until dragFromIndex -> dragRowShift
        else -> 0f
    }
}

private fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress
