package com.onestop.linux.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.onestop.linux.R
import com.onestop.linux.core.Environment
import com.onestop.linux.core.EventBus
import com.onestop.linux.core.LogCollector
import com.onestop.linux.core.AppEvent
import com.onestop.linux.core.ProotCommandBuilder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 终端页（方案 §8.1）：打开 App 即进入内置 Ubuntu 24.04 的 shell。
 *
 * 复用 Termux 的 TerminalView / TerminalSession（terminal-emulator 模块），
 * 只负责：构造 proot argv → 建会话 → 实现两个 Client 接口。
 */
class TerminalFragment : Fragment() {

    private var terminalView: TerminalView? = null
    private var lastScreenDump = 0L
    private var lastRows = -1
    private var lastViewH = -1
    private var watched = false
    /** 是否把终端屏幕内容写入日志。默认关闭：toString() 会分配整屏长字符串，属于主线程开销。 */
    private var sessionTapEnabled = false
    /** 最近一次按键下发时刻（用于端到端延迟测量）。 */
    @Volatile private var lastInputAt = 0L
    /** 本按键的起点（用于打印各阶段耗时）。 */
    @Volatile private var keyT0 = 0L
    @Volatile private var keySeq = 0
    /** 统计：最近 N 次延迟，导出日志时给出中位数/最大值。 */
    private val latencySamples = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private var lastScreenText = ""
    private var session: TerminalSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_terminal, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tv = view.findViewById<TerminalView>(R.id.terminal_view)
        terminalView = tv
        tv.setTerminalViewClient(viewClient)
        // 订阅「环境就绪」事件：就绪后再启动会话（避免 rootfs 未解完导致 proot 失败）
        scope.launch {
            com.onestop.linux.core.EventBus.events.collect { e ->
                if (e is com.onestop.linux.core.AppEvent.EnvironmentReady && session == null) {
                    LogCollector.app("Terminal", "收到 EnvironmentReady，启动会话")
                    startSession()
                }
            }
        }
        tv.setTextSize(28)

        // ★ 不再立即启动会话：等环境就绪（见 MainActivity 发 EnvironmentReady）。
        //   若环境已就绪（例如二次进入），EnvironmentReady 不会再发，故这里主动探测一次。
        if (session == null) {
            val ctxReady = com.onestop.linux.core.BootstrapInstaller.isInstalled(requireContext()) &&
                    com.onestop.linux.core.RootfsInstaller.isInstalled(requireContext())
            if (ctxReady) {
                LogCollector.app("Terminal", "环境已就绪，直接启动会话")
                startSession()
            } else {
                LogCollector.app("Terminal", "等待环境就绪后再启动会话")
                // 等 MainActivity 的 EnvironmentReady 事件
            }
        }
        // ★ 性能修复（真机日志证据）：曾在此处「焦点变化触发 + postDelayed 300ms」各调一次
        //   showSoftInput，导致软键盘反复弹收 → TerminalView.updateSize() 在 68↔33 行之间抖动
        //   → 每次重排整屏并向 shell 发 SIGWINCH，表现为「敲键后很久才回显」。
        //   现在只保留「一次性 requestFocus」，键盘由用户点击或系统策略弹出（与 Termux 行为一致）。
        tv.isFocusableInTouchMode = true
        tv.requestFocus()
        // 尺寸诊断：每次布局变化都记录行数（这是判定「谁在改高度」的关键数据）
        if (!watched) {
            watched = true
            // 记录「绘制完成」耗时，区分主线程被绘制阻塞的情况
        tv.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                val delta = (oldBottom - oldTop) - (bottom - top)
                diagSize("layoutChange(deltaH=$delta)", tv as TerminalView)
            }
            tv.viewTreeObserver.addOnGlobalLayoutListener {
                diagSize("globalLayout", tv)
            }
        }
    }

    /** 显式请求软键盘（TerminalView 是 InputConnection 宿主，但仍需一次显式触发）。 */
    private fun showIme(view: android.view.View) {
        runCatching {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return@runCatching
            view.requestFocus()
            imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startSession() {
        val ctx = requireContext().applicationContext
        val argv = ProotCommandBuilder.loginArgv(ctx)
        val envp = ProotCommandBuilder.loginEnvironment(ctx)
            .map { (k, v) -> "$k=$v" }.toTypedArray()

        LogCollector.init(ctx)
        LogCollector.app("Terminal", "创建会话: argv=${argv.joinToString(" ")}")
        val s = TerminalSession(
            argv.first(),                       // proot 可执行文件（Termux JNI 直接 exec 它）
            Environment.baseDir(ctx).absolutePath,
            argv.drop(1).toTypedArray(),
            envp,
            2000,                               // transcriptRows
            sessionClient
        )
        session = s
        terminalView?.attachSession(s)
    }

    /** 供外部（如服务检测到 dsh web）向终端写入一条命令。 */
    fun sendCommand(cmd: String) {
        session?.write("$cmd\n")
    }

    override fun onResume() {
        super.onResume()
        terminalView?.onScreenUpdated()
        // 尺寸诊断：记录 Fragment 恢复时的视口尺寸，用于排查「行数抖动」
        terminalView?.let {
            val emu = it.mEmulator
            LogCollector.app("SizeDiag", "onResume viewHeight=${it.height} viewWidth=${it.width} " +
                "rows=${emu?.mRows ?: -1} cols=${emu?.mColumns ?: -1}")
        }
    }

    /**
     * 尺寸变化诊断：记录行数变化时的视口尺寸，以及**根窗口 vs 可见区域**的差值。
     * 差值 = 被软键盘（IME）占掉的高度。若差值在 0 与 ~1000px 之间反复切换，
     * 说明抖动来源就是 IME 的弹收（这正是我们怀疑的根因）。
     */
    private fun diagSize(reason: String, tv: TerminalView) {
        // 注意：TerminalEmulator.mScreen 是私有的，公开的是 mRows / mColumns
        val emu = tv.mEmulator
        val rows = emu?.mRows ?: -1
        val cols = emu?.mColumns ?: -1
        if (rows == lastRows && lastViewH == tv.height) return
        lastRows = rows; lastViewH = tv.height
        val act = activity ?: return
        val root = act.window?.decorView
        val visible = android.graphics.Rect().also { root?.getWindowVisibleDisplayFrame(it) }
        val rootH = root?.height ?: -1
        val imeHidden = rootH - visible.bottom      // >0 表示被 IME/系统栏遮挡的高度
        LogCollector.app("SizeDiag",
            "$reason rows=$rows cols=$cols viewH=${tv.height} viewW=${tv.width} " +
            "measuredH=${tv.measuredHeight} rootH=$rootH visibleBottom=${visible.bottom} " +
            "遮挡高度=$imeHidden imeVisible=${imeHidden > 150}")
    }

    override fun onDestroyView() {
        terminalView = null
        super.onDestroyView()
    }

    /** 把输入延迟统计写进日志（导出时能看到中位数/最大值）。 */
    fun dumpLatencyStats() {
        val list = latencySamples.toList().sorted()
        if (list.isEmpty()) { LogCollector.app("Latency", "无样本"); return }
        val p50 = list[list.size / 2]
        val p90 = list[(list.size * 9 / 10).coerceAtMost(list.size - 1)]
        LogCollector.app("Latency", "样本=${list.size} 最小=${list.first()}ms 中位=${p50}ms P90=${p90}ms 最大=${list.last()}ms")
    }

    override fun onDestroy() {
        dumpLatencyStats()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- TerminalViewClient ----------------
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {
            terminalView?.let { it.requestFocus(); showIme(it) }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        // 输入类型：true=VISIBLE_PASSWORD（Termux 默认，兼容三星等输入法）；
        // false=TYPE_NULL（更轻量，部分国产输入法在此模式下按键路径更短）。
        // 真机反馈输入迟钝，先切到 false 观察（可用日志中的 Latency 对比）。
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            lastInputAt = now; keyT0 = now; keySeq++
            LogCollector.app("Key", "#$keySeq onKeyDown code=$keyCode")
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            lastInputAt = now; keyT0 = now; keySeq++
            LogCollector.app("Key", "#$keySeq onCodePoint cp=$codePoint")
            return false
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) { log(tag, message) }
        override fun logWarn(tag: String, message: String) { log(tag, message) }
        override fun logInfo(tag: String, message: String) { log(tag, message) }
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { log(tag, "$message: ${e.message}") }
        override fun logStackTrace(tag: String, e: Exception) { log(tag, e.message ?: e.toString()) }
    }

    // ---------------- TerminalSessionClient ----------------
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            // ★ 端到端延迟测量：按键下发(spawn 时间) → PTY 回显触发 onTextChanged
            //   目的：区分「渲染/主线程慢」与「PTY/PRoot 慢」，避免继续猜测。
            val sentAt = lastInputAt
            if (sentAt > 0) {
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = now - sentAt
                lastInputAt = 0
                latencySamples.add(dt)
                while (latencySamples.size > 200) latencySamples.poll()
                LogCollector.app("Latency", "#$keySeq 按键→回显 ${dt}ms")
            }
            // 把终端屏幕内容作为「会话日志」采集：这是唯一能看到容器内发生什么的方式。
            // TerminalSession 没有公开 screen 字段，正确 API 是 getEmulator().toString()（Termux 复制全文用的就是它）。
            // onTextChanged 触发非常频繁（每次输出都会调），故做 2s 节流 + 内容去重，
            // 避免日志本身成为性能负担（早期 400ms 节流在快速输出时会持续写盘）。
            val now = android.os.SystemClock.elapsedRealtime()
            if (!sessionTapEnabled) return
            if (now - lastScreenDump < 2000) return
            lastScreenDump = now
            runCatching {
                val text = changedSession.emulator?.toString().orEmpty()
                if (text.isNotEmpty() && text != lastScreenText) {
                    lastScreenText = text
                    LogCollector.session(text)
                }
            }
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            scope.launch { EventBus.emit(AppEvent.ContainerState("会话已结束", false)) }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            if (!text.isNullOrEmpty()) session.write(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 1     // CURSOR_STYLE_BLOCK
        override fun logError(tag: String, message: String) { log(tag, message) }
        override fun logWarn(tag: String, message: String) { log(tag, message) }
        override fun logInfo(tag: String, message: String) { log(tag, message) }
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { log(tag, "$message: ${e.message}") }
        override fun logStackTrace(tag: String, e: Exception) { log(tag, e.message ?: e.toString()) }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun clipboard(): ClipboardManager? =
        requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun log(tag: String, msg: String) {
        if (msg.contains("error", true) || msg.contains("fail", true) || msg.contains("warn", true)) {
            LogCollector.app("Term", "$tag: $msg")
        } else {
            android.util.Log.d("OneStop/$tag", msg)
        }
    }
}
