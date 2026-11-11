package com.anjy.atlas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Shows a page Atlas Bot made. The page's own script runs (sorting, filters, charts), but it can't reach anything:
 * the network is blocked, it can't read other files, and links to websites open in the user's browser instead.
 */
class PageViewActivity : Activity() {
    companion object {
        private const val EXTRA_FILE = "file"
        fun intent(ctx: Context, f: File) = Intent(ctx, PageViewActivity::class.java).putExtra(EXTRA_FILE, f.path)
    }
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = intent?.getStringExtra(EXTRA_FILE)?.let { File(it) }
        if (f == null || !BotFiles.inside(this, f) || f.length() > 8L * 1_048_576) { finish(); return }
        title = f.nameWithoutExtension
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        val w = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true          // nothing from the internet
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.domStorageEnabled = false; settings.databaseEnabled = false
            settings.setGeolocationEnabled(false); settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                // belt and braces: any request the page makes is answered with nothing
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                    if (req.url.toString().startsWith("data:")) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.scheme == "http" || u.scheme == "https") try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    return true
                }
            }
        }
        web = w
        setContentView(w)
        window.setDecorFitsSystemWindows(false)
        w.setOnApplyWindowInsetsListener { v, ins -> val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED }
        // loaded as text under a made-up address, so it has no access to the phone's files and no real origin
        w.loadDataWithBaseURL("https://page.atlas.invalid/", f.readText(), "text/html", "utf-8", null)
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
