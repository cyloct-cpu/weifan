package com.weifan.app.core

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复（对齐参考 dialog_backup_restore / dialog_restore_options）。
 *
 * 参考备份包含六项：会话与消息、媒体文件、特别关注、置顶会话、联系人翻译偏好、全局翻译设置。
 * 我们的数据实际分布：
 *   databases/chats.db       会话/消息/标签/待办/投票（我们自己建的 UI 库）
 *   files/weifan.db          whatsmeow 协议层会话凭据与联系人
 *   files/media/             收发媒体
 *   files/avatars/           头像缓存
 *   shared_prefs/weifan.xml  翻译设置、置顶、标签、汇率、客户语言等
 *
 * 打包成 zip 放 Download 目录，用户可自行拷走。恢复时按同样结构覆盖。
 *
 * 设计取舍：
 *  - 用系统自带 ZipOutputStream，不引第三方库
 *  - 边读边写、8KB 缓冲，避免把大文件整个读进内存（v3.0.0 被 lmkd 杀过，这条纪律不能破）
 *  - 只在后台线程调用；调用方负责进度回调切主线程
 */
object Backup {

    /** 背包条目：源文件 → zip 内路径 */
    data class Item(val file: File, val entryName: String)

    /** 收集待备份文件；总字节数一并返回用于进度 */
    fun collect(ctx: Context): Pair<List<Item>, Long> {
        val items = mutableListOf<Item>()
        var total = 0L

        fun add(f: File?, name: String) {
            if (f != null && f.isFile && f.length() > 0) {
                items += Item(f, name)
                total += f.length()
            }
        }

        add(File(ctx.filesDir.parentFile, "databases/chats.db"), "db/chats.db")
        add(File(ctx.filesDir, "weifan.db"), "db/weifan.db")
        add(File(ctx.filesDir.parentFile, "shared_prefs/weifan.xml"), "prefs/weifan.xml")

        for (dir in listOf("media", "avatars")) {
            val d = File(ctx.filesDir, dir)
            if (d.isDirectory) {
                d.listFiles()?.forEach { f ->
                    if (f.isFile) add(f, "$dir/${f.name}")
                }
            }
        }
        return items to total
    }

    /**
     * 导出 zip。progress 回调 (已写字节, 总字节)，在调用线程（后台）触发。
     * 成功返回目标文件。
     */
    fun export(ctx: Context, target: File,
               progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val (items, total) = collect(ctx)
        if (items.isEmpty()) throw IllegalStateException("没有可备份的数据")
        var written = 0L
        ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
            for (item in items) {
                zos.putNextEntry(ZipEntry(item.entryName))
                FileInputStream(item.file).use { fis ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        zos.write(buf, 0, n)
                        written += n
                        progress(written, total)
                    }
                }
                zos.closeEntry()
            }
        }
        return target
    }

    /** 备份内容的可读摘要（对齐参考 dialog_restore_options 的逐项勾选文案） */
    fun summary(ctx: Context): List<Pair<String, String>> {
        fun size(f: File?): String = when {
            f == null || !f.exists() -> "—"
            f.length() >= 1 shl 20 -> String.format("%.1f MB", f.length() / 1048576.0)
            f.length() >= 1 shl 10 -> String.format("%.0f KB", f.length() / 1024.0)
            else -> "${f.length()} B"
        }
        fun dirSize(name: String): String {
            val d = File(ctx.filesDir, name)
            if (!d.isDirectory) return "—"
            val n = d.listFiles()?.size ?: 0
            val bytes = d.listFiles()?.sumOf { it.length() } ?: 0L
            return "$n 个文件 · " + size(File("").apply { })
                .let { if (bytes >= 1 shl 20) String.format("%.1f MB", bytes / 1048576.0)
                      else String.format("%.0f KB", bytes / 1024.0) }
        }
        return listOf(
            "会话与消息" to size(File(ctx.filesDir.parentFile, "databases/chats.db")),
            "设备凭据与联系人" to size(File(ctx.filesDir, "weifan.db")),
            "媒体文件" to dirSize("media"),
            "头像缓存" to dirSize("avatars"),
            "翻译设置与偏好" to size(File(ctx.filesDir.parentFile, "shared_prefs/weifan.xml"))
        )
    }
}
