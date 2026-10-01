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
        setContentView(R.layout.activity_main)
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
                try {
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
                    val check = ProotRunner.selfCheck(applicationContext)
                    if (check.exitCode == 0 && check.stdout.contains("Ubuntu")) {
                        "Ubuntu 24.04 就绪"
                    } else {
                        "环境就绪但自检异常：${check.stdout.take(200)}${check.stderr.take(200)}"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "环境准备失败", t)
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

    override fun onDestroy() {
        detector.stop()
        detectorScope.cancel()
        super.onDestroy()
    }
}
