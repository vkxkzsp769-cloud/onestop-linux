package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * 用 bootstrap 自带（或系统）的 zstd 解压 assets 中的 .tar.zst，产出 tar 流。
 *
 * 方案 §5.1 的实现落点：先 `zstd -d -c` 解到 stdout，再交给 [ZstdTarExtractor] 解析 tar。
 * 优先顺序：bootstrap 的 zstd → 系统 PATH 的 zstd → 抛异常（由上层提示）。
 */
object ZstdDecompressor {

    private const val TAG = "ZstdDecompressor"

    /** 找到可用的 zstd 可执行文件。 */
    fun findZstd(ctx: Context): File? {
        val candidates = listOf(
            File(Environment.usrBin(ctx), "zstd"),   // Termux bootstrap 自带
            File("/system/bin/zstd"),                // 部分 ROM 自带
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * 把 [assetPath] 解压成 tar 流交给 [consumer]。
     * 调用方需保证 zstd 已就绪（[findZstd] 非 null），否则抛 IllegalStateException。
     */
    fun decompressAsset(ctx: Context, assetPath: String, consumer: (InputStream) -> Unit) {
        val zstd = findZstd(ctx)
            ?: throw IllegalStateException("找不到 zstd 可执行文件，无法解压 $assetPath")

        val tmp = File(ctx.cacheDir, "decompress").apply { mkdirs() }
        val packed = File(tmp, assetPath.substringAfterLast('/'))
        // 先把 asset 落到磁盘（zstd 需要文件输入；也便于失败重试）
        ctx.assets.open(assetPath).use { input -> packed.outputStream().use { input.copyTo(it) } }

        val pb = ProcessBuilder(zstd.absolutePath, "-d", "-c", "--long=27", packed.absolutePath)
        pb.redirectErrorStream(false)
        val proc = pb.start()

        val errThread = Thread {
            proc.errorStream.bufferedReader().useLines { it.forEach { l -> Log.w(TAG, "zstd: $l") } }
        }.apply { isDaemon = true; start() }

        try {
            proc.inputStream.use { consumer(it) }
        } finally {
            runCatching { proc.waitFor() }
            errThread.join(2_000)
            packed.delete()
        }
        Log.i(TAG, "解压完成: $assetPath")
    }
}
