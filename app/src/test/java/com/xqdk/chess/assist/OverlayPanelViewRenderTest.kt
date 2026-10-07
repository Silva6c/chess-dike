package com.xqdk.chess.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.xqdk.chess.assist.ui.OverlayPanelView
import com.xqdk.chess.assist.ui.OverlayStateKind
import com.xqdk.chess.assist.ui.OverlayTheme
import com.xqdk.chess.assist.ui.QiTone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 悬浮窗主面板渲染快照（开发辅助）：把三档主题 + 遮挡黄字状态画成 PNG，
 * 输出到 build/overlay_panel_render_*.png 供人工目检（真机/模拟器上 FLAG_SECURE
 * 使截屏整体失败，此为面板视觉验收的主途径）；同时保证 render/applyTheme 全路径无异常。
 * density 440dpi（2.75）：面板 392×197dp = 1078×541px，与真机一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w440dp-h920dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayPanelViewRenderTest {

    private fun renderPanel(name: String, theme: OverlayTheme, occluded: Boolean): Bitmap {
        val ctx = RuntimeEnvironment.getApplication()
        val panel = OverlayPanelView(ctx)
        val widthPx = 1078  // dp(392) @ 2.75
        val heightPx = 541  // dp(197) @ 2.75
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, widthPx, heightPx)
        panel.applyTheme(theme)

        val start = AssistBoard.canonicalStart()
        val arr = IntArray(90)
        for (y in 0..9) for (x in 0..8) arr[y * 9 + x] = start[y][x]

        // 我方回合、库内建议（定着绿✓）+ 点阵 1/3：信息最全的展示状态
        panel.render(com.xqdk.chess.assist.ui.OverlayModel(
            stateText = "识别中",
            stateKind = OverlayStateKind.RUNNING,
            occludedText = if (occluded) "挡住棋盘，建议移开" else null,
            headlineText = "轮到我方走",
            advantageText = "红方优势 +0.35 深18",
            advantageTone = QiTone.GOOD,
            candidateText = "开局库 炮二平五 ✓",
            candidateTone = QiTone.GOOD,
            candidateIndex = 1,
            candidateCount = 3,
            pieces = arr,
            arrowFrom = 7 to 7,
            arrowTo = 4 to 7,
            flipped = false,
            mySideIsRed = true,
            mature = true,
            depthText = "20",
            hashText = "256",
            engineText = "ONNX",
            multiPv = 3,
        ))

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        // render 会把 GONE 行（优势/候选/遮挡）切 VISIBLE 并 requestLayout：
        // 真实悬浮窗由 WindowManager 安排重排，测试需手动补一轮 measure/layout 再 draw
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, widthPx, heightPx)
        panel.draw(Canvas(bmp))
        val out = File("build", "overlay_panel_render_$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        println("rendered ${out.absolutePath}")
        return bmp
    }

    @Test
    fun `render dark glass theme`() {
        renderPanel("dark", OverlayTheme.DARK, occluded = false).recycle()
    }

    @Test
    fun `render black oled theme`() {
        renderPanel("black", OverlayTheme.BLACK, occluded = false).recycle()
    }

    @Test
    fun `render light paper theme`() {
        renderPanel("light", OverlayTheme.LIGHT, occluded = false).recycle()
    }

    @Test
    fun `render occluded warning row`() {
        renderPanel("dark_occluded", OverlayTheme.DARK, occluded = true).recycle()
    }
}
