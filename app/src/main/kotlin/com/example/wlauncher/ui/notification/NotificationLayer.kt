package com.flue.launcher.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.animateItemPlacement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.common.instantPressGesture
import com.flue.launcher.ui.common.rememberPressedState
import com.flue.launcher.ui.theme.WatchColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val REVEAL_DELETE_WIDTH = 74f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationLayer(
    notificationGroups: List<NotificationGroupUi>,
    notificationAccessGranted: Boolean,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onDismissToSideScreen: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismissGroup: (String) -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String, String, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val overscroll = remember { Animatable(0f) }
    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && atTop) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y * 0.35f).coerceAtMost(180f)) }
                    return Offset(0f, available.y)
                }
                if (overscroll.value > 0f && available.y < 0f) {
                    scope.launch { overscroll.snapTo((overscroll.value + available.y).coerceAtLeast(0f)) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value > 80f) {
                    overscroll.snapTo(0f)
                    onRevealTargetChange(null)
                    onDismissToSideScreen()
                    return available
                }
                if (overscroll.value > 0f) {
                    overscroll.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(44.dp))
            .background(Color(0xFF1F1F1F))
            .nestedScroll(nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = overscroll.value }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!notificationAccessGranted) {
                EmptyNotificationCard("开启通知访问后，这里会显示通知")
                Spacer(modifier = Modifier.weight(1f))
            } else if (notificationGroups.isEmpty()) {
                EmptyNotificationCard("暂无通知")
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(notificationGroups, key = { it.packageName }) { group ->
                        Box(modifier = Modifier.animateItemPlacement()) {
                            NotificationGroupBlock(
                                group = group,
                                revealedNotificationTarget = revealedNotificationTarget,
                                onRevealTargetChange = onRevealTargetChange,
                                onToggleGroup = { onToggleGroup(group.packageName) },
                                onDismissGroup = { onDismissGroup(group.packageName) },
                                onDismissNotification = onDismissNotification,
                                onOpenNotification = onOpenNotification
                            )
                        }
                    }
                }
            }

            NotificationBatteryPill(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NotificationGroupBlock(
    group: NotificationGroupUi,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    onToggleGroup: () -> Unit,
    onDismissGroup: () -> Unit,
    onDismissNotification: (String) -> Unit,
    onOpenNotification: (String, String, Offset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AnimatedContent(
            targetState = group.expanded,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 5 }) togetherWith
                    (fadeOut(animationSpec = tween(160)) + slideOutVertically(animationSpec = tween(160)) { -it / 8 })
            },
            label = "notification_group_state"
        ) { expanded ->
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GroupHeader(group = group, onClick = onToggleGroup)
                    group.entries.forEach { entry ->
                        RevealDeleteContainer(
                            target = NotificationRevealTarget.Entry(entry.key),
                            revealedNotificationTarget = revealedNotificationTarget,
                            onRevealTargetChange = onRevealTargetChange,
                            enabled = entry.isClearable,
                            onDelete = { onDismissNotification(entry.key) }
                        ) {
                            NotificationEntryCard(
                                entry = entry,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { origin -> onOpenNotification(entry.key, entry.packageName, origin) }
                            )
                        }
                    }
                }
            } else {
                val hasStack = group.entries.size > 1
                RevealDeleteContainer(
                    target = NotificationRevealTarget.Group(group.packageName),
                    revealedNotificationTarget = revealedNotificationTarget,
                    onRevealTargetChange = onRevealTargetChange,
                    enabled = group.entries.any(NotificationEntryUi::isClearable),
                    onDelete = onDismissGroup
                ) {
                    if (hasStack) {
                        CollapsedGroupCard(group = group, onClick = onToggleGroup)
                    } else {
                        NotificationEntryCard(
                            entry = group.latestEntry,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { origin -> onOpenNotification(group.latestEntry.key, group.latestEntry.packageName, origin) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: NotificationGroupUi, onClick: () -> Unit) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.972f else 1f, spring(stiffness = 860f, dampingRatio = 0.72f), label = "notif_group_header")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .instantPressGesture(pressedState, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(group.appLabel, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ExpandLess, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text("${group.entries.size}条通知", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CollapsedGroupCard(group: NotificationGroupUi, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        repeat(minOf(group.hiddenCount, 2)) { index ->
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = ((index + 1) * 6).dp, start = 8.dp, end = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF2B2B2B))
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NotificationEntryCard(entry = group.latestEntry, modifier = Modifier.fillMaxWidth(), onClick = { onClick() })
            Spacer(modifier = Modifier.height(6.dp))
            Text("+${group.hiddenCount}条新消息", color = WatchColors.TextTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NotificationEntryCard(
    entry: NotificationEntryUi,
    modifier: Modifier,
    onClick: (Offset) -> Unit
) {
    val pressedState = rememberPressedState()
    val pressed by pressedState
    val scale by animateFloatAsState(if (pressed) 0.968f else 1f, spring(stiffness = 860f, dampingRatio = 0.74f), label = "notif_card_scale")
    var center by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(28.dp))
            .background(if (pressed) Color(0xFF3B3B3B) else Color(0xFF353535))
            .onGloballyPositioned { coordinates ->
                val root = coordinates.findRootCoordinates()
                val position = coordinates.positionInRoot()
                center = Offset(
                    x = (position.x + coordinates.size.width / 2f) / root.size.width.coerceAtLeast(1),
                    y = (position.y + coordinates.size.height / 2f) / root.size.height.coerceAtLeast(1)
                )
            }
            .instantPressGesture(pressedState, enabled = entry.contentIntentAvailable || entry.packageName.isNotBlank()) {
                onClick(center)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotificationIcon(icon = entry.icon)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title.ifBlank { entry.appLabel },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.text.ifBlank { entry.appLabel },
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(formatTime(entry.time), color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NotificationIcon(icon: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFFD9D9D9)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                filterQuality = FilterQuality.Medium,
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = Color(0xFF303030), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RevealDeleteContainer(
    target: NotificationRevealTarget,
    revealedNotificationTarget: NotificationRevealTarget?,
    onRevealTargetChange: (NotificationRevealTarget?) -> Unit,
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(target) { Animatable(0f) }
    val revealWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { REVEAL_DELETE_WIDTH.dp.toPx() }

    androidx.compose.runtime.LaunchedEffect(revealedNotificationTarget, revealWidthPx) {
        offsetX.animateTo(if (enabled && revealedNotificationTarget == target) -revealWidthPx else 0f, tween(180))
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(REVEAL_DELETE_WIDTH.dp)
                .height(74.dp)
                .clip(CircleShape)
                .background(Color(0xFF8E4F64)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = Color(0xFF2B1824),
                modifier = Modifier
                    .size(24.dp)
                    .pointerInput(enabled) { detectTapGestures(onTap = { if (enabled) onDelete() }) }
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(target, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(-revealWidthPx, 0f))
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                onRevealTargetChange(if (abs(offsetX.value) > revealWidthPx * 0.45f) target else null)
                            }
                        },
                        onDragCancel = {
                            scope.launch { onRevealTargetChange(null) }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
private fun EmptyNotificationCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF353535))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
    }
}

@Composable
private fun NotificationBatteryPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember(context) { mutableIntStateOf(readBatteryLevel(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                level = readBatteryLevel(context ?: return)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
