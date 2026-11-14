package com.xqdk.chess.learn

import android.content.Context
import com.xqdk.chess.LauncherActivity
import com.xqdk.chess.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 启动冒烟：开局分流落地页（新 launcher）与学习模式页在 JVM 内完整启动不闪退，
 * 关键控件存在；学习页进页即回填"轮到你走"初始状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearnLaunchRobolectricTest {

    @Test
    fun `落地页启动不闪退且控件在位`() {
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        assertNotNull("应有学习模式卡", launcher.findViewById(R.id.card_learn))
        assertNotNull("应有连线模式卡", launcher.findViewById(R.id.card_assist))
        assertNotNull("应有帮助入口", launcher.findViewById(R.id.button_help))
        assertNotNull("应有关于入口", launcher.findViewById(R.id.button_about))
    }

    @Test
    fun `学习页启动不闪退且回填初始状态`() {
        val learn = Robolectric.buildActivity(LearnActivity::class.java).setup().get()
        assertNotNull("应有对局棋盘", learn.findViewById(R.id.learn_board))
        assertNotNull("应有走势曲线", learn.findViewById(R.id.trend_chart))
        val tvState = learn.findViewById<android.widget.TextView>(R.id.tv_learn_state)
        assertEquals("轮到你走", tvState.text.toString())
        val btnSide = learn.findViewById<android.widget.Button>(R.id.btn_side)
        assertEquals("执红", btnSide.text.toString())
    }

    @Test
    fun `执黑持久化回放`() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("assist_settings", Context.MODE_PRIVATE)
            .edit().putString("learn_side", "black").commit()
        try {
            val learn = Robolectric.buildActivity(LearnActivity::class.java).setup().get()
            val btnSide = learn.findViewById<android.widget.Button>(R.id.btn_side)
            assertEquals("执黑", btnSide.text.toString())
        } finally {
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("assist_settings", Context.MODE_PRIVATE)
                .edit().remove("learn_side").commit()
        }
    }
}
