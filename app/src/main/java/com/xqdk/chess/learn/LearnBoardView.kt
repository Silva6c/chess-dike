package com.xqdk.chess.learn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xqdk.chess.R
import com.xqdk.chess.gamelogic.Move
import com.xqdk.chess.gamelogic.Position
import com.xqdk.chess.utils.ArrowShape
import kotlin.math.min

/**
 * 学习模式全屏棋盘（点选走子，参照 OverlayChessView 的 1240×1340 基准几何与
 * hitCell 反解）。
 *
 * 【视觉层规范（2026-11-09 用户拍板）】纠正期**不直接消除**用户走的棋子——
 * 逻辑撤回后棋子回起点（实子、参与 hitCell），终点格叠加"半透明幽灵棋子 +
 * 灰色虚线框"标记该次尝试；皮卡鱼红色应着与三解指导用**悬浮箭头**表现
 * （ArrowShape 最上层，先画放大低透明度光晕层再画实箭头；绿箭头深绿实心+
 * 浅描边保证木色棋盘上的对比度）。**着子点格子光晕已删除**（2026-11-09 用户
 * 反馈光晕噪，指导对比改由箭头自身承担）。
 * 【雷区】所有叠加层（preview/ghost/箭头）都是**纯视觉**：点选互动恒映射
 * 逻辑盘面（controller 持有权威 Board），幽灵格不参与命中——"走其他错误着法"
 * 的自由互动不受影响。不用 Paint.setShadowLayer：其光晕在硬件加速下仅对文字
 * 生效，悬浮感由"放大低透明度光晕层"实现。
 */
class LearnBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 逻辑盘面 90 子（canonical：红恒 y=9；piece[y*9+x]），由 activity 每次渲染回填 */
    var pieces: IntArray? = null
        set(value) {
            if (field === value) return
            field = value; invalidate()
        }

    /** 执黑翻转（我方黑方时红在上） */
    var flipped: Boolean = false
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** EVALUATING/RED_WARNING：我方着"已落"预览（终点实子、起点留空） */
    var previewMove: Move? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** CORRECTION：被撤着法的幽灵标记（终点半透明子+虚线框） */
    var ghostMove: Move? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 皮卡鱼应着/警示箭头（RED_WARNING 粗箭头；CORRECTION 弱化细箭头） */
    var redArrow: Move? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** CORRECTION 态红箭头弱化（细+半透明，作"执迷后果"预告） */
    var redArrowDim: Boolean = false
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 三解绿箭头 */
    var greenArrows: List<Move> = emptyList()
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 最后一步（放行着我方着或皮卡鱼回着）琥珀角标 */
    var lastMove: Move? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    var selected: Position? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 选中子的合法落点（金点提示） */
    var legalTargets: List<Position> = emptyList()
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 点格回调（canonical x,y）——activity 直通 controller.onCellTap */
    var onCellTap: ((x: Int, y: Int) -> Unit)? = null

    // ==== 原版资源（与悬浮窗迷你棋盘共用同一套图）====
    private val boardBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.chessboard)
    }
    private val pieceBitmaps: Array<Bitmap> by lazy {
        arrayOf(
            R.drawable.r_shuai, R.drawable.r_shi, R.drawable.r_xiang, R.drawable.r_ma,
            R.drawable.r_ju, R.drawable.r_pao, R.drawable.r_bing,
            R.drawable.b_jiang, R.drawable.b_shi, R.drawable.b_xiang, R.drawable.b_ma,
            R.drawable.b_ju, R.drawable.b_pao, R.drawable.b_zu,
        ).map { BitmapFactory.decodeResource(resources, it) }.toTypedArray()
    }

    // ==== 与 OverlayChessView 一致的棋盘几何（1240x1340 基准坐标系）====
    private val boardW = 1240f
    private val boardH = 1340f
    private val pieceSize = 110f
    private val xOffset = 22f
    private val yOffset = 5f
    private val interval = 136f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashedFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0xFF9E9E9E.toInt()
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val frameRect = RectF()
    private val arrowPath = Path()
    private val boardDst = RectF()
    private val pieceDst = RectF()
    private val selectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFC107.toInt()
    }
    private val selectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x40FFC107
    }

    /** 缩放与棋盘原点（onDraw 中更新；internal=测试反推格点像素用） */
    internal var scale = 1f
    internal var originX = 0f
    internal var originY = 0f

    private var cx = 0f
    private var cy = 0f

    /** canonical (x,y) -> 画布交点中心（按屏幕朝向映射），结果写入 [cx]/[cy] */
    private fun center(x: Int, y: Int) {
        val sx = if (flipped) 8 - x else x
        val sy = if (flipped) 9 - y else y
        cx = originX + (xOffset + sx * interval + pieceSize / 2) * scale
        cy = originY + (yOffset + sy * interval + pieceSize / 2) * scale
    }

    /** 纵横比锁定：高 = 宽 × 1340/1240（页面内不再拉伸） */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * boardH / boardW).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        scale = min(width / boardW, height / boardH)
        originX = (width - boardW * scale) / 2f
        originY = (height - boardH * scale) / 2f
        boardDst.set(originX, originY, originX + boardW * scale, originY + boardH * scale)
        canvas.drawBitmap(boardBitmap, null, boardDst, bitmapPaint)

        drawLastMove(canvas)
        drawPiecesWithPreview(canvas)
        drawGhost(canvas)
        drawSelection(canvas)
        drawLegalDots(canvas)
        drawArrows(canvas)
    }

    private fun drawLastMove(canvas: Canvas) {
        val mv = lastMove ?: return
        lastMovePaint.color = 0x80FFC107.toInt()
        center(mv.fromPosition.x, mv.fromPosition.y)
        canvas.drawCircle(cx, cy, 9f * scale, lastMovePaint)
        center(mv.toPosition.x, mv.toPosition.y)
        canvas.drawCircle(cx, cy, 9f * scale, lastMovePaint)
    }

    /** 实子层 + EVALUATING/RED_WARNING 的"已落"预览（终点实子、起点留空） */
    private fun drawPiecesWithPreview(canvas: Canvas) {
        val arr = pieces ?: return
        if (arr.size != 90) return
        val preview = previewMove
        val size = pieceSize * scale
        for (y in 0..9) for (x in 0..8) {
            if (preview != null && preview.fromPosition.x == x && preview.fromPosition.y == y) {
                continue // 预览期起点留空
            }
            val p = arr[y * 9 + x]
            if (p !in 1..14) continue
            center(x, y)
            pieceDst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            canvas.drawBitmap(pieceBitmaps[p - 1], null, pieceDst, bitmapPaint)
        }
        if (preview != null) {
            val p = arr[preview.fromPosition.y * 9 + preview.fromPosition.x]
            if (p in 1..14) {
                center(preview.toPosition.x, preview.toPosition.y)
                pieceDst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                canvas.drawBitmap(pieceBitmaps[p - 1], null, pieceDst, bitmapPaint)
            }
        }
    }

    /** 幽灵层：CORRECTION 被撤着法的终点——半透明棋子 + 灰虚线框（起点实子已在上面照常画出） */
    private fun drawGhost(canvas: Canvas) {
        val mv = ghostMove ?: return
        val arr = pieces ?: return
        val p = arr[mv.fromPosition.y * 9 + mv.fromPosition.x]
        if (p !in 1..14) return
        val size = pieceSize * scale
        center(mv.toPosition.x, mv.toPosition.y)
        pieceDst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        ghostPaint.alpha = 110 // 半透明幽灵（0..255）
        canvas.drawBitmap(pieceBitmaps[p - 1], null, pieceDst, ghostPaint)
        ghostPaint.alpha = 255
        frameRect.set(pieceDst)
        canvas.drawRect(frameRect, dashedFrame)
    }

    /** 手动选中格的金色高亮圈 */
    private fun drawSelection(canvas: Canvas) {
        val sel = selected ?: return
        center(sel.x, sel.y)
        val r = pieceSize / 2 * scale
        selectStroke.strokeWidth = (8f * scale).coerceAtLeast(1.5f)
        canvas.drawCircle(cx, cy, r, selectFill)
        canvas.drawCircle(cx, cy, r, selectStroke)
    }

    /** 合法落点金点提示 */
    private fun drawLegalDots(canvas: Canvas) {
        if (legalTargets.isEmpty()) return
        for (t in legalTargets) {
            center(t.x, t.y)
            dotPaint.color = 0x99FFC107.toInt()
            canvas.drawCircle(cx, cy, 13f * scale, dotPaint)
            dotPaint.color = 0xFFB98307.toInt()
            dotPaint.style = Paint.Style.STROKE
            dotPaint.strokeWidth = 2f * scale
            canvas.drawCircle(cx, cy, 13f * scale, dotPaint)
            dotPaint.style = Paint.Style.FILL
        }
    }

    /** 悬浮箭头层（最上层）：先画放大低透明度"光晕层"，再画实箭头。
     *  红=皮卡鱼应着（CORRECTION 期弱化）；绿=三解指导——**箭头深浅即优先级**
     *  （手册已写入：最深=引擎首推），2026-11-09 用户反馈加深对比度：色基加深
     *  至 1B5E20 + 浅薄荷描边，透明度整体上提保证最浅一档仍清晰可辨 */
    private fun drawArrows(canvas: Canvas) {
        if (cachedArrow == null || cachedArrowScale != scale) {
            cachedArrow = ArrowShape(60f, 120f, 0f, 80f * scale, 26f * scale, 10f * scale)
            cachedArrowScale = scale
        }
        val red = redArrow
        if (red != null) {
            drawOneArrow(canvas, red,
                fill = (if (redArrowDim) 0x88C62828 else 0xE6C62828).toInt(),
                stroke = 0xFF8F1F1F.toInt(), strokeWidth = if (redArrowDim) 1.6f else 2.4f)
        }
        // 三解按 MultiPV 序号三档严格对比（2026-11-09 用户反馈原 0xFF/0xE8/0xD1
        // 差异不大）：0xFF 全实 / 0xB3 七成 / 0x59 三成半——深浅即优先级，一眼可分
        val greenAlpha = intArrayOf(0xFF, 0xB3, 0x59)
        for ((idx, mv) in greenArrows.withIndex()) {
            val alpha = greenAlpha.getOrElse(idx) { 0x59 }
            drawOneArrow(canvas, mv, fill = (alpha shl 24) or 0x1B5E20,
                stroke = 0xFFE8F5E9.toInt(), strokeWidth = 2.6f)
        }
    }

    private fun drawOneArrow(canvas: Canvas, mv: Move, fill: Int, stroke: Int, strokeWidth: Float) {
        center(mv.fromPosition.x, mv.fromPosition.y)
        val fx = cx; val fy = cy
        center(mv.toPosition.x, mv.toPosition.y)
        cachedArrow!!.getTransformedPath(arrowPath, fx, fy, cx, cy)
        // 光晕层：放大描边 + 低透明度填充（硬件加速安全，替代 setShadowLayer）
        arrowHalo.strokeWidth = 12f * scale
        arrowHalo.color = fill and 0x33FFFFFF // alpha 压到 ~20%，rgb 保持
        canvas.drawPath(arrowPath, arrowHalo)
        arrowFill.color = fill
        canvas.drawPath(arrowPath, arrowFill)
        arrowStroke.color = stroke
        arrowStroke.strokeWidth = (strokeWidth * scale).coerceAtLeast(1.2f)
        canvas.drawPath(arrowPath, arrowStroke)
    }

    /** 视图坐标 -> canonical 交点（center() 的反解，含 flipped 映射）；半格距内才算命中 */
    private fun hitCell(ex: Float, ey: Float): Pair<Int, Int>? {
        if (scale <= 0f) return null
        val sx = ((ex - originX) / scale - xOffset - pieceSize / 2) / interval
        val sy = ((ey - originY) / scale - yOffset - pieceSize / 2) / interval
        if (sx < -0.5f || sx > 8.5f || sy < -0.5f || sy > 9.5f) return null
        val gx = (sx + 0.5f).toInt().coerceIn(0, 8)
        val gy = (sy + 0.5f).toInt().coerceIn(0, 9)
        val x = if (flipped) 8 - gx else gx
        val y = if (flipped) 9 - gy else gy
        return x to y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            hitCell(event.x, event.y)?.let { onCellTap?.invoke(it.first, it.second) }
            return true
        }
        return true
    }

    private var cachedArrow: ArrowShape? = null
    private var cachedArrowScale = 0f
}
