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
        /**
         * 单帧最少棋子数：低于该值视为"未见棋盘"（回全屏重定位）。
         * 残局可低至 3 子（帅仕 vs 将），不能沿用固定 10 子门槛——那会把合法残局
         * 永远拒之门外，反而让幻觉出 ≥10 检测的坏帧乘虚而入；合法性由 validate() 把关。
         */
        const val MIN_RECOGNIZED_PIECES = 3
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
    @Volatile private var yoloMissStreak = 0
    /** 裁剪推理下连续不稳定的帧数（棋盘挪动后残框自锁时回退全屏重定位） */
    @Volatile private var cropUnstableStreak = 0
    /** 本次会话 YOLO 检出的棋盘网格（用于悬浮窗遮挡判断与裁剪推理），识别失败时清空自愈 */
    @Volatile private var sessionGrid: BoardGrid? = null

    private var analysisEngine: AnalysisEngine? = null
    @Volatile private var engineReady = false
    /** 最近一次引擎错误（显示于状态栏，onEngineReady 时清除） */
    @Volatile private var engineErrorText: String? = null

    /** 建议成熟度跟踪：最佳着法连续 SUGGEST_STABLE_MS 未变（或本次搜索已结束）=> 定着（绿箭头） */
    @Volatile private var stableUcci: String? = null
    @Volatile private var stableSince = 0L
    @Volatile private var searchSettled = false

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
        // YOLO 棋子检测：直接检测 14 类棋子 + 棋盘框，免校准、跨 App 泛化。
        // 初始化失败（含 JVM 测试环境无 TFLite 原生库的 UnsatisfiedLinkError）只提示，不阻塞服务
        yoloDetector = try {
            YoloBoardDetector(this)
        } catch (t: Throwable) {
            Log.w(TAG, "yolo detector init failed", t)
            null
        }
        Log.i(TAG, "yolo detector: ${if (yoloDetector != null) "ready" else "unavailable"}")
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

        // 提前启动分析引擎（后台准备，不阻塞识别）
        analysisEngine = AnalysisEngine(this, analysisListener, config.searchDepth)
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
        // 仅当面板确实遮挡棋盘（抓帧前需短暂隐藏面板约60ms）时才放慢抓帧节奏避免闪烁；
        // 平时面板停在屏幕下方空白区，用快速节奏保证识别延迟
        val slowPath = !userDragging && overlayIntersectsBoard()
        val interval = if (slowPath) OVERLAY_FRAME_INTERVAL_MS else MIN_FRAME_INTERVAL_MS
        if (now - lastFrameTs < interval) {
            // 必须取走帧并关闭，否则 ImageReader 缓冲池占满后不再派发回调
            reader.acquireLatestImage()?.close()
            return
        }
        lastFrameTs = now
        var needRestoreHide = false
        try {
            // 悬浮窗会被录屏镜像进画面（面板区域遮挡棋盘）：分析帧前把面板短暂隐藏
            // （约60ms）。拖动期间抑制隐藏（拖动结束恢复正常）。
            val needHide = slowPath && (overlayPanel?.alpha ?: 0f) > 0f
            if (needHide) {
                mainHandler.post { overlayPanel?.alpha = 0f }
                needRestoreHide = true
                Thread.sleep(60)
            }
            val image = reader.acquireLatestImage() ?: return
            try {
                val frame = imageToFrame(image)
                if (!pendingFrame) {
                    pendingFrame = true
                    workerHandler.post { pendingFrame = false; handleFrame(frame) }
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "onImageAvailable failed", e)
        } finally {
            // 无论取帧成功与否都恢复面板（否则面板会一直消失）
            if (needRestoreHide) {
                mainHandler.post { overlayPanel?.alpha = 1f }
            }
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
        val px = IntArray(w * h)
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
     * 裁剪推理提示：已知棋盘位置时只把棋盘外扩约 1.2 格的区域送入模型，
     * 减少整屏缩放开销并提高棋子有效分辨率；尺寸向下取 16 的倍数以稳定裁剪位图复用。
     * 返回 null 表示整帧。
     */
    private fun cropHint(frame: Frame): DoubleArray? {
        val g = sessionGrid ?: return null
        val x0 = g.nx0 * frame.width
        val y0 = g.ny0 * frame.height
        val x1 = g.nx1 * frame.width
        val y1 = g.ny1 * frame.height
        val mx = (x1 - x0) / 8.0 * 1.2
        val my = (y1 - y0) / 9.0 * 1.2
        val cx0 = max(0.0, x0 - mx)
        val cy0 = max(0.0, y0 - my)
        val cx1 = min(frame.width.toDouble(), x1 + mx)
        val cy1 = min(frame.height.toDouble(), y1 + my)
        val w = ((cx1 - cx0).toInt() / 16) * 16
        val h = ((cy1 - cy0).toInt() / 16) * 16
        if (w < 64 || h < 64) return null
        return doubleArrayOf(cx0, cy0, cx0 + w, cy0 + h)
    }

    private fun handleFrame(frame: Frame) {
        if (paused || manualMode) return
        debugSaveFrameIfRequested(frame)
        val yolo = yoloDetector ?: run {
            yoloMissStreak++
            if (yoloMissStreak == 1 || yoloMissStreak % 20 == 0) {
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
            yoloMissStreak++
            // 裁剪区识别不到（棋盘挪走/被遮挡）：清空会话网格，下一帧回全屏重新定位（自愈）
            sessionGrid = null
            if (yoloMissStreak == 1 || yoloMissStreak % 10 == 0) {
                setStatus("正在识别棋盘…\n请让棋盘完整无遮挡")
            }
            return
        }
        yoloMissStreak = 0
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
            BoardTracker.Event.SAME_BOARD -> { /* 未变化不处理 */ }
        }
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
            if (best != stableUcci) {
                stableUcci = best
                stableSince = System.currentTimeMillis()
                searchSettled = false
            }
            postRender()
        }

        override fun onSearchDone(result: AnalysisResult) {
            if (result.fen != currentFen) return
            lastAnalysis = result
            searchSettled = true
            // 我方回合无建议时保持现状；引擎空闲后重新渲染
            mainHandler.post {
                setStatus("识别中")
                postRender()
            }
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
        val (px0, py0) = config.overlayPos
        // 默认位置：屏幕底部中央的空白区（多数象棋 App 棋盘下方有空余区域；
        // 不遮挡棋盘 => 平时无需为抓帧隐藏面板，完全无闪烁）
        val panelW = panel.dp(396)
        val panelH = panel.dp(192)
        // 持久化位置按旧面板宽度归一化，面板加宽/加高后可能整体出屏 => 钳回屏内
        val x = (if (px0 < 0) (dm.widthPixels - panelW) / 2 else (px0 * dm.widthPixels).toInt())
            .coerceIn(0, max(0, dm.widthPixels - panelW))
        val y = (if (py0 < 0) dm.heightPixels - panelH else (py0 * dm.heightPixels).toInt())
            .coerceIn(0, max(0, dm.heightPixels - panelH))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        panel.onAction = ::onOverlayAction
        panel.onCellTap = ::onManualCellTap
        panel.onDragStateChange = { dragging -> userDragging = dragging }
        panel.setDragHandle(params, wm) {
            config.overlayPos = params.x.toFloat() / dm.widthPixels to params.y.toFloat() / dm.heightPixels
        }
        wm.addView(panel, params)
        overlayPanel = panel
        overlayParams = params
        windowManager = wm
        return panel
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

    private fun strengthButtonText(): String =
        "深度" + strengthLevels[strengthIndex].second

    /** Hash 档位循环（256→512→1024→2048），持久化并对下一次搜索生效 */
    private fun cycleHash() {
        hashIndex = (hashIndex + 1) % hashLevels.size
        val mb = hashLevels[hashIndex]
        config.hashMb = mb
        analysisEngine?.setHash(mb)
        postRender()
        setStatus("引擎 Hash：${mb}MB")
    }

    private fun hashButtonText(): String =
        "Hash" + hashLevels[hashIndex]

    /** 我方颜色（随动自动判定：屏幕下方阵营=我方，红在下=我方执红） */
    private fun mySideIsRed(): Boolean = currentOrientation == Orientation.STANDARD

    private fun setStatus(text: String) {
        lastStatusText = text
        mainHandler.post {
            renderOverlay(statusOverride = text)
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    @Volatile private var lastStatusText = "未启动"

    private fun postRender() {
        mainHandler.post { renderOverlay() }
    }

    private fun renderOverlay(statusOverride: String? = null) {
        // 用户点“关闭”后不重建（直到重新启动服务）
        if (overlayClosed) return
        val panel = ensureOverlay() ?: return
        val fen = currentFen
        val myRed = mySideIsRed()
        val a = lastAnalysis

        val status = statusOverride ?: when {
            manualMode -> "手动模式"
            paused -> "已停止指导\n仍观察局面"
            engineErrorText != null -> "引擎异常：$engineErrorText"
            engineReady -> "识别中"
            else -> "引擎预热中…"
        }
        val turnRed = tracker.redGo
        val myTurn = if (fen.isEmpty()) false else (turnRed == myRed)

        // 选择当前展示的候选
        val lines = a?.lines ?: emptyList()
        val line = if (lines.isNotEmpty()) lines[selectedCandidate.coerceIn(0, lines.size - 1)] else null

        // 左列多行小字（\n 分行）；尚未识别到局面时留空，避免与状态行重复
        var headText = when {
            fen.isEmpty() -> ""
            else -> "我方${if (myRed) "红方" else "黑方"}\n${if (myTurn) "轮到我方走" else "轮到对方走"}"
        }
        var arrowFrom: Pair<Int, Int>? = null
        var arrowTo: Pair<Int, Int>? = null
        var suggestMature = false
        // 识别中断/持续不稳定时，旧建议所依据的局面可能已失效——不再展示，避免误导
        // （手动模式局面由用户维护，不受识别健康度影响）
        val recHealthy = manualMode || (yoloMissStreak == 0 && tracker.unstableStreak < 5)
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
                    val tag = if (idx > 0) "开局库备选${idx + 1} $chs ✓" else "开局库 $chs ✓"
                    // 库内 vwin/vdraw/vlost 为胜/和/负场数，据此算胜率
                    val total = bd.winRate + bd.drawNum + bd.loseNum
                    val wrTxt = if (total > 0) "胜率约 ${(bd.winRate * 100 / total).toInt()}%" else "库内推荐"
                    headText = "$tag\n$wrTxt\n$headText"
                } else {
                    headText = "轮次可能识别有误\n走一步后自动纠正\n$headText"
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
                    val tag = if (selectedCandidate > 0) "备选${selectedCandidate + 1} $chs" else "建议 $chs"
                    val suffix = if (suggestMature) " ✓" else "（暂定）"
                    headText = "$tag$suffix\n${lineScoreText(line)} 深度${line.depth}\n$headText"
                } else if (ucci != null) {
                    headText = "轮次可能识别有误\n走一步后自动纠正\n$headText"
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
                    headText = "若对方走$oppDesc\n我方应$myDesc\n$headText"
                }
            }
            myTurn && engineReady && fen.isNotEmpty() -> {
                headText = "引擎思考中…\n$headText"
            }
        }

        panel.render(OverlayModel(
            statusText = if (headText.isEmpty()) status else "$status\n$headText",
            pieces = currentPieces,
            arrowFrom = arrowFrom,
            arrowTo = arrowTo,
            flipped = currentOrientation == Orientation.FLIPPED,
            mySideIsRed = myRed,
            mature = suggestMature,
            paused = paused,
            strengthText = strengthButtonText(),
            hashText = hashButtonText(),
            manualMode = manualMode,
            selectedCell = manualSelected,
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
