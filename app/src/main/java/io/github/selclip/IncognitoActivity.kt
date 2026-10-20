package io.github.selclip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class IncognitoActivity : Activity() {

    private var webView: WebView? = null
    private lateinit var urlLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val targetUrl = intent?.getStringExtra("url") ?: "https://duckduckgo.com/"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val badge = TextView(this).apply {
            text = "[INC] INCOGNITO"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#00FF66"))
            setPadding(0, 0, dp(10), 0)
        }

        urlLabel = TextView(this).apply {
            text = targetUrl
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#888888"))
            isSingleLine = true
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val extBtn = TextView(this).apply {
            text = "[EXT]"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                val current = webView?.url ?: targetUrl
                launchExternalPrivateOrCopy(current)
            }
        }

        val closeBtn = TextView(this).apply {
            text = "[ X WIPE ]"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#FF6666"))
            setPadding(dp(12), dp(6), dp(10), dp(6))
            setOnClickListener {
                wipeAndClose()
            }
        }

        topBar.addView(badge)
        topBar.addView(urlLabel)
        topBar.addView(extBtn)
        topBar.addView(closeBtn)
        root.addView(topBar)

        val wv = WebView(this).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.saveFormData = false

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(false)
            cm.removeAllCookies(null)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    urlLabel.text = u
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!url.isNullOrBlank()) urlLabel.text = url
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(targetUrl)
        }

        webView = wv
        root.addView(wv)
        setContentView(root)
    }

    private fun launchExternalPrivateOrCopy(url: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sel_incognito", url))

        val privatePackages = listOf(
            "org.mozilla.focus",
            "com.duckduckgo.mobile.android",
            "com.brave.browser",
            "org.mozilla.fenix"
        )
        for (pkg in privatePackages) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                runCatching {
                    startActivity(intent)
                    wipeAndClose()
                    return
                }
            }
        }

        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB", true)
        }
        Toast.makeText(this, "URL copied for Incognito", Toast.LENGTH_SHORT).show()
        runCatching { startActivity(fallback) }
        wipeAndClose()
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            wipeAndClose()
        }
    }

    private fun wipeAndClose() {
        webView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(true)
            wv.clearFormData()
            wv.removeAllViews()
            wv.destroy()
        }
        webView = null
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
