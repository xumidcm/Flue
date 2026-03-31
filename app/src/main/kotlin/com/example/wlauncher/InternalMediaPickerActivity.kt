package com.flue.launcher

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InternalMediaItem(
    val uriString: String,
    val displayName: String,
    val subtitle: String
)

class InternalMediaPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_PICKER_MODE) ?: PICKER_MODE_IMAGE
        setContent {
            WatchLauncherTheme {
                InternalMediaPickerScreen(
                    mode = mode,
                    onBack = { finish() },
                    onItemSelected = { uriString ->
                        setResult(RESULT_OK, Intent().setData(android.net.Uri.parse(uriString)))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun InternalMediaPickerScreen(
    mode: String,
    onBack: () -> Unit,
    onItemSelected: (String) -> Unit
) {
    val isImageMode = mode == PICKER_MODE_IMAGE
    val context = LocalContext.current
    var items by remember(mode) { mutableStateOf<List<InternalMediaItem>>(emptyList()) }

    LaunchedEffect(mode) {
        val activity = context as? ComponentActivity
        items = if (activity != null) loadInternalMediaItems(activity, isImageMode) else emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isImageMode) "内置文件管理器 · 图片" else "内置文件管理器 · 视频",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "点击列表项直接应用到当前表盘",
            color = WatchColors.TextTertiary,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isImageMode) "未找到图片媒体" else "未找到视频媒体",
                    color = WatchColors.TextTertiary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(WatchColors.SurfaceGlass)
                            .clickable { onItemSelected(item.uriString) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.subtitle,
                                color = WatchColors.TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = "使用",
                            color = WatchColors.ActiveCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(WatchColors.SurfaceGlass)
                .clickable { onBack() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "返回", color = WatchColors.ActiveCyan, fontSize = 14.sp)
        }
    }
}

private suspend fun loadInternalMediaItems(
    activity: ComponentActivity,
    isImageMode: Boolean
): List<InternalMediaItem> = withContext(Dispatchers.IO) {
    val resolver = activity.contentResolver
    val collection = if (isImageMode) {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE
    )
    val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
    val items = mutableListOf<InternalMediaItem>()
    resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: (if (isImageMode) "图片" else "视频")
            val date = cursor.getLong(dateCol)
            val sizeBytes = cursor.getLong(sizeCol)
            val uri = ContentUris.withAppendedId(collection, id)
            items += InternalMediaItem(
                uriString = uri.toString(),
                displayName = name,
                subtitle = "修改时间: ${formatEpoch(date)} · ${formatBytes(sizeBytes)}"
            )
        }
    }
    items
}

private fun formatEpoch(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "-"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochSeconds * 1000L))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0B"
    val kb = 1024L
    val mb = kb * 1024L
    val gb = mb * 1024L
    return when {
        bytes >= gb -> String.format("%.1fGB", bytes.toDouble() / gb.toDouble())
        bytes >= mb -> String.format("%.1fMB", bytes.toDouble() / mb.toDouble())
        bytes >= kb -> String.format("%.1fKB", bytes.toDouble() / kb.toDouble())
        else -> "${bytes}B"
    }
}

const val EXTRA_PICKER_MODE = "picker_mode"
const val PICKER_MODE_IMAGE = "image"
const val PICKER_MODE_VIDEO = "video"
