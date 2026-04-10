package com.flue.launcher.ui.drawer

import android.content.res.Resources
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.util.generateHoneycombRows
import kotlin.math.abs
import kotlin.math.roundToInt

internal class HoneycombLayoutManager(
    private var narrowCols: Int = 4
) : RecyclerView.LayoutManager() {

    private var scrollOffsetPx = 0f
    private var positions: List<Offset> = emptyList()
    private var itemSizePx = 0
    private var cellSizePx = 0f
    private var minScrollPx = 0f
    private var maxScrollPx = 0f
    private var dragFromIndex: Int? = null
    private var dragCurrentIndex: Int? = null

    val currentScrollOffset: Float
        get() = scrollOffsetPx

    val currentItemSizePx: Int
        get() = itemSizePx

    val currentCellSizePx: Float
        get() = cellSizePx

    val screenCenterX: Float
        get() = paddingLeft + horizontalSpace / 2f

    val screenCenterY: Float
        get() = paddingTop + verticalSpace / 2f

    val screenRadius: Float
        get() = minOf(horizontalSpace, verticalSpace) / 2f

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun canScrollVertically(): Boolean = true

    override fun isAutoMeasureEnabled(): Boolean = true

    fun updateConfiguration(narrowCols: Int) {
        if (this.narrowCols == narrowCols) return
        this.narrowCols = narrowCols
        requestLayout()
    }

    fun updateDragState(fromIndex: Int?, currentIndex: Int?) {
        if (dragFromIndex == fromIndex && dragCurrentIndex == currentIndex) return
        dragFromIndex = fromIndex
        dragCurrentIndex = currentIndex
        requestLayout()
    }

    fun isNearTop(thresholdPx: Float = itemSizePx * 0.45f): Boolean {
        return abs(scrollOffsetPx - maxScrollPx) <= thresholdPx
    }

    fun getSlotCenter(position: Int, useVisualSlot: Boolean = true): PointF? {
        val slot = positions.getOrNull(
            if (useVisualSlot) visualSlotIndex(position, dragFromIndex, dragCurrentIndex) else position
        ) ?: return null
        return PointF(
            screenCenterX + slot.x,
            screenCenterY + slot.y + scrollOffsetPx
        )
    }

    fun findNearestAdapterPosition(pointerX: Float, pointerY: Float, maxDistance: Float = cellSizePx * 0.95f): Int? {
        if (positions.isEmpty()) return null
        val maxDistanceSq = maxDistance * maxDistance
        var bestIndex: Int? = null
        var bestDistanceSq = Float.MAX_VALUE
        for (index in positions.indices) {
            val center = getSlotCenter(index, useVisualSlot = false) ?: continue
            val dx = pointerX - center.x
            val dy = pointerY - center.y
            val distanceSq = dx * dx + dy * dy
            if (distanceSq <= maxDistanceSq && distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                bestIndex = index
            }
        }
        return bestIndex
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || state.isPreLayout) {
            scrollOffsetPx = 0f
            positions = emptyList()
            return
        }

        updateGridGeometry()
        scrollOffsetPx = scrollOffsetPx.coerceIn(minScrollPx, maxScrollPx)

        val visibleTop = -itemSizePx * 2f
        val visibleBottom = verticalSpace + itemSizePx * 2f

        for (position in 0 until itemCount) {
            val center = getSlotCenter(position) ?: continue
            if (center.y < visibleTop || center.y > visibleBottom) continue

            val child = recycler.getViewForPosition(position)
            addView(child)
            measureChildExactly(child)

            val left = (center.x - itemSizePx / 2f).roundToInt()
            val top = (center.y - itemSizePx / 2f).roundToInt()
            layoutDecoratedWithMargins(child, left, top, left + itemSizePx, top + itemSizePx)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0) return 0
        val previous = scrollOffsetPx
        scrollOffsetPx = (scrollOffsetPx - dy).coerceIn(minScrollPx, maxScrollPx)
        val consumed = (previous - scrollOffsetPx).roundToInt()
        if (consumed != 0) {
            onLayoutChildren(recycler, state)
        }
        return consumed
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
        return ((scrollOffsetPx - minScrollPx).coerceAtLeast(0f)).roundToInt()
    }

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
        return (maxScrollPx - minScrollPx + verticalSpace).roundToInt().coerceAtLeast(verticalSpace)
    }

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int = verticalSpace

    private fun updateGridGeometry() {
        val maxCols = narrowCols + 1
        val availableWidth = horizontalSpace - dpToPx(20f)
        itemSizePx = (availableWidth / (maxCols + 0.35f)).roundToInt()
            .coerceIn(dpToPx(54f), dpToPx(84f))
        cellSizePx = itemSizePx * 1.02f
        positions = generateHoneycombRows(itemCount, narrowCols, cellSizePx)

        val minGridY = positions.minOfOrNull(Offset::y) ?: 0f
        val maxGridY = positions.maxOfOrNull(Offset::y) ?: 0f
        maxScrollPx = -minGridY
        minScrollPx = -maxGridY
    }

    private fun measureChildExactly(child: View) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(itemSizePx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(itemSizePx, View.MeasureSpec.EXACTLY)
        child.measure(widthSpec, heightSpec)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * Resources.getSystem().displayMetrics.density).roundToInt()
    }

    private val horizontalSpace: Int
        get() = width - paddingLeft - paddingRight

    private val verticalSpace: Int
        get() = height - paddingTop - paddingBottom
}

private fun visualSlotIndex(index: Int, dragFromIndex: Int?, dragCurrentIndex: Int?): Int {
    if (dragFromIndex == null || dragCurrentIndex == null || dragFromIndex == dragCurrentIndex) return index
    if (index == dragFromIndex) return dragFromIndex
    return when {
        dragCurrentIndex > dragFromIndex && index in (dragFromIndex + 1)..dragCurrentIndex -> index - 1
        dragCurrentIndex < dragFromIndex && index in dragCurrentIndex until dragFromIndex -> index + 1
        else -> index
    }
}
