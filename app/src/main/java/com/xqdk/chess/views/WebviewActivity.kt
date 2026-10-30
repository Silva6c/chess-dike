package com.xqdk.chess.views

import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xqdk.chess.R

class WebviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        // bind textViewVersion
        val textViewVersion: TextView = findViewById(R.id.textViewVersion)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val datetime = java.util.Date(packageInfo.lastUpdateTime)
        // convert datetime to string with format YYYY-MM-DD HH:MM:SS
        val datetimeString = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(datetime)
        // lastUpdateTime 语义=安装/上次更新时间（原注释"编译时间"失真，体检 P2）
        // 版本行以 versionName 为主（tag 推导的语义化版本，用户可读），vc 为系统递增计数
        textViewVersion.text = "当前版本: ${packageInfo.versionName}（vc ${packageInfo.versionCode}）\n" + "安装于: $datetimeString"

        // bind imageButton and set click listener
        val imageButtonBack: ImageButton = findViewById(R.id.imageButtonBack)
        imageButtonBack.setOnClickListener {
            onBackPressed()
        }

        val webView: WebView = findViewById(R.id.webview)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true

        // https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/web-page-layout.md
        // set width of webView to the screen width, and disable horiztional scrolling
        webView.settings.setLoadWithOverviewMode(false)
        webView.settings.setUseWideViewPort(false)
        webView.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NARROW_COLUMNS;

        val url = intent.getStringExtra("url") ?: "file:///android_asset/help.html"
        // 白名单：只允许加载应用内 asset 页面。当前唯一调用方传的是硬编码 asset URL，
        // 但本 Activity 若将来新增入口（deep link 等），任意外链/文件路径会直达 WebView，
        // 在入口处一律回退默认帮助页（2026-10-31 安全体检加固）
        val safeUrl = if (url.startsWith("file:///android_asset/")) url
                      else "file:///android_asset/help.html"
        webView.loadUrl(safeUrl)
    }


    override fun onBackPressed() {
        val webView: WebView = findViewById(R.id.webview)
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}