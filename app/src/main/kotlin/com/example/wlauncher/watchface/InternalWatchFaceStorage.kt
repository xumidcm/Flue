package com.flue.launcher.watchface

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

object InternalWatchFaceStorage {
    private const val PHOTO_DIR = "photo"
    private const val VIDEO_DIR = "video"

    fun copyPhoto(context: Context, uri: Uri): String? = copyMedia(
        context,
        uri,
        PHOTO_DIR,
        fallbackExtension = "jpg",
        mediaType = MediaType.PHOTO
    )

    fun copyVideo(context: Context, uri: Uri): String? = copyMedia(
        context,
        uri,
        VIDEO_DIR,
        fallbackExtension = "mp4",
        mediaType = MediaType.VIDEO
    )

    fun clearPhoto(context: Context) = clearMedia(context, PHOTO_DIR)

    fun clearVideo(context: Context) = clearMedia(context, VIDEO_DIR)

    private fun copyMedia(
        context: Context,
        uri: Uri,
        folderName: String,
        fallbackExtension: String,
        mediaType: MediaType
    ): String? {
        return runCatching {
            val root = File(context.filesDir, "internal_watchfaces/$folderName").apply { mkdirs() }
            root.listFiles()?.forEach { existing ->
                if (existing.isFile) existing.delete()
            }

            val extension = resolveExtension(context, uri, fallbackExtension, mediaType)
            val target = File(root, "current_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath
        }.getOrNull()
    }

    private fun resolveExtension(
        context: Context,
        uri: Uri,
        fallbackExtension: String,
        mediaType: MediaType
    ): String {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val normalizedFromMime = fromMime?.normalizeExtension()
        if (
            !normalizedFromMime.isNullOrBlank() &&
            mimeType.startsWith(mediaType.mimePrefix) &&
            mediaType.isSupportedExtension(normalizedFromMime)
        ) {
            return normalizedFromMime
        }
        val fromDisplayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                    } else null
                }
        }.getOrNull()?.substringAfterLast('.', "")?.normalizeExtension()
        if (!fromDisplayName.isNullOrBlank() && mediaType.isSupportedExtension(fromDisplayName)) {
            return fromDisplayName
        }
        val fromPath = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).normalizeExtension()
        if (!fromPath.isNullOrBlank() && mediaType.isSupportedExtension(fromPath)) {
            return fromPath
        }
        return fallbackExtension
    }

    private fun String?.normalizeExtension(): String? {
        val normalized = this
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val safe = normalized.takeWhile { it.isLetterOrDigit() }
        return safe.ifBlank { null }
    }

    private enum class MediaType(
        val mimePrefix: String,
        private val supportedExtensions: Set<String>
    ) {
        PHOTO(
            mimePrefix = "image/",
            supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        ),
        VIDEO(
            mimePrefix = "video/",
            supportedExtensions = setOf("mp4", "m4v", "webm", "3gp", "3gpp", "3g2", "mkv", "avi", "mov")
        );

        fun isSupportedExtension(extension: String): Boolean = extension in supportedExtensions
    }

    private fun clearMedia(context: Context, folderName: String) {
        runCatching {
            val root = File(context.filesDir, "internal_watchfaces/$folderName")
            root.listFiles()?.forEach { existing ->
                if (existing.isFile) existing.delete()
            }
        }
    }
}
