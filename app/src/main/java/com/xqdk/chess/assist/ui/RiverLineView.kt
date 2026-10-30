package com.xqdk.chess.assist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.xqdk.chess.R
import kotlin.math.cos

/**
 * 河界线（美学终稿 §2.3 动效一"线的呼吸"）：1.5dp 朱砂横线。
 * 静态 = 纯色朱砂；运行态 = [setBreathing](true) 启动 2.4s 线性无限循环：
 * 亮段（提亮朱砂 #E8936F）沿 240% 渐变域平移（REPEAT 平铺循环无缝），
 * 叠加整线明暗脉动（透明度 55%↔100% 余弦一周期）——双通道叠加保证 1.5dp 细线上
 * 呼吸肉眼可辨。动效预算纪律：本动画仅运行态出现，静止即停。
 */
class RiverLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        /** 呼吸周期（毫秒，终稿 §2.3"线的呼吸 2.4s"） */
        private const val PERIOD_MS = 2400L
        /** 渐变域宽度 = 视图宽 × 2.4（对齐视觉稿 background-size:240%） */
        private const val DOMAIN_SCALE = 2.4f
        /** 呼吸亮峰色（视觉稿高亮段 #D97057 再提亮一档，拉开与朱砂基色的对比） */
        private const val ZHU_BRIGHT = 0xFFE8936F.toInt()
        /** 整线明暗脉动的透明度下限（0.55↔1.0） */
        private const val ALPHA_MIN = 0.55f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xq_zhu)
    }
    private val shaderMatrix = Matrix()
    private var animator: ValueAnimator? = null
    private var breathing = false

    /** 切换呼吸态；同态重复调用零开销（1s 轮询安全） */
    fun setBreathing(enable: Boolean) {
        if (enable == breathing) return
        breathing = enable
        if (enable) {
            paint.shader = buildShader()
            ensureAnimator().start()
        } else {
            animator?.cancel()
            paint.shader = null
            paint.alpha = 255
            invalidate()
        }
    }

    private fun buildShader(): LinearGradient = LinearGradient(
        0f, 0f, width * DOMAIN_SCALE, 0f,
        intArrayOf(paint.color, paint.color, ZHU_BRIGHT, paint.color, paint.color),
        // 亮段占域 40%（0.3–0.7）：窄段在 1.5dp 线上存在感不足
        floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
        Shader.TileMode.REPEAT,
    )

    private fun ensureAnimator(): ValueAnimator {
        animator?.let { return it }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PERIOD_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            animator = this
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 宽度变化后渐变域随之重建（呼吸进行中才需要）
        if (breathing) paint.shader = buildShader()
    }

    override fun onDraw(canvas: Canvas) {
        if (breathing && paint.shader != null) {
            val f = animator?.animatedFraction ?: 0f
            val shift = f * width * DOMAIN_SCALE
            shaderMatrix.setTranslate(-shift, 0f)
            paint.shader.setLocalMatrix(shaderMatrix)
            // 整线明暗呼吸：透明度 0.55↔1.0 余弦一周期（首尾同值，循环无缝）
            val breath = ALPHA_MIN + (1f - ALPHA_MIN) * 0.5f * (1f - cos(2.0 * Math.PI * f).toFloat())
            paint.alpha = (255f * breath).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // detach→attach 循环后恢复呼吸：setBreathing 同态 no-op 不会重启，须在此
        // 补启（体检 P2）；shader 依赖宽度，宽度保留则重建，未 layout 由
        // onSizeChanged 兜底
        if (breathing) {
            if (width > 0) paint.shader = buildShader()
            ensureAnimator().start()
        }
    }
}
