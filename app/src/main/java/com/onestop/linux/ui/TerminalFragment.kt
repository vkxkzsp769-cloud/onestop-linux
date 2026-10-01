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
        tv.setTextSize(28)

        if (session == null) startSession()
        // 让终端拿到焦点并主动拉起软键盘（真机反馈：不主动触发时键盘不弹出）
        tv.isFocusableInTouchMode = true
        tv.requestFocus()
        tv.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) showIme(v) }
        view.postDelayed({ showIme(tv) }, 300)
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
    }

    override fun onDestroyView() {
        terminalView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
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
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
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
            // 把终端屏幕内容作为「会话日志」采集：这是唯一能看到容器内发生什么的方式。
            // TerminalSession 没有公开 screen 字段，正确 API 是 getEmulator().toString()（Termux 复制全文用的就是它）。
            // onTextChanged 触发非常频繁，故做 400ms 节流 + 与上次内容去重，避免刷爆 IO。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastScreenDump < 400) return
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
