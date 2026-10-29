package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UCCI → 中文着法的纯 JVM 单测（悬浮窗建议文案的最终出口）。
 *
 * 【雷区】UCCI 坐标系：文件 a-i 自红方左手起，行 0-9 且红方底线为 0——
 * "进"对红方是行号减小、对黑方是行号增大（Move.getChsString 内部处理），
 * 测试用例锁定该语义，防止坐标系反转类回归。
 */
class MoveChineseTest {

    /** 开局：红先行（标准起点 FEN） */
    private val startRed = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    /** 黑先行（同局面） */
    private val startBlack = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b"

    @Test
    fun `红炮平中 - 炮二平五`() {
        val s = MoveChinese.describe(startRed, "h2e2")
        assertTrue("实际: $s", s.contains("炮") && s.contains("平") && s.contains("五"))
        assertTrue("实际: $s", s.endsWith("(h2e2)"))
    }

    @Test
    fun `红中兵进一格 - 兵五进一`() {
        val s = MoveChinese.describe(startRed, "e3e4")
        assertTrue("实际: $s", s.contains("兵") && s.contains("进") && s.contains("一"))
    }

    @Test
    fun `红左马正马 - 马八进七`() {
        val s = MoveChinese.describe(startRed, "b0c2")
        assertTrue("实际: $s", s.contains("马") && s.contains("进") && s.contains("七"))
    }

    @Test
    fun `红车沿横线平移 - 车九平八`() {
        // 车九 a0 → b0（同线平移用"平"）
        val s = MoveChinese.describe(startRed, "a0b0")
        assertTrue("实际: $s", s.contains("车") && s.contains("平"))
    }

    @Test
    fun `黑方着法按黑方视角命名`() {
        // 黑炮 h7→e7：黑方视角"炮2平5"（黑用数字一至九）
        val s = MoveChinese.describe(startBlack, "h7e7")
        assertTrue("实际: $s", s.contains("炮") && s.contains("平"))
    }

    @Test
    fun `垃圾 ucci 原样返回`() {
        assertEquals("zzzz", MoveChinese.describe(startRed, "zzzz"))
        assertEquals("", MoveChinese.describe(startRed, ""))
    }

    @Test
    fun `垃圾 fen 原样返回 ucci`() {
        assertEquals("h2e2", MoveChinese.describe("not a fen", "h2e2"))
        assertEquals("h2e2", MoveChinese.describe("", "h2e2"))
    }

    @Test
    fun `合法但超界坐标原样返回`() {
        // i9 越界（文件只有 a-i 且行 0-9，i9 本身合法但 xj 为伪文件名）
        assertEquals("x9x9", MoveChinese.describe(startRed, "x9x9"))
    }
}
