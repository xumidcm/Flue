package com.flue.launcher.ui.navigation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun GestureHost(
    screenState: ScreenState,
    onStateChange: (ScreenState) -> Unit,
    modifier: Modifier = Modifier,
    sideScreenEnabled: Boolean = true,
    notificationCenterEnabled: Boolean = true,
    showControlCenter: Boolean = false,
    screenWidthPx: Float = 1f,
    onSideScreenProgressChange: (Float) -> Unit = {},
    content: @Composable () -> Unit
) {
    var totalDx by remember { mutableFloatStateOf(0f) }
    var totalDy by remember { mutableFloatStateOf(0f) }
    var dragMode by remember { mutableStateOf<DragMode?>(null) }

    Box(
        modifier = modifier.pointerInput(screenState, sideScreenEnabled, notificationCenterEnabled, screenWidthPx) {
            detectDragGestures(
                onDragStart = {
                    totalDx = 0f
                    totalDy = 0f
                    dragMode = null
                },
                onDragCancel = {
                    if (dragMode == DragMode.SideScreen) {
                        val settled = if (screenState == ScreenState.SideScreen) 1f else 0f
                        onSideScreenProgressChange(settled)
                    }
                    dragMode = null
                },
                onDrag = { change, dragAmount ->
                    totalDx += dragAmount.x
                    totalDy += dragAmount.y

                    if (dragMode == null && (abs(totalDx) > 24f || abs(totalDy) > 24f)) {
                        dragMode = when {
                            sideScreenEnabled && (screenState == ScreenState.Face || screenState == ScreenState.SideScreen) && abs(totalDx) >= abs(totalDy) -> DragMode.SideScreen
                            notificationCenterEnabled && screenState == ScreenState.Face && totalDy < 0f && abs(totalDy) > abs(totalDx) -> DragMode.Notifications
                            showControlCenter && screenState == ScreenState.Face && totalDx < 0f && abs(totalDx) >= abs(totalDy) -> DragMode.ControlCenter
                            else -> DragMode.Ignored
                        }
                    }

                    when (dragMode) {
                        DragMode.SideScreen -> {
                            val baseProgress = if (screenState == ScreenState.SideScreen) 1f else 0f
                            val progress = (baseProgress + totalDx / screenWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onSideScreenProgressChange(progress)
                            change.consume()
                        }
                        DragMode.Notifications -> {
                            if (abs(totalDy) > 80f && totalDy < 0f) {
                                onStateChange(ScreenState.Notifications)
                                dragMode = DragMode.Ignored
                                change.consume()
                            }
                        }
                        DragMode.ControlCenter -> {
                            if (abs(totalDx) > 80f && totalDx < 0f) {
                                onStateChange(ScreenState.ControlCenter)
                                dragMode = DragMode.Ignored
                                change.consume()
                            }
                        }
                        DragMode.Ignored, null -> Unit
                    }
                },
                onDragEnd = {
                    when (dragMode) {
                        DragMode.SideScreen -> {
                            val baseProgress = if (screenState == ScreenState.SideScreen) 1f else 0f
                            val progress = (baseProgress + totalDx / screenWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val open = progress >= 0.45f
                            onSideScreenProgressChange(if (open) 1f else 0f)
                            onStateChange(if (open) ScreenState.SideScreen else ScreenState.Face)
                        }
                        DragMode.Notifications -> {
                            if (notificationCenterEnabled && totalDy < -80f) {
                                onStateChange(ScreenState.Notifications)
                            }
                        }
                        else -> Unit
                    }
                    dragMode = null
                }
            )
        }
    ) {
        content()
    }
}

private enum class DragMode {
    SideScreen,
    Notifications,
    ControlCenter,
    Ignored
}
