package com.xqdk.chess.assist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos

/**
 * 识别点阵（美学终稿 §2.3/§4.2）：棋盘 9×10 交点的抽象化微点阵，自绘零图片资产。
 *
 * - 主页 = 静态装饰：默认 [AssistState.IDLE]（待机墨 10% 恒静），不带语义；
 * - 连线页 = 全 App 唯一语义仪器面：待机墨恒静 → 识别中琥珀逐点 80ms 错峰
 *   （[AssistState.RUNNING_WAVE]，行波周期 2.4s）→ 等待/思考琥珀恒亮
 *   （RUNNING_STILL）→ 引擎异常警示红（ERROR）。
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
    private val dotRadius = 1.4f * density
    private val colGap = 28f * density
    private val rowGap = 5f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 行波相位 0..2π（RUNNING_WAVE 态由 animator 推进） */
    private var phase = 0f

    /** 每个点的行波相位偏移（构造期算好，避免 onDraw 反复计算） */
    private val phaseOffsets = FloatArray(COLS * ROWS).also { offsets ->
        val radPerMs = (2.0 * Math.PI / WAVE_PERIOD_MS).toFloat()
        for (i in offsets.indices) offsets[i] = i * STAGGER_MS * radPerMs
    }

    private var state = AssistState.IDLE

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
        val gridW = (COLS - 1) * colGap
        val gridH = (ROWS - 1) * rowGap
        val startX = (width - gridW) / 2f
        val startY = (height - gridH) / 2f
        var i = 0
        for (row in 0 until ROWS) {
            val y = startY + row * rowGap
            for (col in 0 until COLS) {
                val x = startX + col * colGap
                paint.color = dotColor(i)
                canvas.drawCircle(x, y, dotRadius, paint)
                i++
            }
        }
    }

    /** 各语义态的点色（纸档 token，与视觉稿点阵四态一致） */
    private fun dotColor(index: Int): Int = when (state) {
        AssistState.IDLE -> 0x1A292D2A // 待机墨 10% 恒静
        AssistState.RUNNING_STILL -> 0xD99A5B00.toInt() // 等待/思考/手动：琥珀恒亮
        AssistState.ERROR -> 0xE6C62828.toInt() // 引擎异常：警示红
        AssistState.RUNNING_WAVE -> {
            // 识别中：琥珀逐点亮——0.35 基亮 + 行波峰 0.85（80ms/点错峰）
            val wave = 0.5f + 0.5f * cos(phase - phaseOffsets[index])
            val alpha = (0.35f + 0.5f * wave).coerceIn(0f, 1f)
            ((alpha * 255).toInt() shl 24) or 0x9A5B00
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
