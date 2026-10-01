package com.onestop.linux

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.onestop.linux.core.AppEvent
import com.onestop.linux.core.BootstrapInstaller
import com.onestop.linux.core.EventBus
import com.onestop.linux.core.LogCollector
import com.onestop.linux.core.ProotCommandBuilder
import com.onestop.linux.core.ProotRunner
import com.onestop.linux.core.RootfsInstaller
import com.onestop.linux.core.ServiceDetector
import com.onestop.linux.service.LinuxSessionService
import com.onestop.linux.ui.TerminalFragment
import com.onestop.linux.ui.WebFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单 Activity + Tab（终端 / 网页）结构（方案 ADR-008）。
 * 启动流程：bootstrap 释放 → rootfs 释放 → 起前台服务 → 终端可用 → 后台开始检测 dsh web。
 */
class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var pager: ViewPager2
    private lateinit var tabs: TabLayout
    private var terminalFragment: TerminalFragment? = null
    private var webFragment: WebFragment? = null
    private val detector by lazy { ServiceDetector(applicationContext) }
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoSwitch = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志采集必须最早初始化：这样「启动就崩」也能留下痕迹
        LogCollector.init(this)
        installCrashHandler()
        LogCollector.app("App", "MainActivity.onCreate 开始")
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_log -> { exportLog(share = false); true }
                R.id.action_share_log -> { exportLog(share = true); true }
                R.id.action_clear_log -> {
                    runCatching { LogCollector.logFile(this).writeText("") }
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show(); true
                }
                R.id.action_selfcheck -> { runSelfCheck(); true }
                else -> false
            }
        }
        pager = findViewById(R.id.pager)
        tabs = findViewById(R.id.tabs)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = when (position) {
                0 -> TerminalFragment().also { terminalFragment = it }
                else -> WebFragment().also { webFragment = it }
            }
        }
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = getString(if (pos == 0) R.string.tab_terminal else R.string.tab_web)
        }.attach()

        requestNotificationPermission()
        startSessionService()
        prepareEnvironment()
        observeEvents()
    }

    /** APK 内置资源的释放：bootstrap（proot/loader）→ rootfs（Ubuntu 24.04）。 */
    private fun prepareEnvironment() {
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    LogCollector.app("Env", "开始环境准备（bootstrap=${BootstrapInstaller.isInstalled(applicationContext)} rootfs=${RootfsInstaller.isInstalled(applicationContext)}）")
                    if (!BootstrapInstaller.isInstalled(applicationContext)) {
                        EventBus.emit(AppEvent.ContainerState("首次启动：正在释放运行环境…", false))
                        BootstrapInstaller.install(applicationContext)
                    }
                    // 必须在任何 ProotRunner/TerminalSession 之前：把 libtalloc.so 复制成 libtalloc.so.2
                    ProotCommandBuilder.ensureRuntimeLibs(applicationContext)
                    if (!RootfsInstaller.isInstalled(applicationContext)) {
                        EventBus.emit(AppEvent.ContainerState("首次启动：正在释放 Ubuntu 24.04…", false))
                        RootfsInstaller.install(applicationContext)
                        RootfsInstaller.runPostInstall(applicationContext) { line ->
                            Log.i(TAG, "post-install: $line")
                        }
                    }
                    LogCollector.app("Env", "释放阶段完成，耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms，开始容器自检")
                    val check = ProotRunner.selfCheck(applicationContext)
                    if (check.exitCode == 0 && check.stdout.contains("Ubuntu")) {
                        "Ubuntu 24.04 就绪"
                    } else {
                        "环境就绪但自检异常：${check.stdout.take(200)}${check.stderr.take(200)}"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "环境准备失败", t)
                    LogCollector.app("Env", "环境准备失败（耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms）", t)
                    "环境准备失败：${t.message}"
                }
            }
            EventBus.emit(AppEvent.ContainerState(msg, msg.contains("就绪")))
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()

            if (msg.contains("就绪")) {
                // 注意：detector.start 内部是「永不退出」的轮询循环，必须放到独立作用域，
                //      不能 await（否则本节协程永不返回）。
                detectorScope.launch { detector.start(this, ::onServiceFound, ::onServiceLost) }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            EventBus.events.collect { e ->
                when (e) {
                    is AppEvent.ServiceUp -> {
                        Toast.makeText(this@MainActivity,
                            "检测到 dsh web：127.0.0.1:${e.found.port}", Toast.LENGTH_SHORT).show()
                        webFragment?.loadService(e.found.url, e.found.token)
                        if (autoSwitch) pager.currentItem = 1
                    }
                    AppEvent.ServiceDown -> {
                        webFragment?.showPlaceholder("dsh web 服务已停止。回到终端重新运行 <code>dsh web</code>。")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun onServiceFound(found: ServiceDetector.Found) {
        EventBus.emit(AppEvent.ServiceUp(found))
        if (!detector.verifyLoopbackOnly(found.port)) {
            Log.w(TAG, "dsh web 可能绑定在 0.0.0.0，建议改为 --host 127.0.0.1")
        }
    }

    private fun onServiceLost() = EventBus.emit(AppEvent.ServiceDown)

    private fun startSessionService() {
        val intent = Intent(this, LinuxSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ---------------- 日志导出 / 自检 / 崩溃捕获 ----------------

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { LogCollector.app("CRASH", "未捕获异常（线程 ${t.name}）", e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 导出一条包含「全部过程」的日志：写入 Download/ 或直接分享。 */
    private fun exportLog(share: Boolean) {
        lifecycleScope.launch {
            val (file, msg) = withContext(Dispatchers.IO) {
                try {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val name = "onestop-log-$stamp.txt"
                    // ① 先写到应用私有目录（一定能写成功）
                    val staged = java.io.File(cacheDir, name)
                    val lines = LogCollector.exportTo(this@MainActivity, staged)

                    // ② 再复制到公共 Download 目录
                    val pubPath = copyToDownloads(name, staged)
                    LogCollector.app("App", "日志已导出: $pubPath ($lines 行)")
                    staged to "已导出 $lines 行\n$pubPath"
                } catch (t: Throwable) {
                    LogCollector.app("App", "导出日志失败", t)
                    null to "导出失败: ${t.message}"
                }
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            if (share && file != null) shareLog(file)
        }
    }

    /** 复制到公共 Download 目录；失败则返回私有路径（用户可以自己找）。 */
    private fun copyToDownloads(name: String, src: java.io.File): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    return "/Download/$name"
                }
            }
            // 兜底：直接写公共目录（legacy 存储）
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dst = java.io.File(dir, name)
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (t: Throwable) {
            LogCollector.app("App", "写入 Download 失败，回退私有目录: ${t.message}")
            src.absolutePath
        }
    }

    private fun shareLog(file: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.action_share_log)))
        }.onFailure {
            LogCollector.app("App", "分享日志失败: ${it.message}")
        }
    }

    /** 一键环境自检：把能说明问题的探测结果全部写进日志，并弹结果。 */
    private fun runSelfCheck() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.appendLine("=== 环境自检 ===")
                sb.appendLine("bootstrap: ${com.onestop.linux.core.BootstrapInstaller.isInstalled(applicationContext)}")
                sb.appendLine("rootfs: ${com.onestop.linux.core.RootfsInstaller.isInstalled(applicationContext)}")
                runCatching { com.onestop.linux.core.ProotCommandBuilder.ensureRuntimeLibs(applicationContext) }
                // Environment.usrLib() 已返回 File，不要再套一层构造函数（曾因此编译失败）
                val usrLib: java.io.File = com.onestop.linux.core.Environment.usrLib(applicationContext)
                listOf("libtalloc.so.2", "libandroid-shmem.so").forEach { libName ->
                    val f = java.io.File(usrLib, libName)
                    sb.appendLine("usr/lib/$libName 存在=${f.isFile} 大小=${f.length()}")
                }
                sb.appendLine("--- 容器探针（proot 进容器执行 cat /etc/os-release）---")
                val r = com.onestop.linux.core.ProotRunner.runInRootfs(
                    applicationContext, "cat /etc/os-release | head -3; echo ---; uname -m; id -u", 120)
                sb.appendLine("exit=${r.exitCode} timeout=${r.timedOut}")
                sb.appendLine("stdout:\n${r.stdout}")
                if (r.stderr.isNotBlank()) sb.appendLine("stderr:\n${r.stderr}")
                sb.appendLine("=== 自检结束 ===")
                sb.toString()
            }
            LogCollector.app("SelfCheck", report)
            Toast.makeText(this@MainActivity, "自检完成，结果已写入日志（请导出查看）", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        detector.stop()
        detectorScope.cancel()
        LogCollector.app("App", "MainActivity.onDestroy")
        super.onDestroy()
    }
}
