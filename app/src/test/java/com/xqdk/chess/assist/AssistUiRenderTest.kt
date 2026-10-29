package com.xqdk.chess.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.xqdk.chess.assist.ui.AssistDotGridView
import com.xqdk.chess.assist.ui.AssistState
import com.xqdk.chess.assist.ui.AssistStateMap
import com.xqdk.chess.assist.ui.RiverLineView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 连线页仪器面新视图（美学终稿 §4.2）：识别点阵四态渲染 + 河界线呼吸开关，
 * 保证 measure/layout/draw 全路径无异常（视觉证据靠模拟器装包目检，此处管崩溃）；
 * 外加状态文案映射纯函数 AssistStateMap 的语义契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w440dp-h920dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistUiRenderTest {

    private fun draw(view: View): Bitmap {
        val w = 858 // 312dp @ 2.75
        val h = 143 // 52dp @ 2.75
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun `dot grid renders all four states`() {
        val ctx = RuntimeEnvironment.getApplication()
        val grid = AssistDotGridView(ctx)
        for (state in AssistState.entries) {
            grid.setAssistState(state)
            // 同态重复调用（1s 轮询路径）应为 no-op 不抛
            grid.setAssistState(state)
            draw(grid).recycle()
        }
    }

    @Test
    fun `river line toggles breathing and renders`() {
        val ctx = RuntimeEnvironment.getApplication()
        val river = RiverLineView(ctx)
        draw(river).recycle() // 静态
        river.setBreathing(true)
        draw(river).recycle() // 呼吸（shader 位移）
        river.setBreathing(true) // 同态 no-op
        river.setBreathing(false)
        draw(river).recycle() // 回静态
    }

    @Test
    fun `status text maps to dot states`() {
        // 异常类 → 警示红
        assertEquals(AssistState.ERROR, AssistStateMap.fromStatusText("引擎异常：引擎进程退出"))
        assertEquals(AssistState.ERROR, AssistStateMap.fromStatusText("ONNX 引擎加载失败\n已回退 YOLO 识别"))
        assertEquals(AssistState.ERROR, AssistStateMap.fromStatusText("识别不稳定\n请让棋盘完整无遮挡"))
        // 终止类 → 待机
        assertEquals(AssistState.IDLE, AssistStateMap.fromStatusText("未启动"))
        assertEquals(AssistState.IDLE, AssistStateMap.fromStatusText("已关闭（识别与录屏均已停止，可在连线页重新启动）"))
        assertEquals(AssistState.IDLE, AssistStateMap.fromStatusText("已停止指导\n点『继续』恢复"))
        // 识别进行中 → 琥珀行波
        assertEquals(AssistState.RUNNING_WAVE, AssistStateMap.fromStatusText("识别中"))
        assertEquals(AssistState.RUNNING_WAVE, AssistStateMap.fromStatusText("正在识别棋盘…\n请让棋盘完整无遮挡"))
        // 其余运行态（思考/预热/手动/轮到）→ 玉绿恒亮（与琥珀行波区分，四态四色）
        assertEquals(AssistState.RUNNING_STILL, AssistStateMap.fromStatusText("引擎思考中…"))
        assertEquals(AssistState.RUNNING_STILL, AssistStateMap.fromStatusText("引擎预热中…"))
        assertEquals(AssistState.RUNNING_STILL, AssistStateMap.fromStatusText("手动模式（再点手动退出）"))
        assertEquals(AssistState.RUNNING_STILL, AssistStateMap.fromStatusText("棋局已更新\n现轮到我方走"))
        // 异常关键词优先级最高（异常+识别中并存按异常处理）
        assertEquals(AssistState.ERROR, AssistStateMap.fromStatusText("识别中检测到引擎异常"))
    }
}
