package com.xqdk.chess.assist

import android.content.Context
import android.os.Looper
import android.view.WindowManager
import com.xqdk.chess.assist.ui.OverlayTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * 回归：用户报"首次使用过后，再一打开软件，悬浮窗自动跳出"——两轮实测，两条根因：
 *
 * 根因一（退出残留，b5d4c80 修）：退出意图与服务生命周期脱钩——onDestroy 只解绑、
 * 任务划掉无收场，前台服务带着录屏与悬浮窗残留。
 *
 * 根因二（冷启动建窗，本文件 `未开始录屏时绑定回调链不得建悬浮窗` 用例锁住）：
 * 服务 bind-only 即创建（连线页绑定，未授权录屏），此时 overlayClosed=false（字段
 * 初值）、悬浮窗权限已授予（首次使用后），Activity onServiceConnected 无条件调用的
 * setSearchMode → postRender → renderOverlay → ensureOverlay 把悬浮窗建出来——
 * 治本 = ensureOverlay 加"无录屏会话拒建"守卫（面板生命周期 ⊆ 录屏会话生命周期）。
 *
 * 三例覆盖：任务划掉收场、bind-only 幂等、绑定回调链不建窗。
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

    @Test
    fun `未开始录屏时绑定回调链不得建悬浮窗（顽固bug主链）`() {
        val controller = Robolectric.buildService(ScreenAssistService::class.java)
        controller.create()
        val service = controller.get()
        val app = RuntimeEnvironment.getApplication()
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 复现真实冷启动路径：AssistActivity.onServiceConnected 无条件调这两个函数，
        // setSearchMode 尾部 postRender 在旧代码会直接建出悬浮窗
        service.applyOverlayTheme(OverlayTheme.DARK)
        service.setSearchMode(true)

        // postRender 是 mainHandler.post；顺带放水 ONNX loader 线程（加载失败回退的
        // setStatus→postRender 与主链共用同一 renderOverlay→ensureOverlay 守卫）
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(30)
        }
        shadowOf(Looper.getMainLooper()).idle()

        // shadowOf(WindowManager) 静态类型是父类 ShadowWindowManager，getViews 在 Impl 子类
        val views = (shadowOf(wm) as ShadowWindowManagerImpl).views
        assertTrue(
            "无录屏会话时不得新建悬浮窗（面板生命周期⊆录屏会话），实际窗口数=${views.size}",
            views.isEmpty(),
        )
        assertFalse("未授权录屏时不应处于采集中", service.isCapturing())
        controller.destroy()
    }
}
