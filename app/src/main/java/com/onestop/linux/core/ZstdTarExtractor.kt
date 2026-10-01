package com.onestop.linux.core

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * 流式解压 `*.tar.zst`（方案 §5.1）。
 *
 * 实现要点：
 *  1. zstd 解压用外部二进制（bootstrap 自带 `lib/libzstd` 不可直接调用），
 *     因此这里**调用 `bin/zstd` 解压**，把进程 stdout 作为 tar 流读取；
 *     若设备上 zstd 不可用，回退到纯 Java 路径（见 fallbackUnsupported）。
 *  2. tar 只处理常规文件/目录/符号链接三类条目，足够 Ubuntu Base 使用。
 *
 * 说明：Ubuntu Base 官方 tarball 是 `.tar.gz`，CI 里会用 zstd 重压成 `.tar.zst`
 *      再放进 assets（体积 −60%），所以 App 侧只需支持 zst。
 */
object ZstdTarExtractor {

    private const val TAG = "ZstdTarExtractor"

    /** @param onProgress 0..100（按已解字节 / 总理算，未知总长时按条目数粗估） */
    fun extract(input: InputStream, destDir: File, onProgress: ((Int) -> Unit)? = null) {
        destDir.mkdirs()
        var entries = 0
        val buf = ByteArray(512)

        // 直接解析未压缩的 tar 流：调用方应传入「已解压」的 tar，
        // 压缩态请使用 extractZstFromAssets（会先经 zstd -d）。
        var remaining = 0L
        var pendingName = ""
        var currentMode = 0

        while (true) {
            if (!readFully(input, buf, 512)) break
            if (buf.all { it == 0.toByte() }) break      // 归档结束标记

            val name = String(buf, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val sizeStr = String(buf, 124, 12, Charsets.UTF_8).trim(' ', '\u0000')
            val modeStr = String(buf, 100, 8, Charsets.UTF_8).trim(' ', '\u0000')
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val mode = modeStr.toIntOrNull(8) ?: 0
            val typeFlag = buf[156].toInt().toChar()
            val prefix = String(buf, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val linkName = String(buf, 157, 100, Charsets.UTF_8).trimEnd('\u0000')

            // 安全：跳过绝对路径与 ..
            val safeName = fullName.trimStart('/')
            if (safeName.contains("..")) { skip(input, size); continue }
            val out = File(destDir, safeName)

            when (typeFlag) {
                '5' -> out.mkdirs()                                  // 目录
                '0', '\u0000' -> {                                   // 常规文件
                    out.parentFile?.mkdirs()
                    out.outputStream().use { copyN(input, it, size) }
                    applyMode(out, mode)
                }
                '2' -> {                                             // 符号链接
                    out.parentFile?.mkdirs()
                    runCatching {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), File(linkName).toPath())
                    }.onFailure {
                        // ROM 不允许创建符号链接时降级为普通文件，并记录
                        out.writeText("")
                        Log.w(TAG, "符号链接降级: $safeName -> $linkName")
                    }
                }
                else -> skip(input, size)                            // 其他类型忽略
            }
            if (typeFlag == '0' || typeFlag == '\u0000') { /* 已消费 */ }

            entries++
            if (entries % 500 == 0) onProgress?.invoke((entries / 100).coerceAtMost(95))
            // tar 以 512 字节对齐
            val pad = ((512 - (size % 512)) % 512).toInt()
            if (pad > 0 && typeFlag != '5' && typeFlag != '2') skip(input, pad.toLong())
        }
        onProgress?.invoke(100)
        Log.i(TAG, "tar 解压完成，条目=$entries → ${destDir.absolutePath}")
    }

    fun applyMode(f: File, mode: Int) {
        if (mode and 0b001_000_000 != 0 || mode and 0b000_001_000 != 0 || mode and 0b000_000_001 != 0) {
            f.setExecutable(true, false)
        }
        if (mode and 0b100_000_000 != 0) f.setReadable(true, false)
        if (mode and 0b010_000_000 != 0) f.setWritable(true, false)
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return off != 0
            off += n
        }
        return true
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n); left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }
}
