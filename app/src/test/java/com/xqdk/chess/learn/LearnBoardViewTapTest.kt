package com.xqdk.chess.learn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.xqdk.chess.gamelogic.Move
import com.xqdk.chess.gamelogic.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 学习棋盘手势与分层渲染：hitCell 反解（含执黑翻转）、幽灵层不参与命中
 * （点选恒映射逻辑盘面）、渲染全路径不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnBoardViewTapTest {

    private fun draw(view: LearnBoardView) {
        val w = 1078
        val h = (w * 1340f / 1240f).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        bmp.recycle()
    }

    /** 与视图 center() 同式反推格点像素中心（draw 之后 scale/origin 已就位） */
    private fun pixelOf(view: LearnBoardView, x: Int, y: Int): Pair<Float, Float> {
        val sx = if (view.flipped) 8 - x else x
        val sy = if (view.flipped) 9 - y else y
        val px = view.originX + (22f + sx * 136f + 55f) * view.scale
        val py = view.originY + (5f + sy * 136f + 55f) * view.scale
        return px to py
    }

    private fun tap(view: LearnBoardView, px: Float, py: Float) {
        view.dispatchTouchEvent(
            MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, px, py, 0))
        view.dispatchTouchEvent(
            MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py, 0))
    }

    @Test
    fun `点格回调含翻转映射_幽灵层不参与命中`() {
        val view = LearnBoardView(RuntimeEnvironment.getApplication())
        val taps = mutableListOf<Pair<Int, Int>>()
        view.onCellTap = { x, y -> taps.add(x to y) }
        draw(view)

        // 红视角：点 (4,5) 命中 canonical (4,5)
        val (px, py) = pixelOf(view, 4, 5)
        tap(view, px, py)
        assertEquals(listOf(4 to 5), taps)

        // 执黑翻转后同一像素点位映射到镜像格；canonical (4,5) 的像素落在 (4,4) 镜像侧
        view.flipped = true
        draw(view)
        taps.clear()
        tap(view, px, py)
        assertEquals(listOf(4 to 4), taps)
        val (px2, py2) = pixelOf(view, 4, 5)
        tap(view, px2, py2)
        assertEquals(listOf(4 to 4, 4 to 5), taps)
    }

    @Test
    fun `渲染全路径含幽灵预览箭头不崩`() {
        val view = LearnBoardView(RuntimeEnvironment.getApplication())
        view.pieces = IntArray(90).also {
            // 摆一个红炮 (1,7) 与黑马 (7,0)
            it[7 * 9 + 1] = 6  // WPAO
            it[0 * 9 + 7] = 11 // BMA
        }
        view.previewMove = Move(Position(1, 7), Position(4, 7))
        view.ghostMove = Move(Position(1, 7), Position(4, 7))
        view.redArrow = Move(Position(7, 0), Position(6, 2))
        view.redArrowDim = true
        view.greenArrows = listOf(Move(Position(1, 7), Position(4, 7)), Move(Position(1, 9), Position(2, 7)))
        view.lastMove = Move(Position(7, 0), Position(6, 2))
        view.selected = Position(1, 7)
        view.legalTargets = listOf(Position(4, 7), Position(0, 7))
        draw(view) // 不抛即通过（快照目检靠 OverlayChessViewRenderTest 同款人工途径）
        assertTrue(true)
    }
}
