package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 释放 Termux bootstrap（方案 §5.1）。
 *
 * bootstrap-aarch64.zip 内含 `bin/proot`、`lib/libproot-loader.so`、`lib/libtalloc.so.2`
 * 以及 bash/coreutils 等运行时；这些是「Android 上可执行的」构建，不需要我们重新编译。
 *
 * 已知限制（详见方案 §12.3.4）：bootstrap zip 中的符号链接在 Java 侧只能做 best-effort
 * 还原（写普通文件或尝试 Files.createSymbolicLink）。若容器内出现软链失效，
 * 正确修法是在 CI 里把 bootstrap 转成 tar.zst（tar 保留 symlink 语义），而不是在 App 里补。
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    fun isInstalled(ctx: Context): Boolean {
        val proot = File(Environment.usrBin(ctx), "proot")
        val loader = File(Environment.usrLib(ctx), "libproot-loader.so")
        return proot.isFile && loader.isFile
    }

    /** @return 解出的条目数；失败抛异常，由调用方展示。 */
    fun install(ctx: Context, onProgress: ((Int) -> Unit)? = null): Int {
        val dest = Environment.bootstrapDir(ctx)
        dest.mkdirs()
        var count = 0

        ctx.assets.open(Environment.BOOTSTRAP_ASSET).use { raw ->
            ZipInputStream(raw.buffered(64 * 1024)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val outFile = File(dest, name)
                    // 防目录穿越
                    if (!outFile.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                        Log.w(TAG, "跳过可疑条目: $name")
                        zip.closeEntry(); entry = zip.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                        applyUnixMode(outFile, entry.name)
                        count++
                    }
                    if (count % 200 == 0) onProgress?.invoke(count)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        // bootstrap 里的 bin/ 与 lib/ 必须具备执行/读取权限
        File(dest, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(dest, "lib").listFiles()?.forEach { it.setReadable(true, false) }

        Log.i(TAG, "bootstrap 释放完成，条目数=$count → ${dest.absolutePath}")
        LogCollector.app("Bootstrap", "释放完成，条目数=$count")
        return count
    }

    /** zip 不保留 unix mode；按经验规则补回执行位（方案 §8.1.3 的简化版）。 */
    private fun applyUnixMode(f: File, entryName: String) {
        val name = entryName.substringAfterLast('/')
        val inBin = entryName.startsWith("bin/") || entryName.startsWith("libexec/")
        if (inBin || name.endsWith(".so") || name.contains(".so.")) {
            f.setExecutable(true, false)
        }
    }

    fun readStream(input: InputStream): Long = input.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
}
