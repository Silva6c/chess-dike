package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * OnnxGeom 纯 JVM 几何/解码单测：矩阵求解、重映射采样、SimCC 与分类输出解码。
 */
class OnnxGeomTest {

    private fun assertClose(a: Double, b: Double, eps: Double = 1e-6) =
        assertTrue("expect $a ≈ $b", abs(a - b) < eps)

    // ==================== 仿射 ====================

    @Test
    fun `仿射 恒等点集解出单位阵`() {
        val src = floatArrayOf(0f, 0f, 100f, 0f, 0f, 200f)
        val m = OnnxGeom.solveAffine(src, src.clone())
        assertClose(m[0].toDouble(), 1.0); assertClose(m[1].toDouble(), 0.0)
        assertClose(m[3].toDouble(), 0.0); assertClose(m[4].toDouble(), 1.0)
        assertClose(m[2].toDouble(), 0.0); assertClose(m[5].toDouble(), 0.0)
    }

    @Test
    fun `仿射 逆变换往返还原`() {
        // 任取一个非奇异仿射：缩放 0.5 + 平移
        val m = floatArrayOf(0.5f, 0f, 13f, 0f, 0.25f, -7f)
        val inv = OnnxGeom.invertAffine(m)
        val p = floatArrayOf(123f, -45f)
        val q = OnnxGeom.applyAffine(m, p[0], p[1])
        val back = OnnxGeom.applyAffine(inv, q[0], q[1])
        assertClose(back[0].toDouble(), p[0].toDouble(), 1e-3)
        assertClose(back[1].toDouble(), p[1].toDouble(), 1e-3)
    }

    // ==================== 单应 ====================

    @Test
    fun `单应 恒等点集解出单位阵`() {
        val src = floatArrayOf(0f, 0f, 100f, 0f, 0f, 200f, 100f, 200f)
        val h = OnnxGeom.solveHomography(src, src.clone())
        assertClose(h[0], 1.0); assertClose(h[4], 1.0); assertClose(h[8], 1.0)
        assertClose(h[1], 0.0); assertClose(h[3], 0.0)
    }

    @Test
    fun `单应 逆变换往返还原`() {
        // 梯形→矩形 的透视变换（模拟棋盘校正）再求逆往返
        val src = floatArrayOf(10f, 20f, 500f, 30f, 40f, 600f, 520f, 610f)
        val dst = floatArrayOf(50f, 50f, 400f, 50f, 50f, 450f, 400f, 450f)
        val h = OnnxGeom.solveHomography(src, dst)
        val inv = OnnxGeom.invertHomography(h)
        // 任取 src 中一点：经 h 到 dst 再经 inv 回 src
        val x = 300.0; val y = 400.0
        fun apply(hh: DoubleArray, px: Double, py: Double): DoubleArray {
            val d = hh[6] * px + hh[7] * py + hh[8]
            return doubleArrayOf((hh[0] * px + hh[1] * py + hh[2]) / d,
                (hh[3] * px + hh[4] * py + hh[5]) / d)
        }
        val q = apply(h, x, y)
        val back = apply(inv, q[0], q[1])
        assertClose(back[0], x, 1e-6)
        assertClose(back[1], y, 1e-6)
    }

    // ==================== 重映射采样 ====================

    @Test
    fun `warpAffine 恒等矩阵图像不变`() {
        val w = 8; val h = 8
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 3) shl 16) or ((i * 7) shl 8) or (i * 11 and 0xFF) }
        val out = OnnxGeom.warpAffine(px, w, h, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), w, h)
        for (i in px.indices) assertEquals("i=$i", px[i], out[i])
    }

    @Test
    fun `warpAffine 平移后内部区域颜色不变 越界补黑`() {
        val w = 8; val h = 8
        val red = 0xFFFF0000.toInt()
        val px = IntArray(w * h) { red }
        // forward: dst(x,y) = src(x-2, y-1)，输出 (3,1) 处应采样源 (1,0)=红；输出 (1,0) 越界=黑
        val out = OnnxGeom.warpAffine(px, w, h, floatArrayOf(1f, 0f, 2f, 0f, 1f, 1f), w, h)
        assertEquals(red, out[1 * w + 3])
        // 越界补黑：alpha 强制不透明、RGB 为 0（与 OpenCV 常数边 RGB 语义一致，下游只读 RGB）
        assertEquals(0, out[0 * w + 1] and 0xFFFFFF)
        assertEquals(0, out[0] and 0xFFFFFF) // (0,0) 源坐标 (-2,-1) 越界
    }

    @Test
    fun `resizeBilinear 2x2 到 1x1 取平均色`() {
        // 四角 R 值 0/10/20/30 → 中心均值 15（双线性权重各 0.25）
        val px = intArrayOf(
            0xFF000000.toInt() or (0 shl 16),
            0xFF000000.toInt() or (10 shl 16),
            0xFF000000.toInt() or (20 shl 16),
            0xFF000000.toInt() or (30 shl 16))
        val out = OnnxGeom.resizeBilinear(px, 2, 2, 1, 1)
        val r = out[0] shr 16 and 0xFF
        assertTrue("R=$r 应在 13..17", r in 13..17)
    }

    // ==================== 归一化 ====================

    @Test
    fun `normalizeChw 三平面拆分并归一化`() {
        val p = 0xFF000000.toInt() or (200 shl 16) or (100 shl 8) or 50
        val out = OnnxGeom.normalizeChw(intArrayOf(p))
        assertClose(out[0].toDouble(), ((200 - 123.675f) / 58.395f).toDouble(), 1e-4)
        assertClose(out[1].toDouble(), ((100 - 116.28f) / 57.12f).toDouble(), 1e-4)
        assertClose(out[2].toDouble(), ((50 - 103.53f) / 57.375f).toDouble(), 1e-4)
    }

    // ==================== SimCC 解码 ====================

    @Test
    fun `simcc 峰值位置解码为像素坐标`() {
        val half = 512
        val x = FloatArray(4 * half); val y = FloatArray(4 * half)
        x[100] = 5f; y[203] = 7f       // 关键点 0：峰值 (100, 203) → (50.0, 101.5)
        x[4 * 128 + 256 - 128 + 128] = 1f // 关键点 1：索引 256 → 128.0
        y[2 * half + 0] = 1f           // 关键点 2：索引 0 → 0.0
        val kps = OnnxGeom.simccKeypoints(x, y, 4, 256)
        assertClose(kps[0].toDouble(), 50.0)
        assertClose(kps[1].toDouble(), 101.5)
        assertClose(kps[2].toDouble(), 128.0)
        assertClose(kps[5].toDouble(), 0.0)
    }

    // ==================== 分类解码 ====================

    @Test
    fun `类别映射覆盖红黑十四子与空`() {
        assertEquals(Piece.WSHUAI, OnnxGeom.pieceFor(2))   // K
        assertEquals(Piece.WBING, OnnxGeom.pieceFor(8))    // P
        assertEquals(Piece.BJIANG, OnnxGeom.pieceFor(9))   // k
        assertEquals(Piece.BZU, OnnxGeom.pieceFor(15))     // p
        assertEquals(Piece.EMPTY, OnnxGeom.pieceFor(0))    // '.'
        assertEquals(Piece.EMPTY, OnnxGeom.pieceFor(1))    // 'x'
    }

    @Test
    fun `decodeCells 按 row×9+col 落格并统计平均 logit`() {
        val logits = FloatArray(90 * 16)
        // 开局：黑方底线 row0 放 rnbakabnr，红方底线 row9 放 RNBAKABNR，其余空
        val blackRow = charArrayOf('r', 'n', 'b', 'a', 'k', 'a', 'b', 'n', 'r')
        val redRow = charArrayOf('R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R')
        fun put(gy: Int, gx: Int, ch: Char) {
            val cls = OnnxGeom.CLASS_CHARS.indexOf(ch)
            logits[(gy * 9 + gx) * 16 + cls] = 4.5f
        }
        for (gx in 0 until 9) { put(0, gx, blackRow[gx]); put(9, gx, redRow[gx]) }
        val (raw, avg, pieces) = OnnxGeom.decodeCells(logits)
        assertEquals(18, pieces)
        assertEquals(Piece.BJU, raw[0][0])      // row0 左上 = 黑车
        assertEquals(Piece.BJIANG, raw[0][4])   // row0 中央 = 黑将
        assertEquals(Piece.WJU, raw[9][0])      // row9 左下 = 红车
        assertEquals(Piece.WSHUAI, raw[9][4])   // row9 中央 = 红帅
        assertClose(avg, 4.5, 1e-4)
    }

    @Test
    fun `decodeCells 全空返回零子零均值`() {
        val (raw, avg, pieces) = OnnxGeom.decodeCells(FloatArray(90 * 16))
        assertEquals(0, pieces)
        assertEquals(0.0, avg, 1e-9)
        for (row in raw) for (p in row) assertEquals(Piece.EMPTY, p)
    }
}
