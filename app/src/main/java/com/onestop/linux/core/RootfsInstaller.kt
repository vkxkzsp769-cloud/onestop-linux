package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 释放内置 Ubuntu 24.04 LTS ARM64 rootfs（方案 §5.1）。
 *
 * 策略：解压到 `rootfs.tmp` → 校验 → 原子 `renameTo` → 写哨兵。
 * 中断不会留下半成品（幂等，可重入）。
 */
object RootfsInstaller {

    private const val TAG = "RootfsInstaller"

    fun isInstalled(ctx: Context): Boolean =
        Environment.installedMarker(ctx).isFile && File(Environment.rootfsDir(ctx), "bin/bash").isFile

    /** @param onProgress 0..100 */
    fun install(ctx: Context, onProgress: ((Int) -> Unit)? = null) {
        if (isInstalled(ctx)) {
            Log.i(TAG, "rootfs 已释放，跳过"); onProgress?.invoke(100); return
        }
        val rootfs = Environment.rootfsDir(ctx)
        val tmp = Environment.rootfsTmpDir(ctx)

        // ① 清理旧临时目录（可能来自上次中断）
        tmp.deleteRecursively(); tmp.mkdirs()
        onProgress?.invoke(2)

        // ② 流式解压 tar.zst
        ctx.assets.open(Environment.ROOTFS_ASSET).use { input ->
            ZstdTarExtractor.extract(input, tmp) { pct -> onProgress?.invoke(2 + pct * 88 / 100) }
        }
        onProgress?.invoke(92)

        // ③ 原子落位（若已存在旧的，先删）
        if (rootfs.exists()) rootfs.deleteRecursively()
        if (!tmp.renameTo(rootfs)) {
            tmp.copyRecursively(rootfs, overwrite = true)
            tmp.deleteRecursively()
        }
        onProgress?.invoke(96)

        // ④ 兜底补执行位（方案 §8.1.3：优先用 tar mode，无清单时按 ELF/so 判定）
        ExecPermissionFixer.restore(rootfs)

        // ⑤ 搬运 post-install 脚本到容器可见位置
        stageScripts(ctx, rootfs)

        Environment.installedMarker(ctx).writeText("installedAt=${System.currentTimeMillis()}\n")
        onProgress?.invoke(100)
        Log.i(TAG, "rootfs 释放完成 → ${rootfs.absolutePath}")
    }

    /** 把 assets/scripts 下的脚本解到 rootfs 内，Path 用容器视角（方案 §5.1 的「⚠️ 搬运步骤」）。 */
    fun stageScripts(ctx: Context, rootfs: File = Environment.rootfsDir(ctx)): File {
        val dest = File(rootfs, "opt/onestop/scripts").apply { mkdirs() }
        runCatching {
            ctx.assets.list("scripts")?.forEach { name ->
                ctx.assets.open("scripts/$name").use { input ->
                    File(dest, name).outputStream().use { input.copyTo(it) }
                }
                File(dest, name).setExecutable(true, false)
            }
        }.onFailure { Log.w(TAG, "搬运脚本失败: ${it.message}") }
        return dest
    }

    /** 首启配置（DNS/用户/sudo/locale），在容器内执行。 */
    fun runPostInstall(ctx: Context, onLine: ((String) -> Unit)? = null): ProotRunner.Result {
        val script = File(Environment.rootfsDir(ctx), "opt/onestop/scripts/post-install.sh")
        if (!script.isFile) stageScripts(ctx)
        return ProotRunner.runInRootfs(ctx, "/bin/bash /opt/onestop/scripts/post-install.sh",
            timeoutSec = 1800, onLine = onLine)
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
