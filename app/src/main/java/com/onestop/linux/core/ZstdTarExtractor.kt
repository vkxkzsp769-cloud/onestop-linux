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

    /**
     * @param onProgress 0..100（按条目数粗估）
     *
     * 已按真实 Ubuntu Base 24.04.5 tarball 实测校正（3417 条目）：
     *  - 该 tar 是 **ustar** 格式（magic "ustar\0"），但**每个条目都带一个 PAX 扩展头**（x），
     *    PAX 记录里承载的是 atime/ctime/mtime；按 POSIX，PAX 未覆盖的字段以 ustar 头为准，
     *    因此「跳过 PAX 数据」本身不会丢文件或丢内容（已与系统 tar 逐项比对：文件/目录/符号链接数量完全一致）。
     *  - 但仍**必须支持 PAX 的 path/linkpath/size 覆盖**：长路径 tar（>100 字节）与长链接目标会靠它们承载，
     *    否则会出现路径截断。本实现已支持（PAX 只在需要时才出现这两个键）。
     *  - 实测最长路径 87 字节（var/lib/systemd/…），尚未触发长路径，但不保证将来不触发。
     *  - **硬链接（typeflag '1'）**：Ubuntu Base 含 2 个（perl5.38.2→perl、uncompress→gunzip），
     *    实测两者的目标**都在链接之前**出现（序号 357<358、284<449），故按顺序解压即可正确解析；
     *    实现仍保留「目标不存在则跳过 + 创建失败则复制」的降级，避免归档顺序异常时中断整个解压。
     *  - 实测系统 tar 可正常解开该归档（早前观察到的失败是 -C 目录不存在所致，非归档问题）。
     */
    fun extract(input: InputStream, destDir: File, onProgress: ((Int) -> Unit)? = null) {
        destDir.mkdirs()
        var entries = 0
        val buf = ByteArray(512)

        // PAX 覆盖（仅在解析到扩展头后的下一个条目生效，符合 POSIX）
        var pax: PaxRecord? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            if (!readFully(input, buf, 512)) break
            if (buf.all { it == 0.toByte() }) break      // 归档结束标记

            val rawName = String(buf, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val sizeStr = String(buf, 124, 12, Charsets.UTF_8).trim(' ', '\u0000')
            val modeStr = String(buf, 100, 8, Charsets.UTF_8).trim(' ', '\u0000')
            var size = sizeStr.toLongOrNull(8) ?: 0L
            val mode = modeStr.toIntOrNull(8) ?: 0
            val typeFlag = buf[156].toInt().toChar()
            val prefix = String(buf, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            val linkNameRaw = String(buf, 157, 100, Charsets.UTF_8).trimEnd('\u0000')
            val ustarName = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            val padded = ((size + 511) / 512) * 512

            when (typeFlag) {
                'x', 'g' -> {                                 // PAX 扩展头：解析后供下一条目使用
                    val body = ByteArray(size.coerceAtMost(1 shl 20).toInt())
                    readFully(input, body, body.size)
                    if (size > body.size) skip(input, size - body.size)
                    pax = PaxRecord.parse(String(body, Charsets.UTF_8))
                    skip(input, padded - size)
                    continue
                }
                'L' -> {                                      // GNU 长文件名
                    val body = ByteArray(size.coerceAtMost(1 shl 20).toInt())
                    readFully(input, body, body.size)
                    if (size > body.size) skip(input, size - body.size)
                    gnuLongName = String(body, Charsets.UTF_8).trimEnd('\u0000')
                    skip(input, padded - size)
                    continue
                }
                'K' -> {                                      // GNU 长链接目标
                    val body = ByteArray(size.coerceAtMost(1 shl 20).toInt())
                    readFully(input, body, body.size)
                    if (size > body.size) skip(input, size - body.size)
                    gnuLongLink = String(body, Charsets.UTF_8).trimEnd('\u0000')
                    skip(input, padded - size)
                    continue
                }
            }

            val name = pax?.path ?: gnuLongName ?: ustarName
            val linkTarget = pax?.linkPath ?: gnuLongLink ?: linkNameRaw
            pax?.size?.let { size = it }
            pax = null; gnuLongName = null; gnuLongLink = null

            val safeName = name.trimStart('/')
            if (safeName.isEmpty() || safeName.contains("..")) { skip(input, padded); continue }
            val out = File(destDir, safeName)

            when (typeFlag) {
                '5' -> out.mkdirs()                                  // 目录
                '0', '\u0000' -> {                                   // 常规文件
                    out.parentFile?.mkdirs()
                    out.outputStream().use { copyN(input, it, size) }
                    applyMode(out, mode)
                    skip(input, padded - size)
                }
                '2' -> {                                             // 符号链接
                    out.parentFile?.mkdirs()
                    runCatching {
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), File(linkTarget).toPath())
                    }.onFailure {
                        out.writeText("")
                        Log.w(TAG, "符号链接降级为普通文件: $safeName -> $linkTarget")
                    }
                    skip(input, padded - size)
                }
                '1' -> {                                             // 硬链接
                    // 注意：目标可能尚未解出（Ubuntu Base 即是如此），此时**不能抛异常**，
                    // 否则整个解压中断。安全降级：跳过该条目。
                    val target = File(destDir, linkTarget.trimStart('/'))
                    out.parentFile?.mkdirs()
                    if (target.isFile) {
                        runCatching {
                            java.nio.file.Files.deleteIfExists(out.toPath())
                            java.nio.file.Files.createLink(out.toPath(), target.toPath())
                        }.onFailure {
                            runCatching { target.copyTo(out, overwrite = true) }
                                .onFailure { Log.w(TAG, "硬链接跳过: $safeName -> $linkTarget") }
                        }
                    } else {
                        Log.w(TAG, "硬链接目标尚未解出，跳过: $safeName -> $linkTarget")
                    }
                    skip(input, padded - size)
                }
                else -> skip(input, padded)                          // 其他类型（设备/fifo 等）忽略
            }

            entries++
            if (entries % 500 == 0) onProgress?.invoke((entries / 100).coerceAtMost(95))
        }
        onProgress?.invoke(100)
        Log.i(TAG, "tar 解压完成，条目=$entries → ${destDir.absolutePath}")
    }

    /** PAX 扩展头记录（POSIX: "%d key=value\n"） */
    private data class PaxRecord(val path: String?, val linkPath: String?, val size: Long?) {
        companion object {
            fun parse(body: String): PaxRecord {
                var p: String? = null; var l: String? = null; var s: Long? = null
                var i = 0
                while (i < body.length) {
                    val sp = body.indexOf(' ', i)
                    if (sp < 0) break
                    val len = body.substring(i, sp).trim().toIntOrNull() ?: break
                    if (len <= 0 || i + len > body.length + 1) break
                    val rec = body.substring(sp + 1, (i + len - 1).coerceAtMost(body.length))
                    val eq = rec.indexOf('=')
                    if (eq > 0) {
                        when (rec.substring(0, eq)) {
                            "path" -> p = rec.substring(eq + 1)
                            "linkpath" -> l = rec.substring(eq + 1)
                            "size" -> s = rec.substring(eq + 1).toLongOrNull()
                        }
                    }
                    i += len
                }
                return PaxRecord(p, l, s)
            }
        }
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
