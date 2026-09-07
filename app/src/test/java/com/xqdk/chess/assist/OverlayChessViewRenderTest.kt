package com.xqdk.chess.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.xqdk.chess.assist.ui.OverlayChessView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 悬浮窗迷你棋盘渲染快照（开发辅助）：把 standard/flipped/箭头三种状态画成 PNG，
 * 输出到 build/overlay_render_*.png 供人工目检；同时保证 onDraw 全路径无异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayChessViewRenderTest {

    private fun render(name: String, flipped: Boolean, withArrow: Boolean, mature: Boolean = false,
                       manual: Boolean = false): Bitmap {
        val view = OverlayChessView(RuntimeEnvironment.getApplication())
        val widthPx = 474
        val heightPx = 513
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)

        val start = AssistBoard.canonicalStart()
        val arr = IntArray(90)
        for (y in 0..9) for (x in 0..8) arr[y * 9 + x] = start[y][x]
        view.pieces = arr
        view.flipped = flipped
        view.mySideIsRed = !flipped // 自动机：屏幕下方阵营=我方
        view.mature = mature
        view.manualMode = manual
        view.selectedCell = if (manual) 4 to 7 else null
        if (withArrow) {
            // 红炮二平五 (7,7)->(4,7)
            view.arrowFrom = 7 to 7
            view.arrowTo = 4 to 7
        }

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File("build", "overlay_render_$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        println("rendered ${out.absolutePath}")
        return bmp
    }

    @Test
    fun `render standard with suggestion arrow`() {
        render("standard_arrow", flipped = false, withArrow = true)
    }

    @Test
    fun `render standard with mature arrow`() {
        render("standard_arrow_mature", flipped = false, withArrow = true, mature = true)
    }

    @Test
    fun `render flipped with suggestion arrow`() {
        render("flipped_arrow", flipped = true, withArrow = true)
    }

    @Test
    fun `render standard plain`() {
        render("standard_plain", flipped = false, withArrow = false)
    }

    @Test
    fun `render manual mode with selected cell`() {
        // 手动模式：选中格绘制金色高亮圈，全绘制路径无异常
        render("manual_selected", flipped = false, withArrow = false, manual = true)
        render("manual_selected_flipped", flipped = true, withArrow = false, manual = true)
    }

    /**
     * 翻转契约回归：服务端传 canonical（红恒在 y=9），视图按 flipped 自行做 180° 映射。
     * 回归 bug：曾把屏幕布局与映射叠加导致"永远红在下"。此处按几何直接采样像素：
     * scale=min(474/1240,513/1340)，canonical (4,9) 红帅在 standard 位于 (237,491)、
     * flipped 映射到 sy=0 位于 (237,23)。
     */
    @Test
    fun `flipped render mirrors screen - red shuai on top`() {
        val std = render("std_pixels", flipped = false, withArrow = false)
        val flip = render("flip_pixels", flipped = true, withArrow = false)
        try {
            // 强红判定：红帅的红字/红圈；木底与米色棋面（r-g 小）不命中
            fun redRatio(bmp: Bitmap, cx: Int, cy: Int): Double {
                var red = 0
                var total = 0
                for (dy in -10..10) for (dx in -10..10) {
                    val p = bmp.getPixel(cx + dx, cy + dy)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    total++
                    if (r > 150 && r - g > 60 && r - b > 60) red++
                }
                return red.toDouble() / total
            }
            val stdBottom = redRatio(std, 237, 491)
            val flipTop = redRatio(flip, 237, 23)
            val flipBottom = redRatio(flip, 237, 491)
            assertTrue("standard 底部应是红帅 (ratio=$stdBottom)", stdBottom > 0.03)
            assertTrue("flipped 后顶部应是红帅 (ratio=$flipTop)", flipTop > 0.03)
            assertTrue("flipped 后底部应是黑将而非红子 (ratio=$flipBottom vs top=$flipTop)",
                flipBottom < flipTop / 3)
        } finally {
            std.recycle()
            flip.recycle()
        }
    }
}
