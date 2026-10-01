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

    /**
     * 把 nativeLibraryDir 里「文件名与 SONAME 不一致」的库复制到私有目录并按 SONAME 命名。
     *
     * 为什么必须这么做（真机实测的坑）：
     *  - AGP 只打包匹配 `lib*.so` 的文件 → `libtalloc.so.2` 会被静默丢弃，所以 jniLibs 里只能叫 `libtalloc.so`；
     *  - 动态链接器按 SONAME 查找（proot 需要 `libtalloc.so.2`）→ 文件名 `libtalloc.so` 又满足不了。
     * 解法即本函数：复制到 `<usr>/lib/libtalloc.so.2`，并把该目录放在 LD_LIBRARY_PATH 首位。
     * （已在 vivo V2429A / Android 16 上验证：复制后 proot 立即跑通）
     */
    fun ensureRuntimeLibs(ctx: Context) {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val libDir = Environment.usrLib(ctx).apply { mkdirs() }
        // 源文件名(在 nativeLibraryDir) → 目标文件名(=SONAME)
        val alias = mapOf(
            "libtalloc.so" to "libtalloc.so.2",
        )
        alias.forEach { (src, dst) ->
            val from = File(nativeDir, src)
            val to = File(libDir, dst)
            if (!from.isFile) return@forEach
            if (to.isFile && to.length() == from.length()) return@forEach   // 已就绪
            runCatching {
                from.copyTo(to, overwrite = true)
                to.setReadable(true, false); to.setExecutable(true, false)
                android.util.Log.i("ProotCommandBuilder", "已就位运行时库: $dst (${to.length()} bytes)")
            }.onFailure { android.util.Log.w("ProotCommandBuilder", "复制 $src → $dst 失败: ${it.message}") }
        }
    }

    /** 返回可直接交给 [com.termux.terminal.TerminalSession] 的 argv（首元素必须是可执行文件路径）。 */
    fun loginArgv(ctx: Context, extraArgs: List<String> = emptyList()): List<String> {
        val usr = Environment.usrBin(ctx)
        // proot 优先取 nativeLibraryDir（PackageManager 会把它解压为可执行文件，
        // 见方案 ADR-003 / §12.3.1）；bootstrap 内的副本仅作兜底。
        val proot = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
            .takeIf { it.isFile && it.canExecute() }
            ?: File(usr, "proot")
        val tmpDir = Environment.prootTmpDir(ctx).apply { mkdirs() }
        val rootfs = Environment.rootfsDir(ctx)
        val base = Environment.baseDir(ctx)

        val shm = File(base, "tmp/shm").apply { mkdirs() }
        val compatProc = File(base, "tmp/proc").apply { mkdirs() }
        val compatPower = File(base, "tmp/power_supply").apply { mkdirs() }
        // ★ 真机截图暴露：proot 报
        //   can't sanitize binding "…/tmp/proc/stat": No such file or directory
        //   即我们 bind 了这个文件却从未创建它。必须在启动 proot 前生成（宿主机视角的假 /proc/stat）。
        ensureCompatStat(compatProc)
        ensureCompatPowerSupply(compatPower)

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
        // proot 自身需要的运行时变量（真机实证后修正）
        "PROOT_TMP_DIR" to Environment.prootTmpDir(ctx).absolutePath,
        // loader 必须与 proot 同目录（nativeLibraryDir 是唯一「可执行」的私有目录）。
        // 真机实证：Termux deb 内为 usr/libexec/proot/loader，CI 中重命名为 libproot-loader.so 落到 jniLibs/
        "PROOT_LOADER" to File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath,
        // ★ 关键：proot 依赖 libtalloc.so.2 与 libandroid-shmem.so，
        //   真机实测这两者缺失时报 `CANNOT LINK EXECUTABLE`。
        //   二者已随 jniLibs 安装到 nativeLibraryDir，故 LD_LIBRARY_PATH 必须**首位**指向它。
        //   （usr/lib 仍需保留，供容器内进程使用）
        "LD_LIBRARY_PATH" to listOf(
            Environment.usrLib(ctx).absolutePath,        // ★ 首位：libtalloc.so.2 的落地处
            ctx.applicationInfo.nativeLibraryDir,        // 其次：libandroid-shmem.so / libproot-loader.so
        ).joinToString(":")
    )

    /**
     * 生成供容器 bind 覆盖用的 `/proc/stat`。
     * 真机报错 "can't sanitize binding …/tmp/proc/stat" 就是缺这个文件。
     * 数值不追求真实，只保证「格式合法 + 随时钟单调递增」，避免桌面负载组件崩溃/除零。
     */
    private fun ensureCompatStat(dir: File) {
        val f = File(dir, "stat")
        runCatching {
            val ticks = (android.os.SystemClock.elapsedRealtime() / 10L).coerceAtLeast(1L)
            val idle = ticks * 95 / 100
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            f.writeText(buildString {
                append("cpu  $ticks 0 0 $idle 0 0 0 0 0 0\n")
                repeat(cores) { append("cpu$it $ticks 0 0 $idle 0 0 0 0 0 0\n") }
                append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
            })
        }.onFailure { android.util.Log.w("ProotCommandBuilder", "写 compat stat 失败: ${it.message}") }
    }

    /** 生成供容器 bind 覆盖用的假电池（否则 upower/xfce4-power-manager 会刷日志）。 */
    private fun ensureCompatPowerSupply(dir: File) {
        val bat = File(dir, "BAT0").apply { mkdirs() }
        runCatching {
            listOf(
                "type" to "Battery", "status" to "Discharging", "capacity" to "78", "present" to "1",
                "energy_full" to "4200000", "energy_now" to "3276000",
                "voltage_now" to "3800000", "power_now" to "1200000",
                "technology" to "Li-ion", "cycle_count" to "50",
            ).forEach { (k, v) -> File(bat, k).writeText("$v\n") }
        }.onFailure { android.util.Log.w("ProotCommandBuilder", "写 compat power_supply 失败: ${it.message}") }
    }

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
