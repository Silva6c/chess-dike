package com.xqdk.chess.assist

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv5 输出后处理（纯 JVM，便于单测）：
 * 解码 [1,25200,20] 张量 -> 置信度过滤 -> 类别感知 NMS -> 比例/尺寸一致性过滤。
 *
 * 过滤策略与 VinXiangQi 的 GetBoardFromPrediction 一致并加强：
 * - 棋子框宽高比须在 [aspectMin, aspectMax]（棋子近似圆形，排除把工具栏按钮/横幅误检当棋子）；
 * - 相对"棋子宽度中位数"偏差过大的框剔除（杀掉"轮到红方"圆形标记等小异物；
 *   对被走子标记环遮挡的真棋子用宽松阈值避免误杀）。
 */
object YoloPostprocessor {

    const val MODEL_INPUT = 640
    const val ANCHORS = 25200
    const val DIMS = 20

    /** letterbox 参数：帧 -> 640 输入的缩放与居中留白 */
    data class Letterbox(val scale: Double, val padX: Double, val padY: Double) {
        companion object {
            fun forFrame(frameW: Int, frameH: Int): Letterbox {
                val s = min(MODEL_INPUT.toDouble() / frameW, MODEL_INPUT.toDouble() / frameH)
                val nw = (frameW * s).roundToInt()
                val nh = (frameH * s).roundToInt()
                return Letterbox(s, (MODEL_INPUT - nw) / 2.0, (MODEL_INPUT - nh) / 2.0)
            }
        }
    }

    /**
     * @param output 解释器原始输出 [1,25200,20]（已乘 sigmoid 的解码值），行序即 anchor 序
     * @param confThreshold 棋子/棋盘统一置信度阈值（参考实现用 0.7，取 0.6 兼顾召回）
     */
    fun decode(
        output: Array<FloatArray>,
        lb: Letterbox,
        frameW: Int,
        frameH: Int,
        confThreshold: Double = 0.60,
        iouThreshold: Double = 0.45,
    ): List<YoloDetection> {
        data class Raw(val labelId: Int, val score: Double, val x1: Double, val y1: Double, val x2: Double, val y2: Double)

        val raws = ArrayList<Raw>(256)
        for (i in 0 until ANCHORS) {
            val row = output[i]
            val obj = row[4].toDouble()
            if (obj < confThreshold) continue
            var bestCls = -1
            var bestScore = 0.0
            for (c in 0 until DIMS - 5) {
                val s = obj * row[5 + c].toDouble()
                if (s > bestScore) { bestScore = s; bestCls = c }
            }
            if (bestCls < 0 || bestScore < confThreshold) continue
            val cx = row[0].toDouble()
            val cy = row[1].toDouble()
            val w = row[2].toDouble()
            val h = row[3].toDouble()
            raws.add(Raw(bestCls, bestScore, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
        }
        if (raws.isEmpty()) return emptyList()

        // 类别感知 NMS
        raws.sortByDescending { it.score }
        val keep = ArrayList<Raw>()
        for (r in raws) {
            var overlapped = false
            for (k in keep) {
                if (k.labelId != r.labelId) continue
                if (iou(r.x1, r.y1, r.x2, r.y2, k.x1, k.y1, k.x2, k.y2) > iouThreshold) { overlapped = true; break }
            }
            if (!overlapped) keep.add(r)
        }

        // 640 输入域 -> 原始帧域
        fun toFrame(v: Double, pad: Double) = (v - pad) / lb.scale
        val dets = ArrayList<YoloDetection>(keep.size)
        for (r in keep) {
            val x1 = toFrame(r.x1, lb.padX); val y1 = toFrame(r.y1, lb.padY)
            val x2 = toFrame(r.x2, lb.padX); val y2 = toFrame(r.y2, lb.padY)
            dets.add(YoloDetection(r.labelId, r.score,
                (x1 + x2) / 2, (y1 + y2) / 2, (x2 - x1), (y2 - y1)))
        }

        // 棋子比例过滤 + 尺寸一致性过滤（board 框不参与）
        val pieces = dets.filter { !it.isBoard && it.w > 0 && aspectOk(it) }
        if (pieces.isEmpty()) return dets.filter { it.isBoard }
        val medianW = median(pieces.map { it.w })
        val medianH = median(pieces.map { it.h })
        val out = ArrayList<YoloDetection>()
        for (d in dets) {
            when {
                d.isBoard -> out.add(d)
                aspectOk(d) && d.w >= medianW * 0.55 && d.w <= medianW * 1.6
                    && d.h >= medianH * 0.55 && d.h <= medianH * 1.6 -> out.add(d)
                // 尺寸离群的丢弃
            }
        }
        return out
    }

    private fun aspectOk(d: YoloDetection): Boolean {
        val ratio = d.w / d.h
        return ratio in 0.7..1.3
    }

    private fun iou(ax1: Double, ay1: Double, ax2: Double, ay2: Double,
                    bx1: Double, by1: Double, bx2: Double, by2: Double): Double {
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val iw = max(0.0, ix2 - ix1); val ih = max(0.0, iy2 - iy1)
        val inter = iw * ih
        if (inter <= 0) return 0.0
        val a = (ax2 - ax1) * (ay2 - ay1)
        val b = (bx2 - bx1) * (by2 - by1)
        return inter / (a + b - inter)
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
