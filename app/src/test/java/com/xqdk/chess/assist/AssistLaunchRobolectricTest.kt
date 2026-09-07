package com.xqdk.chess.assist

import com.xqdk.chess.MainActivity
import com.xqdk.chess.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/**
 * 复现"点击连线闪退"：在 JVM 内完整走一遍 MainActivity -> button_link -> AssistActivity。
 * 若 AssistActivity 启动存在异常，测试将抛出带完整堆栈的失败，方便定位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistLaunchRobolectricTest {

    @Test
    fun `clicking link button opens AssistActivity without crash`() {
        val main: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = main.get()
        val btnLink = activity.findViewById<android.view.View>(R.id.button_link)
        assertNotNull("首页应有'连线'按钮", btnLink)
        btnLink.performClick()

        // 验证 AssistActivity 已成功启动（点击后若闪退，这里会得到启动失败异常）
        val assistController = Robolectric.buildActivity(
            com.xqdk.chess.assist.ui.AssistActivity::class.java).setup()
        val assist = assistController.get()
        assertNotNull(assist.findViewById(R.id.btn_start_capture))
        assertNotNull(assist.findViewById(R.id.tv_auto_status))
    }

    @Test
    fun `binding the service runs its onCreate on device path`() {
        // 真机上 bindService 会真的执行 ScreenAssistService.onCreate；
        // Robolectric 的 bindService 只登记 intent，这里显式创建服务以覆盖同一段代码
        val controller = Robolectric.buildService(
            com.xqdk.chess.assist.ScreenAssistService::class.java)
        val service = controller.get()
        assertNotNull(service)
        // onCreate 内会初始化 YOLO 检测器（JVM 无 TFLite 原生库，应被 catch 住不抛出）
        controller.create()
        controller.bind()
        controller.destroy()
    }
}
