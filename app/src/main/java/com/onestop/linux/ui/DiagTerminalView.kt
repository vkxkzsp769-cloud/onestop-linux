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
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TerminalView(context, attrs, defStyle) {

    @Volatile private var contentReadyAt = 0L
    @Volatile private var pendingMeasure = false
    private var drawCount = 0L
    private var slowDraws = 0L
    private var maxDrawUs = 0L
    private var totalDrawUs = 0L

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { t ->
        if (pendingMeasure) {
            val d = t / 1_000_000 - contentReadyAt
            pendingMeasure = false
            // 只在明显滞后时记录，避免刷屏
            if (d > 60) LogCollector.app("Frame", "内容就绪→首帧 ${d}ms")
        }
    }

    /** 终端内容已更新（onTextChanged 时调用）。 */
    fun markContentReady() {
        contentReadyAt = SystemClock.elapsedRealtime()
        pendingMeasure = true
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

    /** 供导出日志时汇总。 */
    fun dumpFrameStats() {
        if (drawCount == 0L) { LogCollector.app("Frame", "无绘制样本"); return }
        LogCollector.app("Frame",
            "绘制帧数=$drawCount 慢帧(>16ms)=$slowDraws 平均=${totalDrawUs / drawCount / 1000f}ms 最大=${maxDrawUs / 1000f}ms")
    }
}
