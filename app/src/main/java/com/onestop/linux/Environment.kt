package com.onestop.linux.core

import android.content.Context
import java.io.File

/**
 * 全局路径与版本常量（方案 §2.3）。
 * 注意：所有路径都以「宿主视角」书写；容器内视角由 [ProotCommandBuilder] 负责换算。
 */
object Environment {

    /** rootfs 版本号：换包时递增，用于释放哨兵与增量升级（方案 §6.2）。 */
    const val ROOTFS_VERSION = "ubuntu-base-24.04.5-arm64-v2"

    /**
     * 内置 rootfs 资源：**Ubuntu Base 官方 tarball 原样（.tar.gz）**。
     * 真机实证（本机 Android 16）：用 bootstrap 的 GNU tar 解压 29 MB → 105 MB 仅需 1.2 秒；
     * 早期用的 tar.zst + Termux `zstd` 方案因 libzstd.so.1 缺失而完全不可用。
     * 后缀用 `.targz`（非 `.tar.gz`）：AGP 会按 `.gz` 后缀嗅探并把资产**解压**成纯 tar，
     * 导致 APK 内文件与运行时预期不一致（实测差 80 MB）。改名可保留 gzip 原样。
     * 运行时仍按**魔数**自适应（1f 8b → 走 tar -xz，否则走 tar -x）。
     */
    const val ROOTFS_ASSET = "rootfs/ubuntu-base-24.04.5-base-arm64.targz"

    /** 内置 Termux bootstrap（zip），提供 proot / loader / libtalloc / bash 等。 */
    const val BOOTSTRAP_ASSET = "bootstrap/bootstrap-aarch64.zip"

    const val POST_INSTALL_ASSET = "scripts/post-install.sh"

    // ---- 宿主侧路径 ----
    fun baseDir(ctx: Context): File = File(ctx.filesDir, "linux")
    fun bootstrapDir(ctx: Context): File = File(baseDir(ctx), "usr")
    fun usrBin(ctx: Context): File = File(bootstrapDir(ctx), "bin")
    fun usrLib(ctx: Context): File = File(bootstrapDir(ctx), "lib")
    fun prootTmpDir(ctx: Context): File = File(baseDir(ctx), "tmp/proot")
    fun homeDir(ctx: Context): File = File(baseDir(ctx), "home")
    fun rootfsDir(ctx: Context): File = File(baseDir(ctx), "rootfs")
    fun rootfsTmpDir(ctx: Context): File = File(baseDir(ctx), "rootfs.tmp")
    fun scriptsDir(ctx: Context): File = File(baseDir(ctx), "scripts")

    /** 释放完成哨兵（方案 §5.1）。 */
    fun installedMarker(ctx: Context): File =
        File(rootfsDir(ctx), ".installed-$ROOTFS_VERSION")

    /** Termux JNI 跑起来后，rootfs 内 shell 的入口。 */
    const val CONTAINER_SHELL = "/bin/bash"
    const val CONTAINER_USER = "root"
    const val CONTAINER_HOME = "/root"
}
