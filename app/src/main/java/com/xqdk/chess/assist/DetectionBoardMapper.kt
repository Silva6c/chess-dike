package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YOLO 检测结果 -> 9×10 棋盘映射（纯 JVM）。
 *
 * 移植自 VinXiangQi 的 GetBoardFromPrediction 并加强：
 * 1. 优先用模型的 board 框做网格锚点（宽/8、高/9 即格距）；
 * 2. board 缺失或形状异常时，用棋子中心点包围盒兜底（需有足够棋子且间距一致）；
 * 3. 棋子中心 round 到最近交点，越界丢弃，同格冲突保留高分者；
 * 4. 朝向由两个王的位置推断（帅在下=STANDARD）。
 */
object DetectionBoardMapper {

    class MappedBoard(
        /** 屏幕布局 [gy][gx]（gy=0 为屏幕顶部），元素为 Piece 常量或 EMPTY */
        val screenRaw: Array<IntArray>,
        /** canonical 布局（红恒在 y=9） */
        val canonical: Array<IntArray>,
        val orientation: Orientation,
        /** 由棋盘框换算的归一化网格（供悬浮窗遮挡判断等复用） */
        val grid: BoardGrid?,
        val pieceCount: Int,
        val avgScore: Double,
        /** 识别日志：被丢弃的越界/重复棋子数 */
        val dropped: Int,
    )

    /**
     * 单帧最少"棋子检测数"：低于该值不值得映射（整屏无棋盘）。残局可低至 3 子
     * （帅仕 vs 将），故不能沿用 10——那会把稀疏残局永远拒之门外；
     * 结构合法性由 AssistBoard.validate、瞬时坏帧由 BoardTracker 的多帧确认把关。
     */
    fun map(dets: List<YoloDetection>, frameW: Int, frameH: Int, minPieces: Int = 3): MappedBoard? {
        val pieces = dets.filter { !it.isBoard }
        if (pieces.size < minPieces) return null

        val boardDet = dets.filter { it.isBoard }.maxByOrNull { it.score }
        val avgW = pieces.map { it.w }.average()
        val avgH = pieces.map { it.h }.average()

        var bx0 = 0.0; var by0 = 0.0; var bx1 = 0.0; var by1 = 0.0
        var anchored = false
        if (boardDet != null) {
            val x0 = boardDet.cx - boardDet.w / 2
            val y0 = boardDet.cy - boardDet.h / 2
            val x1 = boardDet.cx + boardDet.w / 2
            val y1 = boardDet.cy + boardDet.h / 2
            val ratio = (x1 - x0) / (y1 - y0)
            if (ratio in 0.7..1.3 && (x1 - x0) >= avgW * 7 && (y1 - y0) >= avgH * 8) {
                bx0 = x0; by0 = y0; bx1 = x1; by1 = y1
                anchored = true
            }
        }
        if (!anchored) {
            // 兜底：棋子中心包围盒（至少需要 20 子才可信）
            if (pieces.size < 20) return null
            val minX = pieces.minOf { it.cx }; val maxX = pieces.maxOf { it.cx }
            val minY = pieces.minOf { it.cy }; val maxY = pieces.maxOf { it.cy }
            val w = maxX - minX; val h = maxY - minY
            if (w <= 0 || h <= 0) return null
            if (w / h !in 0.7..1.3) return null
            // 最小棋子间距应与包围盒跨度相容（近似 8 列 9 行的等距网格）
            var minDist = Double.MAX_VALUE
            for (i in pieces.indices) for (j in i + 1 until pieces.size) {
                val dx = pieces[i].cx - pieces[j].cx
                val dy = pieces[i].cy - pieces[j].cy
                val d = sqrt(dx * dx + dy * dy)
                if (d < minDist) minDist = d
            }
            if (h < minDist * 8.0 || w < minDist * 7.0) return null
            bx0 = minX; by0 = minY; bx1 = maxX; by1 = maxY
        }

        val gridW = (bx1 - bx0) / 8.0
        val gridH = (by1 - by0) / 9.0
        if (gridW <= 0 || gridH <= 0) return null

        val cells = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        val cellScore = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        var dropped = 0
        var scoreSum = 0.0
        for (p in pieces) {
            val col = ((p.cx - bx0) / gridW).roundToInt()
            val row = ((p.cy - by0) / gridH).roundToInt()
            if (col !in 0 until AssistBoard.W || row !in 0 until AssistBoard.H) { dropped++; continue }
            if (cells[row][col] != Piece.EMPTY && cellScore[row][col] >= p.score) { dropped++; continue }
            if (cells[row][col] != Piece.EMPTY) scoreSum -= cellScore[row][col]
            cells[row][col] = p.piece
            cellScore[row][col] = p.score
            scoreSum += p.score
        }
        val pieceCount = cells.sumOf { row -> row.count { it != Piece.EMPTY } }

        val orientation = detectOrientation(cells) ?: Orientation.STANDARD
        val canonical = toCanonical(cells, orientation)

        val grid = try {
            BoardGrid.fromPxCorners(
                bx0.coerceIn(0.0, frameW.toDouble()), by0.coerceIn(0.0, frameH.toDouble()),
                bx1.coerceIn(0.0, frameW.toDouble()), by1.coerceIn(0.0, frameH.toDouble()),
                frameW, frameH)
        } catch (e: Exception) { null }

        return MappedBoard(cells, canonical, orientation, grid, pieceCount,
            if (pieceCount > 0) scoreSum / pieceCount else 0.0, dropped)
    }

    /** 双王位置判定屏幕朝向（帅在下=STANDARD，独立实现保持纯 JVM 无耦合） */
    fun detectOrientation(raw: Array<IntArray>): Orientation? {
        var redRow = -1
        var blackRow = -1
        for (gy in 0 until AssistBoard.H) for (gx in 0 until AssistBoard.W) {
            when (raw[gy][gx]) {
                Piece.WSHUAI -> redRow = gy
                Piece.BJIANG -> blackRow = gy
            }
        }
        if (redRow >= 0 && redRow > 4) return Orientation.STANDARD
        if (blackRow >= 0 && blackRow > 4) return Orientation.FLIPPED
        if (redRow >= 0 && redRow <= 4) return Orientation.FLIPPED
        if (blackRow >= 0 && blackRow <= 4) return Orientation.STANDARD
        return null
    }

    /** 屏幕 raw 布局 -> 内部 canonical（红恒在 y=9） */
    fun toCanonical(raw: Array<IntArray>, orientation: Orientation): Array<IntArray> {
        val out = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        for (gy in 0 until AssistBoard.H) for (gx in 0 until AssistBoard.W) {
            val p = raw[gy][gx]
            if (p == Piece.EMPTY) continue
            when (orientation) {
                Orientation.STANDARD -> out[gy][gx] = p
                Orientation.FLIPPED -> out[AssistBoard.H - 1 - gy][AssistBoard.W - 1 - gx] = p
            }
        }
        return out
    }
}
