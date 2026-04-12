package com.flue.launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flue.launcher.ui.anim.appListLayerValues
import com.flue.launcher.ui.anim.faceLayerValues
import com.flue.launcher.ui.anim.notificationLayerValues
import com.flue.launcher.ui.anim.scaleBlurAlpha
import com.flue.launcher.ui.anim.sideScreenLayerValues
import com.flue.launcher.ui.drawer.HoneycombScreen
import com.flue.launcher.ui.drawer.ListDrawerScreen
import com.flue.launcher.ui.home.WatchFaceLayer
import com.flue.launcher.ui.navigation.GestureHost
import com.flue.launcher.ui.navigation.LayoutMode
import com.flue.launcher.ui.navigation.ScreenState
import com.flue.launcher.ui.notification.NotificationLayer
import com.flue.launcher.ui.sidescreen.SideScreenLayer
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import com.flue.launcher.watchface.BuiltInWatchFaceOptions
import com.flue.launcher.watchface.LunchWatchFaceHost
import kotlinx.coroutines.delay

private const val BASE_LAUNCH_MASK_DELAY_MS = 180L

class LauncherActivity : ComponentActivity() {

    private lateinit var vm: LauncherViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler(applicationContext).install()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        onBackPressedDispatcher.addCallback(this) { vm.handleBackPress() }
        setContent {
            WatchLauncherTheme {
                val viewModel: LauncherViewModel = viewModel()
                vm = viewModel
                LauncherScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (::vm.isInitialized) vm.handleHomePress()
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized && vm.animationOverrideEnabled.value) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, R.anim.launcher_return_cupertino_exit)
        }
        if (::vm.isInitialized) {
            vm.onReturnToLauncher()
            vm.refreshWatchFaces()
            vm.refreshNotificationAccess()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::vm.isInitialized && vm.animationOverrideEnabled.value) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}

@Composable
fun LauncherScreen(vm: LauncherViewModel) {
    val context = LocalContext.current
    val screenState by vm.screenState.collectAsState()
    val layoutMode by vm.layoutMode.collectAsState()
    val sideScreenEnabled by vm.sideScreenEnabled.collectAsState()
    val notificationCenterEnabled by vm.notificationCenterEnabled.collectAsState()
    val sideScreenShortcutKeys by vm.sideScreenShortcutKeys.collectAsState()
    val notificationAccessGranted by vm.notificationAccessGranted.collectAsState()
    val notificationGroups by vm.notificationGroups.collectAsState()
    val revealedNotificationTarget by vm.revealedNotificationTarget.collectAsState()
    val blurEnabled by vm.blurEnabled.collectAsState()
    val edgeBlurEnabled by vm.edgeBlurEnabled.collectAsState()
    val apps by vm.apps.collectAsState()
    val allApps by vm.allApps.collectAsState()
    val appOpenOrigin by vm.appOpenOrigin.collectAsState()
    val splashIcon by vm.splashIcon.collectAsState()
    val splashDelay by vm.splashDelay.collectAsState()
    val currentApp by vm.currentApp.collectAsState()
    val launchSourceState by vm.launchSourceState.collectAsState()
    val honeycombCols by vm.honeycombCols.collectAsState()
    val honeycombTopBlur by vm.honeycombTopBlur.collectAsState()
    val honeycombBottomBlur by vm.honeycombBottomBlur.collectAsState()
    val honeycombTopFade by vm.honeycombTopFade.collectAsState()
    val honeycombBottomFade by vm.honeycombBottomFade.collectAsState()
    val selectedWatchFaceId by vm.selectedWatchFaceId.collectAsState()
    val selectedWatchFace by vm.selectedWatchFace.collectAsState()
    val watchFaceSelectionReady by vm.watchFaceSelectionReady.collectAsState()
    val watchFaceRefreshToken by vm.watchFaceRefreshToken.collectAsState()
    val builtInPhotoPath by vm.builtInPhotoPath.collectAsState()
    val builtInVideoPath by vm.builtInVideoPath.collectAsState()
    val builtInPhotoClockPosition by vm.builtInPhotoClockPosition.collectAsState()
    val builtInVideoClockPosition by vm.builtInVideoClockPosition.collectAsState()
    val builtInPhotoClockSize by vm.builtInPhotoClockSize.collectAsState()
    val builtInVideoClockSize by vm.builtInVideoClockSize.collectAsState()
    val builtInPhotoClockBold by vm.builtInPhotoClockBold.collectAsState()
    val builtInVideoClockBold by vm.builtInVideoClockBold.collectAsState()
    val builtInVideoFillScreen by vm.builtInVideoFillScreen.collectAsState()
    val builtInVideoClockColorMode by vm.builtInVideoClockColorMode.collectAsState()

    val layerBlurEnabled = blurEnabled && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || screenState != ScreenState.App)
    val reduceLegacyDrawerEffects = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && screenState == ScreenState.App
    val openWatchFaceSettings = remember(context) {
        {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SETTINGS_DESTINATION, SETTINGS_DESTINATION_WATCH_FACES)
                    .putExtra(EXTRA_SETTINGS_RETURN_TO_FACE, true)
            )
            (context as? Activity)?.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    var prevState by remember { mutableStateOf(screenState) }
    val isReturningFromApp = prevState == ScreenState.App && screenState == ScreenState.Apps
    LaunchedEffect(screenState) { prevState = screenState }

    val showLaunchBackdrop = screenState == ScreenState.App && currentApp != null
    var showSplash by remember { mutableStateOf(false) }
    LaunchedEffect(screenState, splashIcon, splashDelay, currentApp) {
        if (screenState == ScreenState.App && splashIcon && currentApp != null) {
            showSplash = false
            delay(BASE_LAUNCH_MASK_DELAY_MS)
            showSplash = true
        } else {
            showSplash = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        var sideScreenProgress by remember(screenWidthPx) { mutableFloatStateOf(if (screenState == ScreenState.SideScreen) 1f else 0f) }

        LaunchedEffect(screenState, sideScreenEnabled) {
            sideScreenProgress = when {
                !sideScreenEnabled -> 0f
                screenState == ScreenState.SideScreen || screenState == ScreenState.Notifications -> 1f
                else -> 0f
            }
        }

        GestureHost(
            screenState = screenState,
            onStateChange = vm::setState,
            sideScreenEnabled = sideScreenEnabled,
            notificationCenterEnabled = notificationCenterEnabled,
            showControlCenter = false,
            screenWidthPx = screenWidthPx,
            onSideScreenProgressChange = { sideScreenProgress = it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (screenState == ScreenState.Face || screenState == ScreenState.SideScreen) 4f else 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = -screenWidthPx * sideScreenProgress }
                        .scaleBlurAlpha(
                            targetValues = faceLayerValues(screenState),
                            screenHeight = screenHeightPx,
                            blurEnabled = layerBlurEnabled
                        )
                ) {
                    AnimatedContent(
                        targetState = if (watchFaceSelectionReady || selectedWatchFace.isBuiltin || selectedWatchFaceId == BUILT_IN_WATCHFACE_ID) {
                            selectedWatchFace.stableKey
                        } else {
                            "loading"
                        },
                        transitionSpec = {
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(220, delayMillis = 70)) togetherWith
                                fadeOut(animationSpec = androidx.compose.animation.core.tween(180))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "watchface_switch"
                    ) { targetKey ->
                        if (targetKey == "loading") {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                        } else if (selectedWatchFace.isBuiltin) {
                            WatchFaceLayer(
                                watchFaceId = selectedWatchFace.id,
                                photoPath = builtInPhotoPath,
                                videoPath = builtInVideoPath,
                                isFaceVisible = screenState == ScreenState.Face || screenState == ScreenState.SideScreen,
                                photoOptions = BuiltInWatchFaceOptions(
                                    clockPosition = builtInPhotoClockPosition,
                                    clockSizeSp = builtInPhotoClockSize,
                                    boldClock = builtInPhotoClockBold
                                ),
                                videoOptions = BuiltInWatchFaceOptions(
                                    clockPosition = builtInVideoClockPosition,
                                    clockSizeSp = builtInVideoClockSize,
                                    boldClock = builtInVideoClockBold,
                                    cropToFill = builtInVideoFillScreen,
                                    clockColorMode = builtInVideoClockColorMode
                                ),
                                onLongPress = openWatchFaceSettings
                            )
                        } else {
                            LunchWatchFaceHost(
                                descriptor = selectedWatchFace,
                                isFaceVisible = screenState == ScreenState.Face || screenState == ScreenState.SideScreen,
                                refreshToken = watchFaceRefreshToken,
                                onLongPress = openWatchFaceSettings,
                                onLoadFailure = { descriptor, error ->
                                    val rootCause = generateSequence(error) { it.cause }.last()
                                    vm.fallbackToBuiltIn("${descriptor.displayName}: ${rootCause.message ?: rootCause.javaClass.simpleName}")
                                }
                            )
                        }
                    }
                }

                if (sideScreenEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = screenWidthPx * (1f - sideScreenProgress) }
                            .scaleBlurAlpha(
                                targetValues = sideScreenLayerValues(screenState),
                                screenHeight = screenHeightPx,
                                blurEnabled = false
                            )
                            .zIndex(2f)
                    ) {
                        SideScreenLayer(
                            allApps = allApps,
                            shortcutKeys = sideScreenShortcutKeys,
                            notificationGroups = notificationGroups,
                            notificationCenterEnabled = notificationCenterEnabled,
                            notificationAccessGranted = notificationAccessGranted,
                            onLaunchShortcut = { app, origin ->
                                val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                                vm.openApp(app, origin, launchDelay, ScreenState.SideScreen)
                            },
                            onOpenNotificationPreview = { entry, origin ->
                                val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                                vm.openNotification(entry.key, entry.packageName, origin, launchDelay, ScreenState.SideScreen)
                            },
                            onSetShortcut = vm::setSideScreenShortcut,
                            onRemoveShortcut = vm::removeSideScreenShortcut,
                            onOpenNotifications = { if (notificationCenterEnabled) vm.setState(ScreenState.Notifications) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (screenState == ScreenState.Apps) 5f else 0f)
                    .scaleBlurAlpha(
                        targetValues = appListLayerValues(screenState),
                        screenHeight = screenHeightPx,
                        blurEnabled = layerBlurEnabled,
                        origin = if (screenState == ScreenState.App || isReturningFromApp) appOpenOrigin else null
                    )
            ) {
                when (layoutMode) {
                    LayoutMode.Honeycomb -> HoneycombScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = reduceLegacyDrawerEffects,
                        narrowCols = honeycombCols,
                        topBlurRadiusDp = honeycombTopBlur,
                        bottomBlurRadiusDp = honeycombBottomBlur,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        onAppClick = { appInfo, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openApp(appInfo, origin, launchDelay, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                    LayoutMode.List -> ListDrawerScreen(
                        apps = apps,
                        blurEnabled = blurEnabled,
                        edgeBlurEnabled = edgeBlurEnabled,
                        suppressHeavyEffects = reduceLegacyDrawerEffects,
                        topFadeRangeDp = honeycombTopFade,
                        bottomFadeRangeDp = honeycombBottomFade,
                        onAppClick = { appInfo, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openApp(appInfo, origin, launchDelay, ScreenState.Apps)
                        },
                        onReorder = { from, to -> vm.swapApps(from, to) },
                        onScrollToTop = { vm.setState(ScreenState.Face) }
                    )
                }
            }

            if (notificationCenterEnabled) {
                val keepNotificationBackdrop = screenState == ScreenState.App && launchSourceState == ScreenState.Notifications
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (screenState == ScreenState.Notifications) 6f else 2f)
                        .scaleBlurAlpha(
                            targetValues = if (keepNotificationBackdrop) {
                                notificationLayerValues(ScreenState.Notifications)
                            } else {
                                notificationLayerValues(screenState)
                            },
                            screenHeight = screenHeightPx,
                            blurEnabled = false
                        )
                ) {
                    NotificationLayer(
                        notificationGroups = notificationGroups,
                        notificationAccessGranted = notificationAccessGranted,
                        revealedNotificationTarget = revealedNotificationTarget,
                        onRevealTargetChange = vm::setRevealedNotificationTarget,
                        onDismissToSideScreen = { vm.setState(if (sideScreenEnabled) ScreenState.SideScreen else ScreenState.Face) },
                        onToggleGroup = vm::toggleNotificationGroup,
                        onDismissGroup = vm::dismissNotificationGroup,
                        onDismissNotification = vm::dismissNotification,
                        onOpenNotification = { key, packageName, origin ->
                            val launchDelay = BASE_LAUNCH_MASK_DELAY_MS + if (splashIcon) splashDelay.toLong() else 0L
                            vm.openNotification(key, packageName, origin, launchDelay, ScreenState.Notifications)
                        }
                    )
                }
            }

            if (screenState == ScreenState.App) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(9f)
                        .background(Color.Transparent)
                )
            }

            AnimatedVisibility(
                visible = showLaunchBackdrop,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .zIndex(10f),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedVisibility(
                        visible = showSplash && splashIcon && currentApp != null,
                        enter = fadeIn() + scaleIn(initialScale = 0.5f),
                        exit = fadeOut() + scaleOut(targetScale = 0.3f)
                    ) {
                        currentApp?.let { app ->
                            Image(
                                bitmap = app.cachedIcon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }
}
