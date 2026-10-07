package com.xqdk.chess.assist.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xqdk.chess.R
import com.xqdk.chess.assist.ScreenAssistService

/**
 * 连线功能主界面：授权（悬浮窗/通知/录屏）→ 启动识别服务。
 * 棋盘识别全自动（YOLO 检测，免校准），此页只负责权限与启停。
 */
class AssistActivity : AppCompatActivity() {

    private var captureIntentCode = -1
    private var captureIntentData: Intent? = null

    private lateinit var btnOverlay: Button
    private lateinit var btnNotify: Button
    private lateinit var btnStartCapture: Button
    private lateinit var btnOverlayTheme: Button
    private lateinit var tvStatus: TextView

    /** 悬浮窗主题循环切换顺序（深色玻璃 → 纯黑 OLED → 浅色纸感） */
    private val themeOrder = OverlayTheme.entries

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private var service: ScreenAssistService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val cb = binder as? ScreenAssistService.CastBinder
            service = cb?.service()
            bound = true
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            captureIntentCode = result.resultCode
            captureIntentData = result.data
            startCaptureService()
            Toast.makeText(this, "录屏授权成功，识别服务已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予录屏权限，无法识别", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.assist_activity)
        // 连线页背景：墨底 + 26dp 细网格（对齐 Cabinet，替代旧 chessbg 木纹图）
        findViewById<View>(R.id.bg_scroll).background =
            com.xqdk.chess.assist.ui.QiGridDrawable(this)

        btnOverlay = findViewById(R.id.btn_overlay_permission)
        btnNotify = findViewById(R.id.btn_notification_permission)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        btnOverlayTheme = findViewById(R.id.btn_overlay_theme)
        tvStatus = findViewById(R.id.tv_auto_status)

        // 悬浮窗主题初始显示（持久化值）
        btnOverlayTheme.text = "④ 悬浮窗主题：" + OverlayTheme.fromKey(
            com.xqdk.chess.assist.AssistConfig(this).overlayTheme
        ).label
        btnOverlayTheme.setOnClickListener {
            // 循环切换：持久化 + 已建面板即时换肤（未启动时下次启动生效）
            val cur = OverlayTheme.fromKey(
                com.xqdk.chess.assist.AssistConfig(this).overlayTheme
            )
            val next = themeOrder[(themeOrder.indexOf(cur) + 1) % themeOrder.size]
            service?.applyOverlayTheme(next)
            btnOverlayTheme.text = "④ 悬浮窗主题：" + next.label
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        btnStartCapture.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        // 识别引擎：ONNX 角点式（默认，对未收录皮肤泛化好）/ YOLO 检测式；运行中热切换
        val cfg = com.xqdk.chess.assist.AssistConfig(this)
        val rgEngine = findViewById<RadioGroup>(R.id.rg_recognizer_engine)
        findViewById<RadioButton>(
            if (cfg.recognizerEngine == ScreenAssistService.ENGINE_YOLO) R.id.rb_engine_yolo
            else R.id.rb_engine_onnx
        ).isChecked = true
        rgEngine.setOnCheckedChangeListener { _, id ->
            cfg.recognizerEngine = if (id == R.id.rb_engine_yolo)
                ScreenAssistService.ENGINE_YOLO else ScreenAssistService.ENGINE_ONNX
            service?.setRecognizerEngine(cfg.recognizerEngine)
        }

        bindService(Intent(this, ScreenAssistService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
        // 服务继续运行（前台服务），仅解绑
    }

    private fun startCaptureService() {
        if (captureIntentData == null) return
        val intent = Intent(this, ScreenAssistService::class.java)
            .setAction(ScreenAssistService.ACTION_START)
            .putExtra(ScreenAssistService.EXTRA_RESULT_CODE, captureIntentCode)
            .putExtra(ScreenAssistService.EXTRA_RESULT_DATA, captureIntentData)
        ContextCompat.startForegroundService(this, intent)
    }

    /** 文本变化才 setText（根因 6：1s 轮询每秒无条件重写 3 按钮 + 状态行，引发频跳） */
    private fun setTextIfChanged(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        setTextIfChanged(btnOverlay, if (overlayOk) "① 悬浮窗权限：已授权 ✓" else "① 授予悬浮窗权限")
        val notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        setTextIfChanged(btnNotify, if (notifOk) "② 通知权限：已就绪 ✓" else "② 允许通知（前台服务提示）")
        btnNotify.isEnabled = !notifOk && Build.VERSION.SDK_INT >= 33

        val svc = service
        val running = svc?.isCapturing() == true
        setTextIfChanged(btnStartCapture, if (running) "③ 屏幕识别运行中 ✓" else "③ 开始屏幕识别（全自动识别棋盘）")
        setTextIfChanged(tvStatus, when {
            !running -> "状态：未启动，请点击③授权录屏"
            else -> "状态：${svc!!.getStatusText()}"
        })
    }
}
