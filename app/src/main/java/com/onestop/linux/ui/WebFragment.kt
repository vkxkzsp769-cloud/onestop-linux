package com.onestop.linux.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.onestop.linux.R
import com.onestop.linux.core.TokenInjectingClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 网页页（方案 §8.2.4 / §5.4）：在**应用内部** WebView 显示 127.0.0.1 上的 dsh web 页面。
 * 绝不调用外部浏览器：shouldOverrideUrlLoading 对外部地址一律在本 WebView 内加载。
 */
class WebFragment : Fragment() {

    private var webView: WebView? = null
    private var lastUrl: String? = null

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val tokenClient by lazy { TokenInjectingClient(okHttp) { com.onestop.linux.core.TokenStore.current() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_web, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val web = view.findViewById<WebView>(R.id.web_view)
        webView = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(WebBridge(), "OneStop")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host ?: return true
                if (host in setOf("127.0.0.1", "localhost", "::1")) return false
                v.loadUrl(req.url.toString())      // 保持在 App 内，绝不外跳
                return true
            }

            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                tokenClient.intercept(v, req)

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(v, url, favicon)
                installTokenScript(v)
            }

            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) = h.cancel()
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        lastUrl = null
    }

    /**
     * 注入 fetch/XHR 的 token 补丁（方案 §8.2.4）。
     * 优先 document-start 注入（SPA 路由也生效）；老内核退回 onPageStarted。
     */
    private fun installTokenScript(web: WebView) {
        val token = com.onestop.linux.core.TokenStore.current() ?: return
        val js = """
            (function(){
              var T = "$token";
              try { localStorage.setItem('dsh_token', T); } catch(e) {}
              if (window.__onesTopPatched) return; window.__onesTopPatched = true;
              var of = window.fetch;
              if (of) window.fetch = function(i, init){
                init = init || {};
                init.headers = Object.assign({}, init.headers,
                  {"Authorization":"Bearer "+T, "X-DSH-Token":T});
                return of.call(this, i, init);
              };
              var oo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(){
                oo.apply(this, arguments);
                try { this.setRequestHeader("Authorization","Bearer "+T);
                      this.setRequestHeader("X-DSH-Token",T); } catch(e){}
              };
            })();
        """.trimIndent()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    web, js, setOf("http://127.0.0.1", "http://localhost", "http://[::1]")
                )
            }
        } else {
            web.evaluateJavascript(js, null)
        }
    }

    /** 加载检测到的 dsh web 地址（由 MainActivity 在 ServiceUp 事件时调用）。 */
    fun loadService(url: String, token: String?) {
        if (token != null) com.onestop.linux.core.TokenStore.set(token)
        if (url == lastUrl) return
        lastUrl = url
        webView?.loadUrl(url)
    }

    fun showPlaceholder(text: String) {
        webView?.loadDataWithBaseURL(
            null,
            "<html><body style='font-family:sans-serif;padding:24px;color:#888'>$text</body></html>",
            "text/html", "utf-8", null
        )
    }

    inner class WebBridge {
        @android.webkit.JavascriptInterface
        fun postMessage(json: String) {
            android.util.Log.i("OneStop/WebBridge", "web→app: $json")
        }
    }
}
