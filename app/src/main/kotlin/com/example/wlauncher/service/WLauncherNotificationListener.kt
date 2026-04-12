package com.flue.launcher.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotifData(
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

class WLauncherNotificationListener : NotificationListenerService() {

    companion object {
        private val _notifications = MutableStateFlow<List<NotifData>>(emptyList())
        val notifications: StateFlow<List<NotifData>> = _notifications.asStateFlow()

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private var instance: WLauncherNotificationListener? = null

        fun isConnected(): Boolean = instance != null

        fun dismissNotification(key: String) {
            instance?.cancelNotification(key)
        }

        fun dismissNotifications(keys: Collection<String>) {
            instance?.let { listener ->
                keys.forEach(listener::cancelNotification)
            }
        }

        fun openNotification(key: String): Boolean {
            val listener = instance ?: return false
            val sbn = listener.activeNotifications?.firstOrNull { it.key == key } ?: return false
            val pendingIntent = sbn.notification.contentIntent ?: return false
            return runCatching {
                pendingIntent.send()
            }.isSuccess
        }
    }

    override fun onListenerConnected() {
        instance = this
        _connected.value = true
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        instance = null
        _connected.value = false
        _notifications.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    private fun refreshNotifications() {
        try {
            val sbns = activeNotifications ?: return
            val pm = applicationContext.packageManager
            _notifications.value = sbns
                .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
                .sortedByDescending { it.postTime }
                .take(30)
                .map { sbn ->
                    val notification = sbn.notification
                    val extras = notification.extras
                    val appLabel = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                    } catch (_: Exception) {
                        sbn.packageName
                    }
                    val iconBitmap = try {
                        notification.smallIcon
                            ?.loadDrawable(applicationContext)
                            ?.toBitmap(48, 48)
                            ?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }

                    NotifData(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        groupKey = sbn.groupKey,
                        appLabel = appLabel,
                        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                        time = sbn.postTime,
                        icon = iconBitmap,
                        isClearable = sbn.isClearable,
                        contentIntentAvailable = notification.contentIntent != null
                    )
                }
        } catch (_: Exception) {
        }
    }
}
