package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎 info 行解析层的纯 JVM 单测（parseInfo / MutableAnalysisLine.toAnalysisLine /
 * AnalysisLine.scoreText，抽取自 AnalysisEngine——逻辑零改动，此处冻结行为契约）。
 *
 * 【雷区】score 视角语义：pikafish info 的 score 恒为"走子方视角"，红黑换算只发生在
 * toAnalysisLine(redGo)。这些用例锁定换算方向——改反了悬浮窗"红方优势/劣势"与
 * GOOD/BAD 色调会整体反转（原实现踩过的坑）。
 */
class AnalysisParseTest {

    private fun parse(line: String) = parseInfo(line.trim().split(Regex("\\s+")))

    @Test
    fun `标准 cp 行解析`() {
        val ml = parse("info depth 12 seldepth 14 multipv 1 score cp 260 pv h2e2 h9g8")
        assertNotNull(ml)
        ml!!
        assertEquals(1, ml.multiPv)
        assertEquals(12, ml.depth)
        assertEquals(260, ml.scoreCp)
        assertEquals(null, ml.mateIn)
        assertEquals(listOf("h2e2", "h9g8"), ml.pv)
    }

    @Test
    fun `mate 行 scoreCp 归零且带步数`() {
        val ml = parse("info depth 8 score mate 3 pv e4e5")!!
        assertEquals(3, ml.mateIn)
        assertEquals(0, ml.scoreCp)
    }

    @Test
    fun `multipv 2 行独立计数`() {
        val ml = parse("info depth 10 multipv 2 score cp -80 pv h9g8")!!
        assertEquals(2, ml.multiPv)
        assertEquals(-80, ml.scoreCp)
    }

    @Test
    fun `pv 过滤非4字符 token`() {
        // 引擎 info 的 pv 段后偶发 "(none)" 等垃圾 token：UCCI 着法恒 4 字符
        val ml = parse("info depth 5 score cp 30 pv h2e2 h9g8 (none) e6e5")!!
        assertEquals(listOf("h2e2", "h9g8", "e6e5"), ml.pv)
    }

    @Test
    fun `currmove 统计行截断且无 score 返回 null`() {
        assertNull(parse("info depth 12 currmove h2e2 currmovenumber 1"))
        assertNull(parse("info nodes 12345 nps 999"))
    }

    @Test
    fun `string 自述行返回 null`() {
        assertNull(parse("info string NNUE evaluation using pikafish.nnue"))
    }

    @Test
    fun `负数 cp 解析`() {
        val ml = parse("info depth 9 score cp -150 pv e6e5")
        assertEquals(-150, ml!!.scoreCp)
    }

    @Test
    fun `红走时红方视角不翻转`() {
        val ml = parse("info depth 12 score cp 260 pv h2e2")!!
        val line = ml.toAnalysisLine(redGo = true)
        assertEquals(260, line.redScoreCp)
    }

    @Test
    fun `黑走时红方视角取反`() {
        // 引擎给 +260 = 走子方（黑）优 → 红方视角应为 -260
        val ml = parse("info depth 12 score cp 260 pv h7g7")!!
        val line = ml.toAnalysisLine(redGo = false)
        assertEquals(-260, line.redScoreCp)
    }

    @Test
    fun `黑走时 mate 方向取反`() {
        // mate 3 = 走子方（黑）3 步将杀 → 红方视角 -3（红将被杀）
        val ml = parse("info depth 9 score mate 3 pv e6e5")!!
        val line = ml.toAnalysisLine(redGo = false)
        assertEquals(-3, line.mateIn)
    }

    @Test
    fun `scoreText 格式契约`() {
        assertEquals("+2.60", AnalysisLine(redScoreCp = 260).scoreText())
        assertEquals("-1.50", AnalysisLine(redScoreCp = -150).scoreText())
        assertEquals("M3", AnalysisLine(mateIn = 3).scoreText())
        assertEquals("-M2", AnalysisLine(mateIn = -2).scoreText())
        assertEquals("+0.00", AnalysisLine().scoreText())
    }

    @Test
    fun `bestUcci 优先 bestmove 回退首候选 pv`() {
        val withBest = AnalysisResult("fen", true, "h2e2",
            listOf(AnalysisLine(pv = listOf("b0c2"))))
        assertEquals("h2e2", withBest.bestUcci)
        val noBest = AnalysisResult("fen", true, null,
            listOf(AnalysisLine(pv = listOf("b0c2", "h9g8"))))
        assertEquals("b0c2", noBest.bestUcci)
        assertTrue(noBest.bestLine === noBest.lines.first())
    }
}
