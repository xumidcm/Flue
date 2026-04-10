package com.flue.launcher.ui.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.navigation.LayoutMode

@Composable
fun NativeDrawerHost(
    apps: List<AppInfo>,
    layoutMode: LayoutMode,
    blurEnabled: Boolean = true,
    edgeBlurEnabled: Boolean = false,
    suppressHeavyEffects: Boolean = false,
    narrowCols: Int = 4,
    topBlurRadiusDp: Int = 12,
    bottomBlurRadiusDp: Int = 12,
    topFadeRangeDp: Int = 56,
    bottomFadeRangeDp: Int = 56,
    onAppClick: (AppInfo, Offset) -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onScrollToTop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val latestOnAppClick by rememberUpdatedState(onAppClick)
    val latestOnReorder by rememberUpdatedState(onReorder)
    val latestOnScrollToTop by rememberUpdatedState(onScrollToTop)

    var shortcutApp by remember { mutableStateOf<AppInfo?>(null) }
    var displayApps by remember { mutableStateOf(apps) }
    var pendingOrderKeys by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(apps) {
        val incomingKeys = apps.map(AppInfo::componentKey)
        val pendingKeys = pendingOrderKeys
        when {
            pendingKeys != null && incomingKeys == pendingKeys -> {
                displayApps = apps
                pendingOrderKeys = null
            }
            pendingKeys != null && incomingKeys.toSet() == pendingKeys.toSet() && incomingKeys != pendingKeys -> Unit
            else -> {
                displayApps = apps
                pendingOrderKeys = null
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                NativeDrawerView(context).apply {
                    setAppClickListener { app, origin -> latestOnAppClick(app, origin) }
                    setReorderListener { from, to ->
                        val next = displayApps.moveItem(from, to)
                        displayApps = next
                        pendingOrderKeys = next.map(AppInfo::componentKey)
                        latestOnReorder(from, to)
                    }
                    setScrollToTopListener { latestOnScrollToTop() }
                    setShortcutMenuListener { shortcutApp = it }
                }
            },
            update = { view ->
                view.setAppClickListener { app, origin -> latestOnAppClick(app, origin) }
                view.setReorderListener { from, to ->
                    val next = displayApps.moveItem(from, to)
                    displayApps = next
                    pendingOrderKeys = next.map(AppInfo::componentKey)
                    latestOnReorder(from, to)
                }
                view.setScrollToTopListener { latestOnScrollToTop() }
                view.setShortcutMenuListener { shortcutApp = it }
                view.updateConfiguration(
                    layoutMode = layoutMode,
                    blurEnabled = blurEnabled,
                    edgeBlurEnabled = edgeBlurEnabled,
                    suppressHeavyEffects = suppressHeavyEffects,
                    narrowCols = narrowCols,
                    topBlurRadiusDp = topBlurRadiusDp,
                    bottomBlurRadiusDp = bottomBlurRadiusDp,
                    topFadeRangeDp = topFadeRangeDp,
                    bottomFadeRangeDp = bottomFadeRangeDp
                )
                view.submitApps(displayApps)
                view.setShortcutMenuApp(shortcutApp)
            },
            modifier = Modifier.fillMaxSize()
        )

        shortcutApp?.let { app ->
            AppShortcutOverlay(
                app = app,
                blurEnabled = blurEnabled,
                onDismiss = { shortcutApp = null }
            )
        }
    }
}

private fun List<AppInfo>.moveItem(fromIndex: Int, toIndex: Int): List<AppInfo> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    val next = toMutableList()
    val item = next.removeAt(fromIndex)
    next.add(toIndex, item)
    return next
}
