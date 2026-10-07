package com.xqdk.chess.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xqdk.chess.R
import com.xqdk.chess.assist.ui.OverlayAction
import com.xqdk.chess.assist.ui.OverlayModel
import com.xqdk.chess.assist.ui.OverlayPanelView
import com.xqdk.chess.assist.ui.OverlayStateKind
import com.xqdk.chess.assist.ui.OverlayTheme
import com.xqdk.chess.assist.ui.QiTone
import com.xqdk.chess.gamelogic.Piece
import com.xqdk.chess.gamelogic.Zobrist
import com.xqdk.chess.openbook.BHOpenBook
import com.xqdk.chess.openbook.BookData
import com.xqdk.chess.openbook.OpenBook
import kotlin.math.max
import kotlin.math.min

/**
 * 连线服务：录制屏幕（MediaProjection）→ YOLO 识别棋盘 → 悬浮窗提示（仅提示，无任何自动点击）。
 *
 * 合规说明：
 * - 仅使用 MediaProjection（录屏级授权，用户主动确认）与悬浮窗权限；
 * - 不注册无障碍服务、不注入触摸、不读写其他应用数据；
 * - 识别为只读，走子由玩家手动完成。
 */
class ScreenAssistService : Service() {

    companion object {
        private const val TAG = "AssistService"
        const val ACTION_START = "com.xqdk.chess.assist.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "assist_foreground"
        private const val NOTIF_ID = 1001
        private const val MIN_FRAME_INTERVAL_MS = 220L
        /** 仅当悬浮窗面板遮挡棋盘（抓帧前需短暂隐藏面板）时才放慢到该间隔，避免频繁闪烁 */
        private const val OVERLAY_FRAME_INTERVAL_MS = 1400L
        private const val MIN_RECOGNIZE_INTERVAL_MS = 250L
        /** 建议成熟阈值：最佳着法连续未变化的时长，超过即视为"定着"（绿箭头） */
        private const val SUGGEST_STABLE_MS = 1200L
        /** 建议渲染节流：info 流（depth/bestmove 变化即推）高频到达时合并悬浮窗重绘——
         *  每条全量重绘会刷爆主线程（Cabinet 同款 120ms 节流 + 补绘排队），节流窗口内只安排一次补绘 */
        private const val SUGGEST_RENDER_THROTTLE_MS = 120L
        /** 帧差检测采样步长（像素，Cabinet 同款）：棋子/走子痕迹远大于步长，移动必被检出 */
        private const val FRAME_DIFF_STEP = 16
        /** 帧差"有变化"的占比阈值（变化采样点比例，与历史 1/500 行为一致） */
        private const val CHANGE_RATIO_MIN = 0.002f
        /** 帧循环兜底 tick 周期（Cabinet 同款）：画面静止后合成器不再产帧、回调停摆，
         *  确认链（多帧一致）靠它重放 lastFrame 推进，否则走子后永远"正在识别棋盘…" */
        private const val FRAME_TICK_MS = 700L
        /**
         * 单帧最少棋子数：低于该值视为"未见棋盘"（回全屏重定位）。
         * 残局可低至 3 子（帅仕 vs 将），不能沿用固定 10 子门槛——那会把合法残局
         * 永远拒之门外，反而让幻觉出 ≥10 检测的坏帧乘虚而入；合法性由 validate() 把关。
         */
        const val MIN_RECOGNIZED_PIECES = 3
        /** 识别引擎标识：ONNX 两步式（主力，默认）/ YOLO 检测式（回退），主页单选热切换 */
        const val ENGINE_ONNX = "onnx"
        const val ENGINE_YOLO = "yolo"
        /** ONNX 识别最小间隔（毫秒）：下限取 Cabinet pacer 常规与 burst 之间的折中——
         *  角点缓存复用帧很便宜（实测 ~180ms），太短会让全图定位背靠背烧 CPU */
        private const val ONNX_RECOGNIZE_INTERVAL_MIN_MS = 600L
    }

    /** 供 Activity 绑定调用的接口 */
    inner class CastBinder : Binder() {
        fun service(): ScreenAssistService = this@ScreenAssistService
    }

    fun isCapturing(): Boolean = mediaProjection != null && virtualDisplay != null

    /** 最近一次状态文本（供连线页显示） */
    fun getStatusText(): String = lastStatusText

    /**
     * 延迟初始化：服务构造时 Context 尚未 attach（mBase==null），
     * 立即访问 getSharedPreferences 会崩溃；首次访问发生在 onCreate 及之后，安全。
     */
    private val config by lazy { AssistConfig(this) }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val workerThread = HandlerThread("assist-worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var lastFrameTs = 0L
    private var lastRecognizeTs = 0L
    private var pendingFrame = false

    /**
     * 最近一帧的引用（像素数组来自下方双缓冲池）：画面静止后合成器不再产帧
     * （acquireLatestImage 为 null），frameTick 兜底重放它推进多帧确认——已确认
     * 稳态下棋盘未动，其内容即当前屏幕。
     * 不保存 lastFrame 会出现走子后永远"正在识别棋盘…"（Cabinet kept 机制教训）。
     */
    @Volatile private var lastFrame: Frame? = null

    /**
     * 帧像素双缓冲池（识别热路径最大单点分配的治理）：
     * 1080×2280 一帧 ≈ 9.8MB，原先每帧 imageToFrame 新建数组，识别活跃期
     * （帧差活跃 + 未确认期）~10MB/帧喂 GC，中端机可感知卡顿。两块轮换。
     *
     * 安全性（全依赖 worker 线程串行，onImageAvailable/handleFrame/frameTick 都在
     * workerHandler 上执行）：覆盖只可能发生在 handleFrame 尚未开始或已经结束后
     * ——不存在边识别边写的撕裂。极端时序下（识别慢于帧率）在队列中的 handleFrame
     * 所引用的块可能被 takeFrameForDiff 重写为更新的画面，识别帧因此比触发帧"新"
     * ——识别目标本就是当前屏幕，方向正确，多帧确认链不受影响。
     * 【雷区】若未来把帧处理移出 worker 线程或允许多帧并发识别，本池必须同步加锁。
     */
    private val frameBufs = arrayOfNulls<IntArray>(2)
    private var frameBufFlip = 0

    /** 从双缓冲池取一块像素缓冲（尺寸变化如转屏时惰性重建） */
    private fun obtainFrameBuf(size: Int): IntArray {
        frameBufFlip = 1 - frameBufFlip
        var buf = frameBufs[frameBufFlip]
        if (buf == null || buf.size != size) {
            buf = IntArray(size)
            frameBufs[frameBufFlip] = buf
        }
        return buf
    }
    /**
     * 自上次识别消费以来画面是否出现过变化（累积标志，非瞬时值）：
     * 帧差判真变化置位；识别成功消费（tracker 有结论）后清位。
     * frameTick 只在"有未消费变化"或"确认链未完成"时重放，稳态零冗余推理。
     */
    @Volatile private var screenChangedSinceRec = true
    /** 帧差签名槽（上帧采样点像素值）：与上帧逐点比较即更新，零分配复用 */
    private var frameSig: IntArray? = null

    @Volatile private var paused = false
    /**
     * 紧急手动模式：识别连续失效/产出幽灵局面时，暂停帧识别，由用户在小棋盘上
     * 点选"起点→终点"录入实战着法（自动翻转走子方）、再点一次已选中子将其删除。
     */
    @Volatile private var manualMode = false
    /** 手动模式下小棋盘当前选中的格（canonical x,y） */
    @Volatile private var manualSelected: Pair<Int, Int>? = null
    /** 用户已点击“关闭”：不再重建悬浮窗，直到重新启动服务 */
    @Volatile private var overlayClosed = false
    /** 用户正在拖动面板（拖动期间取帧不隐藏面板） */
    @Volatile private var userDragging = false

    private var tracker = BoardTracker(3)
    private var yoloDetector: YoloBoardDetector? = null
    @Volatile private var recMissStreak = 0
    /** 裁剪推理下连续不稳定的帧数（棋盘挪动后残框自锁时回退全屏重定位） */
    @Volatile private var cropUnstableStreak = 0
    /** 本次会话检出的棋盘网格（用于悬浮窗遮挡判断与裁剪推理），识别失败时清空自愈 */
    @Volatile private var sessionGrid: BoardGrid? = null

    // ==================== 识别引擎状态机（ONNX 主力 + YOLO 回退，对齐 Cabinet）====================
    /** 当前生效引擎；worker 帧循环读写、主页经 pendingEngine 请求切换 */
    @Volatile private var engineName = ENGINE_YOLO
    /** 主页请求切换的目标引擎：worker 帧循环里应用（不在 UI 线程做重活） */
    @Volatile private var pendingEngine: String? = null
    /** ONNX 两步式识别器（模型 ~21.5MB 加载重，后台线程初始化，期间帧走跳过逻辑） */
    private var onnxEngine: OnnxBoardRecognizer? = null
    /** 连续产出结构非法局面的帧数（走子动画过渡帧常客）：连续 3 帧强制角点重定位 */
    private var invalidStreak = 0
    /** 上一次 ONNX 识别耗时（毫秒）：下一帧间隔 = max(下限, 耗时×2)，自适应退避 */
    @Volatile private var onnxLastCostMs = 0L

    private var analysisEngine: AnalysisEngine? = null
    @Volatile private var engineReady = false
    /** 最近一次引擎错误（显示于状态栏，onEngineReady 时清除） */
    @Volatile private var engineErrorText: String? = null

    /** 建议成熟度跟踪：最佳着法连续 SUGGEST_STABLE_MS 未变（或本次搜索已结束）=> 定着（绿箭头） */
    @Volatile private var stableUcci: String? = null
    @Volatile private var stableSince = 0L
    @Volatile private var searchSettled = false
    /** 上次触发渲染的 info 行特征（深度/最佳着）：不变则不渲染（根因 4） */
    @Volatile private var lastRenderedDepth = -1
    @Volatile private var lastRenderedBest: String? = null

    @Volatile private var currentFen = ""
    /** canonical 布局 90 子（红恒在 y=9；迷你棋盘视图按 flipped 自行做屏幕朝向映射） */
    @Volatile private var currentPieces: IntArray? = null
    /** 当前确认局面的 canonical 棋盘（开局库查询用） */
    @Volatile private var currentCanonical: Array<IntArray>? = null
    private var currentOrientation = Orientation.STANDARD
    private var lastAnalysis: AnalysisResult? = null
    private var selectedCandidate = 0

    /** 开局库候选（我方回合命中时非空；随 selectedCandidate 循环展示） */
    private var bookMoves: List<BookData>? = null
    /** 开局库（与对弈同款）；仅 worker 线程访问，首次查询时惰性初始化 */
    private var openBook: BHOpenBook? = null
    private var openBookFailed = false

    /** 已确认局面历史（悔棋用）：每次确认入栈，回退时弹出当前、显示上一条 */
    private class HistoryEntry(val result: RecognitionResult, val redGo: Boolean, val fen: String)
    private val history = ArrayDeque<HistoryEntry>()

    /** 引擎强度档位（固定深度，与对弈"固定深度"制式一致） */
    private val strengthLevels = listOf("快速·深度12" to 12, "标准·深度20" to 20, "强劲·深度24" to 24)
    private var strengthIndex = 0
    /** 引擎 Hash 档位（与对弈设置同口径）：从 256 起步循环 */
    private val hashLevels = listOf(256, 512, 1024, 2048)
    private var hashIndex = 0
    /** 引擎变招路数（MultiPV 1-3，悬浮窗左列"变招档"按钮循环；1 路算力最全） */
    private var multiPv: Int = 3

    private var windowManager: WindowManager? = null
    private var overlayPanel: OverlayPanelView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val binder = CastBinder()

    override fun onCreate() {
        super.onCreate()
        tracker = BoardTracker(config.confirmCount)
        // 强度档位与持久化设置对齐（设置值不在档位表内时用”标准”）
        val idx = strengthLevels.indexOfFirst { it.second == config.searchDepth }
        strengthIndex = if (idx >= 0) idx else 1
        // Hash 档位与持久化设置对齐（从 256 起步循环）
        val hidx = hashLevels.indexOfFirst { it == config.hashMb }
        hashIndex = if (hidx >= 0) hidx else 0
        // 变招路数与持久化设置对齐（1-3；1 路算力最全，默认 3 保留候选展示）
        multiPv = config.multiPv
        // YOLO 棋子检测：直接检测 14 类棋子 + 棋盘框，免校准、跨 App 泛化。
        // 初始化失败（含 JVM 测试环境无 TFLite 原生库的 UnsatisfiedLinkError）只提示，不阻塞服务
        yoloDetector = try {
            YoloBoardDetector(this)
        } catch (t: Throwable) {
            Log.w(TAG, "yolo detector init failed", t)
            null
        }
        Log.i(TAG, "yolo detector: ${if (yoloDetector != null) "ready" else "unavailable"}")
        // 识别引擎按持久化配置初始化（默认 ONNX 主力；加载失败自动回退 YOLO）
        initEngine(config.recognizerEngine)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
            startForegroundInternal()
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        createChannel()
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 29)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "连线·屏幕识别", NotificationManager.IMPORTANCE_LOW)
        ch.description = "屏幕识别与悬浮窗指导运行中"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(contentText: String? = null): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.xqdk.chess.assist.ui.AssistActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("象棋迪克·连线识别运行中")
            .setContentText(contentText ?: "仅识别局面并给出走法建议，不会自动走子")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    // ==================== 截屏 ====================

    private fun startCapture(resultCode: Int, data: Intent?) {
        if (mediaProjection != null || data == null) return
        // 重新启动时允许再次显示悬浮窗
        overlayClosed = false
        paused = false
        val metrics = screenMetrics()
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        // 记录帧尺寸：悬浮窗遮挡判断把归一化棋盘框换算回像素时需要
        config.frameSize = w to h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener(::onImageAvailable, workerHandler)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "assist-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, workerHandler
        )
        Log.i(TAG, "capture started: ${w}x$h")

        // 帧循环兜底 tick：画面静止回调停摆时推进多帧确认（见 frameTick 注释）
        workerHandler.removeCallbacks(frameTick)
        workerHandler.postDelayed(frameTick, FRAME_TICK_MS)

        // 提前启动分析引擎（后台准备，不阻塞识别）
        analysisEngine = AnalysisEngine(this, analysisListener, config.searchDepth, config.multiPv)
            .also { it.setHash(config.hashMb); it.start() }
        postRender()
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()
        // 遮挡黄字重算（每真实帧，沿变化才重绘）：FLAG_SECURE 后遮挡不影响识别，
        // 黄字纯属引导用户把面板移开棋盘
        recomputeOcclusion()
        // FLAG_SECURE（任务书根因 3 根治）：悬浮窗已从录屏合成中整体剔除——画面里
        // 永远没有自家面板，抓帧全程无需"隐藏-恢复"动作，原 alpha=0 → sleep(60) →
        // 抓帧 → alpha=1 机制整体删除（那是悬浮窗每 1.4s 消失重现闪烁的本体，
        // 也曾阻塞 assist-worker 线程并存在隐藏/抓帧无栅栏的识别污染）。
        // 慢路径保留降频语义（遮挡条件删除——FLAG_SECURE 后遮挡与识别无关）：
        // 棋盘未定位/识别不稳定时全图定位推理很重，低频采样
        val trackingUnstable = tracker.unstableStreak > 0
        val slowPath = sessionGrid == null || trackingUnstable
        val interval = if (slowPath) OVERLAY_FRAME_INTERVAL_MS else MIN_FRAME_INTERVAL_MS
        if (now - lastFrameTs < interval) {
            // 必须取走帧，否则 ImageReader 缓冲池占满后不再派发回调；
            // 取走后做轻量帧差（稀疏采样毫秒级）：真变化才标记（screenChangedSinceRec）
            takeFrameForDiff(reader, now)
            return
        }
        lastFrameTs = now
        try {
            // 上一帧还在识别队列时本帧注定被覆盖：转帧差采样（真变化不丢标志）
            if (pendingFrame) {
                takeFrameForDiff(reader, now)
                return
            }
            val image = reader.acquireLatestImage() ?: return
            try {
                // 快速路径第一道帧差：全帧稀疏采样，窗口外背景静止即整帧丢弃
                // （省下整屏解码与 YOLO 推理）。慢路径（未定位/确认期）不短路——
                // 定位与多帧确认每帧都要推理
                val ratio = frameChangeRatio(image)
                if (ratio >= CHANGE_RATIO_MIN) {
                    screenChangedSinceRec = true
                }
                if (ratio < CHANGE_RATIO_MIN && !slowPath) return
                val frame = imageToFrame(image)
                lastFrame = frame
                pendingFrame = true
                workerHandler.post { pendingFrame = false; handleFrame(frame) }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "onImageAvailable failed", e)
        }
    }

    /** 节流/丢弃路径的帧处理：取走并关闭帧，做稀疏采样帧差；真变化才置位标志并保存像素 */
    private fun takeFrameForDiff(reader: ImageReader, now: Long) {
        try {
            reader.acquireLatestImage()?.use { img ->
                val ratio = frameChangeRatio(img)
                if (ratio >= CHANGE_RATIO_MIN) {
                    screenChangedSinceRec = true
                    // 变化帧像素即刻保存（Cabinet kept 机制）：录屏帧"取走即焚"，
                    // 不保存则兜底重放拿到的永远是更旧的画面（走子后冻结的根源）
                    lastFrame = imageToFrame(img)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame diff failed", e)
        }
    }

    /**
     * 帧循环兜底 tick（Cabinet FRAME_TICK_MS 同款）：画面静止后合成器不再产帧、
     * ImageReader 回调停摆，多帧确认链会饿死（走子后永远"正在识别棋盘…"）。
     * 仅在"有未消费的画面变化"或"确认链未完成"时重放 lastFrame 推进；
     * 稳态（已确认且无变化）零冗余推理。
     */
    private val frameTick = object : Runnable {
        override fun run() {
            if (!paused && !manualMode) {
                val unstable = tracker.unstableStreak > 0
                if ((screenChangedSinceRec || unstable) && !pendingFrame) {
                    val f = lastFrame
                    if (f != null) {
                        pendingFrame = true
                        workerHandler.post { pendingFrame = false; handleFrame(f) }
                    }
                }
            }
            workerHandler.postDelayed(this, FRAME_TICK_MS)
        }
    }

    private var frameRowBytes: ByteArray? = null

    private fun imageToFrame(image: Image): Frame {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        // 双缓冲池取用（原先此处每帧 IntArray(w*h) ≈ 10MB，是识别管线最大分配点）
        val px = obtainFrameBuf(w * h)
        if (pixelStride == 4) {
            // 快路径：整行一次批量 JNI 读，再在 JVM 内解 RGBA（避免每像素 4 次单字节 JNI 调用）
            var row = frameRowBytes
            if (row == null || row.size < w * 4) row = ByteArray(w * 4)
            frameRowBytes = row
            var p = 0
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, w * 4)
                var i = 0
                while (i < w * 4) {
                    px[p++] = (0xFF shl 24) or
                        ((row[i].toInt() and 0xFF) shl 16) or
                        ((row[i + 1].toInt() and 0xFF) shl 8) or
                        (row[i + 2].toInt() and 0xFF)
                    i += 4
                }
            }
        } else {
            for (y in 0 until h) {
                val rowStart = y * rowStride
                for (x in 0 until w) {
                    val off = rowStart + x * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Frame(w, h, px)
    }

    /**
     * 帧差变化占比（变化采样点 / 参与比较的采样点，0~1，Cabinet 同款）：
     * 对（棋盘外扩区或全屏）区域做步长 16px 的稀疏采样，与上帧逐点比较。
     * 录屏为合成器直出，静止画面逐位一致，差异占比 > 0.2% 才算"有变化"（只滤零星闪烁）。
     * 采样区与推理 cropHint 同区（已定位棋盘时只采棋盘外扩 ~1.2 格区域）。
     * 比较即更新签名槽（frameSig），零分配复用。
     */
    private fun frameChangeRatio(image: Image): Float {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val crop = cropHint(w, h)
        val x0 = if (crop != null) max(0, crop[0].toInt()) else 0
        val y0 = if (crop != null) max(0, crop[1].toInt()) else 0
        val x1 = if (crop != null) min(w, crop[2].toInt()) else w
        val y1 = if (crop != null) min(h, crop[3].toInt()) else h
        val cols = ((x1 - x0) / FRAME_DIFF_STEP).coerceAtLeast(1)
        val rows = ((y1 - y0) / FRAME_DIFF_STEP).coerceAtLeast(1)
        val cur = frameSig?.takeIf { it.size == cols * rows }
            ?: IntArray(cols * rows).also { frameSig = it }
        var diff = 0
        var idx = 0
        if (pixelStride == 4) {
            // 快路径：逐行批量 JNI 读（复用 frameRowBytes），行内稀疏采样
            var row = frameRowBytes
            if (row == null || row.size < w * 4) row = ByteArray(w * 4)
            frameRowBytes = row
            for (ry in 0 until rows) {
                val y = min(h - 1, y0 + ry * FRAME_DIFF_STEP)
                buffer.position(y * rowStride)
                buffer.get(row, 0, w * 4)
                for (cx in 0 until cols) {
                    val x = min(w - 1, x0 + cx * FRAME_DIFF_STEP)
                    val off = x * 4
                    val v = (0xFF shl 24) or
                        ((row[off].toInt() and 0xFF) shl 16) or
                        ((row[off + 1].toInt() and 0xFF) shl 8) or
                        (row[off + 2].toInt() and 0xFF)
                    if (v != cur[idx]) diff++
                    cur[idx] = v
                    idx++
                }
            }
        } else {
            // 兜底：非 4 字节步长的逐点读（采样点稀疏，开销可接受）
            for (ry in 0 until rows) {
                val y = min(h - 1, y0 + ry * FRAME_DIFF_STEP)
                for (cx in 0 until cols) {
                    val x = min(w - 1, x0 + cx * FRAME_DIFF_STEP)
                    val off = y * rowStride + x * pixelStride
                    val v = (0xFF shl 24) or
                        ((buffer.get(off).toInt() and 0xFF) shl 16) or
                        ((buffer.get(off + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(off + 2).toInt() and 0xFF)
                    if (v != cur[idx]) diff++
                    cur[idx] = v
                    idx++
                }
            }
        }
        // 新建签名的全 0 初值与实际像素比对必然超限，首帧/采样区尺寸变化自动判"有变化"
        return diff.toFloat() / (cols * rows).coerceAtLeast(1)
    }

    /**
     * 调试开关：当 /sdcard/assist_debug_save 存在时，把服务实际收到的帧原始字节存到
     * /sdcard/assist_frame.raw（magic=ASFR, 大端 int w/h, 之后 RGB 三字节每像素）。
     */
    private fun debugSaveFrameIfRequested(frame: Frame) {
        if (!com.xqdk.chess.BuildConfig.DEBUG) return
        val trigger = java.io.File("/sdcard/assist_debug_save")
        if (!trigger.exists()) return
        trigger.delete()
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val outPath = java.io.File(dir, "assist_frame.raw").absolutePath
            java.io.DataOutputStream(java.io.FileOutputStream(outPath)).use { out ->
                out.writeInt(0x41534652) // 'ASFR'
                out.writeInt(frame.width)
                out.writeInt(frame.height)
                for (p in frame.argb) {
                    out.writeByte((p shr 16) and 0xFF)
                    out.writeByte((p shr 8) and 0xFF)
                    out.writeByte(p and 0xFF)
                }
            }
            Log.i(TAG, "debug frame saved $outPath")
        } catch (e: Exception) {
            Log.w(TAG, "debug frame save failed", e)
        }
    }

    // ==================== 识别与分析 ====================

    /**
     * 裁剪推理提示（像素尺寸版本，帧差采样区共用）：已知棋盘位置时只取棋盘外扩约
     * 1.2 格的区域；尺寸向下取 16 的倍数。返回 null 表示整帧。
     */
    private fun cropHint(frameW: Int, frameH: Int): DoubleArray? {
        val g = sessionGrid ?: return null
        val x0 = g.nx0 * frameW
        val y0 = g.ny0 * frameH
        val x1 = g.nx1 * frameW
        val y1 = g.ny1 * frameH
        val mx = (x1 - x0) / 8.0 * 1.2
        val my = (y1 - y0) / 9.0 * 1.2
        val cx0 = max(0.0, x0 - mx)
        val cy0 = max(0.0, y0 - my)
        val cx1 = min(frameW.toDouble(), x1 + mx)
        val cy1 = min(frameH.toDouble(), y1 + my)
        val w = ((cx1 - cx0).toInt() / 16) * 16
        val h = ((cy1 - cy0).toInt() / 16) * 16
        if (w < 64 || h < 64) return null
        return doubleArrayOf(cx0, cy0, cx0 + w, cy0 + h)
    }

    private fun cropHint(frame: Frame): DoubleArray? = cropHint(frame.width, frame.height)

    private fun handleFrame(frame: Frame) {
        if (paused || manualMode) return
        // 保存最近一帧引用（Frame 像素独立分配，保存引用安全）：
        // 画面静止回调停摆时由 frameTick 重放它推进多帧确认
        lastFrame = frame
        debugSaveFrameIfRequested(frame)
        // 引擎切换请求在 worker 帧循环里应用（不在 UI 线程释放/加载会话）
        pendingEngine?.let { want ->
            pendingEngine = null
            if (want != engineName) switchEngine(want)
        }
        if (engineName == ENGINE_ONNX) {
            handleFrameOnnx(frame)
            return
        }
        val yolo = yoloDetector ?: run {
            recMissStreak++
            if (recMissStreak == 1 || recMissStreak % 20 == 0) {
                setStatus("识别引擎初始化失败：请尝试重启应用（重装可修复）")
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRecognizeTs < MIN_RECOGNIZE_INTERVAL_MS) return
        lastRecognizeTs = now
        val crop = cropHint(frame)
        val mapped = try {
            yolo.detect(frame, crop)
        } catch (e: Exception) {
            Log.w(TAG, "yolo detect failed", e)
            null
        }
        if (mapped == null || mapped.pieceCount < MIN_RECOGNIZED_PIECES) {
            recMissStreak++
            // 裁剪区识别不到（棋盘挪走/被遮挡）：清空会话网格，下一帧回全屏重新定位（自愈）
            sessionGrid = null
            if (recMissStreak == 1 || recMissStreak % 10 == 0) {
                setStatus("正在识别棋盘…\n请让棋盘完整无遮挡")
            }
            return
        }
        recMissStreak = 0
        // 悬浮窗遮挡判断沿用网格语义：用 YOLO 棋盘框更新会话网格（同时作为裁剪推理提示）
        sessionGrid = mapped.grid
        val result = RecognitionResult(
            mapped.canonical, mapped.screenRaw, mapped.orientation,
            AssistBoard.validate(mapped.canonical), 0, mapped.pieceCount, mapped.avgScore)
        Log.d(TAG, "rec(yolo): pieces=${mapped.pieceCount} orient=${mapped.orientation} " +
            "issue=${result.issues.size} dropped=${mapped.dropped}")
        when (tracker.onFrame(result)) {
            BoardTracker.Event.NEW_BOARD, BoardTracker.Event.NEW_GAME -> {
                cropUnstableStreak = 0
                // 画面变化已被识别成功消费：兜底 tick 不再重放（等下一次真实变化）
                screenChangedSinceRec = false
                onBoardConfirmed()
            }
            BoardTracker.Event.UNSTABLE -> {
                // 裁剪框内长时间不稳定（棋盘挪动后残框自锁）：回退全屏重新定位
                if (crop != null) {
                    cropUnstableStreak++
                    if (cropUnstableStreak >= 10) {
                        cropUnstableStreak = 0
                        sessionGrid = null
                    }
                } else {
                    cropUnstableStreak = 0
                }
                if (tracker.unstableStreak >= 15) {
                    setStatus("识别不稳定\n请让棋盘完整无遮挡\n画面稳定后自动恢复")
                }
            }
            BoardTracker.Event.SAME_BOARD -> {
                // 已确认局面与屏幕一致：本帧画面已消费，兜底 tick 可停重放
                screenChangedSinceRec = false
            }
        }
    }

    /**
     * ONNX 引擎识别一帧（主力引擎）：RTMPose 角点定位 + 全盘分类，产出后走与
     * YOLO 相同的确认链（tracker.onFrame）。帧管线（帧差/lastFrame/frameTick）不动，
     * 引擎替换只发生在本函数的"识别调用"层。
     */
    private fun handleFrameOnnx(frame: Frame) {
        val eng = onnxEngine
        if (eng == null || !eng.loaded) return // 模型加载中/已回退：跳帧
        val now = System.currentTimeMillis()
        // 自适应节拍：间隔 = max(下限, 上帧耗时×2)。全图定位帧（真机 ~1s）自动退避，
        // 杜绝背靠背推理；角点复用帧便宜，按下限 600ms 保持确认节奏
        val interval = maxOf(ONNX_RECOGNIZE_INTERVAL_MIN_MS.toLong(), onnxLastCostMs * 2)
        if (now - lastRecognizeTs < interval) return
        lastRecognizeTs = now
        val t0 = System.currentTimeMillis()
        val r = try {
            eng.recognize(frame)
        } catch (t: Throwable) {
            Log.w(TAG, "onnx recognize error", t)
            null
        }
        val cost = System.currentTimeMillis() - t0
        onnxLastCostMs = cost
        if (r == null) {
            Log.d(TAG, "onnx recognize -> null (cost=$cost)")
            // 已定位棋盘下的失败多为走子动画过渡帧（悬空子/计数抖动）：保留会话网格
            // （棋盘没动，角点缓存与遮挡判断继续有效）；连续 3 次失败才判定棋盘真
            // 挪走/切了 App，强制角点重定位 + 回全屏（自愈，Cabinet 同款语义）
            recMissStreak++
            if (recMissStreak >= 3) {
                eng.invalidateCorners()
                sessionGrid = null
            }
            if (recMissStreak == 1 || recMissStreak % 10 == 0) {
                setStatus("正在识别棋盘…\n请让棋盘完整无遮挡")
            }
            return
        }
        recMissStreak = 0
        // 角点包围盒换算会话网格（悬浮窗遮挡判断与 YOLO 裁剪提示同语义复用）。
        // 非法结果不可信——尤其不能用它更新遮挡网格：曾因切换 App 后旧网格残留，
        // 遮挡判断失准，识别吃到悬浮窗入镜的残缺画面形成死循环（Cabinet 教训）
        val grid = try {
            BoardGrid.fromPxCorners(
                r.boxPx[0].coerceIn(0.0, frame.width.toDouble()),
                r.boxPx[1].coerceIn(0.0, frame.height.toDouble()),
                r.boxPx[2].coerceIn(0.0, frame.width.toDouble()),
                r.boxPx[3].coerceIn(0.0, frame.height.toDouble()),
                frame.width, frame.height)
        } catch (e: Exception) { null }
        val result = RecognitionResult(
            r.canonical, r.screenRaw, r.orientation,
            AssistBoard.validate(r.canonical), 0, r.pieceCount, r.avgLogit)
        // 过渡帧也可能产出结构非法局面（悬空子被错读）：非法帧不清 sessionGrid
        // （棋盘没动，旧网格仍是当前最佳估计），连续非法由 invalidStreak 熔断强制
        // 角点重定位；棋盘真挪走由 r==null 路径自愈
        val issuesEmpty = result.issues.isEmpty()
        if (issuesEmpty) {
            invalidStreak = 0
            sessionGrid = grid
            // 合法帧即视为画面已消费（frameTick 是否继续重放由 unstableStreak 决定）
            screenChangedSinceRec = false
        } else {
            invalidStreak++
            if (invalidStreak >= 3) {
                // 幂等操作：连续非法期间每帧强制一次全图重定位（Cabinet 同款不清零）
                eng.invalidateCorners()
            }
        }
        Log.d(TAG, "rec(onnx): pieces=${r.pieceCount} orient=${r.orientation} " +
            "issue=${result.issues.size} ${r.elapsedMs}ms")
        when (tracker.onFrame(result)) {
            BoardTracker.Event.NEW_BOARD, BoardTracker.Event.NEW_GAME -> {
                cropUnstableStreak = 0
                onBoardConfirmed()
            }
            BoardTracker.Event.UNSTABLE -> {
                if (tracker.unstableStreak >= 15) {
                    setStatus("识别不稳定\n请让棋盘完整无遮挡\n画面稳定后自动恢复")
                }
            }
            BoardTracker.Event.SAME_BOARD -> {
                screenChangedSinceRec = false
            }
        }
    }

    /** 主页切换识别引擎（worker 帧循环里应用；切换后局面重新确认） */
    fun setRecognizerEngine(name: String) { pendingEngine = name }

    /** 按配置初始化识别引擎；onnx 模型加载重（~21.5MB），放后台线程，期间帧走跳过逻辑 */
    private fun initEngine(name: String) {
        engineName = name
        if (name != ENGINE_ONNX) return
        Thread {
            val eng = OnnxBoardRecognizer(assets)
            val ok = try { eng.load() } catch (t: Throwable) {
                Log.w(TAG, "onnx load threw", t); false
            }
            if (engineName != ENGINE_ONNX) { eng.release(); return@Thread } // 期间已被切换走
            onnxEngine = if (ok) eng else null
            if (!ok) {
                engineName = ENGINE_YOLO
                setStatus("ONNX 引擎加载失败\n已回退 YOLO 识别")
            }
            Log.i(TAG, "onnx engine: ${if (ok) "ready" else "load failed, fallback to yolo"}")
        }.start()
    }

    /** 切换引擎：释放旧 ONNX 会话、重置跟踪状态（两引擎输出细节有差异，重新确认更稳） */
    private fun switchEngine(want: String) {
        Log.i(TAG, "switch engine: $engineName -> $want")
        tracker.reset()
        resetSuggestionState()
        onnxEngine?.release()
        onnxEngine = null
        sessionGrid = null
        recMissStreak = 0
        cropUnstableStreak = 0
        invalidStreak = 0
        onnxLastCostMs = 0
        screenChangedSinceRec = true
        engineName = want
        if (want == ENGINE_ONNX) initEngine(want)
    }

    private fun onBoardConfirmed() {
        val confirmed = tracker.confirmed ?: return
        currentCanonical = confirmed.canonical
        currentPieces = flatten(confirmed.canonical)
        currentOrientation = confirmed.orientation
        currentFen = AssistBoard.toFen(confirmed.canonical, tracker.redGo)
        resetSuggestionState()
        history.addLast(HistoryEntry(confirmed, tracker.redGo, currentFen))
        if (history.size > 60) history.removeFirst()
        Log.i(TAG, "board confirmed: $currentFen")
        postRender()
        scheduleAnalysis()
    }

    private fun flatten(p: Array<IntArray>): IntArray {
        val out = IntArray(90)
        for (y in 0 until 10) for (x in 0 until 9) out[y * 9 + x] = p[y][x]
        return out
    }

    /** 重置建议状态：局面/轮次变化后，旧的分析结果与成熟度跟踪全部作废 */
    private fun resetSuggestionState() {
        lastAnalysis = null
        selectedCandidate = 0
        bookMoves = null
        stableUcci = null
        stableSince = 0L
        searchSettled = false
        // 渲染特征值一并作废：新局面的第一条 info 行必须触发渲染
        lastRenderedDepth = -1
        lastRenderedBest = null
    }

    /** MoveChinese.describe 容错版：解析失败时原样返回 UCCI */
    private fun describeSafe(fen: String, ucci: String): String =
        try { MoveChinese.describe(fen, ucci) } catch (e: Exception) { ucci }

    /**
     * 分析调度（worker 线程）：我方回合先查开局库（与对弈同款，命中即秒出且不占用引擎），
     * 未命中走引擎深度搜索；对方回合直接引擎预案。
     */
    private fun scheduleAnalysis() {
        workerHandler.post {
            val canonical = currentCanonical ?: return@post
            val fen = currentFen
            if (fen.isEmpty()) return@post
            val redGo = tracker.redGo
            val myTurn = redGo == mySideIsRed()
            if (myTurn) {
                val moves = queryBook(canonical, redGo)
                if (!moves.isNullOrEmpty()) {
                    bookMoves = moves
                    mainHandler.post { postRender() }
                    return@post
                }
            }
            bookMoves = null
            if (!engineReady) {
                mainHandler.post { setStatus(engineErrorText ?: "引擎预热中…") }
                return@post
            }
            analysisEngine?.request(fen)
            if (myTurn) mainHandler.post { setStatus("引擎思考中…") }
        }
    }

    /** 查询开局库（惰性初始化；仅 worker 线程调用）。异常时降级为 null（走引擎）。 */
    private fun queryBook(canonical: Array<IntArray>, redGo: Boolean): List<BookData>? {
        if (openBookFailed) return null
        val book = openBook ?: try {
            BHOpenBook(this).also { openBook = it }
        } catch (t: Throwable) {
            Log.w(TAG, "openbook init failed", t)
            openBookFailed = true
            return null
        }
        return try {
            book.query(Zobrist.getZobristFromBoard(canonical, redGo), redGo, OpenBook.SortRule.BEST_SCORE)
        } catch (t: Throwable) {
            Log.w(TAG, "book query failed", t)
            null
        }
    }

    private val analysisListener = object : AnalysisEngine.Listener {
        override fun onEngineReady() {
            engineReady = true
            engineErrorText = null
            setStatus("识别中")
            scheduleAnalysis()
        }

        override fun onSearchUpdate(result: AnalysisResult) {
            if (result.fen != currentFen) return
            lastAnalysis = result
            // 成熟度跟踪：最佳着法连续未变化超过阈值 => 定着（绿箭头）
            val best = result.lines.firstOrNull()?.pv?.firstOrNull() ?: result.bestUcci
            val prevSettled = searchSettled
            if (best != stableUcci) {
                stableUcci = best
                stableSince = System.currentTimeMillis()
                searchSettled = false
            }
            // 引擎 info 行节流（根因 4）：每条含分数的 info 行不再无条件全量渲染，
            // 只在深度变化 / 最佳着变化 / 成熟度翻转时渲染，另配 120ms 节流兜底。
            // 深度取第一线（与渲染展示的 line.depth 语义一致，多线深度同步爬升）
            val depth = result.lines.firstOrNull()?.depth ?: 0
            if (depth != lastRenderedDepth || best != lastRenderedBest || prevSettled != searchSettled) {
                lastRenderedDepth = depth
                lastRenderedBest = best
                throttledPostRender()
            }
        }

        override fun onSearchDone(result: AnalysisResult) {
            if (result.fen != currentFen) return
            lastAnalysis = result
            searchSettled = true
            // 状态文案回"识别中"并渲染（成熟度翻转暂定→定着由渲染体现）；
            // setStatus 内部统一走 postRender 管线，重复 postRender 已合并，无双渲染
            setStatus("识别中")
        }

        override fun onEngineError(message: String) {
            Log.w(TAG, "engine error: $message")
            if (overlayClosed) return
            // 引擎已不可用：错误显示到状态栏与通知栏，不再静默（超长信息截断，避免占满左列）
            engineReady = false
            val short = if (message.length > 40) message.take(40) + "…" else message
            engineErrorText = short
            setStatus("引擎异常：$short")
        }
    }

    // ==================== 悬浮窗 ====================

    private fun ensureOverlay(): OverlayPanelView? {
        if (overlayPanel != null) return overlayPanel
        if (!android.provider.Settings.canDrawOverlays(this)) return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = OverlayPanelView(this)
        val dm = screenMetrics()
        // ⚠ 坐标系：TYPE_APPLICATION_OVERLAY 的 x/y 原点在"内容区"顶部（不含状态栏），
        // 而 screenMetrics() 返回物理整屏（realMetrics）——用物理高度落位会把面板底部
        // 裁出屏（模拟器实测底部差 66px）。落位/钳制/位置归一化统一用内容区尺寸；
        // dm（物理尺寸）仅用于 config.frameSize 的识别坐标系语义（勿混用）
        val availW = resources.displayMetrics.widthPixels
        val availH = resources.displayMetrics.heightPixels
        // 面板固定尺寸（对齐 Cabinet 392×197dp 三列契约）：总宽 392dp（6+114+4+171+2+90+6），
        // 高约 197dp（棋盘 185 主导）。⚠ 注释算式加和是 393，以代码值 dp(392) 为准
        val panelW = panel.dp(392)
        val panelH = panel.dp(197)
        val (px0, py0) = config.overlayPos
        // 默认位置：屏幕底部中央的空白区（多数象棋 App 棋盘下方有空余区域，
        // 不遮挡棋盘）；持久化位置按旧面板尺寸归一化，面板尺寸变化后可能整体出屏 => 钳回屏内
        val x = (if (px0 < 0) (availW - panelW) / 2 else (px0 * availW).toInt())
            .coerceIn(0, max(0, availW - panelW))
        val y = (if (py0 < 0) availH - panelH else (py0 * availH).toInt())
            .coerceIn(0, max(0, availH - panelH))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE（防闪烁根治，任务书任务三 1）：secure 层从录屏/截屏/MediaProjection
            // 虚拟屏的合成中整体剔除——悬浮窗照常显示给用户眼睛，但永不入镜，抓帧零闪烁、
            // 识别看到完整棋盘。预期副作用：用户手动截屏截不到悬浮窗；模拟器 adb screencap
            // 整体失败（RC=1 零字节）属 secure 语义，不是 bug
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        panel.onAction = { action ->
            // 线程收敛（识别管线互不干扰）：除 CLOSE 外全部动作 post 到 worker 执行。
            // UNDO/FLIP_TURN/MANUAL 会写 tracker/currentFen/history 等识别状态，这些
            // 状态的生产者（handleFrame → tracker.onFrame）都在 worker 线程——UI 线程
            // 直接写与帧循环存在数据竞争；post 后与识别任务同队列串行化。
            // CLOSE 需 removeView（必须 UI 线程），保持主线程执行
            if (action == OverlayAction.CLOSE) onOverlayAction(action)
            else workerHandler.post { onOverlayAction(action) }
        }
        panel.onCellTap = ::onManualCellTap
        panel.onDragStateChange = { dragging ->
            userDragging = dragging
            // 拖动结束即时重算遮挡（黄字沿变化显隐），不等 ≤700ms 的下一帧/tick
            if (!dragging) workerHandler.post { recomputeOcclusion() }
        }
        // attach 前预应用主题（背景/文字色部分路径在 attach 前设置不生效——
        // Cabinet 踩坑教训 ②，addView 后还需 panel.post 二次应用）
        panel.applyTheme(OverlayTheme.fromKey(config.overlayTheme))
        panel.setDragHandle(params, wm) {
            config.overlayPos = params.x.toFloat() / availW to params.y.toFloat() / availH
        }
        wm.addView(panel, params)
        // 入场动效：淡入 + 1.02→1 缩放（150ms；只做出现提示，禁持续动画/循环光效）
        panel.alpha = 0f
        panel.scaleX = 1.02f
        panel.scaleY = 1.02f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        // attach 后再应用一次主题（同 Cabinet：attach 前设置在悬浮窗上部分不生效）
        panel.post { panel.applyTheme(OverlayTheme.fromKey(config.overlayTheme)) }
        overlayPanel = panel
        overlayParams = params
        windowManager = wm
        return panel
    }

    /** 面板是否压住已定位棋盘（FLAG_SECURE 后不影响识别，黄字纯属引导用户移开） */
    @Volatile private var panelOccluded = false

    /**
     * 遮挡几何重算（worker 线程）：沿变化才 postRender（渲染事件驱动，无 TTL 无计时器）。
     * 调用点：onImageAvailable 入口（每真实帧）、拖动结束（松手即刷新）。
     */
    private fun recomputeOcclusion() {
        val occ = !userDragging && overlayIntersectsBoard()
        if (occ != panelOccluded) {
            panelOccluded = occ
            Log.i(TAG, "board occlusion: panel=$occ")
            postRender()
        }
    }

    /** 切换悬浮窗主题（连线页"悬浮窗主题"按钮循环调用）：持久化 + 已建面板即时换肤 */
    fun applyOverlayTheme(theme: OverlayTheme) {
        config.overlayTheme = theme.key
        overlayPanel?.applyTheme(theme)
    }

    /** 悬浮窗面板矩形与已定位棋盘矩形是否重叠（重叠时抓帧前才需短暂隐藏面板） */
    private fun overlayIntersectsBoard(): Boolean {
        val panel = overlayPanel ?: return false
        val grid = sessionGrid ?: return false
        val params = overlayParams ?: return false
        if (panel.width <= 0 || panel.height <= 0) return false
        val (fw, fh) = config.frameSize
        if (fw <= 0 || fh <= 0) return false
        val bx0 = grid.nx0 * fw
        val by0 = grid.ny0 * fh
        val bx1 = grid.nx1 * fw
        val by1 = grid.ny1 * fh
        val px0 = params.x
        val py0 = params.y
        val px1 = px0 + panel.width
        val py1 = py0 + panel.height
        return px0 < bx1 && px1 > bx0 && py0 < by1 && py1 > by0
    }

    private fun onOverlayAction(action: OverlayAction) {
        when (action) {
            OverlayAction.PAUSE -> {
                paused = !paused
                setStatus(if (paused) "已停止指导\n点『继续』恢复" else "继续指导")
            }
            OverlayAction.CLOSE -> {
                // 关闭悬浮窗：停止录屏、移除面板、停止指导（与录制耦合，之后可在连线页重新授权启动）
                overlayClosed = true
                overlayPanel?.let { p -> runCatching { windowManager?.removeView(p) } }
                overlayPanel = null
                paused = true
                releaseCapture()
                setStatus("已关闭（识别与录屏均已停止，可在连线页重新启动）")
                stopSelf()
            }
            OverlayAction.CYCLE_CANDIDATE -> {
                val n = maxOf(bookMoves?.size ?: 0, lastAnalysis?.lines?.size ?: 0)
                if (n > 0) {
                    selectedCandidate = (selectedCandidate + 1) % n
                    postRender()
                }
            }
            OverlayAction.UNDO -> undoOneStep()
            OverlayAction.MANUAL -> toggleManualMode()
            OverlayAction.FLIP_TURN -> {
                // 更新棋局（原"轮次"）：识别差分偶发失败会让 redGo 卡在旧值（显示"轮到对方走"
                // 而实际轮到我方），一键翻转并按新轮次重建 FEN、重新查库/启动引擎
                tracker.redGo = !tracker.redGo
                resetSuggestionState()
                val canonical = currentCanonical
                if (canonical != null) {
                    currentFen = AssistBoard.toFen(canonical, tracker.redGo)
                }
                postRender()
                scheduleAnalysis()
                setStatus("棋局已更新\n现${if (tracker.redGo == mySideIsRed()) "轮到我方走" else "轮到对方走"}")
            }
            OverlayAction.CYCLE_STRENGTH -> cycleStrength()
            OverlayAction.CYCLE_HASH -> cycleHash()
            OverlayAction.CYCLE_ENGINE -> {
                // 识别引擎热切换：经 pendingEngine 在 worker 帧循环应用（不在 UI 线程
                // 释放/加载会话），切换后 tracker 重置、局面重新确认
                val want = if (engineName == ENGINE_ONNX) ENGINE_YOLO else ENGINE_ONNX
                setRecognizerEngine(want)
                setStatus("切换识别引擎：${if (want == ENGINE_ONNX) "ONNX 角点式" else "YOLO 检测式"}\n切换后重新确认棋盘")
            }
            OverlayAction.CYCLE_MULTIPV -> cycleMultiPv()
        }
    }

    /**
     * 悔棋：回退到上一个已确认局面并重新分析（上一手之前的建议）。
     * 纯本地状态回退，不操作游戏 App；回退后暂停实时同步，点『继续』恢复。
     */
    private fun undoOneStep() {
        if (history.size <= 1) {
            setStatus("没有可悔的局面（会随对弈自动积累）")
            return
        }
        history.removeLast()
        val prev = history.last()
        tracker.restore(prev.result, prev.redGo)
        currentCanonical = prev.result.canonical
        currentPieces = flatten(prev.result.canonical)
        currentOrientation = prev.result.orientation
        currentFen = prev.fen
        resetSuggestionState()
        // 暂停实时同步：否则屏幕上的当前局面会在下一帧覆盖悔棋结果
        // （手动模式下帧识别本就被 manualMode 拦截，退出后无需再点"继续"）
        if (!manualMode) paused = true
        postRender()
        scheduleAnalysis()
        setStatus("已悔棋一步\n点『继续』恢复")
    }

    /**
     * 紧急手动模式开关：识别彻底失效（如某些残局界面）或产出幽灵局面时，
     * 暂停帧识别，由用户在小棋盘上手工维护局面让引擎继续指导。
     * 种子局面取最近一次确认局面；识别从未成功时从起始局面开始录入。
     */
    private fun toggleManualMode() {
        manualMode = !manualMode
        manualSelected = null
        if (manualMode) {
            if (currentCanonical == null) {
                val start = AssistBoard.canonicalStart()
                currentCanonical = start
                currentPieces = flatten(start)
                currentOrientation = Orientation.STANDARD
                currentFen = AssistBoard.toFen(start, tracker.redGo)
            }
            // 快照种子局面（悔棋的锚点；与已确认局面相同则不重复入栈）
            val canonical = currentCanonical
            if (canonical != null && history.lastOrNull()?.fen != currentFen) {
                pushManualHistory(canonical, tracker.redGo)
            }
            setStatus("手动模式（再点手动退出）\n点子选中→点目标格=走子\n再点选中子=删除\n『更新棋局』纠正轮次")
        } else {
            setStatus("已退出手动模式\n恢复自动识别")
        }
        postRender()
    }

    /** 手动模式：小棋盘点格（canonical x,y）——选子 / 走子 / 删子 */
    private fun onManualCellTap(x: Int, y: Int) {
        if (!manualMode) return
        val board = currentCanonical ?: return
        val sel = manualSelected
        if (sel == null) {
            // 未选中：点有子的格则选中，空格无操作
            if (board[y][x] != Piece.EMPTY) {
                manualSelected = x to y
                postRender()
            }
            return
        }
        if (sel == x to y) {
            // 再点一次已选中的子：删除（帅/将必须保留，否则局面非法、引擎无法分析）
            val p = board[y][x]
            manualSelected = null
            if (p == Piece.WSHUAI || p == Piece.BJIANG) {
                setStatus("手动模式\n帅/将不可删除")
                postRender()
                return
            }
            board[y][x] = Piece.EMPTY
            applyManualBoard(board, flipTurn = false)
            return
        }
        // 走子：起点必须有子；目标格可为空格或对方子（吃子），按实战着法录入并翻转走子方
        val moving = board[sel.second][sel.first]
        manualSelected = null
        if (moving == Piece.EMPTY) {
            postRender()
            return
        }
        board[sel.second][sel.first] = Piece.EMPTY
        board[y][x] = moving
        applyManualBoard(board, flipTurn = true)
    }

    /** 手动局面快照入历史栈（复用悔棋：可逐步撤销手工编辑） */
    private fun pushManualHistory(board: Array<IntArray>, redGo: Boolean) {
        val result = RecognitionResult(
            AssistBoard.clone(board), AssistBoard.clone(board), currentOrientation,
            emptyList(), 0, countPieces(board), 1.0)
        history.addLast(HistoryEntry(result, redGo, AssistBoard.toFen(board, redGo)))
        if (history.size > 60) history.removeFirst()
    }

    private fun countPieces(board: Array<IntArray>): Int =
        board.sumOf { row -> row.count { it != Piece.EMPTY } }

    /** 手动编辑生效：入栈新局面（history.last 恒等于当前显示局面，悔棋可逐步回退），重建缓存并重新查库/分析 */
    private fun applyManualBoard(board: Array<IntArray>, flipTurn: Boolean) {
        if (flipTurn) tracker.redGo = !tracker.redGo
        currentCanonical = board
        currentPieces = flatten(board)
        currentFen = AssistBoard.toFen(board, tracker.redGo)
        pushManualHistory(board, tracker.redGo)
        resetSuggestionState()
        postRender()
        scheduleAnalysis()
    }

    /** 引擎强度：固定深度三档循环（对弈同款制式），持久化并对下一次搜索生效 */
    private fun cycleStrength() {
        strengthIndex = (strengthIndex + 1) % strengthLevels.size
        val (name, depth) = strengthLevels[strengthIndex]
        config.searchDepth = depth
        analysisEngine?.setSearchDepth(depth)
        postRender()
        setStatus("引擎强度：$name")
    }

    /** Hash 档位循环（256→512→1024→2048），持久化并对下一次搜索生效 */
    private fun cycleHash() {
        hashIndex = (hashIndex + 1) % hashLevels.size
        val mb = hashLevels[hashIndex]
        config.hashMb = mb
        analysisEngine?.setHash(mb)
        postRender()
        setStatus("引擎 Hash：${mb}MB")
    }

    /**
     * 变招路数循环（1→2→3→1），持久化并对下一次搜索生效。
     * 1 路（无变招）算力全部集中于最佳着——最强；2-3 路附带备选候选但摊薄算力。
     * 路数=1 时旧候选消失、"变招"按钮自然无备选可切（符合预期）。
     */
    private fun cycleMultiPv() {
        multiPv = multiPv % 3 + 1
        config.multiPv = multiPv
        analysisEngine?.setMultiPv(multiPv)
        postRender()
        setStatus(
            if (multiPv == 1) "变招：1 路（算力最全）"
            else "变招：$multiPv 路（附${multiPv - 1}个备选，算力摊薄）"
        )
    }

    /** 我方颜色（随动自动判定：屏幕下方阵营=我方，红在下=我方执红） */
    private fun mySideIsRed(): Boolean = currentOrientation == Orientation.STANDARD

    @Volatile private var lastStatusText = "未启动"
    /** setStatus 的瞬时提示文案：只在随后一次渲染中生效（此后回到常规状态文案），由主线程消费后清空 */
    @Volatile private var statusOverrideText: String? = null
    /** 最近一次已推送到通知栏的状态文本（通知去重：内容不变不 notify，根因 10） */
    @Volatile private var lastNotifiedText: String? = null

    /**
     * 状态更新（统一渲染入口，根因 4）：只更新文本 + 走 postRender 管线，不再自己
     * 直接 renderOverlay——与 onSearchDone 等处的 postRender 合并，消灭双渲染。
     */
    private fun setStatus(text: String) {
        lastStatusText = text
        statusOverrideText = text
        notifyStatusChanged(text)
        postRender()
        // 渲染消费后失效：下一次 postRender（引擎 info/棋盘确认等）回到常规状态文案。
        // post 到主线程队尾，保证在本次渲染之后执行（FIFO）
        mainHandler.post { statusOverrideText = null }
    }

    /** 通知栏只在新状态文本变化时 notify（根因 10：原来每次 setStatus 都刷通知栏） */
    private fun notifyStatusChanged(text: String) {
        if (text == lastNotifiedText) return
        lastNotifiedText = text
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    /** 渲染合并去重（根因 4）：主线程消息队列里已有待执行渲染时不再排队 */
    @Volatile private var renderPending = false

    private fun postRender() {
        if (renderPending) return
        renderPending = true
        mainHandler.post {
            renderPending = false
            renderOverlay(statusOverride = statusOverrideText)
        }
    }

    /** 建议渲染节流窗口：info 流高频到达时窗口内只渲染一次，其余安排一次补绘（Cabinet 同款） */
    @Volatile private var lastRenderAt = 0L
    @Volatile private var renderThrottlePending = false

    /** 120ms 渲染节流（引擎 info 行专用）：窗口内只渲染一次 + 补绘排队，防搜索高峰期刷爆主线程 */
    private fun throttledPostRender() {
        val now = System.currentTimeMillis()
        if (now - lastRenderAt >= SUGGEST_RENDER_THROTTLE_MS) {
            lastRenderAt = now
            postRender()
        } else if (!renderThrottlePending) {
            renderThrottlePending = true
            val delay = SUGGEST_RENDER_THROTTLE_MS - (now - lastRenderAt)
            mainHandler.postDelayed({
                renderThrottlePending = false
                lastRenderAt = System.currentTimeMillis()
                postRender()
            }, delay)
        }
    }

    private fun renderOverlay(statusOverride: String? = null) {
        // 用户点“关闭”后不重建（直到重新启动服务）
        if (overlayClosed) return
        val panel = ensureOverlay() ?: return
        val fen = currentFen
        val myRed = mySideIsRed()
        val a = lastAnalysis
        val turnRed = tracker.redGo
        val myTurn = if (fen.isEmpty()) false else (turnRed == myRed)

        // ---- 顶部状态行（语义点 + 次级小字，颜色由主题调色板解析）----
        val (stateText, stateKind) = when {
            manualMode -> "手动模式" to OverlayStateKind.MANUAL
            paused -> "已停止" to OverlayStateKind.IDLE
            engineErrorText != null -> "引擎异常" to OverlayStateKind.ERROR
            engineReady -> "识别中" to OverlayStateKind.RUNNING
            else -> "引擎预热中" to OverlayStateKind.WAITING
        }

        // ---- 主状态大字（一眼原则；setStatus 覆盖文案优先，随后一次渲染后失效）----
        val override = statusOverride ?: statusOverrideText
        val headline = when {
            override != null -> override
            fen.isEmpty() -> "识别中"
            paused -> "已停止指导"
            myTurn -> "轮到我方走"
            else -> "轮到对方走"
        }

        // 选择当前展示的候选
        val lines = a?.lines ?: emptyList()
        val line = if (lines.isNotEmpty()) lines[selectedCandidate.coerceIn(0, lines.size - 1)] else null

        // 识别中断/持续不稳定时，旧建议所依据的局面可能已失效——不再展示，避免误导
        // （手动模式局面由用户维护，不受识别健康度影响）
        val recHealthy = manualMode || (recMissStreak == 0 && tracker.unstableStreak < 5)
        val book = if (myTurn) bookMoves else null

        // 送子防线：着法起点必须是我方棋子——轮次错乱/幽灵 FEN 时宁可不显示
        fun fromIsOurs(ucci: String): Boolean {
            val arr = currentPieces ?: return false
            if (ucci.length < 4) return false
            val fx = ucci[0] - 'a'
            val fy = 9 - (ucci[1] - '0')
            if (fx !in 0..8 || fy !in 0..9) return false
            val p = arr[fy * 9 + fx]
            return p in 1..14 && Piece.isRed(p) == myRed
        }

        // ---- 优势行 / 候选预案行 / 箭头（结构化槽位，"一眼原则"）----
        var advantageText: String? = null
        var advantageTone = QiTone.NONE
        var candidateText: String? = null
        var candidateTone = QiTone.NONE
        var candidateIndex = 0
        var candidateCount = 0
        var arrowFrom: Pair<Int, Int>? = null
        var arrowTo: Pair<Int, Int>? = null
        var suggestMature = false

        when {
            myTurn && recHealthy && book != null && book.isNotEmpty() -> {
                // 开局库命中：直接展示库内着法（定着绿✓，零等待）
                val idx = selectedCandidate.coerceIn(0, book.size - 1)
                val bd = book[idx]
                val ucci = bd.move
                if (fromIsOurs(ucci)) {
                    suggestMature = true
                    val arrow = ucciToCells(ucci)
                    arrowFrom = arrow.first
                    arrowTo = arrow.second
                    val chs = describeSafe(fen, ucci)
                    candidateText = if (idx > 0) "开局库备选${idx + 1} $chs ✓" else "开局库 $chs ✓"
                    candidateTone = QiTone.GOOD
                    // 库内 vwin/vdraw/vlost 为胜/和/负场数，据此算胜率
                    val total = bd.winRate + bd.drawNum + bd.loseNum
                    if (total > 0) {
                        advantageText = "胜率约 ${(bd.winRate * 100 / total).toInt()}%"
                        advantageTone = QiTone.GOOD
                    }
                    candidateIndex = idx + 1
                    candidateCount = book.size
                } else {
                    candidateText = "轮次可能识别有误\n走一步后自动纠正"
                    candidateTone = QiTone.WARN
                }
            }
            myTurn && recHealthy && line != null -> {
                // 我方回合：建议（分析中会随 info 行实时更新）
                val ucci = line.pv.firstOrNull() ?: a?.bestUcci
                if (ucci != null && fromIsOurs(ucci)) {
                    suggestMature = searchSettled ||
                        System.currentTimeMillis() - stableSince >= SUGGEST_STABLE_MS
                    val chs = describeSafe(fen, ucci)
                    val arrow = ucciToCells(ucci)
                    arrowFrom = arrow.first
                    arrowTo = arrow.second
                    candidateText = (if (selectedCandidate > 0) "备选${selectedCandidate + 1} $chs" else "建议 $chs") +
                        if (suggestMature) " ✓" else "（暂定）"
                    advantageText = "${lineScoreText(line)} 深${line.depth}"
                    advantageTone = if (line.redScoreCp >= 0) QiTone.GOOD else QiTone.BAD
                    candidateIndex = selectedCandidate + 1
                    candidateCount = lines.size
                } else if (ucci != null) {
                    candidateText = "轮次可能识别有误\n走一步后自动纠正"
                    candidateTone = QiTone.WARN
                }
            }
            !myTurn && line != null && line.pv.size >= 2 -> {
                // 对方回合：算力产出我方应对，但只给文字预案——对方还没走，把"我方应手"
                // 画在当前盘面上会指向我方棋子，看起来像"我方打我方"
                val oppU = line.pv[0]
                val myU = line.pv[1]
                if (fromIsOurs(myU)) {
                    val oppDesc = describeSafe(fen, oppU)
                    val myDesc = describeSafe(AssistBoard.applyUcci(fen, oppU), myU)
                    candidateText = "若对方走$oppDesc\n我方应$myDesc"
                    advantageText = "${lineScoreText(line)} 深${line.depth}"
                    advantageTone = if (line.redScoreCp >= 0) QiTone.GOOD else QiTone.BAD
                }
            }
            myTurn && engineReady && fen.isNotEmpty() -> {
                candidateText = "引擎思考中…"
                candidateTone = QiTone.WARN
            }
        }

        panel.render(OverlayModel(
            stateText = stateText,
            stateKind = stateKind,
            occludedText = if (panelOccluded) "挡住棋盘，建议移开" else null,
            headlineText = headline,
            advantageText = advantageText,
            advantageTone = advantageTone,
            candidateText = candidateText,
            candidateTone = candidateTone,
            candidateIndex = candidateIndex,
            candidateCount = candidateCount,
            pieces = currentPieces,
            arrowFrom = arrowFrom,
            arrowTo = arrowTo,
            flipped = currentOrientation == Orientation.FLIPPED,
            mySideIsRed = myRed,
            mature = suggestMature,
            paused = paused,
            manualMode = manualMode,
            selectedCell = manualSelected,
            depthText = strengthLevels[strengthIndex].second.toString(),
            hashText = hashLevels[hashIndex].toString(),
            engineText = if (engineName == ENGINE_ONNX) "ONNX" else "YOLO",
            multiPv = multiPv,
        ))
    }

    private fun lineScoreText(line: AnalysisLine): String {
        val adv = line.scoreText()
        return if (line.redScoreCp >= 0) "红方优势 $adv" else "红方劣势 $adv"
    }

    /** ucci(4字符) -> 内部 (x,y) 起止 */
    private fun ucciToCells(ucci: String): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        if (ucci.length < 4) return Pair(0 to 0, 0 to 0)
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        return Pair(fx to fy, tx to ty)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        releaseCapture()
        yoloDetector?.close()
        yoloDetector = null
        onnxEngine?.release()
        onnxEngine = null
        analysisEngine?.shutdown()
        analysisEngine = null
        overlayPanel?.let { p ->
            windowManager?.removeView(p)
        }
        overlayPanel = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun releaseCapture() {
        workerHandler.removeCallbacks(frameTick)
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { }
        imageReader = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun OverlayPanelView.dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
