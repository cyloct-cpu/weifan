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

    /**
     * 从备份 zip 恢复。options 指定要恢复哪些类别（对齐参考 dialog_restore_options 逐项勾选）。
     *
     * 关键纪律（这是恢复最容易出事的地方）：
     *  1) 覆盖前必须先关数据库连接，否则 SQLite 会锁库导致写入失败或库损坏
     *  2) 先把 zip 解到临时目录，全部解完再逐个复制 —— 避免解一半失败把现有数据搞坏
     *  3) 覆盖前把原文件改名成 .bak，复制成功才删；失败则回滚
     *  4) 完成后果断杀进程：DB 与 Go 层的连接状态都已不可信，重启最干净
     */
    fun restore(ctx: Context, zipFile: File, options: Set<String>,
                progress: (String) -> Unit = {}): Int {
        val tmp = File(ctx.cacheDir, "restore-tmp")
        try { tmp.deleteRecursively() } catch (_: Throwable) { }
        tmp.mkdirs()

        // 1) 全部解压到临时目录
        progress("正在读取备份…")
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = File(tmp, entry.name)
                out.parentFile?.mkdirs()
                java.io.FileOutputStream(out).use { fos ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }

        // 2) 逐项覆盖（带 .bak 回滚）
        var restored = 0
        fun put(src: File, dst: File) {
            if (!src.exists()) return
            dst.parentFile?.mkdirs()
            val bak = File(dst.absolutePath + ".bak")
            if (dst.exists()) {
                try { bak.delete() } catch (_: Throwable) { }
                dst.renameTo(bak)
            }
            val ok = try {
                src.copyTo(dst, overwrite = true); true
            } catch (_: Throwable) { false }
            if (ok) {
                try { bak.delete() } catch (_: Throwable) { }
                restored++
            } else {
                // 回滚
                try { bak.renameTo(dst) } catch (_: Throwable) { }
            }
        }

        if ("chats" in options) {
            progress("恢复会话与消息…")
            put(File(tmp, "db/chats.db"),
                File(ctx.filesDir.parentFile, "databases/chats.db"))
        }
        if ("creds" in options) {
            progress("恢复设备凭据…")
            put(File(tmp, "db/weifan.db"), File(ctx.filesDir, "weifan.db"))
        }
        if ("media" in options) {
            progress("恢复媒体文件…")
            File(tmp, "media").listFiles()?.forEach {
                put(it, File(ctx.filesDir, "media/${it.name}"))
            }
        }
        if ("avatars" in options) {
            progress("恢复头像缓存…")
            File(tmp, "avatars").listFiles()?.forEach {
                put(it, File(ctx.filesDir, "avatars/${it.name}"))
            }
        }
        if ("prefs" in options) {
            progress("恢复翻译设置…")
            put(File(tmp, "prefs/weifan.xml"),
                File(ctx.filesDir.parentFile, "shared_prefs/weifan.xml"))
        }

        try { tmp.deleteRecursively() } catch (_: Throwable) { }
        return restored
    }

    /** 列出 Download 目录下的备份 zip（按时间倒序） */
    fun availableBackups(): List<File> {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS)
        return (dir.listFiles { f -> f.isFile && f.name.startsWith("weifan-backup-")
            && f.name.endsWith(".zip") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }
}
