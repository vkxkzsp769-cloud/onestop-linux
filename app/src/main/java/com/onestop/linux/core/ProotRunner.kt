package com.onestop.linux.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 一次性容器命令执行器（不经过 PTY）。
 *
 * 用途：post-install、环境探测、（后续）GPU 检测。
 * 注意：这里用 ProcessBuilder 直接跑 proot，**不**经过 Termux 的 libtermux.so，
 *      因此不会分配 PTY；需要交互的场合请用 TerminalSession。
 */
object ProotRunner {

    private const val TAG = "ProotRunner"

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun runInRootfs(
        ctx: Context,
        containerCommand: String,
        timeoutSec: Long = 600,
        onLine: ((String) -> Unit)? = null
    ): Result {
        val argv = ProotCommandBuilder.execArgv(ctx, containerCommand)
        val env = ProotCommandBuilder.loginEnvironment(ctx)

        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)
        pb.environment().putAll(env)
        pb.directory(ctx.filesDir)

        return try {
            val proc = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        out.append(line).append('\n')
                        onLine?.invoke(line)
                    }
                }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                    lines.forEach { err.append(it).append('\n') }
                }
            }
            outThread.start(); errThread.start()

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                outThread.join(2_000); errThread.join(2_000)
                Log.w(TAG, "命令超时(${timeoutSec}s): $containerCommand")
                return Result(-1, out.toString(), err.toString(), timedOut = true)
            }
            outThread.join(5_000); errThread.join(5_000)
            Result(proc.exitValue(), out.toString(), err.toString(), timedOut = false)
        } catch (t: Throwable) {
            Log.e(TAG, "执行失败: $containerCommand", t)
            Result(-1, "", t.message ?: t.toString(), timedOut = false)
        }
    }

    /** 校验容器是否真的能起来（方案 §13.3 E2 的程序化版本）。 */
    fun selfCheck(ctx: Context): Result =
        runInRootfs(ctx, "cat /etc/os-release; echo ---; uname -m; echo ---; id -u", timeoutSec = 120)
}
