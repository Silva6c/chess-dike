package com.xqdk.chess.learn

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 走势曲线（学习模式独有功能）：我方视角评估分（cp）随放行着法推进的折线图。
 * 零轴=均势；红线在零轴上方=我方优势、下方=劣势。
 * 自绘 View（仓内 RiverLineView/AssistDotGridView 同款模式）：属性 setter 同值
 * 跳过 + invalidate；mate 哨兵分（±10000）绘图时钳到 ±[PLOT_CLAMP] 防压扁常规分差。
 *
 * 【准确性（2026-11-10 审计）】横向/纵向映射抽成纯函数（[plotX]/[plotY]，
 * internal=单测锚定）：首点贴左 pad、末点贴右 pad、中间均匀分布；0 分→零轴中线、
 * +max→顶部 pad、-max→底部 height-pad（对称满幅，v 越大 y 越小=优势朝上）。
 * 线宽/点径/虚线按屏密度换算 dp——原实现写死 px，440dpi 下线宽实际只有 ~1.5dp，
 * 低密度屏上偏粗、高密度屏上偏细。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        /** 绘图钳制（cp）：|分| 超过此值按此值画（mate 哨兵不压扁常规分差） */
        private const val PLOT_CLAMP = 1500f
        /** 绘图内边距（dp，onDraw 现场按密度换算） */
        private const val PAD_DP = 6f
        private const val COLOR_LINE = 0xFFA33D32.toInt() // xq_zhu 朱砂
        private const val COLOR_DOT = 0xFF9A5B00.toInt() // xq_amber 琥珀末端点
        private const val COLOR_AXIS = 0x55292D2A.toInt() // 零轴虚线（ink 33%）
    }

    private val density = resources.displayMetrics.density

    /** 我方视角 cp 点列（原值含 mate 哨兵）。
     *  【雷区】setter 内先快照再比较：调用方直传的是被原地变异的 MutableList
     *  （LearnActivity 直传 controller.trend），同引用 equals 恒真会把内容变化
     *  吞掉——invalidate 永不触发，折线图首帧后再不重绘（真机 bug 修复） */
    var points: List<Int> = emptyList()
        set(value) {
            val snapshot = value.toList()
            if (field == snapshot) return
            field = snapshot; invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_LINE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_DOT }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_AXIS
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 3.5f * density), 0f)
    }

    /** 纵向映射（纯函数，internal=单测锚定）：0→零轴中线；+maxAbs→顶部 pad；
     *  -maxAbs→底部 height-pad。调用方保证 maxAbs>0（onDraw 的 maxOf(300f,…)） */
    internal fun plotY(v: Float, maxAbs: Float, heightPx: Int, padPx: Float): Float =
        heightPx / 2f - v / maxAbs * (heightPx / 2f - padPx)

    /** 横向映射（纯函数）：首点贴左 pad、末点贴右 pad、中间均匀分布；
     *  单点落在左 pad（序列起点语义，不居中） */
    internal fun plotX(index: Int, size: Int, widthPx: Int, padPx: Float): Float {
        if (size <= 1) return padPx
        return padPx + index * (widthPx - 2 * padPx) / (size - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val pad = PAD_DP * density
        val midY = height / 2f
        // 零轴（均势线）
        canvas.drawLine(pad, midY, width - pad, midY, axisPaint)

        if (points.isEmpty()) return
        val plot = points.map { it.coerceIn(-PLOT_CLAMP.toInt(), PLOT_CLAMP.toInt()).toFloat() }
        // 纵向满幅自适应：最大 |分| 至少 300cp，避免开局微小分差把曲线拉成噪声
        val maxAbs = maxOf(300f, plot.maxOf { abs(it) })
        val xOf = { i: Int -> plotX(i, plot.size, width, pad) }
        val yOf = { v: Float -> plotY(v, maxAbs, height, pad) }

        if (plot.size == 1) {
            canvas.drawCircle(xOf(0), yOf(plot[0]), 2.5f * density, dotPaint)
            return
        }
        var px = xOf(0)
        var py = yOf(plot[0])
        for (i in 1 until plot.size) {
            val nx = xOf(i)
            val ny = yOf(plot[i])
            canvas.drawLine(px, py, nx, ny, linePaint)
            px = nx; py = ny
        }
        // 末端点
        canvas.drawCircle(px, py, 2.5f * density, dotPaint)
    }
}
