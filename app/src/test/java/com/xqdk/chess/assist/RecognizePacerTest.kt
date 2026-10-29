package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别节拍器单测：成本自适应间隔、失败退避、成功恢复、硬上限。
 * 时间全部注入（markEnd/canRecognizeNow 的 now 参数），不依赖真实时钟。
 */
class RecognizePacerTest {

    @Test
    fun `首次调用放行`() {
        val p = RecognizePacer(minFloorMs = 1000)
        assertTrue(p.canRecognizeNow(now = 100_000))
    }

    @Test
    fun `识别后按间隔拦截`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 200, ok = true)
        // 间隔 = max(1000, 200*2) = 1000 → 10.5s 时仍被拦，11s 放行
        assertFalse(p.canRecognizeNow(now = 10_500))
        assertTrue(p.canRecognizeNow(now = 11_000))
    }

    @Test
    fun `成本自适应——贵帧拉长间隔`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 2000, ok = true)
        // 间隔 = max(1000, 2000*2) = 4000
        assertEquals(4000L, p.minIntervalMs())
        assertFalse(p.canRecognizeNow(now = 13_000))
        assertTrue(p.canRecognizeNow(now = 14_000))
    }

    @Test
    fun `成本自适应——快帧不低于下限`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 300, ok = true)
        assertEquals(1000L, p.minIntervalMs())
        // YOLO 下限 500：YOLO 推理更便宜（快帧 ~200ms 级），不被 ONNX 的 1000 下限拖累
        val y = RecognizePacer(minFloorMs = 500)
        y.markEnd(now = 10_000, costMs = 200, ok = true)
        assertEquals(500L, y.minIntervalMs())
    }

    @Test
    fun `失败退避指数拉长`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 200, ok = false)
        assertEquals(1500L, p.minIntervalMs())
        p.markEnd(now = 12_000, costMs = 200, ok = false)
        assertEquals(2250L, p.minIntervalMs())
        p.markEnd(now = 15_000, costMs = 200, ok = false)
        assertEquals(3375L, p.minIntervalMs())
    }

    @Test
    fun `退避有硬上限 8 秒`() {
        val p = RecognizePacer(minFloorMs = 1000)
        repeat(10) { p.markEnd(now = 0, costMs = 200, ok = false) }
        assertEquals(RecognizePacer.MAX_INTERVAL_MS, p.minIntervalMs())
    }

    @Test
    fun `成功识别恢复退避`() {
        val p = RecognizePacer(minFloorMs = 1000)
        repeat(3) { p.markEnd(now = 0, costMs = 200, ok = false) }
        assertTrue(p.minIntervalMs() > 1000)
        p.markEnd(now = 20_000, costMs = 200, ok = true)
        assertEquals(1000L, p.minIntervalMs())
        assertTrue(p.canRecognizeNow(now = 21_000))
    }

    @Test
    fun `reset 清退避并立即放行`() {
        val p = RecognizePacer(minFloorMs = 1000)
        repeat(5) { p.markEnd(now = 10_000, costMs = 4000, ok = false) }
        assertFalse(p.canRecognizeNow(now = 11_000))
        p.reset()
        assertEquals(1000L, p.minIntervalMs())
        assertTrue(p.canRecognizeNow(now = 10_001))
    }

    @Test
    fun `burst 请求后间隔收紧为成本x1_1`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 1000, ok = true) // 常规间隔 2000
        assertEquals(2000L, p.minIntervalMs())
        p.requestBurst()
        assertEquals(1100L, p.minIntervalMs())
        // 下限 300:快帧(~200ms 级)时不低于 300
        val y = RecognizePacer(minFloorMs = 500)
        y.requestBurst()
        assertEquals(RecognizePacer.BURST_FLOOR_MS, y.minIntervalMs())
    }

    @Test
    fun `burst 逐帧消耗耗尽后回归常规`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst(times = 2)
        p.markEnd(now = 10_000, costMs = 1000, ok = true)
        assertEquals(1100L, p.minIntervalMs())
        p.markEnd(now = 11_100, costMs = 1000, ok = true)
        assertEquals(2000L, p.minIntervalMs()) // 已耗尽,回常规 cost*2
    }

    @Test
    fun `burst 期间失败退避仍然生效`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst()
        p.markEnd(now = 10_000, costMs = 1000, ok = false)
        assertEquals(1650L, p.minIntervalMs()) // 1100 × 1.5
    }

    @Test
    fun `对局中瞬时失败不退避`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 10_000, costMs = 400, ok = false, transient = true)
        assertEquals(1000L, p.minIntervalMs()) // 退避系数保持 1,间隔 = max(1000, 800)
        p.markEnd(now = 11_000, costMs = 400, ok = false, transient = true)
        assertEquals(1000L, p.minIntervalMs()) // 连续瞬时失败也不指数拉长
    }

    @Test
    fun `对局中瞬时失败不消耗 burst`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst(times = 2)
        p.markEnd(now = 10_000, costMs = 400, ok = false, transient = true)
        p.markEnd(now = 10_400, costMs = 400, ok = false, transient = true)
        // 两次过渡帧失败后预算仍在:成功帧继续享受 cost×1.1 快节奏
        p.markEnd(now = 10_800, costMs = 400, ok = true)
        assertEquals(440L, p.minIntervalMs())
        p.markEnd(now = 11_240, costMs = 400, ok = true)
        assertEquals(1000L, p.minIntervalMs()) // 第二次成功耗尽 burst,回常规
    }

    @Test
    fun `burst 重复请求取最大次数`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst(times = 2)
        p.markEnd(now = 0, costMs = 0, ok = true) // 剩 1,仍 burst(间隔 300)
        assertEquals(RecognizePacer.BURST_FLOOR_MS, p.minIntervalMs())
        p.requestBurst(times = 4) // max(1,4)=4,不叠加
        repeat(4) { p.markEnd(now = 0, costMs = 0, ok = true) } // 剩 0,回常规
        assertEquals(1000L, p.minIntervalMs()) // cost=0 → max(1000, 0)=1000
    }

    @Test
    fun `reset 清 burst`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst()
        p.markEnd(now = 10_000, costMs = 1000, ok = true)
        assertTrue(p.minIntervalMs() < 2000L) // burst 期 1100
        p.reset()
        assertEquals(1000L, p.minIntervalMs()) // 清历史成本,回 floor
        assertFalse(p.isBursting())
    }

    @Test
    fun `清退避保留成本与 burst`() {
        val p = RecognizePacer(minFloorMs = 1000)
        repeat(4) { p.markEnd(now = 0, costMs = 2000, ok = false) } // 退避 1.5^4 → 顶到 8s 上限
        assertEquals(RecognizePacer.MAX_INTERVAL_MS, p.minIntervalMs())
        p.requestBurst(times = 3)
        p.clearBackoff() // 切页级大变化：只清退避
        // 间隔 = max(300, 2000×1.1) = 2200（退避不再放大），burst 仍在
        assertEquals(2200L, p.minIntervalMs())
        assertTrue(p.isBursting())
    }

    @Test
    fun `describe 常规态`() {
        val p = RecognizePacer(minFloorMs = 1000)
        assertEquals("常规", p.describe())
    }

    @Test
    fun `describe 退避态`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 0, costMs = 200, ok = false)
        assertEquals("退避×1.5", p.describe())
    }

    @Test
    fun `describe burst 与退避叠加`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.requestBurst(times = 2)
        p.markEnd(now = 0, costMs = 200, ok = false, transient = false)
        // burst 余 1（消耗一次）+ 退避 1.5：两段信息都在
        assertEquals("burst余1 退避×1.5", p.describe())
    }

    @Test
    fun `describe 成功后退避清零`() {
        val p = RecognizePacer(minFloorMs = 1000)
        p.markEnd(now = 0, costMs = 200, ok = false)
        p.markEnd(now = 2000, costMs = 200, ok = true)
        assertEquals("常规", p.describe())
    }
}
