package com.onestop.linux.core

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * `dsh web` 服务自动检测（方案 §8.2.2，三级检测）。
 *
 * ① 端口提示文件：容器内 `/tmp/dsh-web.port`（宿主路径 rootfs/tmp/dsh-web.port）
 * ② 回环监听端口扫描：解析 /proc/net/tcp{,6} 中属于本 App UID 的 LISTEN 端口
 * ③ HTTP 指纹确认：健康检查端点 / 响应头 / 标题，避免误报（如 python -m http.server）
 *
 * 命中后通过 [onFound] 回调把端口与 token 交给 UI，由 WebView 加载。
 */
class ServiceDetector(
    private val ctx: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .build()
) {

    companion object {
        private const val TAG = "ServiceDetector"
        private const val PORT_FILE = "tmp/dsh-web.port"

        /** 兜底端口字典：三级检测都失败时按列表探测（方案 §8.2.6 风险处置）。 */
        val FALLBACK_PORTS = listOf(3000, 5000, 6006, 8000, 8080, 8123, 8888, 9000)
    }

    data class Found(val port: Int, val token: String?, val confidence: String, val reason: String, val url: String)

    private var job: Job? = null
    private var observer: FileObserver? = null

    fun start(scope: CoroutineScope, onFound: (Found) -> Unit, onLost: () -> Unit = {}) {
        val tmpDir = File(Environment.rootfsDir(ctx), "tmp").apply { mkdirs() }
        observer = object : FileObserver(tmpDir.absolutePath,
            CREATE or MODIFY or CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.contains("dsh-web")) Log.i(TAG, "端口文件事件: $path")
            }
        }.also { runCatching { it.startWatching() } }

        var lastFound: Found? = null
        job = scope.launch(Dispatchers.IO) {
            var elapsed = 0L
            var missStreak = 0
            while (isActive) {
                val candidates = LinkedHashSet<Int>()
                readAnnouncedPort()?.let { candidates += it }
                candidates += listenPorts()
                if (candidates.isEmpty()) candidates += FALLBACK_PORTS

                var hit: Found? = null
                for (p in candidates) {
                    hit = probe(p)
                    if (hit != null) break
                }

                if (hit != null) {
                    missStreak = 0
                    if (lastFound?.port != hit.port) {
                        Log.i(TAG, "检出服务: ${hit.url} (${hit.confidence}/${hit.reason})")
                        lastFound = hit
                        onFound(hit)
                    }
                } else if (lastFound != null) {
                    if (++missStreak >= 3) { lastFound = null; Log.i(TAG, "服务已消失"); onLost() }
                }

                val interval = if (elapsed < 30_000) 500L else 3_000L
                delay(interval); elapsed += interval
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { observer?.stopWatching() }; observer = null
    }

    // ---------- 第 1 级：端口提示文件 ----------
    private fun readAnnouncedPort(): Int? = runCatching {
        val f = File(Environment.rootfsDir(ctx), PORT_FILE)
        if (!f.isFile) return@runCatching null
        f.readText().trim().lineSequence().firstOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    // ---------- 第 2 级：/proc/net/tcp 回环 LISTEN 扫描 ----------
    fun listenPorts(): Set<Int> = buildSet {
        listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
            runCatching {
                File(path).readLines().drop(1).forEach { line ->
                    val c = line.trim().split(Regex("\\s+"))
                    if (c.size < 4) return@forEach
                    val local = c[1]
                    val state = c[3].toIntOrNull(16) ?: return@forEach
                    if (state != 0x0A) return@forEach            // TCP_LISTEN
                    val parts = local.split(":")
                    if (parts.size != 2) return@forEach
                    val port = parts[1].toIntOrNull(16) ?: return@forEach
                    val ipHex = parts[0].uppercase()
                    val loopback = ipHex == "0100007F" || ipHex == "00000000" ||
                            ipHex.all { it == '0' } ||
                            ipHex == "00000000000000000000000001000000"
                    if (loopback) add(port)
                }
            }
        }
    }

    // ---------- 第 3 级：HTTP 指纹 ----------
    fun probe(port: Int): Found? {
        // ① 约定健康检查端点
        tryGet("http://127.0.0.1:$port/__dsh/health")?.let { (code, body, token) ->
            if (code == 200 && body.contains("dsh", true))
                return Found(port, token, "HIGH", "health endpoint", "http://127.0.0.1:$port/")
        }
        // ② 响应头
        val head = tryHead("http://127.0.0.1:$port/") ?: return null
        val server = head.first
        val token = head.second
        if (server.contains("dsh", true))
            return Found(port, token, "HIGH", "response header", "http://127.0.0.1:$port/")
        // ③ 标题特征
        val body = tryGet("http://127.0.0.1:$port/")?.second.orEmpty()
        if (Regex("<title>[^<]*dsh", RegexOption.IGNORE_CASE).containsMatchIn(body))
            return Found(port, token, "MEDIUM", "html title", "http://127.0.0.1:$port/")
        return null
    }

    private fun tryGet(url: String): Triple<Int, String, String?>? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            Triple(r.code, body, r.header("X-DSH-Token"))
        }
    }.getOrNull()

    private fun tryHead(url: String): Pair<String, String?>? = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
            (r.header("Server").orEmpty() + " " + r.header("X-Powered-By").orEmpty()) to r.header("X-DSH-Token")
        }
    }.getOrNull()

    /** 校验服务是否只绑回环（方案 §8.2.3）；false 表示绑在 0.0.0.0，需提示用户。 */
    fun verifyLoopbackOnly(port: Int): Boolean {
        val nonLoop = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        }.getOrNull() ?: return true
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(nonLoop, port), 300) }
            false                                   // 连上了 → 不是只绑回环
        }.getOrElse { true }
    }
}
