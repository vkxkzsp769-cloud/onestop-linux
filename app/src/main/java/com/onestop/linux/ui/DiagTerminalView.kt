package com.onestop.linux.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import com.onestop.linux.core.LogCollector
import com.termux.view.TerminalView

/**
 * 带绘制诊断的 TerminalView。
 *
 * 为什么下沉到这一层：真机日志已证明
 *   - PTY/PRoot 很快（proot 退出码=0，耗时约 200ms）
 *   - 「按键 → 回显」只有 1~3ms（Latency 打点）
 * 但用户仍反馈「打字时字母出现很慢」→ 瓶颈在**内容就绪 → 屏幕真正刷新**这一段。
 * 本类测量两件事：
 *   1) 「内容就绪 → 首帧」延迟（Choreographer 帧回调时间戳）
 *   2) 帧间隔分布（判定是否掉帧：120Hz≈8.3ms / 60Hz≈16.7ms）
 *   3) onDraw 耗时与慢帧计数（判定绘制成本）
 *
 * 说明：TerminalView 上游声明为 `public final class`，无法继承；
 *      tools/vendor-termux.sh 的补丁 3c 会去掉 final 并追加 getEmulatorForDiag()。
 *      实现上刻意避免「lambda 自引用」（曾导致 Kotlin 递归类型推断报错）。
 */
class DiagTerminalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TerminalView(context, attrs) {

    private val choreographer: Choreographer = Choreographer.getInstance()

    @Volatile private var contentReadyAt = 0L
    @Volatile private var pendingMeasure = false

    // 帧间隔统计
    private var frameCount = 0
    private val frameGaps = ArrayList<Long>(256)
    private var lastFrameAt = 0L
    private var watching = false

    // onDraw 统计
    private var drawCount = 0L
    private var slowDraws = 0L
    private var maxDrawUs = 0L
    private var totalDrawUs = 0L

    /** 帧回调：不用 lambda 捕获自身，避免递归类型推断。 */
    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val nowMs = frameTimeNanos / 1_000_000L
            if (lastFrameAt > 0L) {
                val gap = nowMs - lastFrameAt
                frameCount++
                if (frameGaps.size < 256) frameGaps.add(gap)
            }
            lastFrameAt = nowMs

            if (pendingMeasure) {
                pendingMeasure = false
                val d = nowMs - contentReadyAt
                if (d > 60L) LogCollector.app("Frame", "内容就绪→首帧 ${d}ms")
            }

            if (watching && frameCount < 140) {
                choreographer.postFrameCallback(this)
            } else {
                watching = false
            }
        }
    }

    /** 终端内容已更新（onTextChanged 时调用）。 */
    fun markContentReady() {
        contentReadyAt = SystemClock.elapsedRealtime()
        pendingMeasure = true
        choreographer.postFrameCallback(frameCallback)
    }

    /** 开始一段帧间隔观察（用户开始打字时调用）。 */
    fun startFrameWatch() {
        if (watching) return
        watching = true
        frameCount = 0
        frameGaps.clear()
        lastFrameAt = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    /** 诊断用：当前终端行列（TerminalView.mEmulator 私有，用 vendor 补丁加的 getter）。 */
    fun diagRows(): Int {
        return try { getEmulatorForDiag()?.mRows ?: -1 } catch (t: Throwable) { -1 }
    }

    fun diagCols(): Int {
        return try { getEmulatorForDiag()?.mColumns ?: -1 } catch (t: Throwable) { -1 }
    }

    override fun onDraw(canvas: Canvas) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        super.onDraw(canvas)
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000L
        drawCount++
        totalDrawUs += us
        if (us > maxDrawUs) maxDrawUs = us
        if (us > 16_000L) {
            slowDraws++
            if (slowDraws % 10L == 1L) {
                LogCollector.app("Frame", "慢帧 onDraw=${us / 1000f}ms（累计慢帧=$slowDraws/$drawCount）")
            }
        }
    }

    /** 一行摘要，便于直接显示给用户。 */
    fun frameGapSummary(): String {
        val gaps = ArrayList(frameGaps)
        if (gaps.isEmpty()) return "无帧样本"
        gaps.sort()
        val p50 = gaps[gaps.size / 2]
        val p90 = gaps[(gaps.size * 9 / 10).coerceAtMost(gaps.size - 1)]
        return "帧间隔 中位=${p50}ms P90=${p90}ms 最大=${gaps.last()}ms 样本=${gaps.size}"
    }

    /** 导出日志时汇总。 */
    fun dumpFrameStats() {
        if (drawCount == 0L) {
            LogCollector.app("Frame", "无绘制样本")
        } else {
            LogCollector.app("Frame",
                "绘制帧数=$drawCount 慢帧(>16ms)=$slowDraws " +
                "平均=${totalDrawUs / drawCount / 1000f}ms 最大=${maxDrawUs / 1000f}ms")
        }
        val gaps = ArrayList(frameGaps)
        if (gaps.isEmpty()) {
            LogCollector.app("FrameGap", "无帧间隔样本")
            return
        }
        gaps.sort()
        val p50 = gaps[gaps.size / 2]
        val p90 = gaps[(gaps.size * 9 / 10).coerceAtMost(gaps.size - 1)]
        LogCollector.app("FrameGap",
            "样本=${gaps.size} 最小=${gaps.first()}ms 中位=${p50}ms P90=${p90}ms 最大=${gaps.last()}ms " +
            "（8.3ms≈120fps / 16.7ms≈60fps；中位明显偏大=掉帧）")
    }
}
