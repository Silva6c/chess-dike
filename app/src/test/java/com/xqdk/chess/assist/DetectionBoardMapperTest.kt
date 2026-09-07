package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YOLO 检测 -> 9×10 棋盘映射的纯 JVM 单测。
 */
class DetectionBoardMapperTest {

    private val boardX0 = 100.0
    private val boardY0 = 200.0
    private val cellW = 90.0
    private val cellH = 80.0
    private val pieceSize = 66.0
    private val frameW = 1080
    private val frameH = 2400

    private fun labelIdOf(piece: Int): Int = YoloDetection.PIECE_CODES.indexOf(piece)

    private fun pieceDet(piece: Int, col: Double, row: Double, score: Double = 0.9): YoloDetection {
        val cx = boardX0 + col * cellW
        val cy = boardY0 + row * cellH
        return YoloDetection(labelIdOf(piece), score, cx, cy, pieceSize, pieceSize)
    }

    private val boardDet = YoloDetection(
        YoloDetection.LABEL_BOARD, 0.95,
        boardX0 + 4 * cellW, boardY0 + 4.5 * cellH, 8 * cellW, 9 * cellH)

    private fun startDetections(flipped: Boolean): List<YoloDetection> {
        val start = AssistBoard.canonicalStart()
        val out = ArrayList<YoloDetection>()
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val p = start[y][x]
            if (p == Piece.EMPTY) continue
            if (flipped) {
                // 屏幕上红在上：canonical(y,x) 出现在屏幕 (9-y, 8-x)
                out.add(pieceDet(p, (AssistBoard.W - 1 - x).toDouble(), (AssistBoard.H - 1 - y).toDouble()))
            } else {
                out.add(pieceDet(p, x.toDouble(), y.toDouble()))
            }
        }
        return out + boardDet
    }

    @Test
    fun `standard start maps to canonical start`() {
        val mapped = DetectionBoardMapper.map(startDetections(flipped = false), frameW, frameH)
        assertNotNull(mapped)
        assertTrue(AssistBoard.equal(AssistBoard.canonicalStart(), mapped!!.canonical))
        assertEquals(Orientation.STANDARD, mapped.orientation)
        assertEquals(32, mapped.pieceCount)
        assertEquals(0, mapped.dropped)
        assertTrue(mapped.avgScore > 0.85)
        assertNotNull(mapped.grid)
    }

    @Test
    fun `flipped start maps to canonical start with flipped orientation`() {
        val mapped = DetectionBoardMapper.map(startDetections(flipped = true), frameW, frameH)
        assertNotNull(mapped)
        assertTrue(AssistBoard.equal(AssistBoard.canonicalStart(), mapped!!.canonical))
        assertEquals(Orientation.FLIPPED, mapped.orientation)
        assertEquals(32, mapped.pieceCount)
    }

    @Test
    fun `out of grid pieces are dropped and higher score wins conflicts`() {
        // 补足 minPieces 门槛的无关棋子（分散在不同格）
        val filler = listOf(
            Piece.WJU to (0.0 to 9.0), Piece.WXIANG to (2.0 to 9.0), Piece.WSHI to (3.0 to 9.0),
            Piece.WMA to (7.0 to 9.0), Piece.WXIANG to (6.0 to 9.0), Piece.WSHI to (5.0 to 9.0),
            Piece.WBING to (0.0 to 6.0), Piece.WBING to (2.0 to 6.0), Piece.WBING to (4.0 to 6.0),
            Piece.WBING to (6.0 to 6.0), Piece.WBING to (8.0 to 6.0),
        ).map { pieceDet(it.first, it.second.first, it.second.second) }
        val dets = mutableListOf(
            boardDet,
            pieceDet(Piece.WSHUAI, 4.0, 9.0),
            // 越界：屏幕顶行之外（row=-1）
            YoloDetection(labelIdOf(Piece.BSHI), 0.91,
                boardX0 + 4 * cellW, boardY0 - cellH, pieceSize, pieceSize),
            // 同格冲突：低分先来、高分留下
            pieceDet(Piece.WPAO, 1.0, 7.0, score = 0.80),
            pieceDet(Piece.WMA, 1.0, 7.0, score = 0.93),
        )
        dets.addAll(3, filler)
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH)
        assertNotNull(mapped)
        assertEquals(Piece.WMA, mapped!!.screenRaw[7][1])
        assertEquals(Piece.WSHUAI, mapped.screenRaw[9][4])
        assertEquals(0, mapped.screenRaw[0][4])
        assertEquals(1, mapped.dropped) // 越界 1（同格冲突由高分覆盖，不计丢弃）
    }

    @Test
    fun `fallback to piece bounding box when board missing`() {
        val dets = startDetections(flipped = false).filter { !it.isBoard }
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH)
        assertNotNull(mapped)
        assertTrue(AssistBoard.equal(AssistBoard.canonicalStart(), mapped!!.canonical))
        assertEquals(32, mapped.pieceCount)
        assertNotNull(mapped.grid)
    }

    @Test
    fun `too few pieces without board returns null`() {
        val dets = listOf(
            pieceDet(Piece.WSHUAI, 4.0, 9.0),
            pieceDet(Piece.WJU, 0.0, 9.0),
            pieceDet(Piece.BJIANG, 4.0, 0.0),
            pieceDet(Piece.BJU, 0.0, 0.0),
            pieceDet(Piece.WBING, 4.0, 6.0),
        )
        assertNull(DetectionBoardMapper.map(dets, frameW, frameH))
    }

    @Test
    fun `sparse endgame maps when board box detected`() {
        // 真实截图残局（8 子）：board 框存在即应映射——曾因固定 10 子门槛永远返回 null
        val dets = listOf(
            boardDet,
            pieceDet(Piece.WSHUAI, 4.0, 9.0),
            pieceDet(Piece.WSHI, 3.0, 9.0),
            pieceDet(Piece.WSHI, 5.0, 9.0),
            pieceDet(Piece.WMA, 4.0, 5.0),
            pieceDet(Piece.BJIANG, 4.0, 0.0),
            pieceDet(Piece.BXIANG, 4.0, 2.0),
            pieceDet(Piece.BXIANG, 6.0, 4.0),
            pieceDet(Piece.BZU, 6.0, 6.0),
        )
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH)
        assertNotNull(mapped)
        assertEquals(8, mapped!!.pieceCount)
        assertTrue(AssistBoard.validate(mapped.canonical).isEmpty())
    }

    @Test
    fun `degenerate board box falls back to bbox`() {
        // board 框长宽比异常（1:2）=> 不应作为锚点，走 bbox 兜底
        val badBoard = YoloDetection(
            YoloDetection.LABEL_BOARD, 0.95,
            boardX0 + 4 * cellW, boardY0 + 4.5 * cellH, 4 * cellW, 9 * cellH)
        val dets = startDetections(flipped = false).filter { !it.isBoard } + badBoard
        val mapped = DetectionBoardMapper.map(dets, frameW, frameH)
        assertNotNull(mapped)
        assertTrue(AssistBoard.equal(AssistBoard.canonicalStart(), mapped!!.canonical))
    }
}
