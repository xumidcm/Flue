package com.flue.launcher.data.model

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val cachedIcon: Bitmap,
    val cachedBlurredIcon: Bitmap
) {
    val componentKey: String
        get() = "$packageName/$activityName"
}
