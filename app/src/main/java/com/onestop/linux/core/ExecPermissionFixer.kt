package com.onestop.linux.core

import android.util.Log
import java.io.File

/**
 * 执行位兜底修复（方案 §8.1.3）。
 *
 * 背景：Android 内部存储是 FUSE/ext4 混合语义，tar 解出的 mode 位在个别 ROM 上会被抹掉，
 *      表现为 `bash: Permission denied`。
 * 做法：**不做「按目录名猜」**（早期写法会把 README/.desktop 也变成可执行文件），
 *      而是按「文件内容/后缀」判定：
 *        ① ELF 魔数 \x7fELF 的常规文件 → 补 x
 *        ② *.so / *.so.* → 补 x
 *        ③ 首行为 #! 的脚本 → 补 x
 * 理想做法是读 tar mode 清单逐个比对（见方案 §8.1.3 的 modes.txt 方案）。
 */
object ExecPermissionFixer {

    private const val TAG = "ExecPermFixer"

    private val ELF = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    fun restore(rootfs: File) {
        var fixed = 0
        rootfs.walkTopDown()
            .onEnter { it.name != "proc" && it.name != "sys" && it.name != "dev" }
            .filter { it.isFile }
            .forEach { f ->
                val need = when {
                    f.name.endsWith(".so") || f.name.contains(".so.") -> true
                    hasElfMagic(f) -> true
                    startsWithShebang(f) -> true
                    else -> false
                }
                if (need && !f.canExecute()) {
                    if (f.setExecutable(true, false)) fixed++
                }
            }
        Log.i(TAG, "执行位兜底：修正 $fixed 个文件")
    }

    private fun hasElfMagic(f: File): Boolean = runCatching {
        if (f.length() < 4) return@runCatching false
        val b = ByteArray(4)
        f.inputStream().use { it.read(b) }
        b.contentEquals(ELF)
    }.getOrDefault(false)

    private fun startsWithShebang(f: File): Boolean = runCatching {
        if (f.length() < 2 || f.length() > 1 shl 20) return@runCatching false
        val b = ByteArray(2)
        f.inputStream().use { it.read(b) }
        b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()
    }.getOrDefault(false)
}
