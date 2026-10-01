package com.onestop.linux.core

import android.content.Context
import java.io.File

/**
 * PRoot 启动参数生成器。
 *
 * 参数集与 doroUbuntu/LinuxHub 的实测可用集合对齐（方案 §12.3.1 / §5.7 ③），
 * 关键点：
 *  - `--link2symlink`：Android 无硬链接权限，靠 proot 把硬链接转成符号链接；
 *  - `-0`：把当前 uid 映射成容器内 root；
 *  - `/dev` `/proc` `/sys` 必须 bind，否则容器里读不到设备与进程信息；
 *  - `/proc/stat`、`/sys/class/power_supply` 用宿主侧生成的文件/目录「覆盖」bind，
 *    避免桌面组件读到 Android 语义的数据而崩溃（方案 §12.3.1）。
 */
object ProotCommandBuilder {

    /** 返回可直接交给 [com.termux.terminal.TerminalSession] 的 argv（首元素必须是可执行文件路径）。 */
    fun loginArgv(ctx: Context, extraArgs: List<String> = emptyList()): List<String> {
        val usr = Environment.usrBin(ctx)
        val proot = File(usr, "proot")
        val tmpDir = Environment.prootTmpDir(ctx).apply { mkdirs() }
        val rootfs = Environment.rootfsDir(ctx)
        val base = Environment.baseDir(ctx)

        val shm = File(base, "tmp/shm").apply { mkdirs() }
        val compatProc = File(base, "tmp/proc").apply { mkdirs() }
        val compatPower = File(base, "tmp/power_supply").apply { mkdirs() }

        // rootfs 内需要存在的挂载点（tar 解压后可能缺失）
        listOf("dev", "proc", "sys", "tmp").forEach { File(rootfs, it).mkdirs() }

        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        argv += "--kill-on-exit"
        argv += "--link2symlink"
        argv += "-0"
        argv += "-r"; argv += rootfs.absolutePath
        argv += "-w"; argv += Environment.CONTAINER_HOME
        argv += "-b"; argv += "/dev"
        argv += "-b"; argv += "/proc"
        argv += "-b"; argv += "/sys"
        argv += "-b"; argv += "${shm.absolutePath}:/dev/shm"
        argv += "-b"; argv += "${compatProc.absolutePath}/stat:/proc/stat"
        argv += "-b"; argv += "${compatPower.absolutePath}:/sys/class/power_supply"
        // 可用时把共享存储映射进容器（无权限时 proot 会忽略）
        argv += "-b"; argv += "/storage/emulated/0:/sdcard"
        argv += Environment.CONTAINER_SHELL
        argv += "--login"
        argv += extraArgs
        return argv
    }

    /**
     * 传给子进程的环境（Termux JNI 以 "VAR=value" 数组形式接收）。
     * 注意这些变量是 **容器外**（宿主进程）环境的变量，供 proot 自身与初始 shell 使用。
     */
    fun loginEnvironment(ctx: Context): Map<String, String> = mapOf(
        "HOME" to Environment.CONTAINER_HOME,
        "USER" to Environment.CONTAINER_USER,
        "SHELL" to Environment.CONTAINER_SHELL,
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TMPDIR" to "/tmp",
        // proot 自身需要的运行时变量（方案 §12.3.1）
        "PROOT_TMP_DIR" to Environment.prootTmpDir(ctx).absolutePath,
        "PROOT_LOADER" to File(Environment.usrLib(ctx), "libproot-loader.so").absolutePath,
        "LD_LIBRARY_PATH" to Environment.usrLib(ctx).absolutePath
    )

    /** 在容器内执行一条命令（用于 post-install、GPU 探测等一次性任务）。 */
    fun execArgv(ctx: Context, containerCommand: String): List<String> {
        val argv = loginArgv(ctx).toMutableList()
        // loginArgv 末尾是 "--login"，替换成真正的执行体
        argv.removeAt(argv.size - 1)
        argv += "-c"
        argv += containerCommand
        return argv
    }
}
