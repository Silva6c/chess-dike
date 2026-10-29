package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 诊断汇总统计的纯函数单测：直方图→百分位换算（DiagLogWriter 的 P50/P95 数据源）。
 * 纯 JVM 计算，无 Android 依赖。
 */
class DiagLogWriterStatsTest {

    /** 桶宽 100ms（DiagLogWriter.HIST_BUCKET_MS 同值；桶中值 = i*100+50） */
    private fun histOf(vararg counts: Int) = intArrayOf(*counts)

    @Test
    fun `空直方图返回 0`() {
        assertEquals(0, DiagLogWriter.percentileFromHist(histOf(0, 0, 0), 0.5))
    }

    @Test
    fun `单桶直方图取桶中值`() {
        // 1 个样本落在桶 0（0-100ms）→ 任何百分位都是桶中值 50
        assertEquals(50, DiagLogWriter.percentileFromHist(histOf(1, 0, 0), 0.5))
        assertEquals(50, DiagLogWriter.percentileFromHist(histOf(1, 0, 0), 0.95))
    }

    @Test
    fun `P50 落在中间桶`() {
        // 样本分布：桶 0×1、桶 1×1、桶 2×1 → 总 3，P50 目标 = ceil(1.5) = 2 → 桶 1 → 150
        assertEquals(150, DiagLogWriter.percentileFromHist(histOf(1, 1, 1), 0.5))
    }

    @Test
    fun `P95 向上取整不丢头部`() {
        // 20 个样本全在桶 0 和桶 1（19 个桶 0 + 1 个桶 1）：
        // P95 目标 = ceil(19) = 19 → 前缀和桶 0 = 19 ≥ 19 → 桶 0 → 50
        assertEquals(50, DiagLogWriter.percentileFromHist(histOf(19, 1), 0.95))
        // P50 目标 = ceil(10) = 10 → 桶 0 → 50
        assertEquals(50, DiagLogWriter.percentileFromHist(histOf(19, 1), 0.5))
        // P100 目标 = 20 → 桶 1 → 150
        assertEquals(150, DiagLogWriter.percentileFromHist(histOf(19, 1), 1.0))
    }

    @Test
    fun `溢出桶封顶`() {
        // 31 桶结构：桶 30 为 ≥3000ms 溢出桶，中值 3050（下界近似）
        val hist = IntArray(31)
        hist[30] = 4
        assertEquals(3050, DiagLogWriter.percentileFromHist(hist, 0.5))
    }
}
