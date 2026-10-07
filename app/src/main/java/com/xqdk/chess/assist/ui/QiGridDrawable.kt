package com.xqdk.chess.assist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 主页默认背景：墨底 #14171C + 26dp 细网格（棋韵 §5.1）。
 * 自绘而非位图平铺——任意屏幕密度下线条都锐利，且零图片资产；
 * 网格线取 3.5% 白，只提供"坐标纸"般的秩序感，不与内容抢注意力。
 */
class QiGridDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density

    /** 网格步长 26dp（与视觉稿一致） */
    private val step = 26f * density

    private val bgPaint = Paint().apply { color = 0xFF14171C.toInt() }

    private val linePaint = Paint().apply {
        color = 0x09FFFFFF
        strokeWidth = 1f * density
        isAntiAlias = false
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, bgPaint)
        var x = b.left.toFloat()
        while (x <= b.right) {
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), linePaint)
            x += step
        }
        var y = b.top.toFloat()
        while (y <= b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, linePaint)
            y += step
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
