package com.xqdk.chess.assist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.xqdk.chess.R
import kotlin.math.cos

/**
 * 识别点阵（美学终稿 §2.3/§4.2）：棋盘 9×10 交点的抽象化微点阵，自绘零图片资产。
 *
 * - 主页 = 静态装饰：默认 [AssistState.IDLE]（待机墨 10% 恒静），不带语义；
 * - 连线页 = 全 App 唯一语义仪器面：待机墨恒静 → 识别中琥珀逐点 80ms 错峰
 *   （[AssistState.RUNNING_WAVE]，行波周期 2.4s）→ 等待/思考玉绿恒亮
 *   （RUNNING_STILL，与琥珀行波区分开——四态四色可辨）→ 引擎异常警示红（ERROR）。
 * - 几何随视图实际尺寸缩放（列距铺满可用宽、行距铺满可用高，点半径随行距等比），
 *   不是固定格距居中摆放——大屏铺得开、小屏收得拢。
 *
 * 动效预算纪律（终稿 §2.3：全 App 仅三处动效）：本视图只有"逐点错峰"一种动画，
 * 由 [setAssistState] 驱动，同态重复调用零开销（no-op），退出即停 animator。
 */
class AssistDotGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        /** 棋盘交点抽象：9 列 × 10 行 */
        private const val COLS = 9
        private const val ROWS = 10
        /** 错峰间隔（毫秒/点，终稿 §2.3"识别中琥珀逐点 80ms 错峰"） */
        private const val STAGGER_MS = 80f
        /** 行波周期（毫秒，与"线的呼吸"同参数档） */
        private const val WAVE_PERIOD_MS = 2400f
    }

    private val density = resources.displayMetrics.density

    // 绘制几何随视图实际尺寸缩放（onSizeChanged 重算）；以下初值仅为 0 尺寸兜底
    private var drawRadius = 1.4f * density
    private var drawColGap = 28f * density
    private var drawRowGap = 5f * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 边点内缩一个半径防裁边；列距/行距由可用宽高均分（铺满），点半径随行距
        // 等比并钳制——极端宽/高比下点不至于小到不可见或大到成球
        drawRadius = (h / ROWS.toFloat() * 0.3f).coerceIn(1.0f * density, 3.0f * density)
        drawColGap = (w - 2 * drawRadius) / (COLS - 1)
        drawRowGap = (h - 2 * drawRadius) / (ROWS - 1)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 行波相位 0..2π（RUNNING_WAVE 态由 animator 推进） */
    private var phase = 0f

    /** 每个点的行波相位偏移（构造期算好，避免 onDraw 反复计算） */
    private val phaseOffsets = FloatArray(COLS * ROWS).also { offsets ->
        val radPerMs = (2.0 * Math.PI / WAVE_PERIOD_MS).toFloat()
        for (i in offsets.indices) offsets[i] = i * STAGGER_MS * radPerMs
    }

    private var state = AssistState.IDLE

    // 四态点色基色：自 colors.xml token 派生（alpha 组合），token 改值不漂移（体检 P2：
    // 原 0xE62E7D32 等字面量与 token 数值重复定义，改 token 时会静默失配）
    private val idleDot = alphaToken(0x1A, R.color.xq_ink_text) // 待机墨 10%
    private val stillDot = alphaToken(0xE6, R.color.xq_jade)    // 等待/思考/手动：玉绿
    private val errorDot = alphaToken(0xE6, R.color.xq_warn)    // 引擎异常：警示红
    private val amberBase = ContextCompat.getColor(context, R.color.xq_amber) and 0xFFFFFF

    private fun alphaToken(alpha: Int, res: Int): Int =
        (alpha shl 24) or (ContextCompat.getColor(context, res) and 0xFFFFFF)

    private val animator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
        duration = WAVE_PERIOD_MS.toLong()
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** 切换仪器面语义状态；同态重复调用零开销（1s 轮询安全） */
    fun setAssistState(newState: AssistState) {
        if (newState == state) return
        state = newState
        if (newState == AssistState.RUNNING_WAVE) {
            if (!animator.isRunning) animator.start()
        } else {
            animator.cancel()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val gridW = (COLS - 1) * drawColGap
        val gridH = (ROWS - 1) * drawRowGap
        val startX = (width - gridW) / 2f
        val startY = (height - gridH) / 2f
        var i = 0
        for (row in 0 until ROWS) {
            val y = startY + row * drawRowGap
            for (col in 0 until COLS) {
                val x = startX + col * drawColGap
                paint.color = dotColor(i)
                canvas.drawCircle(x, y, drawRadius, paint)
                i++
            }
        }
    }

    /** 各语义态的点色（纸档 token 派生，四态四色：墨/琥珀行波/玉绿/警示红） */
    private fun dotColor(index: Int): Int = when (state) {
        AssistState.IDLE -> idleDot
        AssistState.RUNNING_STILL -> stillDot
        AssistState.ERROR -> errorDot
        AssistState.RUNNING_WAVE -> {
            // 识别中：琥珀逐点亮——0.35 基亮 + 行波峰 0.85（80ms/点错峰）
            val wave = 0.5f + 0.5f * cos(phase - phaseOffsets[index])
            val alpha = (0.35f + 0.5f * wave).coerceIn(0f, 1f)
            ((alpha * 255).toInt() shl 24) or amberBase
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // detach→attach 循环后按当前态恢复行波：onDetachedFromWindow 已 cancel，
        // 而 setAssistState 同态 no-op 不会重启，须在此补启（体检 P2）
        if (state == AssistState.RUNNING_WAVE && !animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
