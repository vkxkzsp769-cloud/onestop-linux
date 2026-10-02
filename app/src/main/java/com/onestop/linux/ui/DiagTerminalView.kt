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
 * 为什么要下沉到这一层：真机日志已证明
 *   - PTY/PRoot 很快（proot 退出码=0 耗时 ~200ms）
 *   - 「按键 → 回显」只要 2~3ms（Latency 打点）
 * 但用户仍觉得「敲了字很久才出现」→ 说明瓶颈在**绘制**（内容已就绪但屏幕没及时重画）。
 * 本类测量：
 *   1) markContentReady() 记录「新内容就绪」时刻；
 *   2) 下一帧真正 onDraw 时算出「内容就绪 → 首帧」延迟；
 *   3) 同时统计单帧 onDraw 的重绘面积与耗时，判断是否被整屏重绘拖慢。
 */
class DiagTerminalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TerminalView(context, attrs) {

    @Volatile private var contentReadyAt = 0L
    @Volatile private var pendingMeasure = false
    private var drawCount = 0L
    private var slowDraws = 0L
    private var maxDrawUs = 0L
    private var totalDrawUs = 0L

    private val choreographer = Choreographer.getInstance()

    // 帧间隔统计：用来判定「是不是掉帧」。
    // 若间隔中位数 > 16ms（60Hz）或 > 8ms（120Hz），说明 App 的帧被拖延，
    // 那么即使 PTY 回显只要 1~3ms，用户仍会看到「字母半天才出来」。
    private var lastFrameAt = 0L
    private var frameCount = 0L
    private val frameGaps = ArrayList<Long>(256)

    private val frameCallback = Choreographer.FrameCallback { t ->
        val nowMs = t / 1_000_000
        if (lastFrameAt > 0) {
            val gap = nowMs - lastFrameAt
            frameCount++
            if (frameGaps.size < 256) frameGaps.add(gap)
        }
        lastFrameAt = nowMs
        if (pendingMeasure) {
            val d = nowMs - contentReadyAt
            pendingMeasure = false
            if (d > 60) LogCollector.app("Frame", "内容就绪→首帧 ${d}ms")
        }
        // 持续观察一小段时间内的帧间隔（必须传 FrameCallback，不能传 View 自身）
        if (frameCount in 1..140) choreographer.postFrameCallback(frameCallback)
    }

    /** 终端内容已更新（onTextChanged 时调用）。 */
    fun markContentReady() {
        contentReadyAt = SystemClock.elapsedRealtime()
        pendingMeasure = true
        choreographer.postFrameCallback(frameCallback)
    }

    /** 诊断用：当前终端行/列（TerminalView 的 mEmulator 是私有，用我们补丁加的 getter）。 */
    // 注意：Kotlin 对 Java getX 的属性映射有前提，这里直接调用方法名最稳
    fun diagRows(): Int = runCatching { getEmulatorForDiag()?.mRows ?: -1 }.getOrDefault(-1)
    fun diagCols(): Int = runCatching { getEmulatorForDiag()?.mColumns ?: -1 }.getOrDefault(-1)

    /** 开始一段帧间隔观察（例如用户开始打字时）。 */
    fun startFrameWatch() {
        frameCount = 0
        frameGaps.clear()
        lastFrameAt = 0
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        super.onDraw(canvas)
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        drawCount++
        totalDrawUs += us
        if (us > maxDrawUs) maxDrawUs = us
        if (us > 16_000) {                       // 超过 16ms 视为掉帧
            slowDraws++
            if (slowDraws % 10 == 1L) {
                LogCollector.app("Frame", "慢帧 onDraw=${us / 1000f}ms（累计慢帧=$slowDraws/${drawCount}）")
            }
        }
    }

    /** 一行摘要，便于直接显示给用户/贴进对话。 */
    fun frameGapSummary(): String {
        val gaps = frameGaps.sorted()
        if (gaps.isEmpty()) return "无帧样本"
        val p50 = gaps[gaps.size / 2]
        val p90 = gaps[(gaps.size * 9 / 10).coerceAtMost(gaps.size - 1)]
        return "帧间隔 中位=${p50}ms P90=${p90}ms 最大=${gaps.last()}ms 样本=${gaps.size}"
    }

    /** 供导出日志时汇总。 */
    fun dumpFrameStats() {
        if (drawCount == 0L) { LogCollector.app("Frame", "无绘制样本") } else {
            LogCollector.app("Frame",
                "绘制帧数=$drawCount 慢帧(>16ms)=$slowDraws 平均=${totalDrawUs / drawCount / 1000f}ms 最大=${maxDrawUs / 1000f}ms")
        }
        val gaps = frameGaps.sorted()
        if (gaps.isEmpty()) { LogCollector.app("FrameGap", "无帧间隔样本"); return }
        val p50 = gaps[gaps.size / 2]
        val p90 = gaps[(gaps.size * 9 / 10).coerceAtMost(gaps.size - 1)]
        LogCollector.app("FrameGap",
            "样本=${gaps.size} 最小=${gaps.first()}ms 中位=${p50}ms P90=${p90}ms 最大=${gaps.last()}ms " +
            "（16.7ms≈60fps / 8.3ms≈120fps；中位明显偏大即为掉帧）")
    }
}
