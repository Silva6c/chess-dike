package com.xqdk.chess.assist

import com.xqdk.chess.assist.ui.AssistActivity
import com.xqdk.chess.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复现"启动闪退"回归：launcher 已直指 AssistActivity（2026-10-29 砍主页跳转层，
 * 原 MainActivity → button_link 中间层移除），在 JVM 内完整启动连线页验证不闪退；
 * 主按钮/状态行/帮助与关于入口的存在性一并覆盖。若启动链路存在异常，
 * 测试将抛出带完整堆栈的失败，方便定位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistLaunchRobolectricTest {

    @Test
    fun `launcher AssistActivity starts without crash`() {
        val assist = Robolectric.buildActivity(AssistActivity::class.java).setup().get()
        assertNotNull("连线页应有主按钮", assist.findViewById(R.id.btn_start_capture))
        assertNotNull("连线页应有状态行", assist.findViewById(R.id.tv_auto_status))
        assertNotNull("连线页应有帮助入口", assist.findViewById(R.id.button_help))
        assertNotNull("连线页应有关于入口", assist.findViewById(R.id.button_about))
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
