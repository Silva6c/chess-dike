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
import android.widget.Button
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
    private lateinit var tvStatus: TextView

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

        btnOverlay = findViewById(R.id.btn_overlay_permission)
        btnNotify = findViewById(R.id.btn_notification_permission)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        tvStatus = findViewById(R.id.tv_auto_status)

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

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        btnOverlay.text = if (overlayOk) "① 悬浮窗权限：已授权 ✓" else "① 授予悬浮窗权限"
        val notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        btnNotify.text = if (notifOk) "② 通知权限：已就绪 ✓" else "② 允许通知（前台服务提示）"
        btnNotify.isEnabled = !notifOk && Build.VERSION.SDK_INT >= 33

        val svc = service
        val running = svc?.isCapturing() == true
        btnStartCapture.text = if (running) "③ 屏幕识别运行中 ✓" else "③ 开始屏幕识别（全自动识别棋盘）"
        tvStatus.text = when {
            !running -> "状态：未启动，请点击③授权录屏"
            else -> "状态：${svc!!.getStatusText()}"
        }
    }
}
