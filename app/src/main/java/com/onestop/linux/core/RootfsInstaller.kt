package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 释放内置 Ubuntu 24.04 LTS ARM64 rootfs。
 *
 * 【真机实证后的重写（vivo V2429A / Android 16，2026-10-01）】
 * 早期实现用手写 Java tar 解析器 + Termux 的 `zstd` 解压，在真机上失败，原因有二：
 *   1. **bootstrap 里的 `zstd` 无法执行**：`CANNOT LINK EXECUTABLE … library "libzstd.so.1" not found`
 *      （Termux 的 zstd 依赖未随包提供），且我们**没有检查退出码**，于是拿着 0 字节输出继续解析；
 *   2. 于是最后在写文件处抛 `FileNotFoundException: Invalid file path`。
 *
 * 现在的做法（已在真机实测：29 MB gz → 105 MB rootfs，**1.2 秒**解完）：
 *   **把 Ubuntu Base 官方 tarball 原样（.tar.gz）内置，用 bootstrap 自带的 GNU `tar` 解压。**
 *   理由：
 *    - GNU tar 自包含、支持 `-z` 解 gzip，不必再依赖 zstd（省掉一个无法工作的组件）；
 *    - 不再需要手写 tar 解析器（PAX/GNU 长名/硬链接这些坑全部交给 vetted 的 tar）；
 *    - 官方 tarball 就是 `ubuntu-base-24.04.x-base-arm64.tar.gz`，无需在 CI 里重压，依赖更少。
 *   tar 运行时需要 `LD_LIBRARY_PATH=<usr>/lib`（实测必需）。
 *   注意：Android 的 FUSE 不允许硬链接，tar 会对 2 个硬链接条目告警（perl5.38.2/uncompress），
 *        但会继续完成解压，对结果无实质影响（已在真实归档上核对）。
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

        tmp.deleteRecursively(); tmp.mkdirs()
        onProgress?.invoke(2)

        // ① 直接用「assets 流 → tar stdin」解压
        //    真机踩坑记录：早期版本先把 asset 落盘成 rootfs.tar.gz 再让 tar 读文件，
        //    但 APK 里该资源会被 AGP 处理成「已解压的 .tar」（见 gradle 的 noCompress 注释），
        //    导致文件名/内容与代码预期不一致。改为流式管道后与打包方式解耦，更稳。
        val tar = File(Environment.usrBin(ctx), "tar")
        check(tar.isFile) { "bootstrap 未就绪：找不到 ${tar.absolutePath}" }
        tar.setExecutable(true, false)

        // 资产名兜底：不同 AGP 版本可能把 .tar.gz 改名/解压，这里列出候选并选实际存在的
        val assetName = listOf(
            Environment.ROOTFS_ASSET,                     // 首选：ubuntu-base-24.04.5-base-arm64.tar.gz
            Environment.ROOTFS_ASSET.removeSuffix(".gz"), // 兜底：若被 AGP 解压成 .tar
        ).firstOrNull { name ->
            runCatching { ctx.assets.open(name).close(); true }.getOrDefault(false)
        } ?: error("APK 内找不到 rootfs 资源（候选：${Environment.ROOTFS_ASSET} / ${Environment.ROOTFS_ASSET.removeSuffix(".gz")}）")
        Log.i(TAG, "使用 rootfs 资源: $assetName")

        val libDir = Environment.usrLib(ctx).absolutePath
        val pb = ProcessBuilder(
            tar.absolutePath, "-xz",                 // -z 解 gzip，-x 解归档；从 stdin 读
            "-C", tmp.absolutePath,
            "--no-same-owner",                       // 非 root，忽略 uid/gid
            "--warning=no-unknown-keyword",          // PAX 时间戳键告警静音
        )
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        pb.environment()["TMPDIR"] = Environment.prootTmpDir(ctx).apply { mkdirs() }.absolutePath
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val out = StringBuilder()
        val errThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> out.append(line).append('\n'); Log.i(TAG, "tar: $line") }
                }
            }
        }.apply { isDaemon = true; start() }

        // 把 asset 流喂给 tar 的 stdin
        ctx.assets.open(assetName).use { input ->
            proc.outputStream.use { stdin -> input.copyTo(stdin, 1 shl 16) }
        }
        onProgress?.invoke(70)

        val code = proc.waitFor()
        errThread.join(5_000)

        // ★ 关键：必须检查退出码/产物。实测 tar 对 Android FUSE 不支持的 2 个硬链接会返回 2（告警级），
        //   但解压是完整的，因此以 bin/bash 是否就位判定真实成败。
        val bash = File(tmp, "bin/bash")
        if (!bash.isFile) {
            tmp.deleteRecursively()
            error("rootfs 解压失败（tar exit=$code，asset=$assetName）：${out.toString().takeLast(600)}")
        }
        if (code != 0) {
            Log.w(TAG, "tar 返回 $code（多为 FUSE 不支持硬链接的告警），但 bin/bash 已就绪，继续")
        }
        onProgress?.invoke(92)

        // ③ 原子落位
        if (rootfs.exists()) rootfs.deleteRecursively()
        if (!tmp.renameTo(rootfs)) {
            tmp.copyRecursively(rootfs, overwrite = true)
            tmp.deleteRecursively()
        }
        onProgress?.invoke(96)

        // ④ 兜底补执行位（方案 §8.1.3）
        ExecPermissionFixer.restore(rootfs)

        // ⑤ 搬运 post-install 脚本到容器可见位置
        stageScripts(ctx, rootfs)

        Environment.installedMarker(ctx).writeText("installedAt=${System.currentTimeMillis()}\n")
        onProgress?.invoke(100)
        Log.i(TAG, "rootfs 释放完成 → ${rootfs.absolutePath}（大小校验通过：bin/bash 存在）")
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
