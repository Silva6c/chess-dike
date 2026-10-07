package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import kotlin.math.floor

/**
 * ONNX 识别管线的纯 JVM 几何与解码工具（无 Android 依赖，可单测）。
 *
 * 坐标与矩阵约定与 OpenCV 对齐（移植自 chinese-chess-helper 的 OnnxRecognizer）：
 * - 仿射矩阵 2×3 按行存 [a b c; d e f]，正向语义 dst = M·src；
 * - 单应矩阵 3×3 按行存 9 元素，最后一元素固定为 1；
 * - warp 采样一律用"输出像素经逆矩阵找源坐标"（OpenCV warpAffine/warpPerspective 默认行为）；
 * - resize 用 OpenCV INTER_LINEAR 的中心对齐语义 src = (dst+0.5)·scale − 0.5。
 */
object OnnxGeom {

    /** 模型 16 类输出顺序：空、未知、红七子（大写）、黑七子（小写） */
    val CLASS_CHARS = charArrayOf('.', 'x', 'K', 'A', 'B', 'N', 'R', 'C', 'P', 'k', 'a', 'b', 'n', 'r', 'c', 'p')

    // ==================== 仿射（2×3） ====================

    /**
     * 由 3 对点解 2×3 仿射（精确解）：src/dst 各 6 元素 [x0,y0,x1,y1,x2,y2]。
     * 与 OpenCV getAffineTransform(src, dst) 等价。
     */
    fun solveAffine(src: FloatArray, dst: FloatArray): FloatArray {
        // 对 x' = a·x + b·y + c 与 y' = d·x + e·y + f 各解一个 3×3 线性方程组，
        // 系数矩阵相同，克莱姆法则一次求出
        val x0 = src[0]; val y0 = src[1]; val x1 = src[2]; val y1 = src[3]; val x2 = src[4]; val y2 = src[5]
        val det = x0 * (y1 - y2) - x1 * (y0 - y2) + x2 * (y0 - y1)
        require(det != 0f) { "仿射求解：三点共线" }
        // 分子按克莱姆法则展开（把 dst 的 u 或 v 代入右端列）
        fun solveCramer(u0: Float, u1: Float, u2: Float): FloatArray {
            val a = (u0 * (y1 - y2) - u1 * (y0 - y2) + u2 * (y0 - y1)) / det
            val b = (x0 * (u1 - u2) - x1 * (u0 - u2) + x2 * (u0 - u1)) / det
            val c = (x0 * y1 * u2 - x0 * y2 * u1 - x1 * y0 * u2 + x1 * y2 * u0 + x2 * y0 * u1 - x2 * y1 * u0) / det
            return floatArrayOf(a, b, c)
        }
        val r0 = solveCramer(dst[0], dst[2], dst[4])
        val r1 = solveCramer(dst[1], dst[3], dst[5])
        return floatArrayOf(r0[0], r0[1], r0[2], r1[0], r1[1], r1[2])
    }

    /** 2×3 仿射求逆（要求可逆：det ≠ 0） */
    fun invertAffine(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]
        val det = a * e - b * d
        require(det != 0f) { "仿射不可逆" }
        val ia = e / det
        val ib = -b / det
        val ic = (b * f - c * e) / det
        val id = -d / det
        val ie = a / det
        val ifv = (c * d - a * f) / det
        return floatArrayOf(ia, ib, ic, id, ie, ifv)
    }

    /** 对点 (x,y) 施加 2×3 仿射，返回 (x', y') */
    fun applyAffine(m: FloatArray, x: Float, y: Float): FloatArray =
        floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    // ==================== 单应（3×3） ====================

    /**
     * 由 4 对点解单应（DLT，与 OpenCV getPerspectiveTransform 等价）：
     * src/dst 各 8 元素 [x0,y0,...,x3,y3]，返回 9 元素（h8 固定 1）。
     */
    fun solveHomography(src: FloatArray, dst: FloatArray): DoubleArray {
        // 每对点贡献两行：u 行与 v 行，未知数 [a b c d e f g h]
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble(); val y = src[2 * i + 1].toDouble()
            val u = dst[2 * i].toDouble(); val v = dst[2 * i + 1].toDouble()
            val r = 2 * i
            a[r][0] = x; a[r][1] = y; a[r][2] = 1.0; a[r][6] = -u * x; a[r][7] = -u * y
            b[r] = u
            a[r + 1][3] = x; a[r + 1][4] = y; a[r + 1][5] = 1.0; a[r + 1][6] = -v * x; a[r + 1][7] = -v * y
            b[r + 1] = v
        }
        val xs = gaussSolve(a, b)
        require(xs != null) { "单应求解：方程组奇异" }
        return doubleArrayOf(xs[0], xs[1], xs[2], xs[3], xs[4], xs[5], xs[6], xs[7], 1.0)
    }

    /** 3×3 单应求逆（伴随/行列式法） */
    fun invertHomography(h: DoubleArray): DoubleArray {
        val h0 = h[0]; val h1 = h[1]; val h2 = h[2]
        val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
        val h6 = h[6]; val h7 = h[7]; val h8 = h[8]
        val c0 = h4 * h8 - h5 * h7
        val c1 = h5 * h6 - h3 * h8
        val c2 = h3 * h7 - h4 * h6
        val det = h0 * c0 + h1 * c1 + h2 * c2
        require(det != 0.0) { "单应不可逆" }
        return doubleArrayOf(
            c0 / det, (h2 * h7 - h1 * h8) / det, (h1 * h5 - h2 * h4) / det,
            c1 / det, (h0 * h8 - h2 * h6) / det, (h2 * h3 - h0 * h5) / det,
            c2 / det, (h1 * h6 - h0 * h7) / det, (h0 * h4 - h1 * h3) / det,
        )
    }

    /** 高斯消元解 8×8 线性方程组（部分主元），奇异返回 null */
    private fun gaussSolve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].clone() }
        val b = b0.clone()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
            if (kotlin.math.abs(a[piv][col]) < 1e-12) return null
            if (piv != col) { a[col] = a[piv].also { a[piv] = a[col] }; val t = b[col]; b[col] = b[piv]; b[piv] = t }
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c2 in col until n) a[r][c2] -= f * a[col][c2]
                b[r] -= f * b[col]
            }
        }
        return DoubleArray(n) { b[it] / a[it][it] }
    }

    // ==================== 像素采样与重映射 ====================

    /**
     * ARGB 双线性采样，越界邻居按常数黑（0）参与插值——与 OpenCV warp 的 BORDER_CONSTANT 一致：
     * 权重为 0 的越界邻居不产生黑边（恒等变换边缘不黑边），权重 >0 时等价补黑。
     */
    fun sampleBilinearZero(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val x1 = x0 + 1; val y1 = y0 + 1
        fun at(xi: Int, yi: Int): Int =
            if (xi < 0 || yi < 0 || xi >= w || yi >= h) 0 else px[yi * w + xi]
        return bilerp(at(x0, y0), at(x1, y0), at(x0, y1), at(x1, y1), fx, fy)
    }

    /** ARGB 双线性采样，越界按边界复制（等价 OpenCV resize 的 INTER_LINEAR 边界） */
    fun sampleBilinearClamp(px: IntArray, w: Int, h: Int, xf: Float, yf: Float): Int {
        val x = xf.coerceIn(0f, (w - 1).toFloat())
        val y = yf.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val x1 = kotlin.math.min(x0 + 1, w - 1); val y1 = kotlin.math.min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        return bilerp(px[y0 * w + x0], px[y0 * w + x1], px[y1 * w + x0], px[y1 * w + x1], fx, fy)
    }

    private fun bilerp(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
        val w00 = (1f - fx) * (1f - fy); val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy; val w11 = fx * fy
        val r = (w00 * (p00 shr 16 and 0xFF) + w10 * (p10 shr 16 and 0xFF) +
            w01 * (p01 shr 16 and 0xFF) + w11 * (p11 shr 16 and 0xFF)).toInt()
        val g = (w00 * (p00 shr 8 and 0xFF) + w10 * (p10 shr 8 and 0xFF) +
            w01 * (p01 shr 8 and 0xFF) + w11 * (p11 shr 8 and 0xFF)).toInt()
        val b = (w00 * (p00 and 0xFF) + w10 * (p10 and 0xFF) +
            w01 * (p01 and 0xFF) + w11 * (p11 and 0xFF)).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /**
     * 仿射重映射：m6 为 forward（源→输出）矩阵，对每个输出像素用逆矩阵找源坐标采样，
     * 越界补黑——语义与 OpenCV warpAffine(src, dst, M)（默认 flags）一致。
     */
    fun warpAffine(px: IntArray, w: Int, h: Int, m6: FloatArray, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        warpAffineInto(px, w, h, m6, outW, outH, out)
        return out
    }

    /** 复用输出缓冲版 [warpAffine]：识别热路径用，dst 长度须为 outW*outH */
    fun warpAffineInto(px: IntArray, w: Int, h: Int, m6: FloatArray, outW: Int, outH: Int, dst: IntArray) {
        val inv = invertAffine(m6)
        var o = 0
        for (dy in 0 until outH) {
            // 增量计算：x' = ia·dx + (ib·dy + ic)，dx 每步加 ia
            var sx = inv[0] * 0f + inv[1] * dy + inv[2]
            var sy = inv[3] * 0f + inv[4] * dy + inv[5]
            for (dx in 0 until outW) {
                dst[o++] = sampleBilinearZero(px, w, h, sx, sy)
                sx += inv[0]; sy += inv[3]
            }
        }
    }

    /** 透视重映射：h33 为 forward（源→输出）单应，语义与 OpenCV warpPerspective 一致 */
    fun warpPerspective(px: IntArray, w: Int, h: Int, h33: DoubleArray, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        warpPerspectiveInto(px, w, h, h33, outW, outH, out)
        return out
    }

    /** 复用输出缓冲版 [warpPerspective]：识别热路径用，dst 长度须为 outW*outH */
    fun warpPerspectiveInto(px: IntArray, w: Int, h: Int, h33: DoubleArray, outW: Int, outH: Int, dst: IntArray) {
        val inv = invertHomography(h33)
        var o = 0
        for (dy in 0 until outH) {
            for (dx in 0 until outW) {
                val den = inv[6] * dx + inv[7] * dy + inv[8]
                val sx = ((inv[0] * dx + inv[1] * dy + inv[2]) / den).toFloat()
                val sy = ((inv[3] * dx + inv[4] * dy + inv[5]) / den).toFloat()
                dst[o++] = sampleBilinearZero(px, w, h, sx, sy)
            }
        }
    }

    /** 双线性缩放（中心对齐 + 边界复制，等价 OpenCV resize INTER_LINEAR） */
    fun resizeBilinear(px: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        resizeBilinearInto(px, sw, sh, dw, dh, out)
        return out
    }

    /** 复用输出缓冲版 [resizeBilinear]：识别热路径用，dst 长度须为 dw*dh */
    fun resizeBilinearInto(px: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, dst: IntArray) {
        val sxScale = sw.toDouble() / dw
        val syScale = sh.toDouble() / dh
        for (dy in 0 until dh) {
            val sy = ((dy + 0.5) * syScale - 0.5).toFloat()
            for (dx in 0 until dw) {
                val sx = ((dx + 0.5) * sxScale - 0.5).toFloat()
                dst[dy * dw + dx] = sampleBilinearClamp(px, sw, sh, sx, sy)
            }
        }
    }

    // ==================== 模型输入归一化 ====================

    // ImageNet 均值/方差（与参考实现逐项一致）
    val IMAGENET_MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    val IMAGENET_STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    /** ARGB 像素 → RGB 三平面 CHW FloatArray，逐通道 (p − mean) / std */
    fun normalizeChw(px: IntArray): FloatArray {
        val out = FloatArray(3 * px.size)
        normalizeChwInto(px, out)
        return out
    }

    /** 复用输出缓冲版 [normalizeChw]：识别热路径用，dst 长度须为 3*px.size */
    fun normalizeChwInto(px: IntArray, dst: FloatArray) {
        val total = px.size
        val mr = IMAGENET_MEAN[0]; val sr = IMAGENET_STD[0]
        val mg = IMAGENET_MEAN[1]; val sg = IMAGENET_STD[1]
        val mb = IMAGENET_MEAN[2]; val sb = IMAGENET_STD[2]
        for (i in 0 until total) {
            val p = px[i]
            dst[i] = ((p shr 16 and 0xFF) - mr) / sr
            dst[total + i] = ((p shr 8 and 0xFF) - mg) / sg
            dst[2 * total + i] = ((p and 0xFF) - mb) / sb
        }
    }

    // ==================== SimCC 角点解码 ====================

    /**
     * RTMPose SimCC 输出解码：对每个关键点在 x/y 分布上取 argmax，
     * 坐标 = argmax / (inputSize×2) × inputSize（即 argmax/2 像素）。
     * simccX/simccY 形状 [k, 512]（展平），返回 [k] 组 (x, y) 像素坐标（warp 空间）。
     */
    fun simccKeypoints(simccX: FloatArray, simccY: FloatArray, k: Int, inputSize: Int): FloatArray {
        val out = FloatArray(2 * k)
        val half = inputSize * 2
        for (i in 0 until k) {
            var bx = 0; var bxv = simccX[i * half]
            var by = 0; var byv = simccY[i * half]
            for (j in 1 until half) {
                if (simccX[i * half + j] > bxv) { bxv = simccX[i * half + j]; bx = j }
                if (simccY[i * half + j] > byv) { byv = simccY[i * half + j]; by = j }
            }
            out[2 * i] = bx.toFloat() / half * inputSize
            out[2 * i + 1] = by.toFloat() / half * inputSize
        }
        return out
    }

    // ==================== 分类输出解码 ====================

    /** 模型类别号 → Piece 常量（'.'/'x' 均视为空格；'x' 为未知图案，具体子种不可知） */
    fun pieceFor(cls: Int): Int = when (CLASS_CHARS.getOrElse(cls) { '.' }) {
        'K' -> Piece.WSHUAI; 'A' -> Piece.WSHI; 'B' -> Piece.WXIANG; 'N' -> Piece.WMA
        'R' -> Piece.WJU; 'C' -> Piece.WPAO; 'P' -> Piece.WBING
        'k' -> Piece.BJIANG; 'a' -> Piece.BSHI; 'b' -> Piece.BXIANG; 'n' -> Piece.BMA
        'r' -> Piece.BJU; 'c' -> Piece.BPAO; 'p' -> Piece.BZU
        else -> Piece.EMPTY
    }

    /**
     * 分类输出 [90×16] logits → 屏幕 raw 布局 [gy][gx]（gy=0 为屏幕顶部）。
     * 返回 (raw, 有子格平均 logit, 有子数)。置信度沿用参考实现：取最大 logit，不做 softmax。
     */
    fun decodeCells(logits: FloatArray): Triple<Array<IntArray>, Double, Int> {
        val raw = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        var sum = 0.0
        var pieces = 0
        for (i in 0 until 90) {
            val base = i * 16
            var best = 0; var bestV = logits[base]
            for (j in 1 until 16) {
                if (logits[base + j] > bestV) { bestV = logits[base + j]; best = j }
            }
            val gy = i / 9; val gx = i % 9
            val p = pieceFor(best)
            raw[gy][gx] = p
            if (p != Piece.EMPTY) { sum += bestV; pieces++ }
        }
        return Triple(raw, if (pieces > 0) sum / pieces else 0.0, pieces)
    }
}
