package com.homebase.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Thin shell around the Home Base dashboard page.
 * The dashboard lives on GitHub Pages, so changing it never needs a new APK.
 * This app only does what a web page cannot: open other apps, and fetch data without browser limits.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val dashboardUrl = "https://dukemangrant.github.io/home-base/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(0xFF06070F.toInt())
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return false
                if (host == Uri.parse(dashboardUrl).host) return false
                openExternal(request.url.toString())
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(dashboardUrl)
        web.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the page close its own panels first; leave the app only if it did nothing.
            web.evaluateJavascript("(window.hbBack ? window.hbBack() : false)") { result ->
                if (result != "true") finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOffline() {
        val html = "<html><body style='background:#06070f;color:#f3f5ff;font-family:sans-serif;" +
                "text-align:center;padding-top:18%'>" +
                "<h1>Can't reach the dashboard</h1>" +
                "<p style='font-size:26px'>Check the Wi-Fi, then press Select to retry.</p>" +
                "<button autofocus style='font-size:30px;padding:16px 40px' " +
                "onclick=\"location.href='$dashboardUrl'\">Retry</button></body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun openExternal(url: String) {
        runOnUiThread {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // No browser available; nothing to do.
            }
        }
    }

    /** Functions the dashboard page can call as window.Android.* */
    inner class Bridge {

        @JavascriptInterface
        fun launch(pkg: String): Boolean {
            val pm = packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                ?: pm.getLaunchIntentForPackage(pkg)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runOnUiThread {
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // App refused to start.
                }
            }
            return true
        }

        @JavascriptInterface
        fun isInstalled(pkg: String): Boolean {
            return try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            openExternal(url)
        }

        /** Every launchable app on the stick, as JSON: [{"pkg": "...", "label": "..."}] */
        @JavascriptInterface
        fun listApps(): String {
            val out = JSONArray()
            val seen = HashSet<String>()
            val categories = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            for (category in categories) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (info in packageManager.queryIntentActivities(query, 0)) {
                    val pkg = info.activityInfo.packageName
                    if (seen.add(pkg)) {
                        out.put(
                            JSONObject()
                                .put("pkg", pkg)
                                .put("label", info.loadLabel(packageManager).toString())
                        )
                    }
                }
            }
            return out.toString()
        }

        /**
         * Fetch a URL outside the browser's cross-site limits.
         * The answer comes back by calling window.__hb(id, httpStatus, bodyText) on the page.
         */
        @JavascriptInterface
        fun fetch(id: String, url: String) {
            thread {
                var status: Int
                var body: String
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) HomeBase/1.0")
                    c.setRequestProperty("Accept", "*/*")
                    status = c.responseCode
                    val stream = if (status in 200..299) c.inputStream else c.errorStream
                    body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                } catch (e: Exception) {
                    status = -1
                    body = e.toString()
                }
                val js = "window.__hb(" + JSONObject.quote(id) + "," + status + "," +
                        JSONObject.quote(body) + ")"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }
    }
}
