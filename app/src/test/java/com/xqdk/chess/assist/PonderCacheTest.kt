package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 空闲预计算（β）命中缓存纯逻辑单测：预判线首着 applyUcci 套用到父局面后与
 * 识别确认局面全串相等即命中，取 pv[1..] 为我方建议链。纯 JVM（applyUcci 的
 * 走子/翻边语义已由 AssistCoreTest 覆盖，这里只测缓存选取逻辑）。
 */
class PonderCacheTest {

    /** 构造一条预判线（红方视角分数 30cp、默认深 20 = 常规搜索深度） */
    private fun line(pv: List<String>, depth: Int = 20) =
        AnalysisLine(1, depth, 30, null, pv)

    private fun result(fen: String, lines: List<AnalysisLine>) =
        AnalysisResult(fen, true, null, lines)

    @Test
    fun `首着套中局面即命中并返回预判线`() {
        val child = AssistBoard.applyUcci(AssistBoard.START_FEN, "h2e2")
        val c = PonderCache()
        c.feed(AssistBoard.START_FEN, result(AssistBoard.START_FEN, listOf(line(listOf("h2e2", "b0c2")))))
        val got = c.pick(child)
        assertEquals("b0c2", got?.pv?.get(1))
        assertEquals(20, got?.depth)
    }

    @Test
    fun `多预判线命中对应的那条`() {
        val l1 = line(listOf("b0c2", "h2e2"))
        val l2 = line(listOf("h2e2", "b0c2"))
        val child = AssistBoard.applyUcci(AssistBoard.START_FEN, "h2e2")
        val c = PonderCache()
        c.feed(AssistBoard.START_FEN, result(AssistBoard.START_FEN, listOf(l1, l2)))
        assertEquals(l2, c.pick(child))
    }

    @Test
    fun `未套中与短pv线与清空均返回null`() {
        val c = PonderCache()
        c.feed(AssistBoard.START_FEN, result(AssistBoard.START_FEN, listOf(
            line(listOf("h2e2", "b0c2")),
            line(listOf("b0c2"))))) // 短 pv 线（无我方应手）必须被跳过
        // 对方实际走了别的着 → 套不中
        assertNull(c.pick(AssistBoard.applyUcci(AssistBoard.START_FEN, "b0c2")))
        // 父局面自身（走子方未翻转）不可能构成命中
        assertNull(c.pick(AssistBoard.START_FEN))
        c.clear()
        assertNull(c.pick(AssistBoard.applyUcci(AssistBoard.START_FEN, "h2e2")))
    }

    @Test
    fun `再次feed覆盖旧缓存`() {
        val c = PonderCache()
        c.feed(AssistBoard.START_FEN, result(AssistBoard.START_FEN, listOf(line(listOf("h2e2", "b0c2")))))
        // 对局推进：新父局面（黑方已应着，轮红）
        val fen2 = AssistBoard.applyUcci(AssistBoard.START_FEN, "b0c2")
        c.feed(fen2, result(fen2, listOf(line(listOf("h2e2", "h2e6")))))
        // 旧缓存的命中路径已失效
        assertNull(c.pick(AssistBoard.applyUcci(AssistBoard.START_FEN, "h2e2")))
        assertEquals("h2e6", c.pick(AssistBoard.applyUcci(fen2, "h2e2"))?.pv?.get(1))
    }
}
