package com.xqdk.chess.assist

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.FloatBuffer

/**
 * ONNX 两步式棋盘识别（移植自 chinese-chess-helper 的 OnnxRecognizer）：
 *
 * 1. RTMPose 关节点模型检测棋盘 4 角（A0 左上 / A8 右上 / J0 左下 / J8 右下），
 *    全图 bbox + 1.25 padding 仿射到 256×256，SimCC 输出 argmax 解码后逆仿射回原图；
 * 2. 单应透视变换到 450×500 俯视图（角点落在 (50,50)/(400,50)/(50,450)/(400,450)）；
 * 3. Swin 分类模型对 90 个交叉点输出 16 类 logits（空/未知/红七子/黑七子），argmax 成局面。
 *
 * 与 YOLO 检测式相比：角点定位 + 全盘分类对未见过的棋盘皮肤泛化更好，
 * 且 4 角语义天然给出棋盘朝向，不依赖 board 框检测（后者帧间会跳变）。
 * 预处理数值（归一化、warp 尺寸、裁剪、类别顺序）与原上游逐项一致，保证与模型匹配。
 *
 * 仅在服务 worker 线程调用 recognize；load/release 可能来自不同线程，全部加锁串行。
 *
 * @param profiling 分段计时开关（角点/透视/分类各段耗时，timing 日志）。默认关——
 *   恒开会每次识别多拼一条字符串写日志（稳态 ~1s/条）。
 */
class OnnxBoardRecognizer(
    private val assets: AssetManager,
    private val profiling: Boolean = false,
) {

    companion object {
        private const val TAG = "OnnxBoardRecognizer"

        private const val POSE_INPUT = 256
        private const val POSE_PADDING = 1.25f
        private const val POSE_MODEL = "models/4_v6-0301.onnx"
        private const val CLS_MODEL = "models/nano_v3-0319.onnx"

        private const val WARP_W = 450
        private const val WARP_H = 500
        private const val WARP_PADDING = 50
        private const val CLS_CROP_W = 400
        private const val CLS_CROP_H = 450
        private const val CLS_IN_W = 280
        private const val CLS_IN_H = 315

        /** 角点包围盒相对帧面积的合理范围（棋盘至少占 2% 屏幕且不超过 1.2 倍） */
        private const val AREA_MIN_RATIO = 0.02
        private const val AREA_MAX_RATIO = 1.2
        /** 角点包围盒宽高比合理范围（象棋棋盘约 9:10） */
        private const val ASPECT_MIN = 0.5
        private const val ASPECT_MAX = 2.2

        /** 单帧最少棋子数（与 DetectionBoardMapper 的 minPieces 对齐，残局可低至 3 子） */
        private const val MIN_PIECES = 3

        /** 角点复检间隔（毫秒）：复用角点超过该时长后强制全图重定位一次 */
        private const val POSE_RECHECK_MS = 8000L

        /** ONNX 推理 intra-op 线程数（0 = onnxruntime 默认策略：物理核数） */
        private const val INTRA_THREADS = 2
    }

    // 分段耗时累计（仅 worker 线程写，profiling=false 恒为 0）
    private var tPoseWarp = 0L
    private var tPoseRun = 0L
    private var tBoardWarp = 0L
    private var tClsPrep = 0L
    private var tClsRun = 0L

    /**
     * 最近一次 RTMPose 成功定位的四角（帧像素坐标）。棋盘 App 里棋盘位置不会自己动，
     * 复用可跳过 RTMPose（约占单帧 25~30%）；棋盘挪走/被遮挡由调用方在连续识别
     * 失败或连续不稳定时调 [invalidateCorners] 强制重新定位。
     */
    @Volatile private var cachedCorners: FloatArray? = null

    /**
     * 角点复检间隔：复用角点超时后强制全图重跑一次 RTMPose。
     * 复用失效不只发生在"识别失败"（那有 invalidateCorners 自愈）——切换对局 App/
     * 界面后棋盘几何剧变，旧角点采出新画面会产出"结构合法但棋子错位"的局面：
     * 棋子数、合法性全部正常，分类层无从察觉；且同一错位 pose 输出确定，BoardTracker
     * 判 SAME_BOARD，识别就此冻结在旧局面（实测：悬浮窗停留 cnvcs 残局，屏幕已是
     * 象棋迪克开局）。定期对焦是唯一不依赖失败信号的自愈：pose 检测 ~26ms，每 8s
     * 一次代价可忽略；棋盘没动则复检结果与缓存一致（零行为变化），挪了则直接采准。
     */
    private var lastFullPoseAt = 0L

    /** 强制下一帧重新跑 RTMPose 全图定位（棋盘位置变化后的自愈入口） */
    @Synchronized
    fun invalidateCorners() {
        cachedCorners = null
        lastFullPoseAt = 0L
    }

    /**
     * 单帧识别结果（screenRaw 约定与 DetectionBoardMapper 一致：[gy][gx]，gy=0 屏幕顶部）。
     * orientation 恒为真实屏幕朝向（几何+内容联合判定，见 screenOrientation）；
     * canonical 恒为规范布局（红在 y=9），二者即使 warp 被旋转也各自正确。
     */
    class OnnxResult(
        val screenRaw: Array<IntArray>,
        val canonical: Array<IntArray>,
        val orientation: Orientation,
        /** 4 角包围盒（像素，帧坐标）：x0,y0,x1,y1 */
        val boxPx: DoubleArray,
        val avgLogit: Double,
        val pieceCount: Int,
        val elapsedMs: Long,
    )

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var poseSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var poseInputName = "input"
    private var poseOutputNames: List<String> = emptyList()
    private var clsInputName = "input"
    private var clsOutputName = "output"

    // ==================== 识别热路径复用缓冲（雷区见各注释）====================
    // 【雷区】 recognize 为 @Synchronized 单线程串行，下列缓冲才敢提为实例字段；
    // 若未来改成多线程并发识别，这里全部会串帧——先加锁再动。
    // 【雷区】尺寸全部由常量推导、恒定不变；若改 POSE_INPUT/WARP_*/CLS_* 任一
    // 常量，缓冲尺寸自动跟随（按常量表达式初始化），但 direct buffer 的
    // rewind/put 配套逻辑依赖"长度恰好填满"，改形状时同步核对。
    // 每次识别新建 3~4MB 临时数组，以稳态 ~1s/帧、burst ~300ms/帧的节奏喂 GC
    //（中端机可感知卡顿），全部提为实例字段后单次识别 Java 堆近零分配。
    private val poseWarpOut = IntArray(POSE_INPUT * POSE_INPUT)
    private val poseInputArr = FloatArray(3 * POSE_INPUT * POSE_INPUT)
    private val boardWarpOut = IntArray(WARP_W * WARP_H)
    private val clsCropOut = IntArray(CLS_CROP_W * CLS_CROP_H)
    private val clsResizeOut = IntArray(CLS_IN_W * CLS_IN_H)
    private val clsInputArr = FloatArray(3 * CLS_IN_W * CLS_IN_H)
    /** SimCC 输出与分类 logits 的长度取决于模型输出形状，惰性对齐（形状变了自动重分配） */
    private var simccX: FloatArray? = null
    private var simccY: FloatArray? = null
    private var logits: FloatArray? = null

    /**
     * 直接内存 FloatBuffer。【雷区】onnxruntime 的 createTensor 对 direct 缓冲
     * 是"引用内存"（推理期间零拷贝），对 heap 缓冲（FloatBuffer.wrap 出来的）
     * 每次都要整体拷入新分配的 native 内存——所以必须 direct。引用内存意味着
     * session.run 执行期间不允许别的线程改 buffer 内容，同样由 recognize 的
     * @Synchronized 保证。字节序必须 nativeOrder，否则数据全错且不报错。
     */
    private val poseInputBuf = directFloatBuffer(poseInputArr.size)
    private val clsInputBuf = directFloatBuffer(clsInputArr.size)

    private fun directFloatBuffer(floatSize: Int): FloatBuffer =
        java.nio.ByteBuffer.allocateDirect(floatSize * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()

    /**
     * tensor 的 floatBuffer 内容拷入复用数组（长度不匹配时重分配）。
     * 用于模型输出长度本应恒定但防御形状变化的场景。
     */
    private fun drain(fb: FloatBuffer, cur: FloatArray?): FloatArray {
        var a = cur
        if (a == null || a.size != fb.remaining()) a = FloatArray(fb.remaining())
        fb.get(a)
        return a
    }

    @Volatile var loaded = false
        private set

    /** 加载两个模型（失败返回 false 并吞异常，由调用方决定回退策略） */
    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            val start = System.currentTimeMillis()
            run {
                val bytes = assets.open(POSE_MODEL).use { it.readBytes() }
                poseSession = createSession(bytes, INTRA_THREADS)
                val s = poseSession!!
                poseInputName = s.inputNames.iterator().next()
                poseOutputNames = s.outputNames.toList()
            }
            run {
                val bytes = assets.open(CLS_MODEL).use { it.readBytes() }
                clsSession = createSession(bytes, INTRA_THREADS)
                val s = clsSession!!
                clsInputName = s.inputNames.iterator().next()
                clsOutputName = s.outputNames.first()
            }
            loaded = true
            Log.i(TAG, "onnx models loaded in ${System.currentTimeMillis() - start}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "onnx 模型加载失败（检查 assets/models 下是否已放置 ONNX 文件）", t)
            release()
            false
        }
    }

    /**
     * 创建推理会话。注意不要启用 XNNPACK：x86_64 模拟器上 native abort（SIGABRT），
     * Java 层 try/catch 拦不住，进程直接死——已实测。收益调优走线程数。
     */
    private fun createSession(modelBytes: ByteArray, intraThreads: Int): OrtSession {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(intraThreads)
        return ortEnv.createSession(modelBytes, opts)
    }

    @Synchronized
    fun release() {
        loaded = false
        cachedCorners = null
        try { poseSession?.close() } catch (_: Throwable) {}
        try { clsSession?.close() } catch (_: Throwable) {}
        poseSession = null; clsSession = null
    }

    /** 识别一帧，返回 null 表示失败（角点不可信/推理异常），调用方按"未识别"处理 */
    @Synchronized
    fun recognize(frame: Frame): OnnxResult? {
        if (!loaded) return null
        val start = System.currentTimeMillis()
        if (profiling) { tPoseWarp = 0; tPoseRun = 0; tBoardWarp = 0; tClsPrep = 0; tClsRun = 0 }
        val reuse = cachedCorners
        // 到期复检：复用超时后本帧强制全图重定位。检出即换新（新角点更准），
        // 棋盘没动结果不变、挪了直接采准；检出失败走 return null，由调用方的
        // 连续失败计数决定是否清缓存自愈。lastFullPoseAt 只在真正跑过全图定位
        // 后推进，保证失败后的下一帧继续尝试而不是再等一个周期。
        val recheckDue = reuse != null && start - lastFullPoseAt > POSE_RECHECK_MS
        return try {
            val corners = if (reuse == null || recheckDue) {
                detectCorners(frame) ?: return null
            } else reuse
            val box = boxOfCorners(corners)
            if (!boxPlausible(box, frame.width, frame.height)) {
                Log.d(TAG, "corner box implausible: ${box.joinToString()}")
                return null
            }
            if (reuse == null || recheckDue) {
                if (recheckDue) {
                    // 复检诊断日志：角点漂移说明棋盘真的挪了/切了画面（此时错位识别
                    // 可能已持续数秒），常量级漂移属定位抖动，无行为差异
                    val drift = maxCornerDrift(reuse!!, corners)
                    Log.d(TAG, "pose recheck: drift=%.0fpx %s"
                        .format(drift, if (drift > frame.width * 0.03) "→ recaptured" else "→ steady"))
                }
                cachedCorners = corners // 全图定位成功且校验通过才缓存
                lastFullPoseAt = start
            }
            val warped = warpBoard(frame, corners)
            val (raw, avg, pieces) = classifyCells(warped)
            if (pieces < MIN_PIECES) {
                Log.d(TAG, "onnx pieces too few: $pieces")
                return null
            }
            val contentOrient = DetectionBoardMapper.detectOrientation(raw) ?: return null
            val canonical = DetectionBoardMapper.toCanonical(raw, contentOrient)
            val orientation = DetectionBoardMapper.screenOrientation(corners, contentOrient)
            if (contentOrient != orientation) {
                // warp 内部朝向与屏幕朝向不一致 = warp 被旋转了（pose 角点身份漂移）；
                // canonical 已由 contentOrient 自愈、orientation 已由几何判定纠回屏幕
                // 真实朝向，此日志仅用于观测漂移频率（2026-10-01 模拟器事故同款）
                Log.d(TAG, "warp rotated: content=$contentOrient screen=$orientation")
            }
            if (profiling) {
                // 空串=全图定位；复检帧同样跑了 RTMPose，标 (recheck) 与纯复用区分
                val poseTag = when {
                    reuse == null || recheckDue -> if (reuse == null) "" else "(recheck)"
                    else -> "(reuse)"
                }
                Log.i(TAG, "timing%s: poseWarp=%dms poseRun=%dms boardWarp=%dms clsPrep=%dms clsRun=%dms total=%dms"
                    .format(poseTag, tPoseWarp / 1_000_000,
                        tPoseRun / 1_000_000, tBoardWarp / 1_000_000,
                        tClsPrep / 1_000_000, tClsRun / 1_000_000, System.currentTimeMillis() - start))
            }
            OnnxResult(raw, canonical, orientation, box, avg, pieces,
                System.currentTimeMillis() - start)
        } catch (t: Throwable) {
            Log.w(TAG, "onnx recognize failed", t)
            null
        }
    }

    // ==================== 第一步：RTMPose 角点检测 ====================

    /**
     * 构造与参考实现（mmpose get_warp_matrix, rot=0）逐项一致的仿射矩阵：
     * 全图 bbox + 1.25 padding，三分点法。返回 forward 矩阵（原图 → 256×256）。
     */
    private fun poseAffine(w: Float, h: Float): FloatArray {
        val cx = w / 2f; val cy = h / 2f
        val srcW = w * POSE_PADDING
        // rot=0：src_dir = (-0.5·srcW, 0)，dst_dir = (-0.5·dstW, 0)
        val srcDirX = -0.5f * srcW
        val dstW = POSE_INPUT.toFloat(); val dstH = POSE_INPUT.toFloat()
        val dstDirX = -0.5f * dstW
        // 三组源点：center / center+dir / 第三点 = p1 + (dir.y, -dir.x)
        val sx0 = cx; val sy0 = cy
        val sx1 = cx + srcDirX; val sy1 = cy
        val sx2 = sx1; val sy2 = sy1 - srcDirX
        val dx0 = dstW / 2f; val dy0 = dstH / 2f
        val dx1 = dx0 + dstDirX; val dy1 = dy0
        val dx2 = dx1; val dy2 = dy1 - dstDirX
        return OnnxGeom.solveAffine(
            floatArrayOf(sx0, sy0, sx1, sy1, sx2, sy2),
            floatArrayOf(dx0, dy0, dx1, dy1, dx2, dy2))
    }

    private fun detectCorners(frame: Frame): FloatArray? {
        val m = poseAffine(frame.width.toFloat(), frame.height.toFloat())
        var t = if (profiling) System.nanoTime() else 0L
        OnnxGeom.warpAffineInto(frame.argb, frame.width, frame.height, m, POSE_INPUT, POSE_INPUT, poseWarpOut)
        OnnxGeom.normalizeChwInto(poseWarpOut, poseInputArr)
        if (profiling) tPoseWarp += System.nanoTime() - t
        t = if (profiling) System.nanoTime() else 0L
        val session = poseSession ?: return null
        // direct buffer 三步：rewind 清位 → 整段写入 → rewind 回起点
        //（createTensor 从 position 读到 limit，put 后 position 在末尾必须归零）
        poseInputBuf.rewind()
        poseInputBuf.put(poseInputArr)
        poseInputBuf.rewind()
        OnnxTensor.createTensor(ortEnv, poseInputBuf,
            longArrayOf(1, 3, POSE_INPUT.toLong(), POSE_INPUT.toLong())).use { tensor ->
            session.run(mapOf(poseInputName to tensor)).use { results ->
                // 输出两个张量 simcc_x / simcc_y，形状 [1,4,512]（Result.get 返回 Optional，需再解包）
                val outNames = poseOutputNames.ifEmpty { session.outputNames.toList() }
                val tx = results.get(outNames[0]).get() as OnnxTensor
                val ty = results.get(outNames[1]).get() as OnnxTensor
                val arrX = drain(tx.floatBuffer, simccX); simccX = arrX
                val arrY = drain(ty.floatBuffer, simccY); simccY = arrY
                val kps = OnnxGeom.simccKeypoints(arrX, arrY, 4, POSE_INPUT)
                // warp 空间坐标逐点逆仿射回原图
                val inv = OnnxGeom.invertAffine(m)
                val out = FloatArray(8)
                for (i in 0 until 4) {
                    val p = OnnxGeom.applyAffine(inv, kps[2 * i], kps[2 * i + 1])
                    out[2 * i] = p[0]; out[2 * i + 1] = p[1]
                }
                if (profiling) tPoseRun += System.nanoTime() - t
                return out
            }
        }
    }

    // ==================== 第二步：透视变换 + 第三步：分类 ====================

    /** 4 角 → 450×500 俯视图（角点映射到 padding 内侧四角，与参考实现一致） */
    private fun warpBoard(frame: Frame, corners: FloatArray): IntArray {
        val t = if (profiling) System.nanoTime() else 0L
        val p = WARP_PADDING.toFloat()
        val h = OnnxGeom.solveHomography(corners, floatArrayOf(
            p, p, (WARP_W - p), p, p, (WARP_H - p), (WARP_W - p), (WARP_H - p)))
        OnnxGeom.warpPerspectiveInto(frame.argb, frame.width, frame.height, h, WARP_W, WARP_H, boardWarpOut)
        if (profiling) tBoardWarp += System.nanoTime() - t
        return boardWarpOut
    }

    /** 俯视图中心裁 400×450 → 缩放 280×315 → Swin 分类 → 90 格解码 */
    private fun classifyCells(warped: IntArray): Triple<Array<IntArray>, Double, Int> {
        // 中心裁剪（WARP_W/H 恒大于裁剪尺寸，无需边界保护）
        val cropX = (WARP_W - CLS_CROP_W) / 2
        val cropY = (WARP_H - CLS_CROP_H) / 2
        for (r in 0 until CLS_CROP_H) {
            System.arraycopy(warped, (cropY + r) * WARP_W + cropX, clsCropOut, r * CLS_CROP_W, CLS_CROP_W)
        }
        var t = if (profiling) System.nanoTime() else 0L
        OnnxGeom.resizeBilinearInto(clsCropOut, CLS_CROP_W, CLS_CROP_H, CLS_IN_W, CLS_IN_H, clsResizeOut)
        OnnxGeom.normalizeChwInto(clsResizeOut, clsInputArr)
        if (profiling) tClsPrep += System.nanoTime() - t
        t = if (profiling) System.nanoTime() else 0L

        val session = clsSession ?: throw IllegalStateException("classifier session 未加载")
        clsInputBuf.rewind()
        clsInputBuf.put(clsInputArr)
        clsInputBuf.rewind()
        OnnxTensor.createTensor(ortEnv, clsInputBuf,
            longArrayOf(1, 3, CLS_IN_H.toLong(), CLS_IN_W.toLong())).use { tensor ->
            session.run(mapOf(clsInputName to tensor)).use { results ->
                val t2 = results.get(clsOutputName).get() as OnnxTensor // Optional 需再解包
                val arr = drain(t2.floatBuffer, logits); logits = arr
                val decoded = OnnxGeom.decodeCells(arr)
                if (profiling) tClsRun += System.nanoTime() - t
                return decoded
            }
        }
    }

    // ==================== 合理性校验 ====================

    private fun boxOfCorners(corners: FloatArray): DoubleArray = doubleArrayOf(
        minOf(corners[0], corners[2], corners[4], corners[6]).toDouble(),
        minOf(corners[1], corners[3], corners[5], corners[7]).toDouble(),
        maxOf(corners[0], corners[2], corners[4], corners[6]).toDouble(),
        maxOf(corners[1], corners[3], corners[5], corners[7]).toDouble())

    /** 两次角点定位的最大单点偏移（像素）：复检诊断用，衡量棋盘是否真的挪了 */
    private fun maxCornerDrift(old: FloatArray, new: FloatArray): Double {
        var max = 0.0
        for (i in 0 until 4) {
            val dx = (new[i * 2] - old[i * 2]).toDouble()
            val dy = (new[i * 2 + 1] - old[i * 2 + 1]).toDouble()
            max = maxOf(max, kotlin.math.hypot(dx, dy))
        }
        return max
    }

    /** 角点包围盒面积占比与宽高比校验：防角点漂移到图外/退化为一条线 */
    private fun boxPlausible(box: DoubleArray, frameW: Int, frameH: Int): Boolean {
        val bw = box[2] - box[0]; val bh = box[3] - box[1]
        if (bw <= 0 || bh <= 0) return false
        if (bw / bh !in ASPECT_MIN..ASPECT_MAX) return false
        val area = bw * bh / (frameW.toDouble() * frameH)
        return area in AREA_MIN_RATIO..AREA_MAX_RATIO
    }
}
