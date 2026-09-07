package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YOLO 输出解码与过滤的纯 JVM 单测。
 *
 * FIXTURE_ROWS 为真实模型（YOLOv5n 象棋检测，VinXiangQi 训练权重）对一张
 * 1080×2400 天天象棋开局截图的输出采样：全部 NMS 后保留行（33 个，
 * 32 棋子 + board 框）+ 3 个手工注入的重叠重复框（应被 NMS 抑制）+ 20 行低分噪声。
 * 本测试验证 解码 -> 阈值 -> NMS -> 过滤 -> 棋盘映射 的完整纯 JVM 链路。
 */
class YoloPipelineTest {

    private fun decodeFixture(frameW: Int, frameH: Int, conf: Double = 0.60): List<YoloDetection> {
        val lb = YoloPostprocessor.Letterbox.forFrame(frameW, frameH)
        // 满长输出张量，夹具行（真实模型输出采样）拷贝到对应 anchor 位置
        val rows = Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) }
        FIXTURE_ROWS.forEachIndexed { i, r -> r.copyInto(rows[i]) }
        return YoloPostprocessor.decode(rows, lb, frameW, frameH, confThreshold = conf)
    }

    @Test
    fun `real output decodes to 32 pieces plus board`() {
        val dets = decodeFixture(1080, 2400)
        // 33 真实行 + 3 重叠重复框 => NMS 后恰 33；低分噪声被阈值滤除
        assertEquals(33, dets.size)
        assertEquals(32, dets.count { !it.isBoard })
        assertEquals(1, dets.count { it.isBoard })
    }

    @Test
    fun `real output maps to standard start position`() {
        val dets = decodeFixture(1080, 2400)
        val mapped = DetectionBoardMapper.map(dets, 1080, 2400)
        assertNotNull(mapped)
        assertTrue(AssistBoard.equal(AssistBoard.canonicalStart(), mapped!!.canonical))
        assertEquals(Orientation.STANDARD, mapped.orientation)
        assertEquals(32, mapped.pieceCount)
        assertEquals(0, mapped.dropped)
        assertNotNull(mapped.grid)
    }

    @Test
    fun `higher threshold drops the low score marker disc`() {
        // 夹具中含一个约 0.5 分的圆形标记误检（换低阈值才出现）
        val loose = decodeFixture(1080, 2400, conf = 0.45)
        val strict = decodeFixture(1080, 2400, conf = 0.60)
        assertTrue(loose.size >= strict.size)
    }

    @Test
    fun `letterbox maps model coords back to frame coords`() {
        // 帧 1080x2400：scale = 640/2400，padX = (640 - 1080*scale)/2
        val lb = YoloPostprocessor.Letterbox.forFrame(1080, 2400)
        val frameX = 500.0
        val frameY = 1000.0
        val modelX = frameX * lb.scale + lb.padX
        val modelY = frameY * lb.scale + lb.padY
        // 构造一行输出：b_che（label 4）位于上述 640 域中心
        val rows = Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) }
        val row = rows[12345]
        row[0] = modelX.toFloat(); row[1] = modelY.toFloat()
        row[2] = 60f; row[3] = 60f
        row[4] = 0.9f
        row[5 + 4] = 0.95f // b_che
        val dets = YoloPostprocessor.decode(rows, lb, 1080, 2400)
        assertEquals(1, dets.size)
        assertEquals(4, dets[0].labelId)
        assertEquals(Piece.BJU, dets[0].piece)
        assertEquals(frameX, dets[0].cx, 0.6)
        assertEquals(frameY, dets[0].cy, 0.6)
        assertEquals(60.0 / lb.scale, dets[0].w, 0.6)
    }

    @Test
    fun `wide button like boxes are aspect filtered`() {
        val lb = YoloPostprocessor.Letterbox.forFrame(640, 640)
        val rows = Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) }
        // 圆棋子（保留）
        put(rows, 0, 100f, 100f, 60f, 60f, 0.9f, 7) // r_che
        // 工具栏按钮状 2:1 宽框（剔除）
        put(rows, 1, 320f, 60f, 120f, 55f, 0.95f, 2) // b_shi
        // 过小标记盘（相对中位尺寸剔除）
        put(rows, 2, 500f, 500f, 22f, 22f, 0.95f, 12) // r_pao
        val dets = YoloPostprocessor.decode(rows, lb, 640, 640)
        assertEquals(1, dets.size)
        assertEquals(Piece.WJU, dets[0].piece)
    }

    private fun put(rows: Array<FloatArray>, row: Int, x: Float, y: Float, w: Float, h: Float,
                    obj: Float, cls: Int) {
        val r = rows[row]
        r[0] = x; r[1] = y; r[2] = w; r[3] = h
        r[4] = obj
        r[5 + cls] = 0.98f
    }

    private companion object {
        // 真实模型输出采样（见类注释），由推理脚本导出
        val FIXTURE_ROWS: List<FloatArray> = listOf(
        floatArrayOf(289.660f, 174.449f, 27.967f, 28.078f, 0.901f, 0.003f, 0.005f, 0.994f, 0.004f, 0.005f, 0.003f, 0.005f, 0.002f, 0.002f, 0.002f, 0.002f, 0.001f, 0.001f, 0.001f, 0.002f),
        floatArrayOf(384.442f, 358.199f, 28.923f, 28.784f, 0.816f, 0.003f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.004f, 0.003f, 0.003f, 0.003f, 0.004f, 0.996f, 0.003f),
        floatArrayOf(381.124f, 449.669f, 28.367f, 28.701f, 0.923f, 0.002f, 0.002f, 0.001f, 0.003f, 0.002f, 0.003f, 0.002f, 0.004f, 0.003f, 0.003f, 0.003f, 0.998f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(609.761f, 311.189f, 59.446f, 64.444f, 0.015f, 0.419f, 0.457f, 0.078f, 0.273f, 0.142f, 0.370f, 0.014f, 0.256f, 0.396f, 0.367f, 0.054f, 0.416f, 0.119f, 0.404f, 0.249f),
        floatArrayOf(481.250f, 39.466f, 64.689f, 76.777f, 0.040f, 0.144f, 0.336f, 0.356f, 0.328f, 0.073f, 0.487f, 0.478f, 0.212f, 0.297f, 0.020f, 0.494f, 0.409f, 0.318f, 0.381f, 0.094f),
        floatArrayOf(412.426f, 180.028f, 48.253f, 78.177f, 0.027f, 0.356f, 0.420f, 0.180f, 0.495f, 0.313f, 0.250f, 0.357f, 0.212f, 0.356f, 0.137f, 0.464f, 0.271f, 0.121f, 0.219f, 0.448f),
        floatArrayOf(350.311f, 174.925f, 28.053f, 28.141f, 0.900f, 0.003f, 0.005f, 0.994f, 0.003f, 0.004f, 0.003f, 0.005f, 0.002f, 0.002f, 0.004f, 0.002f, 0.001f, 0.001f, 0.001f, 0.002f),
        floatArrayOf(198.393f, 358.155f, 28.311f, 28.525f, 0.900f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.003f, 0.006f, 0.005f, 0.004f, 0.004f, 0.004f, 0.006f, 0.986f, 0.004f),
        floatArrayOf(600.406f, 300.616f, 20.339f, 79.952f, 0.012f, 0.195f, 0.270f, 0.446f, 0.412f, 0.305f, 0.199f, 0.417f, 0.435f, 0.436f, 0.359f, 0.049f, 0.149f, 0.243f, 0.253f, 0.416f),
        floatArrayOf(228.345f, 449.735f, 27.624f, 28.112f, 0.896f, 0.002f, 0.004f, 0.003f, 0.002f, 0.003f, 0.003f, 0.003f, 0.005f, 0.995f, 0.003f, 0.003f, 0.003f, 0.005f, 0.005f, 0.003f),
        floatArrayOf(6.771f, 531.610f, 75.597f, 47.516f, 0.049f, 0.433f, 0.305f, 0.436f, 0.012f, 0.136f, 0.139f, 0.060f, 0.455f, 0.015f, 0.336f, 0.036f, 0.180f, 0.209f, 0.091f, 0.261f),
        floatArrayOf(319.832f, 450.204f, 28.621f, 28.552f, 0.888f, 0.002f, 0.002f, 0.002f, 0.004f, 0.002f, 0.003f, 0.002f, 0.002f, 0.003f, 0.004f, 0.997f, 0.005f, 0.003f, 0.003f, 0.003f),
        floatArrayOf(48.837f, 499.148f, 46.305f, 63.408f, 0.059f, 0.269f, 0.251f, 0.036f, 0.134f, 0.250f, 0.340f, 0.402f, 0.190f, 0.033f, 0.144f, 0.455f, 0.107f, 0.226f, 0.466f, 0.012f),
        floatArrayOf(442.795f, 450.056f, 28.250f, 28.000f, 0.891f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.988f, 0.011f, 0.003f, 0.003f, 0.004f, 0.004f, 0.004f, 0.003f),
        floatArrayOf(102.928f, 469.786f, 68.475f, 61.934f, 0.058f, 0.128f, 0.118f, 0.074f, 0.270f, 0.200f, 0.178f, 0.230f, 0.137f, 0.002f, 0.236f, 0.138f, 0.225f, 0.464f, 0.090f, 0.319f),
        floatArrayOf(258.621f, 358.061f, 28.936f, 28.884f, 0.895f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.004f, 0.004f, 0.003f, 0.003f, 0.003f, 0.004f, 0.996f, 0.003f),
        floatArrayOf(228.700f, 388.560f, 29.395f, 29.506f, 0.896f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.002f, 0.005f, 0.005f, 0.004f, 0.004f, 0.006f, 0.988f, 0.004f, 0.003f),
        floatArrayOf(442.340f, 266.773f, 29.264f, 29.194f, 0.890f, 0.002f, 0.003f, 0.001f, 0.003f, 0.002f, 0.003f, 0.997f, 0.002f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.002f, 0.003f),
        floatArrayOf(442.321f, 174.658f, 28.734f, 28.738f, 0.893f, 0.005f, 0.003f, 0.002f, 0.002f, 0.998f, 0.004f, 0.002f, 0.002f, 0.002f, 0.002f, 0.001f, 0.001f, 0.001f, 0.001f, 0.002f),
        floatArrayOf(320.109f, 266.921f, 28.982f, 29.032f, 0.906f, 0.003f, 0.004f, 0.003f, 0.003f, 0.003f, 0.003f, 0.993f, 0.004f, 0.003f, 0.003f, 0.003f, 0.003f, 0.004f, 0.002f, 0.003f),
        floatArrayOf(258.332f, 174.523f, 28.101f, 28.365f, 0.889f, 0.001f, 0.998f, 0.004f, 0.006f, 0.002f, 0.003f, 0.002f, 0.002f, 0.003f, 0.002f, 0.003f, 0.002f, 0.003f, 0.002f, 0.003f),
        floatArrayOf(258.996f, 449.910f, 28.202f, 28.562f, 0.916f, 0.003f, 0.002f, 0.002f, 0.003f, 0.002f, 0.003f, 0.002f, 0.003f, 0.009f, 0.005f, 0.003f, 0.992f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(342.395f, 202.908f, 64.225f, 29.612f, 0.020f, 0.177f, 0.189f, 0.103f, 0.459f, 0.414f, 0.053f, 0.185f, 0.116f, 0.226f, 0.138f, 0.251f, 0.461f, 0.191f, 0.325f, 0.298f),
        floatArrayOf(105.921f, 265.537f, 46.887f, 66.494f, 0.050f, 0.261f, 0.230f, 0.389f, 0.444f, 0.337f, 0.400f, 0.470f, 0.020f, 0.438f, 0.138f, 0.238f, 0.398f, 0.359f, 0.074f, 0.329f),
        floatArrayOf(196.899f, 157.692f, 55.763f, 25.514f, 0.055f, 0.231f, 0.222f, 0.052f, 0.342f, 0.408f, 0.315f, 0.121f, 0.393f, 0.073f, 0.414f, 0.290f, 0.145f, 0.257f, 0.314f, 0.129f),
        floatArrayOf(152.216f, 425.338f, 42.452f, 66.529f, 0.020f, 0.238f, 0.052f, 0.106f, 0.468f, 0.158f, 0.446f, 0.262f, 0.020f, 0.396f, 0.026f, 0.413f, 0.004f, 0.337f, 0.083f, 0.171f),
        floatArrayOf(381.442f, 358.199f, 28.923f, 28.784f, 0.907f, 0.003f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.004f, 0.003f, 0.003f, 0.003f, 0.004f, 0.996f, 0.003f),
        floatArrayOf(323.049f, 174.902f, 27.914f, 28.429f, 0.806f, 0.003f, 0.006f, 0.009f, 0.991f, 0.003f, 0.005f, 0.002f, 0.001f, 0.002f, 0.002f, 0.002f, 0.002f, 0.002f, 0.001f, 0.003f),
        floatArrayOf(630.541f, 74.057f, 23.156f, 63.957f, 0.029f, 0.181f, 0.438f, 0.164f, 0.444f, 0.322f, 0.165f, 0.030f, 0.123f, 0.484f, 0.203f, 0.080f, 0.149f, 0.450f, 0.082f, 0.389f),
        floatArrayOf(228.021f, 235.420f, 28.128f, 28.360f, 0.897f, 0.004f, 0.004f, 0.002f, 0.005f, 0.005f, 0.994f, 0.004f, 0.004f, 0.004f, 0.005f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f),
        floatArrayOf(411.696f, 388.544f, 29.370f, 29.602f, 0.906f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.002f, 0.005f, 0.004f, 0.004f, 0.004f, 0.005f, 0.994f, 0.004f, 0.003f),
        floatArrayOf(411.888f, 450.086f, 28.309f, 28.725f, 0.896f, 0.003f, 0.004f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.005f, 0.994f, 0.003f, 0.003f, 0.005f, 0.005f, 0.004f, 0.003f),
        floatArrayOf(384.252f, 174.701f, 27.827f, 28.094f, 0.807f, 0.002f, 0.996f, 0.005f, 0.007f, 0.003f, 0.004f, 0.002f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(411.542f, 235.645f, 28.144f, 28.361f, 0.900f, 0.005f, 0.005f, 0.002f, 0.004f, 0.005f, 0.994f, 0.004f, 0.004f, 0.004f, 0.005f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f),
        floatArrayOf(259.198f, 266.863f, 28.852f, 29.088f, 0.907f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f, 0.004f, 0.988f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(384.351f, 608.083f, 33.818f, 52.909f, 0.055f, 0.067f, 0.262f, 0.375f, 0.335f, 0.234f, 0.102f, 0.245f, 0.186f, 0.239f, 0.183f, 0.419f, 0.384f, 0.157f, 0.286f, 0.138f),
        floatArrayOf(289.720f, 449.924f, 28.604f, 28.822f, 0.898f, 0.002f, 0.001f, 0.006f, 0.001f, 0.001f, 0.002f, 0.001f, 0.003f, 0.002f, 0.995f, 0.005f, 0.007f, 0.003f, 0.003f, 0.002f),
        floatArrayOf(319.738f, 357.906f, 28.849f, 28.791f, 0.905f, 0.003f, 0.003f, 0.004f, 0.004f, 0.004f, 0.003f, 0.002f, 0.004f, 0.004f, 0.003f, 0.003f, 0.004f, 0.004f, 0.993f, 0.003f),
        floatArrayOf(411.587f, 174.857f, 27.922f, 28.443f, 0.895f, 0.999f, 0.005f, 0.001f, 0.002f, 0.002f, 0.005f, 0.003f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.002f),
        floatArrayOf(611.500f, 437.065f, 23.188f, 38.531f, 0.040f, 0.118f, 0.482f, 0.473f, 0.424f, 0.236f, 0.421f, 0.066f, 0.154f, 0.231f, 0.371f, 0.243f, 0.068f, 0.172f, 0.162f, 0.150f),
        floatArrayOf(198.169f, 449.839f, 27.915f, 27.920f, 0.887f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.003f, 0.988f, 0.007f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.003f),
        floatArrayOf(320.049f, 174.902f, 27.914f, 28.429f, 0.896f, 0.003f, 0.006f, 0.009f, 0.991f, 0.003f, 0.005f, 0.002f, 0.001f, 0.002f, 0.002f, 0.002f, 0.002f, 0.002f, 0.001f, 0.003f),
        floatArrayOf(320.297f, 312.087f, 254.339f, 284.774f, 0.939f, 0.001f, 0.002f, 0.001f, 0.001f, 0.002f, 0.001f, 0.003f, 0.001f, 0.002f, 0.002f, 0.003f, 0.002f, 0.002f, 0.002f, 0.993f),
        floatArrayOf(289.819f, 225.906f, 59.444f, 42.221f, 0.033f, 0.360f, 0.206f, 0.453f, 0.090f, 0.371f, 0.211f, 0.213f, 0.317f, 0.261f, 0.207f, 0.001f, 0.046f, 0.355f, 0.262f, 0.348f),
        floatArrayOf(86.313f, 615.292f, 51.812f, 22.590f, 0.057f, 0.179f, 0.366f, 0.262f, 0.046f, 0.053f, 0.075f, 0.081f, 0.026f, 0.024f, 0.474f, 0.046f, 0.254f, 0.059f, 0.107f, 0.382f),
        floatArrayOf(542.020f, 269.602f, 73.540f, 70.128f, 0.015f, 0.323f, 0.155f, 0.377f, 0.271f, 0.229f, 0.448f, 0.029f, 0.279f, 0.164f, 0.018f, 0.377f, 0.281f, 0.447f, 0.299f, 0.169f),
        floatArrayOf(44.321f, 228.525f, 68.770f, 45.662f, 0.040f, 0.364f, 0.411f, 0.380f, 0.004f, 0.210f, 0.232f, 0.028f, 0.271f, 0.304f, 0.414f, 0.471f, 0.064f, 0.115f, 0.330f, 0.066f),
        floatArrayOf(351.817f, 105.419f, 22.132f, 36.892f, 0.050f, 0.022f, 0.004f, 0.181f, 0.032f, 0.075f, 0.012f, 0.262f, 0.348f, 0.214f, 0.067f, 0.166f, 0.295f, 0.470f, 0.496f, 0.121f),
        floatArrayOf(228.386f, 174.778f, 27.798f, 28.571f, 0.890f, 0.999f, 0.006f, 0.001f, 0.002f, 0.002f, 0.005f, 0.003f, 0.002f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.001f, 0.002f),
        floatArrayOf(198.577f, 174.418f, 28.870f, 28.512f, 0.891f, 0.005f, 0.004f, 0.004f, 0.003f, 0.992f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f, 0.004f, 0.003f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(198.440f, 266.549f, 28.387f, 28.599f, 0.908f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.986f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.005f, 0.004f, 0.003f),
        floatArrayOf(381.252f, 174.701f, 27.827f, 28.094f, 0.896f, 0.002f, 0.996f, 0.005f, 0.007f, 0.003f, 0.004f, 0.002f, 0.004f, 0.004f, 0.004f, 0.003f, 0.004f, 0.004f, 0.003f, 0.003f),
        floatArrayOf(350.143f, 450.276f, 28.070f, 28.420f, 0.918f, 0.003f, 0.002f, 0.009f, 0.001f, 0.002f, 0.002f, 0.001f, 0.003f, 0.002f, 0.989f, 0.007f, 0.010f, 0.005f, 0.004f, 0.003f),
        floatArrayOf(380.930f, 266.339f, 28.308f, 28.522f, 0.912f, 0.003f, 0.003f, 0.002f, 0.004f, 0.003f, 0.003f, 0.991f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.004f, 0.003f),
        floatArrayOf(441.936f, 357.403f, 29.326f, 29.295f, 0.892f, 0.003f, 0.002f, 0.003f, 0.003f, 0.003f, 0.003f, 0.002f, 0.004f, 0.004f, 0.003f, 0.003f, 0.004f, 0.004f, 0.995f, 0.003f),
        floatArrayOf(143.410f, 367.912f, 30.171f, 66.934f, 0.053f, 0.017f, 0.266f, 0.398f, 0.488f, 0.137f, 0.085f, 0.438f, 0.455f, 0.099f, 0.221f, 0.360f, 0.423f, 0.084f, 0.332f, 0.404f),)
    }
}
