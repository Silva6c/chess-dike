package com.xqdk.chess.assist.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import com.xqdk.chess.R
import com.xqdk.chess.gamelogic.Piece
import com.xqdk.chess.utils.ArrowShape
import kotlin.math.min

/**
 * 悬浮窗里的迷你棋盘：与主界面完全一致的"微缩原版棋盘"
 * （同一张 chessboard.png 底图 + 同一套棋子位图），并把最佳走法画成箭头。
 *
 * 朝向"随动"：[flipped] 为 true（被识别的 App 屏幕上红方在上）时，
 * 迷你棋盘同样红在上、黑在下，与真实屏幕一致；底部永远是被识别屏幕的下方一侧。
 */
class OverlayChessView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 90 个棋子（内部约定 piece[y][x]，EMPTY=0，canonical：红恒在 y=9），null 表示尚无局面。
     *  相等比对后跳过 invalidate（根因 4：一次 render 最多 9 次无条件重绘）；
     *  pieces 数组由服务端复用引用，搜索期重复 render 时引用相等即跳过 */
    var pieces: IntArray? = null
        set(value) {
            if (field === value) return
            field = value; invalidate()
        }

    /** 推荐走法起止（canonical 内部坐标 x,y），null 则不画箭头 */
    var arrowFrom: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }
    var arrowTo: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 被识别屏幕是否"红在上"（换边/翻转朝向）：true 时迷你棋盘同样红在上 */
    var flipped: Boolean = false
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 我方是否红方（用于"我方"色条的位置与颜色） */
    var mySideIsRed: Boolean = true
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 建议已定着（成熟）：true 绿箭头，false 暂定蓝箭头 */
    var mature: Boolean = false
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 紧急手动模式：点按用于选子/走子/删子（onCellTap），而非切换候选 */
    var manualMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value; if (!value) selectedCell = null; invalidate()
        }

    /** 手动模式下当前选中的格（canonical x,y），绘制金色高亮圈 */
    var selectedCell: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value; invalidate()
        }

    /** 用户点击棋盘切换候选时回调（非手动模式） */
    var onNextCandidate: (() -> Unit)? = null

    /** 手动模式点格回调（canonical x,y；未命中棋盘交点不回调） */
    var onCellTap: ((x: Int, y: Int) -> Unit)? = null

    // ==== 原版资源（与 ChessView 共用同一套图）====
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

    // ==== 与 ChessView.java 一致的棋盘几何（1240x1340 基准坐标系）====
    private val boardW = 1240f
    private val boardH = 1340f
    private val pieceSize = 110f
    private val xOffset = 22f
    private val yOffset = 5f
    private val interval = 136f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val barPaint = Paint()
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

    init {
        // 圆角 8dp 裁剪（对齐 Cabinet 美学）：clipToOutline 走硬件 outline 路径，零重绘成本，
        // 优于 clipPath（后者为每帧产生离屏缓冲）
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    8f * resources.displayMetrics.density,
                )
            }
        }
    }

    companion object {
        /** "我方"色条颜色（原 Color.parseColor 字符串解析常量化，不该发生在重绘热路径） */
        private const val COLOR_RED_BAR = 0xFFE53935.toInt()
        private const val COLOR_DARK_BAR = 0xFF37474F.toInt()
    }

    /** 缩放与棋盘原点（onDraw 中更新） */
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    /**
     * center() 的输出槽（Cabinet 同款）：不返回 Pair 而是填充 cx/cy——重绘热路径
     * 每帧 ~32 个格点，Pair 分配版本在持续制造垃圾。
     */
    private var cx = 0f
    private var cy = 0f

    /** canonical (x,y) -> 画布交点中心（按屏幕朝向映射），结果写入 [cx]/[cy] */
    private fun center(x: Int, y: Int) {
        val sx = if (flipped) 8 - x else x
        val sy = if (flipped) 9 - y else y
        cx = originX + (xOffset + sx * interval + pieceSize / 2) * scale
        cy = originY + (yOffset + sy * interval + pieceSize / 2) * scale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        scale = min(width / boardW, height / boardH)
        originX = (width - boardW * scale) / 2f
        originY = (height - boardH * scale) / 2f
        boardDst.set(originX, originY, originX + boardW * scale, originY + boardH * scale)
        canvas.drawBitmap(boardBitmap, null, boardDst, bitmapPaint)

        drawPieces(canvas)
        drawSelection(canvas)
        drawArrow(canvas)
        drawSideLabel(canvas)
    }

    private fun drawPieces(canvas: Canvas) {
        val arr = pieces ?: return
        val size = pieceSize * scale
        for (y in 0..9) for (x in 0..8) {
            val p = arr[y * 9 + x]
            if (p !in 1..14) continue
            center(x, y)
            pieceDst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            canvas.drawBitmap(pieceBitmaps[p - 1], null, pieceDst, bitmapPaint)
        }
    }

    /** 箭头形状缓存（参数只随 scale 变，scale 未变直接复用：重绘热路径免构造免 Matrix 重算） */
    private var cachedArrow: ArrowShape? = null
    private var cachedArrowScale = 0f

    private fun drawArrow(canvas: Canvas) {
        val f = arrowFrom ?: return
        val t = arrowTo ?: return
        center(f.first, f.second)
        val fx = cx; val fy = cy
        center(t.first, t.second)
        val tx = cx; val ty = cy
        // 我方着法箭头：暂定=蓝，定着（成熟）=深玉绿（#1B5E20 在木色棋盘上对比更好，
        // 由描边承担对比，填充 #A5D6A7 只是高亮语义）
        val strokeColor = if (mature) 0xFF1B5E20.toInt() else 0xFF2B6FFF.toInt()
        val fillColor = if (mature) 0xFFA5D6A7.toInt() else 0xFF8AB6FF.toInt()
        // ArrowShape 默认参数基于 1240x1340 基准坐标系，按本视图缩放
        if (cachedArrow == null || cachedArrowScale != scale) {
            cachedArrow = ArrowShape(60f, 120f, 0f, 80f * scale, 26f * scale, 10f * scale)
            cachedArrowScale = scale
        }
        cachedArrow!!.getTransformedPath(arrowPath, fx, fy, tx, ty)
        arrowFill.color = fillColor
        arrowStroke.color = strokeColor
        arrowStroke.strokeWidth = (2f * scale).coerceAtLeast(1.2f)
        canvas.drawPath(arrowPath, arrowFill)
        canvas.drawPath(arrowPath, arrowStroke)
    }

    /** 手动模式：绘制选中格的金色高亮圈 */
    private fun drawSelection(canvas: Canvas) {
        val sel = selectedCell ?: return
        center(sel.first, sel.second)
        val px = cx; val py = cy
        val r = pieceSize * scale / 2f
        selectStroke.strokeWidth = 8f * scale.coerceAtLeast(0.75f)
        canvas.drawCircle(px, py, r, selectFill)
        canvas.drawCircle(px, py, r, selectStroke)
    }

    /** 顶部/底部色条指示"我方"所在侧与颜色（随动：底部=被识别屏幕下方一侧） */
    private fun drawSideLabel(canvas: Canvas) {
        val bottomIsRed = !flipped
        val myAtBottom = mySideIsRed == bottomIsRed
        val barH = 6f * scale
        barPaint.color = if (mySideIsRed) COLOR_RED_BAR else COLOR_DARK_BAR
        val top = if (myAtBottom) boardDst.bottom - barH else boardDst.top
        canvas.drawRect(boardDst.left, top, boardDst.right, top + barH, barPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (manualMode) {
                hitCell(event.x, event.y)?.let { onCellTap?.invoke(it.first, it.second) }
            } else {
                onNextCandidate?.invoke()
            }
            return true
        }
        return true
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
}
