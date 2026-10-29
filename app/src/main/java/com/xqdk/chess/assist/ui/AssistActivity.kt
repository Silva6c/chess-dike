package com.xqdk.chess.assist.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
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
 * 连线工作页（美学终稿 §3.2）：纸底仪器面（识别点阵 + 状态行 + hero + 河界线）+
 * 三步授权清单（几何序号行）+ 主按钮（全 App 唯一主操作：开始/停止连线）+
 * 设置（悬浮窗主题循环 / 识别引擎单选 / 思考制式单选）+ 底部帮助/关于入口。
 * 棋盘识别全自动，此页只负责权限与启停。
 * 本页同时是 launcher（2026-10-29 砍主页跳转层：原"主页 → 开始连线"中间层移除，
 * 打开应用即达连线页；免责弹窗随启动语义一并迁入）。
 * 交互契约一行不删：每秒轮询、变化才回填、启动守卫、运行中热切换、绑定补应用配置。
 */
class AssistActivity : AppCompatActivity() {

    companion object {

        /**
         * 免责声明（四条条款，对齐 Cabinet 口径；首启弹窗 / About / 帮助页 / README
         * 共用同一文案，任何修改需四处同步）。
         */
        const val DISCLAIMER =
            "本软件（象棋迪克）为象棋学习辅助工具，仅供个人学习、复盘研究与技术交流使用。请在使用前仔细阅读以下条款：\n\n" +
            "一、本软件仅通过系统录屏授权识别画面并以悬浮窗显示走法建议，不包含自动走子、触摸注入、无障碍服务、Root/Xposed 等功能，所有着法均由玩家手动操作。\n\n" +
            "二、严禁将本软件用于任何正式比赛、等级分对局、网络对局平台或赌博等违反平台规则与法律法规的场景；由此产生的一切后果由使用者本人承担。\n\n" +
            "三、本软件仅供学习研究，请于获取后 24 小时内自行删除。\n\n" +
            "四、本软件按\"现状\"提供，不对软件的可用性与准确性作任何保证，不对因使用本软件造成的任何直接或间接损失承担责任。\n\n" +
            "点击\"同意并继续\"即表示您已阅读、理解并同意以上全部条款。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 Vincentzyx/VinXiangQi 项目；\n" +
            "· ONNX 两步式识别引擎（RTMPose 角点 + Swin 分类）的模型与算法来自 " +
            "Shirakawa-Kotone/chinese-chess-helper 项目；\n" +
            "· 引擎框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"
    }

    /** 步骤行状态（几何序号语义：完成玉✓实心 / 待办朱砂描边 / 未开始灰空心） */
    private enum class StepState { DONE, TODO, LOCKED }

    /** 主按钮两态（美学终稿 §4 主按钮：可用·朱砂 / 运行·警示"停止指导"） */
    private enum class MainBtnState { IDLE, RUNNING }

    private var captureIntentCode = -1
    private var captureIntentData: Intent? = null

    // 步骤行（①②整行可点，③静态说明行）
    private lateinit var btnOverlay: View
    private lateinit var btnNotify: View
    private lateinit var stepOverlayNum: TextView
    private lateinit var stepOverlayHint: TextView
    private lateinit var stepOverlayChip: TextView
    private lateinit var stepNotifyNum: TextView
    private lateinit var stepNotifyHint: TextView
    private lateinit var stepNotifyChip: TextView
    private lateinit var stepCaptureNum: TextView
    private lateinit var stepCaptureChip: TextView

    private lateinit var btnStartCapture: Button
    private lateinit var btnOverlayTheme: Button
    private lateinit var tvStatus: TextView

    // 仪器面：状态点 / hero / 河界线 / 识别点阵
    private lateinit var stateDot: View
    private lateinit var hero: TextView
    private lateinit var riverLine: RiverLineView
    private lateinit var dotGrid: AssistDotGridView
    private var heroLive = false
    private var mainBtnState: MainBtnState? = null
    private val appliedStep = mutableMapOf<Int, Pair<StepState, String>>()

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
            // 按持久化配置补应用主题/识别引擎：绑定前在设置页切换时 service 为 null
            // 被静默丢弃，运行中服务与页面显示会不一致
            service?.applyOverlayTheme(
                OverlayTheme.fromKey(com.xqdk.chess.assist.AssistConfig(this@AssistActivity).overlayTheme)
            )
            service?.setRecognizerEngine(
                com.xqdk.chess.assist.AssistConfig(this@AssistActivity).recognizerEngine
            )
            // 思考制式同口径补应用：悬浮窗芯片按制式二选一显示，绑定前切换被静默
            // 丢弃会导致服务制式与页面选中态不一致
            service?.setSearchMode(
                com.xqdk.chess.assist.AssistConfig(this@AssistActivity).searchMode == "time"
            )
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 只清 service 引用不动 bound：bound 语义是"bindService 是否成功过"，
            // 断连后绑定本身仍存活（服务重启会再回调 onServiceConnected）；
            // 置 false 会让 onDestroy 跳过 unbind 而泄漏 ServiceConnection
            service = null
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

        // 每次应用启动必弹（本页即 launcher，继承原主页启动语义）；savedInstanceState
        // 守卫：配置重建走恢复路径，不在同一次启动内重复弹窗；isTaskRoot 守卫：
        // singleTask 复用/外部唤起（通知点入）不新建实例不重弹，只对真冷启动弹
        if (savedInstanceState == null && isTaskRoot()) {
            maybeShowDisclaimer()
        }

        // 底部入口：帮助（本地帮助页）/ 关于（本地对话框）——原主页文字入口迁入连线页
        findViewById<Button>(R.id.button_help).setOnClickListener {
            val intent = Intent(this, com.xqdk.chess.views.WebviewActivity::class.java).apply {
                putExtra("url", "file:///android_asset/help.html")
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.button_about).setOnClickListener { showAboutDialog() }

        btnOverlay = findViewById(R.id.btn_overlay_permission)
        btnNotify = findViewById(R.id.btn_notification_permission)
        stepOverlayNum = findViewById(R.id.step_overlay_num)
        stepOverlayHint = findViewById(R.id.step_overlay_hint)
        stepOverlayChip = findViewById(R.id.step_overlay_chip)
        stepNotifyNum = findViewById(R.id.step_notify_num)
        stepNotifyHint = findViewById(R.id.step_notify_hint)
        stepNotifyChip = findViewById(R.id.step_notify_chip)
        stepCaptureNum = findViewById(R.id.step_capture_num)
        stepCaptureChip = findViewById(R.id.step_capture_chip)
        btnStartCapture = findViewById(R.id.btn_start_capture)
        btnOverlayTheme = findViewById(R.id.btn_overlay_theme)
        tvStatus = findViewById(R.id.tv_auto_status)
        stateDot = findViewById(R.id.state_dot)
        hero = findViewById(R.id.assist_hero)
        riverLine = findViewById(R.id.river_line)
        dotGrid = findViewById(R.id.dot_grid)

        // 悬浮窗主题初始显示（持久化值）；循环切换：先落盘再通知服务——service 为 null
        // （已 CLOSE 停服、连接断开）时 applyOverlayTheme 不会被调用，只改文字会导致
        // 配置与显示不一致
        btnOverlayTheme.text = OverlayTheme.fromKey(
            com.xqdk.chess.assist.AssistConfig(this).overlayTheme
        ).label
        btnOverlayTheme.setOnClickListener {
            val cur = OverlayTheme.fromKey(
                com.xqdk.chess.assist.AssistConfig(this).overlayTheme
            )
            val next = themeOrder[(themeOrder.indexOf(cur) + 1) % themeOrder.size]
            com.xqdk.chess.assist.AssistConfig(this).overlayTheme = next.key
            service?.applyOverlayTheme(next)
            btnOverlayTheme.text = next.label
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
            // 一个动作（终稿原则#1）：运行中再点 = 停止指导，与悬浮窗"关闭"同路径，
            // 之后可在本页重新授权启动（startCapture 复位会话态）
            val svc = service
            if (svc?.isCapturing() == true) {
                svc.stopGuidance()
                return@setOnClickListener
            }
            // 前置校验：悬浮窗未授权时启动服务，ensureOverlay 静默返回 null，
            // 界面显示"运行中"却永远没有悬浮面板
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先完成①悬浮窗权限授权，再启动屏幕识别", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
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

        // 思考制式：深度制（默认，建议更强）/ 时间制（固定时长收束，节奏可预期）；
        // 运行中热切换——悬浮窗据此只显示对应制式的切换芯片（深度档或时长档）
        val rgMode = findViewById<RadioGroup>(R.id.rg_search_mode)
        findViewById<RadioButton>(
            if (cfg.searchMode == "time") R.id.rb_mode_time else R.id.rb_mode_depth
        ).isChecked = true
        rgMode.setOnCheckedChangeListener { _, id ->
            val isTime = id == R.id.rb_mode_time
            cfg.searchMode = if (isTime) "time" else "depth"
            service?.setSearchMode(isTime)
        }

        // 性能诊断：识别分段计时日志开关（真机排查识别耗时用）——logcat 过滤
        // AssistService，同时落盘到 Download 目录的 md 文件（服务返回文件路径，
        // Toast 标明位置，用户从文件管理器直接取）。服务未绑定（停服中）也先落盘
        // 配置，服务启动时读到即生效；服务在线则即时重建 ONNX 引擎（分段计时是
        // 识别器构造参数）
        val btnBench = findViewById<Button>(R.id.btn_bench_profiling)
        btnBench.text = if (cfg.benchProfiling) "开" else "关"
        btnBench.setOnClickListener {
            val on = !cfg.benchProfiling
            cfg.benchProfiling = on
            btnBench.text = if (on) "开" else "关"
            val svc = service
            if (svc != null) {
                val path = svc.setBenchProfiling(on)
                Toast.makeText(this, when {
                    on && path != null -> "诊断已开启：$path\n（关闭时自动封口，可直接发送该文件）"
                    on -> "诊断已开启：文件创建失败，请用 logcat 过滤 AssistService 采集"
                    else -> "诊断已关闭，文件已封口"
                }, if (on) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已保存，下次启动识别时生效", Toast.LENGTH_SHORT).show()
            }
        }

        // bindService 返回值决定是否需要解绑：绑定失败仍置 bound 会在 onDestroy 泄漏连接
        bound = bindService(Intent(this, ScreenAssistService::class.java), connection, Context.BIND_AUTO_CREATE)

        // 检查更新：仅手动触发（不主动检查是产品拍板）。查询走独立线程
        // （AppUpdateChecker.fetchReleasesJson 为网络 IO，3s 超时），结果回主线程
        // 弹窗/Toast；按钮检查期间禁用防重复点击。失败如实提示——
        // api.github.com 在中国大陆通常需可访问 GitHub 的网络环境
        val btnUpdate = findViewById<Button>(R.id.btn_check_update)
        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            btnUpdate.text = "检查中…"
            val checker = com.xqdk.chess.assist.AppUpdateChecker(
                com.xqdk.chess.BuildConfig.BUILD_DATE
            )
            // 一次性线程（查询完毕自然回收，不留滞留池）
            Thread {
                val result = checker.check()
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "检查"
                    when (result) {
                        is com.xqdk.chess.assist.UpdateResult.UpToDate ->
                            Toast.makeText(this,
                                "已是最新版本（构建日期 ${com.xqdk.chess.BuildConfig.BUILD_DATE}）",
                                Toast.LENGTH_SHORT).show()
                        is com.xqdk.chess.assist.UpdateResult.Newer ->
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("发现新版本 ${result.tag}")
                                .setMessage(
                                    "最新稳定版发布于 ${result.date}（当前构建 ${com.xqdk.chess.BuildConfig.BUILD_DATE}）。\n\n" +
                                    "APK 请从 GitHub Release 页面下载安装（需要可访问 GitHub 的网络环境；" +
                                    "下载后如与已装包签名不同，需卸载重装）。"
                                )
                                .setPositiveButton("打开下载页") { _, _ ->
                                    runCatching {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(
                                                com.xqdk.chess.assist.AppUpdateChecker.RELEASES_PAGE))
                                        )
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        is com.xqdk.chess.assist.UpdateResult.Failure ->
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回填识别引擎实际值：悬浮窗热切换（同步落盘）后页面无推送通道，停留中的
        // 页面 RadioGroup 会显示与实际相反的选择（点选反而触发一次真切换）
        service?.let { svc ->
            val rgEngine = findViewById<RadioGroup>(R.id.rg_recognizer_engine)
            val actual = if (svc.getRecognizerEngine() == ScreenAssistService.ENGINE_YOLO)
                R.id.rb_engine_yolo else R.id.rb_engine_onnx
            if (rgEngine.checkedRadioButtonId != actual) {
                findViewById<RadioButton>(actual).isChecked = true
            }
        }
        refreshStatus()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    override fun onDestroy() {
        // 用户主动退出连线页（返回键 / 免责"不同意退出"）= 明确的结束意图：随退停服务，
        // 不留"前台服务 + 录屏 + 悬浮窗"三件套残留——否则下次打开 App 悬浮窗自动出现
        // （用户实测 bug：首次使用后退出，再开软件面板已在屏上）。stopSelf 在仍有绑定
        // 时只是挂起，随后 unbind 才真正销毁服务，顺序不能反。
        // 配置重建（旋转）isFinishing=false 不受影响；按 Home 切去象棋 App 走 onStop
        // 不销毁本页，悬浮窗指导照常存活。
        if (isFinishing) service?.stopGuidance()
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun startCaptureService() {
        if (captureIntentData == null) return
        val intent = Intent(this, ScreenAssistService::class.java)
            .setAction(ScreenAssistService.ACTION_START)
            .putExtra(ScreenAssistService.EXTRA_RESULT_CODE, captureIntentCode)
            .putExtra(ScreenAssistService.EXTRA_RESULT_DATA, captureIntentData)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * 免责声明弹窗：每次启动必弹（无可跳过、无持久化跳过），明确"仅供学习研究、
     * 禁止用于对局平台"等使用边界；同意后进入连线页，不同意直接退出应用。
     */
    private fun maybeShowDisclaimer() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ -> d.dismiss() }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    /** 关于：本地对话框（特色功能 + 免责声明 + 鸣谢），不依赖外部站点 */
    private fun showAboutDialog() {
        val text = "象棋迪克 XQDK v${com.xqdk.chess.BuildConfig.VERSION_NAME}\n\n" +
            "特色功能：\n" +
            "· AI 连线识别：ONNX 角点式（主力）/ YOLO 免校准屏幕识别、自动朝向、" +
            "悬浮窗实时走法建议、开局库速查、多候选与紧急手动模式（只识别提示，不自动走子）；\n" +
            "· 内置 Pikafish 引擎与桔库开局库，深度/时间双思考制式可选。\n\n" +
            DISCLAIMER + "\n\n" +
            CREDITS
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于 · 象棋迪克")
            .setIcon(com.xqdk.chess.R.drawable.ic_launcher)
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }

    /** 文本变化才 setText（1s 轮询不重写视图，避免频跳） */
    private fun setTextIfChanged(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** 序号圆背景：fill 有值=实心（完成），否则描边（待办/未开始） */
    private fun circleNum(fill: Int?, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (fill != null) setColor(fill) else setStroke(dp(1.5f), stroke)
        }

    /** 状态胶囊背景：pill 描边 + 卡片面底 */
    private fun pillChip(stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999f).toFloat()
            setColor(color(R.color.xq_surface))
            setStroke(dp(1f), stroke)
        }

    /**
     * 步骤行回填（美学终稿 §3.2 几何序号行，参照内阁 setStep 模式）：
     * 完成=玉✓实心+玉胶囊；待办=序号朱砂描边+朱砂胶囊"去授予"；未开始=灰空心。
     * 同态重复调用零开销（1s 轮询安全）。
     */
    private fun setStep(num: TextView, chip: TextView, state: StepState, chipText: String) {
        if (appliedStep[num.id] == state to chipText) return
        appliedStep[num.id] = state to chipText
        when (state) {
            StepState.DONE -> {
                num.text = "✓"
                num.background = circleNum(color(R.color.xq_jade), 0)
                num.setTextColor(color(R.color.xq_paper))
            }
            StepState.TODO -> {
                num.text = num.tag as? String ?: ""
                num.background = circleNum(null, color(R.color.xq_zhu))
                num.setTextColor(color(R.color.xq_zhu))
            }
            StepState.LOCKED -> {
                num.text = num.tag as? String ?: ""
                num.background = circleNum(null, color(R.color.xq_muted))
                num.setTextColor(color(R.color.xq_muted))
            }
        }
        val accent = when (state) {
            StepState.DONE -> R.color.xq_jade
            StepState.TODO -> R.color.xq_zhu
            StepState.LOCKED -> R.color.xq_muted
        }
        chip.text = chipText
        chip.background = pillChip(color(accent))
        chip.setTextColor(color(accent))
    }

    /** 主按钮两态：朱砂"开始屏幕识别" / 警示"停止指导"（线框色 + 文字即全部表情） */
    private fun setMainButton(state: MainBtnState) {
        if (mainBtnState == state) return
        mainBtnState = state
        when (state) {
            MainBtnState.IDLE -> {
                btnStartCapture.text = "开始屏幕识别"
                btnStartCapture.setBackgroundResource(R.drawable.main_btn_outline)
                btnStartCapture.setTextColor(color(R.color.xq_ink_text))
            }
            MainBtnState.RUNNING -> {
                btnStartCapture.text = "停止指导"
                btnStartCapture.setBackgroundResource(R.drawable.main_btn_outline_warn)
                btnStartCapture.setTextColor(color(R.color.xq_warn))
            }
        }
    }

    /** 状态点：8dp 实心圆，颜色即语义（tag 存色做变化检测） */
    private fun setStateDot(colorValue: Int) {
        if (stateDot.tag == colorValue) return
        stateDot.tag = colorValue
        stateDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorValue)
        }
    }

    /** hero 两态：待机品牌句（serif 28sp）/ 运行态实时状态文字（sans 20sp，让位） */
    private fun setHero(live: Boolean, liveText: String = "") {
        if (heroLive == live && (!live || hero.text.toString() == liveText)) return
        heroLive = live
        if (live) {
            hero.text = liveText
            hero.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            hero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            hero.letterSpacing = 0f
            hero.maxLines = 2
        } else {
            hero.text = getString(R.string.main_hero)
            hero.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            hero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            hero.letterSpacing = 0.02f
            hero.maxLines = Int.MAX_VALUE
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val svc = service
        val running = svc?.isCapturing() == true

        setStep(stepOverlayNum, stepOverlayChip,
            if (overlayOk) StepState.DONE else StepState.TODO,
            if (overlayOk) "已就绪" else "去授予")
        setTextIfChanged(stepOverlayHint,
            if (overlayOk) "已授权 · 可随时在系统设置中更改" else "用于在其他应用上方显示走法面板")

        setStep(stepNotifyNum, stepNotifyChip,
            if (notifOk) StepState.DONE else StepState.TODO,
            if (notifOk) "已就绪" else "去授予")
        setTextIfChanged(stepNotifyHint,
            if (notifOk) "前台服务提示正常" else "Android 13+ 前台服务需要")
        btnNotify.isEnabled = !notifOk && Build.VERSION.SDK_INT >= 33

        setStep(stepCaptureNum, stepCaptureChip,
            if (running) StepState.DONE else StepState.LOCKED,
            if (running) "运行中" else "未启动")

        setMainButton(if (running) MainBtnState.RUNNING else MainBtnState.IDLE)

        if (running) {
            val statusText = svc!!.getStatusText()
            val mapped = AssistStateMap.fromStatusText(statusText)
            setStateDot(
                when (mapped) {
                    AssistState.ERROR -> color(R.color.xq_warn)
                    AssistState.RUNNING_WAVE -> color(R.color.xq_jade)
                    AssistState.RUNNING_STILL -> color(R.color.xq_amber)
                    AssistState.IDLE -> color(R.color.xq_muted)
                }
            )
            setTextIfChanged(tvStatus, statusText.lineSequence().firstOrNull() ?: "识别中")
            setHero(true, statusText)
            riverLine.setBreathing(true)
            dotGrid.setAssistState(mapped)
        } else {
            val pending = !overlayOk || !notifOk
            setStateDot(color(if (pending) R.color.xq_amber else R.color.xq_jade))
            setTextIfChanged(tvStatus, if (pending) "待授权" else "已就绪")
            setHero(false)
            riverLine.setBreathing(false)
            dotGrid.setAssistState(AssistState.IDLE)
        }
    }
}
