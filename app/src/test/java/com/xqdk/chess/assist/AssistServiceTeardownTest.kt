package com.xqdk.chess.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 回归：用户报"首次使用过后，再一打开软件，悬浮窗自动跳出"——根因是退出意图
 * 与服务生命周期脱钩（onDestroy 只解绑、任务划掉无收场，面板"停止"仅暂停），
 * 前台服务带着录屏与悬浮窗残留，下次打开 App 面板已在屏上。
 *
 * 修复后两条退出路径都必须随退收场（stopGuidance：收面板 + 停录屏 + stopSelf）：
 * 1. AssistActivity isFinishing 销毁（本测试不起 Activity 全链，行为由服务侧
 *    stopGuidance 幂等性保证，页面改动风险在 onTaskRemoved 同一函数）；
 * 2. 服务 onTaskRemoved（最近任务划掉）——本测试直接驱动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistServiceTeardownTest {

    @Test
    fun `任务划掉后服务自停且无捕获残留`() {
        val controller = Robolectric.buildService(ScreenAssistService::class.java)
        controller.create() // YOLO/ONNX 初始化失败在服务内被 catch（JVM 无 native 库）
        val service = controller.get()

        service.onTaskRemoved(null)

        val shadow = shadowOf(service)
        assertTrue("onTaskRemoved 应触发 stopSelf", shadow.isStoppedBySelf)
        assertFalse("录屏/采集不应残留", service.isCapturing())
        controller.destroy()
    }

    @Test
    fun `从未开始识别时任务划掉同样安全（幂等收场）`() {
        // bind-only 场景：面板与录屏皆 null，stopGuidance 不得抛出
        val controller = Robolectric.buildService(ScreenAssistService::class.java)
        controller.create()
        val service = controller.get()

        service.onTaskRemoved(null)

        assertFalse(service.isCapturing())
        controller.destroy()
    }
}
