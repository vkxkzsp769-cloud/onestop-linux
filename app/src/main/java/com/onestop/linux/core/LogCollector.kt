package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 全流程日志采集（唯一入口）。
 *
 * 设计目标：**一次导出即可看到软件运行的全部过程**，无需 adb。
 *
 * 采集来源（三路合一，按时间戳写入同一个文件）：
 *  1. [app]       —— 应用自身所有关键步骤（启动、释放、探测、异常堆栈）
 *  2. [proc]      —— 子进程原始输出（proot / tar / 容器命令），含 stdout+stderr 与退出码
 *  3. [session]   —— 终端会话的原始 PTY 输出（即用户在终端里看到的一切，含容器内报错）
 *
 * 另在导出时附带环境快照（设备型号/ABI/Android 版本、ABI 目录、资源是否存在、目录树摘要），
 * 这样即使「什么都没跑起来」，也能从快照判断卡在哪一步。
 *
 * 写入策略：单文件追加 + 到达上限自动轮转（保留 1 个 .1 备份），避免长跑把磁盘写满。
 * 线程安全：所有写入经单线程 executor 串行化，避免多线程交错导致日志错乱。
 */
object LogCollector {

    private const val TAG = "LogCollector"

    /** 是否把日志同时镜像到 logcat。默认关闭：logcat 写入在某些 ROM 上会阻塞主线程。 */
    private const val MIRROR_TO_LOGCAT = false

    /** 单文件上限（超出则轮转为 .1）。 */
    private const val MAX_BYTES = 8L * 1024 * 1024     // 8 MB

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "onestop-log").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val droppedLines = AtomicLong(0)

    @Volatile private var logFile: File? = null
    @Volatile private var sessionTapEnabled = true

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun logFile(ctx: Context): File = File(Environment.logsDir(ctx), "onestop.log")

    /** 幂等初始化；任何地方可直接调用。 */
    fun init(ctx: Context): File {
        val f = logFile(ctx)
        if (started.compareAndSet(false, true)) {
            f.parentFile?.mkdirs()
            logFile = f
            write("app", "===== OneStop Linux 日志开始 =====")
            snapshot(ctx)
        }
        return f
    }

    // ---------------- 三路写入 ----------------

    /**
     * 应用自身日志。
     *
     * ★ 性能相关（真机踩坑）：**默认不再调用 android.util.Log**。
     *   原因：「按键 → 回显」实测只有 1~3ms，但用户仍感觉字母出现很慢，
     *   怀疑主线程被阻塞。逐键打点时每个按键都会走一次 [app]，
     *   而部分 ROM（vivo/HyperOS 等）的 logcat 写入会在调用线程上阻塞，
     *   足以造成可见卡顿。现在只做「异步文件写入」，完全不动 logcat。
     *   需要 logcat 时把 [MIRROR_TO_LOGCAT] 改为 true（仅调试用）。
     */
    fun app(tag: String, msg: String, t: Throwable? = null) {
        if (MIRROR_TO_LOGCAT) Log.i("OneStop/$tag", msg)
        write("app", "[$tag] $msg")
        t?.let { write("app", "[$tag] 异常: ${it.javaClass.simpleName}: ${it.message}\n" + it.stackTraceToString()) }
    }

    /** 子进程输出（一行一条）。 */
    fun proc(name: String, line: String) = write("proc", "[$name] $line")

    /** 子进程结束（含退出码与耗时）。 */
    fun procExit(name: String, exitCode: Int, elapsedMs: Long, timeout: Boolean = false) =
        write("proc", "[$name] 退出码=$exitCode 耗时=${elapsedMs}ms${if (timeout) " ⚠超时" else ""}")

    /** 终端会话原始输出（批量，避免逐字符写爆 IO）。 */
    fun session(data: String) {
        if (!sessionTapEnabled || data.isEmpty()) return
        write("session", data.trimEnd('\n'))
    }

    fun setSessionTapEnabled(enabled: Boolean) { sessionTapEnabled = enabled }

    /** 供 UI 显示的一行状态。 */
    fun statusLine(ctx: Context): String {
        val f = logFile(ctx)
        val size = if (f.isFile) f.length() else 0
        return "日志 ${size / 1024} KB" + if (droppedLines.get() > 0) "（丢弃 ${droppedLines.get()} 行）" else ""
    }

    // ---------------- 内部写入 ----------------

    private fun write(channel: String, text: String) {
        val f = logFile ?: return
        io.execute {
            try {
                if (f.length() > MAX_BYTES) {
                    val bak = File(f.parentFile, f.name + ".1")
                    runCatching { bak.delete() }
                    runCatching { f.renameTo(bak) }
                }
                f.appendText("${ts.format(Date())} [$channel] $text\n")
            } catch (e: Throwable) {
                droppedLines.incrementAndGet()
            }
        }
    }

    // ---------------- 环境快照 ----------------

    private fun snapshot(ctx: Context) {
        val sb = StringBuilder()
        sb.appendLine("--- 环境快照 ---")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("应用: targetSdk=${ctx.applicationInfo.targetSdkVersion} versionCode=${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(-1)}")
        sb.appendLine("nativeLibraryDir: ${ctx.applicationInfo.nativeLibraryDir}")
        runCatching {
            File(ctx.applicationInfo.nativeLibraryDir).listFiles()?.forEach {
                sb.appendLine("  lib: ${it.name} ${it.length()}B canExec=${it.canExecute()}")
            }
        }
        sb.appendLine("dataDir: ${ctx.applicationInfo.dataDir}")
        sb.appendLine("rootfs 资产: ${Environment.ROOTFS_ASSET}")
        sb.appendLine("bootstrap 资产: ${Environment.BOOTSTRAP_ASSET}")
        runCatching {
            val r = Environment.rootfsDir(ctx)
            sb.appendLine("rootfsDir 存在=${r.isDirectory} bin/bash 存在=${File(r, "bin/bash").isFile} 哨兵=${Environment.installedMarker(ctx).isFile}")
            val u = Environment.bootstrapDir(ctx)
            sb.appendLine("bootstrapDir 存在=${u.isDirectory} bin/proot=${File(Environment.usrBin(ctx), "proot").exists()} bin/tar=${File(Environment.usrBin(ctx), "tar").exists()}")
            File(Environment.usrLib(ctx), "libtalloc.so.2").let { sb.appendLine("usr/lib/libtalloc.so.2 存在=${it.isFile} 大小=${it.length()}") }
        }
        runCatching { sb.appendLine("可用内存: ${Runtime.getRuntime().let { "${(it.totalMemory() - it.freeMemory()) / 1048576}MB / ${it.maxMemory() / 1048576}MB" }}") }
        sb.appendLine("会话抓屏日志(sessionTap): $sessionTapEnabled （关闭可减少主线程长字符串分配）")
        write("app", sb.toString().trimEnd())
    }

    /** 导出：把主日志 + 轮转备份 + 环境快照副本写到目标文件，返回行数。 */
    fun exportTo(ctx: Context, target: File): Long {
        val src = logFile(ctx)
        target.parentFile?.mkdirs()
        var lines = 0L
        target.outputStream().use { out ->
            out.write("# OneStop Linux 诊断日志导出\n".toByteArray())
            listOf(File(src.parentFile, src.name + ".1"), src).forEach { f ->
                if (f.isFile) {
                    f.readLines().forEach { out.write((it + "\n").toByteArray()); lines++ }
                }
            }
            out.write("\n# ---- 终端会话快照（最近 200 行）----\n".toByteArray())
            runCatching {
                val sh = File(Environment.baseDir(ctx), "last-session.log")
                if (sh.isFile) sh.readLines().takeLast(200).forEach { out.write(("$it\n").toByteArray()) }
            }
        }
        return lines
    }
}
