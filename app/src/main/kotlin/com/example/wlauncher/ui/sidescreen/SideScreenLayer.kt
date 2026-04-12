package com.flue.launcher.ui.sidescreen

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.notification.NotificationEntryUi
import com.flue.launcher.ui.notification.NotificationGroupUi
import com.flue.launcher.ui.theme.WatchColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHORTCUT_COLUMNS = 3
private const val SIDE_SCREEN_NOTIFICATION_OPEN_DRAG = -84f

@Composable
fun SideScreenLayer(
    allApps: List<AppInfo>,
    shortcutKeys: List<String?>,
    notificationGroups: List<NotificationGroupUi>,
    notificationCenterEnabled: Boolean,
    notificationAccessGranted: Boolean,
    onLaunchShortcut: (AppInfo, Offset) -> Unit,
    onOpenNotificationPreview: (NotificationEntryUi, Offset) -> Unit,
    onSetShortcut: (Int, String) -> Unit,
    onRemoveShortcut: (Int) -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var pickerSlotIndex by remember { mutableStateOf<Int?>(null) }
    var menuSlotIndex by remember { mutableStateOf<Int?>(null) }
    val visibleSlotCount = if (notificationCenterEnabled) 6 else 9
    val visibleKeys = shortcutKeys.take(visibleSlotCount)
    val appLookup = remember(allApps) { allApps.associateBy { it.componentKey } }
    val totalNotifications = remember(notificationGroups) { notificationGroups.sumOf { it.entries.size } }
    val latestEntry = remember(notificationGroups) { notificationGroups.firstOrNull()?.latestEntry }
    val verticalOffset = remember { Animatable(0f) }

    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("M月d日  EEEE", Locale.CHINESE)
        while (true) {
            val now = Date()
            currentTime = timeFmt.format(now)
            currentDate = dateFmt.format(now)
            delay(1_000)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootSize = it.size }
            .clip(RoundedCornerShape(44.dp))
            .background(Color(0xFF1F1F1F))
            .pointerInput(notificationCenterEnabled, totalNotifications) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        if (dragAmount.y < 0f && notificationCenterEnabled && totalNotifications > 0) {
                            val next = (verticalOffset.value + dragAmount.y).coerceAtLeast(-140f)
                            scope.launch { verticalOffset.snapTo(next) }
                            change.consume()
                        } else if (dragAmount.y > 0f) {
                            val next = (verticalOffset.value + dragAmount.y * 0.3f).coerceAtMost(36f)
                            scope.launch { verticalOffset.snapTo(next) }
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (notificationCenterEnabled && totalNotifications > 0 && verticalOffset.value <= SIDE_SCREEN_NOTIFICATION_OPEN_DRAG) {
                                verticalOffset.snapTo(0f)
                                onOpenNotifications()
                            } else {
                                verticalOffset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { verticalOffset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = verticalOffset.value }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(currentTime, color = Color.White.copy(alpha = 0.92f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(currentDate, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(16.dp))

            ShortcutGridPanel(
                apps = visibleKeys.map { key -> key?.let(appLookup::get) },
                rootSize = rootSize,
                onAdd = { pickerSlotIndex = it },
                onLaunch = onLaunchShortcut,
                onLongPress = { menuSlotIndex = it }
            )

            Spacer(modifier = Modifier.weight(1f))

            if (notificationCenterEnabled && notificationAccessGranted && latestEntry != null) {
                NotificationPreviewPill(
                    totalNotifications = totalNotifications,
                    appLabel = latestEntry.appLabel,
                    summary = latestEntry.text.ifBlank { latestEntry.title.ifBlank { latestEntry.appLabel } },
                    timeText = formatTime(latestEntry.time),
                    icon = latestEntry.icon,
                    rootSize = rootSize,
                    onOpenNotifications = onOpenNotifications,
                    onOpenNotification = { origin -> onOpenNotificationPreview(latestEntry, origin) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            SideBatteryPill(modifier = Modifier.padding(bottom = 2.dp))
        }
    }

    if (pickerSlotIndex != null) {
        ShortcutPickerDialog(
            apps = allApps,
            onDismiss = { pickerSlotIndex = null },
            onSelect = { app ->
                val slotIndex = pickerSlotIndex ?: return@ShortcutPickerDialog
                onSetShortcut(slotIndex, app.componentKey)
                pickerSlotIndex = null
            }
        )
    }

    if (menuSlotIndex != null) {
        val app = visibleKeys.getOrNull(menuSlotIndex ?: -1)?.let(appLookup::get)
        if (app != null) {
            ShortcutRemoveDialog(
                app = app,
                onDismiss = { menuSlotIndex = null },
                onRemove = {
                    val slotIndex = menuSlotIndex ?: return@ShortcutRemoveDialog
                    onRemoveShortcut(slotIndex)
                    menuSlotIndex = null
                }
            )
        } else {
            menuSlotIndex = null
        }
    }
}

@Composable
private fun ShortcutGridPanel(
    apps: List<AppInfo?>,
    rootSize: IntSize,
    onAdd: (Int) -> Unit,
    onLaunch: (AppInfo, Offset) -> Unit,
    onLongPress: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF313131))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        apps.chunked(SHORTCUT_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEachIndexed { columnIndex, app ->
                    val slotIndex = rowIndex * SHORTCUT_COLUMNS + columnIndex
                    ShortcutSlot(
                        app = app,
                        rootSize = rootSize,
                        onAdd = { onAdd(slotIndex) },
                        onLaunch = onLaunch,
                        onLongPress = { if (app != null) onLongPress(slotIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutSlot(
    app: AppInfo?,
    rootSize: IntSize,
    onAdd: () -> Unit,
    onLaunch: (AppInfo, Offset) -> Unit,
    onLongPress: () -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.958f else 1f,
        animationSpec = spring(stiffness = 820f, dampingRatio = 0.72f),
        label = "side_shortcut_scale"
    )
    var center by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    Box(
        modifier = Modifier
            .size(74.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (pressed) Color(0xFF4A4A4A) else Color(0xFF505050))
            .onGloballyPositioned { coordinates ->
                if (rootSize.width > 0 && rootSize.height > 0) {
                    val position = coordinates.positionInRoot()
                    center = Offset(
                        x = (position.x + coordinates.size.width / 2f) / rootSize.width,
                        y = (position.y + coordinates.size.height / 2f) / rootSize.height
                    )
                }
            }
            .pointerInput(app, rootSize) {
                detectTapGestures(
                    onPress = {
                        pressedState.value = true
                        tryAwaitRelease()
                        pressedState.value = false
                    },
                    onTap = {
                        if (app != null) onLaunch(app, center) else onAdd()
                    },
                    onLongPress = { if (app != null) onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (app == null) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
        } else {
            Image(
                bitmap = app.cachedIcon,
                contentDescription = app.label,
                modifier = Modifier.size(58.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun NotificationPreviewPill(
    totalNotifications: Int,
    appLabel: String,
    summary: String,
    timeText: String,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    rootSize: IntSize,
    onOpenNotifications: () -> Unit,
    onOpenNotification: (Offset) -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.74f), label = "side_notification_scale")
    var iconCenter by remember(rootSize) { mutableStateOf(Offset(0.18f, 0.82f)) }
    val isStacked = totalNotifications > 1
    Box(modifier = Modifier.fillMaxWidth()) {
        if (isStacked) {
            repeat(minOf(totalNotifications - 1, 2)) { index ->
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(top = ((index + 1) * 6).dp, start = 8.dp, end = 8.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF2B2B2B))
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (pressed) Color(0xFF3B3B3B) else Color(0xFF353535))
                    .pointerInput(totalNotifications) {
                        detectTapGestures(
                            onPress = {
                                pressedState.value = true
                                val released = tryAwaitRelease()
                                pressedState.value = false
                                if (released) {
                                    if (isStacked) onOpenNotifications() else onOpenNotification(iconCenter)
                                }
                            }
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD9D9D9))
                            .onGloballyPositioned { coordinates ->
                                if (rootSize.width > 0 && rootSize.height > 0) {
                                    val position = coordinates.positionInRoot()
                                    iconCenter = Offset(
                                        x = (position.x + coordinates.size.width / 2f) / rootSize.width,
                                        y = (position.y + coordinates.size.height / 2f) / rootSize.height
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (icon != null) {
                            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(appLabel, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(summary, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 2)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(timeText, color = Color.White, fontSize = 13.sp)
                }
            }
            if (isStacked) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("+${totalNotifications - 1}条通知", color = WatchColors.TextTertiary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ShortcutPickerDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onSelect: (AppInfo) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF242424))
                    .padding(16.dp)
            ) {
                Text("选择快捷启动", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(apps, key = { it.componentKey }) { app ->
                        val pressedState = rememberPressedState()
                        val pressed by pressedState
                        val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "picker_item")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (pressed) Color(0xFF323232) else Color(0xFF2D2D2D))
                                .pointerInput(app.componentKey) {
                                    detectTapGestures(
                                        onPress = {
                                            pressedState.value = true
                                            val released = tryAwaitRelease()
                                            pressedState.value = false
                                            if (released) onSelect(app)
                                        }
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(app.cachedIcon, contentDescription = app.label, modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(app.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(app.packageName, color = WatchColors.TextTertiary, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRemoveDialog(
    app: AppInfo,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF2C2C2E))
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("移除", color = Color(0xFFFF453A), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.pointerInput(app.componentKey) {
                            detectTapGestures(onTap = { onRemove() })
                        })
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Image(app.cachedIcon, contentDescription = app.label, modifier = Modifier.size(88.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(modifier = Modifier.height(6.dp))
                Text(app.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SideBatteryPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember(context) { mutableIntStateOf(readBatteryLevel(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                level = readBatteryLevel(context ?: return)
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val tint = when {
        level <= 10 -> WatchColors.ActiveRed
        level <= 20 -> WatchColors.ActiveOrange
        else -> WatchColors.ActiveGreen
    }
    Box(
        modifier = modifier
            .width(54.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF242424)),
        contentAlignment = Alignment.Center
    ) {
        Text("${level}%", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun readBatteryLevel(context: Context): Int {
    return (context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?: 0).coerceIn(0, 100)
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
