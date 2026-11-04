package com.weifan.app.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把媒体文件保存到系统相册（对齐参考 media_download + MediaStore/DIRECTORY_PICTURES）。
 *
 * 之前我们下载的图片只落在 App 私有目录（/data/data/.../files/media/），
 * 用户在系统相册里根本看不到，也不知道存哪了 —— 这是用户实机反馈的问题。
 *
 * 实现按 Android 版本分两条路：
 *  - Android 10+（API 29）：走 MediaStore，写 RELATIVE_PATH=Pictures/WeFlip，
 *    中途用 IS_PENDING 占位，写完再置 0，避免相册出现半张图
 *  - Android 9-（API 28）：MediaStore 不能指定相对路径，直接写
 *    Environment.DIRECTORY_PICTURES 下的文件，再触发媒体扫描
 */
object MediaSaver {

    private const val ALBUM = "WeFlip"

    /** 判定是否图片（视频也能存，但先用扩展名区分，避免把文档塞进相册） */
    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".heic")
    }

    private fun isVideo(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") ||
            n.endsWith(".mov")
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".heic", true) -> "image/heic"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".3gp", true) -> "video/3gp"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mov", true) -> "video/quicktime"
        else -> "image/jpeg"
    }

    /**
     * 保存到相册。返回 true = 成功（已写入系统相册）。
     * 调用方负责在后台线程调，并检查存储权限（API<=28 需要 WRITE_EXTERNAL_STORAGE）。
     */
    fun saveToGallery(ctx: Context, src: File, displayName: String? = null): Boolean {
        if (!src.exists() || src.length() == 0L) return false
        val name = (displayName ?: src.name).ifBlank { "weifan_${System.currentTimeMillis()}" }
        val mime = mimeOf(name)
        val isVideoFile = isVideo(name)
        val dir = if (isVideoFile) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (isVideoFile)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(collection, values) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out, 8 * 1024) }
                } ?: return false
                // 清除 pending 标记，相册才会显示
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val album = File(
                    Environment.getExternalStoragePublicDirectory(dir), ALBUM)
                if (!album.exists()) album.mkdirs()
                val dst = File(album, name)
                src.inputStream().use { ins ->
                    dst.outputStream().use { out -> ins.copyTo(out, 8 * 1024) }
                }
                // 通知媒体库扫描，否则相册里看不到
                @Suppress("DEPRECATION")
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 给用户看的保存路径说明（Toast 文案用） */
    fun galleryHint(): String = "相册 / $ALBUM"
}