package com.xqdk.chess.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv5 棋子检测器（Android 侧，TFLite）。
 *
 * 模型：assets/yolov5n_xq_fp16.tflite（YOLOv5n，640×640，fp16 权重，输入 NHWC float32，
 * 输出 [1,25200,20] float32）。输入为 RGB（注意不是 BGR，BGR 会把红黑阵营互换）。
 * 纯推理封装：解码与棋盘映射在纯 JVM 的 YoloPostprocessor / DetectionBoardMapper 中，
 * 便于单元测试。
 *
 * 性能要点：
 * - 支持[cropHint]裁剪推理：已知棋盘位置时只取棋盘外扩区域，单次 drawBitmap 完成缩放；
 * - 像素 -> float 在 JVM 内一次性写入复用数组，再整块 put 进输入缓冲（避免百万次 putFloat JNI）；
 * - 解码直接消费解释器输出（无 2MB flat 拷贝）。
 */
class YoloBoardDetector(context: Context) {

    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val inputFloat: FloatArray
    private val inputFloatBuffer: FloatBuffer
    private val output: Array<Array<FloatArray>>
    private val workPx = IntArray(YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
    private val dstRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var square: Bitmap? = null
    private var cropBitmap: Bitmap? = null

    init {
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModel(context, MODEL_FILE), options)
        input = ByteBuffer.allocateDirect(4 * 3 * YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
            .order(ByteOrder.nativeOrder())
        inputFloatBuffer = input.asFloatBuffer()
        inputFloat = FloatArray(3 * YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
        output = Array(1) { Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) } }
    }

    /**
     * 检测一帧并映射为 9×10 棋盘。无足够棋子/映射失败返回 null。
     *
     * @param cropHint 裁剪区域（帧像素坐标 x0,y0,x1,y1）；null 表示整帧
     */
    fun detect(frame: Frame, cropHint: DoubleArray? = null): DetectionBoardMapper.MappedBoard? {
        val cx0: Int; val cy0: Int; val cw: Int; val ch: Int
        if (cropHint != null && cropHint[2] - cropHint[0] >= 64 && cropHint[3] - cropHint[1] >= 64) {
            cx0 = max(0, cropHint[0].toInt()); cy0 = max(0, cropHint[1].toInt())
            cw = min(frame.width, cropHint[2].toInt()) - cx0
            ch = min(frame.height, cropHint[3].toInt()) - cy0
        } else {
            cx0 = 0; cy0 = 0; cw = frame.width; ch = frame.height
        }
        val lb = renderInput(frame, cx0, cy0, cw, ch)
        val t0 = System.currentTimeMillis()
        input.rewind()
        interpreter.run(input, output)
        val inferMs = System.currentTimeMillis() - t0
        val dets = YoloPostprocessor.decode(output[0], lb, cw, ch)
        // 裁剪坐标 -> 帧坐标
        val shifted = if (cx0 == 0 && cy0 == 0) dets
        else dets.map { YoloDetection(it.labelId, it.score, it.cx + cx0, it.cy + cy0, it.w, it.h) }
        val mapped = DetectionBoardMapper.map(shifted, frame.width, frame.height)
        Log.d(TAG, "yolo: infer=${inferMs}ms crop=${cw}x$ch dets=${dets.size} " +
            "pieces=${mapped?.pieceCount ?: 0} avg=${mapped?.avgScore?.let { "%.2f".format(it) }} " +
            "dropped=${mapped?.dropped ?: 0}")
        return mapped
    }

    fun close() {
        interpreter.close()
        square?.recycle()
        square = null
        cropBitmap?.recycle()
        cropBitmap = null
    }

    /** 帧 -> 640×640 letterbox RGB float 输入，返回 letterbox 参数（相对裁剪区域） */
    private fun renderInput(frame: Frame, cx0: Int, cy0: Int, cw: Int, chh: Int): YoloPostprocessor.Letterbox {
        val lb = YoloPostprocessor.Letterbox.forFrame(cw, chh)
        val nw = (cw * lb.scale).roundToInt().coerceAtLeast(1)
        val nh = (chh * lb.scale).roundToInt().coerceAtLeast(1)
        // 直接从像素数组取裁剪区（一次数组拷贝，尺寸稳定时复用位图以减少 GC 压力）
        var crop = cropBitmap
        if (crop == null || crop.width != cw || crop.height != chh) {
            crop?.recycle()
            crop = Bitmap.createBitmap(cw, chh, Bitmap.Config.ARGB_8888)
            cropBitmap = crop
        }
        crop.setPixels(frame.argb, cy0 * frame.width + cx0, frame.width, 0, 0, cw, chh)
        val sq = square ?: Bitmap.createBitmap(
            YoloPostprocessor.MODEL_INPUT, YoloPostprocessor.MODEL_INPUT, Bitmap.Config.ARGB_8888)
            .also { square = it }
        val canvas = Canvas(sq)
        canvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(lb.padX.toFloat(), lb.padY.toFloat(),
            lb.padX.toFloat() + nw, lb.padY.toFloat() + nh)
        canvas.drawBitmap(crop, null, dstRect, paint)
        sq.getPixels(workPx, 0, YoloPostprocessor.MODEL_INPUT, 0, 0,
            YoloPostprocessor.MODEL_INPUT, YoloPostprocessor.MODEL_INPUT)
        // ARGB -> RGB float（JVM 内一次循环），再整块写入输入缓冲
        val f = inputFloat
        var k = 0
        for (p in workPx) {
            f[k++] = ((p shr 16) and 0xFF) / 255f
            f[k++] = ((p shr 8) and 0xFF) / 255f
            f[k++] = (p and 0xFF) / 255f
        }
        input.rewind()
        inputFloatBuffer.rewind()
        inputFloatBuffer.put(f)
        return lb
    }

    private fun loadModel(context: Context, file: String): ByteBuffer {
        context.assets.open(file).use { ins ->
            val buf = ByteBuffer.allocateDirect(ins.available()).order(ByteOrder.nativeOrder())
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                buf.put(chunk, 0, n)
            }
            buf.rewind()
            return buf
        }
    }

    companion object {
        private const val TAG = "AssistService"
        const val MODEL_FILE = "yolov5n_xq_fp16.tflite"
    }
}
