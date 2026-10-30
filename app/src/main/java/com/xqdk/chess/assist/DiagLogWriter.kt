package com.xqdk.chess.assist

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 性能诊断日志（Markdown，性能诊断开关的落盘出口）——**综合单文件**，四节结构：
 *
 * 1. **会话**：设备/版本/开始时间 + 参数快照（识别引擎、变招路数、深度/时间制、
 *    Hash、确认帧数）——[appendConfig]；
 * 2. **事件**：服务/引擎生命周期与档位变化（启动/停止/引擎切换/退避清除/自愈/
 *    棋盘确认…），低频，写后即 flush——[appendEvent]；
 * 3. **识别帧**：每次识别一行（成功与失败帧都写）：间隔/定位/五段耗时/节拍/
 *    结果（失败带原因）——[appendRecognize]；
 * 4. **引擎**：皮卡鱼每次深度推进与定着一行：局面/深度/耗时/首变——[appendEngine]。
 *
 * 内置统计：每 [SUMMARY_EVERY] 个识别行自动追加一条心跳汇总（成功/失败计数、
 * 平均/P50/P95 耗时、平均间隔、失败原因分布）；[close] 写最终汇总封口。
 * P50/P95 用 100ms 桶直方图累计（内存 O(1)），桶→百分位换算是 [percentileFromHist]
 * 纯函数，JVM 单测直测。
 *
 * "开关打开则持续出 log"：三路并写——识别帧（每次识别必有行，失败帧同样写且带
 * 原因）、事件（生命周期/档位/自愈）、引擎（搜索推进）；识别空闲期由心跳汇总保证
 * 文件持续有产出，不再出现旧版"只有一列五段耗时、间隙全空白"的瘸腿日志。
 *
 * 写入位置：API 29+ 走 MediaStore.Downloads（免存储权限写入公共 Download）；
 * API 26-28（minSdk）无 WRITE_EXTERNAL_STORAGE 权限，回退应用专属外部目录并在
 * [displayPath] 如实反映——应用内 Toast 标明的就是 [displayPath]。
 *
 * 【线程】所有 append 可来自主线程（事件/引擎回调/按钮）或 worker 线程（识别行），
 * close 可来自主线程——内部 synchronized 保护。写行失败静默（诊断通道绝不能
 * 反过来拖垮识别热路径）。
 */
class DiagLogWriter(context: Context) {

    companion object {
        private const val TAG = "DiagLog"
        private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)

        /** 每 N 个识别行写一条心跳汇总（持续有产出，空闲期也能看到节拍状态） */
        private const val SUMMARY_EVERY = 20

        /** 直方图桶宽 ms（P50/P95 近似精度） */
        private const val HIST_BUCKET_MS = 100

        /** 直方图桶数：0..29 各覆盖 100ms，第 30 桶为 ≥3000ms 溢出桶 */
        private const val HIST_BUCKETS = 31

        /**
         * 直方图 → 百分位近似值 ms（桶中值；溢出桶中值 3050 为下界近似）。
         * 纯函数，JVM 单测直测。
         */
        fun percentileFromHist(hist: IntArray, pct: Double): Int {
            val total = hist.sum()
            if (total == 0) return 0
            // 目标序数向上取整：P95 of 20 = 第 19 个样本（0.95*20=19），不丢头部。
            // -1e-9 修正：total*pct 有浮点上偏（20*0.95 = 19.000…004，ceil 会跳到 20）
            val target = kotlin.math.ceil(total * pct - 1e-9).toInt().coerceIn(1, total)
            var acc = 0
            for (i in hist.indices) {
                acc += hist[i]
                if (acc >= target) return i * HIST_BUCKET_MS + HIST_BUCKET_MS / 2
            }
            return (hist.size - 1) * HIST_BUCKET_MS + HIST_BUCKET_MS / 2
        }
    }

    /** 用户可读的文件绝对路径（创建失败为 null；应用内标明位置用） */
    val displayPath: String?

    private val appContext: Context = context.applicationContext
    private val out: BufferedWriter?
    private val lock = Any()
    private var closed = false

    // ---- 识别统计累计器（synchronized(lock) 内访问）----
    private var lastRowTs = 0L          // 上一识别行时间戳（"间隔"列数据源）
    private var recRows = 0             // 识别行总数（含失败）
    private var okRows = 0              // 成功行数
    private var intervalSum = 0L        // 间隔总和（intervalN 个样本）
    private var intervalN = 0
    private val costHist = IntArray(HIST_BUCKETS)   // 识别总耗时直方图
    private val failCounts = LinkedHashMap<String, Int>()  // 失败原因 → 次数

    init {
        val name = "XQDK_诊断_${TS.format(Date())}.md"
        var writer: BufferedWriter? = null
        var path: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                }
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    writer = resolver.openOutputStream(uri)?.let {
                        BufferedWriter(OutputStreamWriter(it))
                    }
                    // DATA 列在 API 29+ 仍可读：换回真实文件路径用于展示
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) path = c.getString(0)
                    }
                }
            } else {
                // API 26-28：无存储权限写不了公共 Download，回退应用专属目录
                val dir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
                val f = File(dir, name)
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f)))
                path = f.absolutePath
            }
        } catch (t: Throwable) {
            Log.w(TAG, "diag file create failed", t)
            try { writer?.close() } catch (_: Throwable) {}
            writer = null
            path = null
        }
        out = writer
        displayPath = path
        writeHeader()
    }

    /** 文件头：设备/版本/列含义说明，采集数据回来不用猜列名 */
    private fun writeHeader() {
        synchronized(lock) {
            val w = out ?: return
            runCatching {
                val ver = try {
                    com.xqdk.chess.BuildConfig.VERSION_NAME
                } catch (_: Throwable) { "?" }
                w.write("# XQDK 性能诊断\n\n")
                w.write("> ⚠️ 隐私提示：本文件含完整对局记录（每步局面 FEN）与设备型号/系统版本，" +
                        "外发（含找开发者排查）前请自行斟酌内容。\n\n")
                w.write("- 设备：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）\n")
                w.write("- 版本：$ver\n")
                w.write("- 开始：${CLOCK.format(Date())}\n")
                w.write("- 列含义见各节首行注释；识别帧节/引擎节每次识别与每次搜索推进都会写，" +
                        "事件节记录生命周期与档位变化，每 $SUMMARY_EVERY 识别行自动汇总一次\n\n")
                w.flush()
            }
        }
    }

    /** 会话参数快照：诊断回来先看这行，参数对不对一目了然 */
    fun appendConfig(recognizer: String, searchDesc: String, multiPv: Int, hashMb: Int, confirmCount: Int) {
        synchronized(lock) {
            val w = out ?: return
            runCatching {
                w.write("## 会话\n\n")
                w.write("- 识别引擎：$recognizer\n")
                w.write("- 搜索制式：$searchDesc\n")
                w.write("- 变招路数：${multiPv} 路\n")
                w.write("- Hash：${hashMb}MB\n")
                w.write("- 确认帧数：$confirmCount\n\n")
                w.flush()
            }.onFailure { Log.w(TAG, "diag append failed", it) }
        }
    }

    /** 事件行（生命周期/档位变化/自愈/棋盘确认）：低频，写后即 flush */
    fun appendEvent(tag: String, detail: String) {
        synchronized(lock) {
            if (closed) return
            val w = out ?: return
            runCatching {
                if (!eventTableReady) { w.write("## 事件\n\n| 时间 | 事件 | 详情 |\n|---|---|---|\n"); eventTableReady = true }
                w.write("| ${CLOCK.format(Date())} | $tag | $detail |\n")
                w.flush()
            }.onFailure { Log.w(TAG, "diag append failed", it) }
        }
    }
    private var eventTableReady = false

    /**
     * 识别行：每次识别一行（成功与失败帧都写——失败帧的分段耗时同样是排查
     * "识别异常缓慢"的关键数据）。
     *
     * @param ts       识别开始时间戳（行时间取结束时刻更贴近耗时语义，由调用方传）
     * @param poseTag  定位方式：""=全图定位 / "(reuse)"=角点缓存复用 / "(recheck)"=复检 /
     *                 "yolo"=YOLO 引擎 / "?"=定位失败帧
     * @param segNs    五段纳秒 [poseWarp, poseRun, boardWarp, clsPrep, clsRun]（YOLO 全 0）
     * @param totalMs  单帧总耗时 ms（含预处理）
     * @param pace     放行节拍（RecognizePacer.describe：常规 / burst余N / 退避×N）
     * @param ok       结果可用且合法
     * @param failReason 失败原因（ok=true 时忽略；如"定位失败"/"棋子过少(3)"/"非法×2"）
     */
    fun appendRecognize(ts: Long, poseTag: String, segNs: LongArray, totalMs: Long,
                        pace: String, ok: Boolean, failReason: String?) {
        synchronized(lock) {
            if (closed) return
            val w = out ?: return
            runCatching {
                if (!recTableReady) { w.write("## 识别帧\n\n| 时间 | 间隔 | 定位 | 总 | poseWarp | poseRun | boardWarp | clsPrep | clsRun | 节拍 | 结果 |\n|---|---|---|---|---|---|---|---|---|---|---|\n"); recTableReady = true }
                // 间隔 = 距上一识别行（首行无参照，写 "-"）；"总"以外的五段为纳秒换算
                val interval = if (lastRowTs == 0L) "-" else (ts - lastRowTs).toString()
                lastRowTs = ts
                val time = CLOCK.format(Date(ts))
                val seg = { i: Int -> segNs.getOrElse(i) { 0 } / 1_000_000 }
                w.write("| $time | $interval | ${poseTag.ifEmpty { "全图" }} | $totalMs " +
                        "| ${seg(0)} | ${seg(1)} | ${seg(2)} | ${seg(3)} | ${seg(4)} " +
                        "| $pace | ${if (ok) "成功" else "失败:${failReason ?: "未知"}"} |\n")
                // ---- 统计累计 ----
                recRows++
                if (ok) okRows++ else {
                    val key = failReason ?: "未知"
                    failCounts[key] = (failCounts[key] ?: 0) + 1
                }
                (interval.toIntOrNull())?.let { if (it >= 0) { intervalSum += it; intervalN++ } }
                costHist[minOf((totalMs / HIST_BUCKET_MS).toInt(), HIST_BUCKETS - 1)]++
                if (recRows % 10 == 0) w.flush()
                if (recRows % SUMMARY_EVERY == 0) writeSummaryLocked(time)
            }.onFailure { Log.w(TAG, "diag append failed", it) }
        }
    }
    private var recTableReady = false

    /**
     * 心跳汇总行（识别帧节内的引用行，[SUMMARY_EVERY] 行一次）：成功/失败、
     * 平均/P50/P95 耗时、平均间隔、失败原因分布——真机数据回来不用人肉算。
     */
    private fun writeSummaryLocked(time: String) {
        val w = out ?: return
        val failDesc = if (failCounts.isEmpty()) "无"
            else failCounts.entries.joinToString("、") { "${it.key}×${it.value}" }
        // 平均耗时：直方图桶中值×桶计数的加权和（近似，误差 ≤±50ms）
        var weighted = 0L
        for (i in costHist.indices) weighted += costHist[i].toLong() * (i * HIST_BUCKET_MS + HIST_BUCKET_MS / 2)
        val avgMs = if (recRows == 0) 0 else weighted / recRows
        val avgInterval = if (intervalN == 0) "-" else "${intervalSum / intervalN}ms"
        w.write("> $time 汇总：$recRows 帧（成功 $okRows / 失败 ${recRows - okRows}）· " +
                "平均 ${avgMs}ms P50 ${percentileFromHist(costHist, 0.5)}ms " +
                "P95 ${percentileFromHist(costHist, 0.95)}ms · 平均间隔 $avgInterval · 失败：$failDesc\n")
        w.flush()
    }

    /**
     * 引擎行：皮卡鱼搜索每次深度推进（[done]=false）与定着收束（[done]=true）一行。
     * [costMs] = 本局面搜索已进行时长（调用方从局面起始时间戳计算）。
     */
    fun appendEngine(fen: String, depth: Int, costMs: Long, bestUcci: String?, done: Boolean) {
        synchronized(lock) {
            if (closed) return
            val w = out ?: return
            runCatching {
                if (!engTableReady) { w.write("\n## 引擎\n\n| 时间 | 局面 | 深度 | 耗时 | 首变 |\n|---|---|---|---|---|\n"); engTableReady = true }
                // 局面 FEN 截前缀（rmk 前的盘面部分已足够对局面对账，整串太长）
                val fenShort = fen.substringBefore(" -")
                w.write("| ${CLOCK.format(Date())} | $fenShort | $depth | $costMs " +
                        "| ${bestUcci ?: "-"}${if (done) "（定）" else ""} |\n")
                w.flush()
            }.onFailure { Log.w(TAG, "diag append failed", it) }
        }
    }
    private var engTableReady = false

    /** 写最终汇总并关闭流（幂等；随后 append 静默丢弃） */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val w = out ?: return
            runCatching {
                w.write("\n## 收尾\n\n")
                w.write("- 结束：${CLOCK.format(Date())}\n")
                if (recRows > 0) {
                    var weighted = 0L
                    for (i in costHist.indices) weighted += costHist[i].toLong() * (i * HIST_BUCKET_MS + HIST_BUCKET_MS / 2)
                    val avgInterval = if (intervalN == 0) "-" else "${intervalSum / intervalN}ms"
                    val failDesc = if (failCounts.isEmpty()) "无"
                        else failCounts.entries.joinToString("、") { "${it.key}×${it.value}" }
                    w.write("- 识别 $recRows 帧（成功 $okRows / 失败 ${recRows - okRows}）· 平均 " +
                            "${weighted / recRows}ms P50 ${percentileFromHist(costHist, 0.5)}ms " +
                            "P95 ${percentileFromHist(costHist, 0.95)}ms · 平均间隔 $avgInterval\n")
                    w.write("- 失败原因分布：$failDesc\n")
                }
                w.flush()
                w.close()
            }.onFailure { runCatching { w.close() } }
        }
        // 触发媒体扫描让文件管理器立即可见（MediaStore 写入通常已可见，双保险）
        displayPath?.let {
            runCatching {
                MediaScannerConnection.scanFile(appContext, arrayOf(it), null, null)
            }
        }
    }
}
