package com.flue.launcher.ui.notification

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

data class NotificationEntryUi(
    val key: String,
    val packageName: String,
    val groupKey: String?,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: ImageBitmap?,
    val isClearable: Boolean,
    val contentIntentAvailable: Boolean
)

data class NotificationGroupUi(
    val packageName: String,
    val appLabel: String,
    val icon: ImageBitmap?,
    val latestTime: Long,
    val entries: List<NotificationEntryUi>,
    val expanded: Boolean
) {
    val latestEntry: NotificationEntryUi
        get() = entries.first()

    val hiddenCount: Int
        get() = (entries.size - 1).coerceAtLeast(0)
}

sealed interface NotificationRevealTarget {
    data class Group(val packageName: String) : NotificationRevealTarget
    data class Entry(val key: String) : NotificationRevealTarget
}

data class NotificationOpenRequest(
    val key: String,
    val packageName: String,
    val origin: Offset
)
