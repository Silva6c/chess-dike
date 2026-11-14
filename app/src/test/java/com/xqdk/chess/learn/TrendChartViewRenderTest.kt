package com.xqdk.chess.learn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 走势曲线渲染：空数据 / 单点 / 多点含 mate 哨兵钳制，全路径不崩；
 *  同实例原位变异后重设必须重绘（2026-11-10 折线图不显示 bug 的回归锁） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrendChartViewRenderTest {

    private fun draw(view: TrendChartView) {
        val w = 1000
        val h = 320
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        bmp.recycle()
    }

    @Test
    fun `空数据渲染`() {
        val view = TrendChartView(RuntimeEnvironment.getApplication())
        draw(view)
    }

    @Test
    fun `单点与多点渲染含哨兵钳制`() {
        val view = TrendChartView(RuntimeEnvironment.getApplication())
        view.points = listOf(40)
        draw(view)
        view.points = listOf(40, -120, 60, 10000, -10000, -30)
        draw(view)
    }

    /** 回归锁：controller.trend 是原地变异的同一 MutableList 实例，setter 若直接
     *  同引用 equals 比较（恒真）会吞掉 invalidate——折线图首帧后再不重绘 */
    @Test
    fun `同实例原位变异后重设必须重绘`() {
        val view = TrendChartView(RuntimeEnvironment.getApplication())
        val live = mutableListOf(30)
        view.points = live
        shadowOf(view).clearWasInvalidated()
        live.add(-80)
        view.points = live
        assertTrue(shadowOf(view).wasInvalidated())
    }

    /** 绘图准确性锚点（2026-11-10 折线审计）：纵向映射 0→零轴中线、±max→上下
     *  pad 边界、±v 关于中线镜像对称、v 越大 y 越小（优势朝上） */
    @Test
    fun `纵向映射锚点_零轴居中_满幅对称`() {
        val view = TrendChartView(RuntimeEnvironment.getApplication())
        val pad = 16f
        val h = 320
        assertEquals(160f, view.plotY(0f, 300f, h, pad), 1e-4f)
        assertEquals(pad, view.plotY(300f, 300f, h, pad), 1e-4f)
        assertEquals(h - pad, view.plotY(-300f, 300f, h, pad), 1e-4f)
        val yPlus = view.plotY(120f, 300f, h, pad)
        val yMinus = view.plotY(-120f, 300f, h, pad)
        assertEquals(160f, (yPlus + yMinus) / 2f, 1e-4f) // 镜像对称
        assertTrue(view.plotY(200f, 300f, h, pad) < view.plotY(100f, 300f, h, pad))
    }

    /** 横向映射锚点：首点贴左 pad、末点贴右 pad、中间均匀（单调）；
     *  单点落左 pad（序列起点语义） */
    @Test
    fun `横向映射锚点_首末贴边_单调`() {
        val view = TrendChartView(RuntimeEnvironment.getApplication())
        val pad = 16f
        val w = 1000
        assertEquals(pad, view.plotX(0, 6, w, pad), 1e-4f)
        assertEquals(w - pad, view.plotX(5, 6, w, pad), 1e-4f)
        assertTrue(view.plotX(1, 6, w, pad) < view.plotX(2, 6, w, pad))
        assertTrue(view.plotX(3, 6, w, pad) < view.plotX(4, 6, w, pad))
        assertEquals(pad, view.plotX(0, 1, w, pad), 1e-4f)
    }
}
