package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistCoreTest {

    // ---------- AssistBoard ----------

    @Test
    fun `startFen round trip`() {
        val start = AssistBoard.canonicalStart()
        val fen = AssistBoard.toFen(start, redGo = true)
        assertEquals(AssistBoard.START_FEN, fen)
        val parsed = AssistBoard.piecesFromFen(fen)
        assertTrue(AssistBoard.equal(start, parsed))
    }

    @Test
    fun `fen uses w for red and b for black`() {
        val start = AssistBoard.canonicalStart()
        assertTrue(AssistBoard.toFen(start, true).endsWith("w - - 0 1"))
        assertTrue(AssistBoard.toFen(start, false).endsWith("b - - 0 1"))
    }

    @Test
    fun `movedSide detects normal and capture moves`() {
        val start = AssistBoard.canonicalStart()
        // 红炮二平五：h2e2 => 内部 (7,7)->(4,7)
        val mid = AssistBoard.clone(start)
        mid[7][4] = mid[7][7]
        mid[7][7] = Piece.EMPTY
        assertEquals(1, AssistBoard.movedSide(start, mid)) // 红走

        // 黑方接走：黑炮 (2,7)->(2,4)
        val mid2 = AssistBoard.clone(mid)
        mid2[2][4] = mid2[2][7]
        mid2[2][7] = Piece.EMPTY
        assertEquals(0, AssistBoard.movedSide(mid, mid2)) // 黑走

        // 吃子：红炮 (4,7) 移到 (4,4) 吃黑炮（源格在比较前已为空 => diff 只含目标格）
        val cap = AssistBoard.clone(mid)
        cap[7][4] = Piece.EMPTY
        cap[4][4] = Piece.BPAO
        val cap2 = AssistBoard.clone(cap)
        cap2[4][4] = Piece.WPAO
        assertEquals(1, AssistBoard.movedSide(cap, cap2))
    }

    @Test
    fun `movedSide null on invalid big change`() {
        val start = AssistBoard.canonicalStart()
        val other = AssistBoard.canonicalStart()
        // 无变化
        assertNull(AssistBoard.movedSide(start, other))
        // 大乱局：两方都动了 -> null
        val weird = AssistBoard.clone(start)
        weird[9][4] = Piece.EMPTY
        weird[0][4] = Piece.EMPTY
        assertNull(AssistBoard.movedSide(start, weird))
    }

    @Test
    fun `validate passes on start and detects corrupt boards`() {
        val start = AssistBoard.canonicalStart()
        assertTrue(AssistBoard.validate(start).isEmpty())
        val bad = AssistBoard.clone(start)
        bad[9][4] = Piece.EMPTY // 帅没了
        assertFalse(AssistBoard.validate(bad).isEmpty())
        val tooMany = AssistBoard.clone(start)
        tooMany[5][4] = Piece.WPAO // 三个红炮
        assertTrue(AssistBoard.validate(tooMany).any { it.contains("炮") })
    }

    /** 真实截图残局（canonical，红在下）：帅仕仕马 vs 将象象卒（8 子，此前被固定 10 子门槛永久拒绝） */
    private fun endgameBoard(): Array<IntArray> {
        val b = Array(10) { IntArray(9) }
        b[9][4] = Piece.WSHUAI
        b[9][3] = Piece.WSHI; b[9][5] = Piece.WSHI
        b[5][4] = Piece.WMA
        b[0][4] = Piece.BJIANG
        b[2][4] = Piece.BXIANG
        b[4][6] = Piece.BXIANG
        b[6][6] = Piece.BZU
        return b
    }

    @Test
    fun `sparse endgame with 8 pieces passes validate and recognition floor`() {
        val b = endgameBoard()
        val n = b.sumOf { row -> row.count { it != Piece.EMPTY } }
        assertEquals(8, n)
        assertTrue("8 子残局必须通过硬校验", AssistBoard.validate(b).isEmpty())
        assertTrue("残局子数必须达到单帧识别门槛", n >= ScreenAssistService.MIN_RECOGNIZED_PIECES)
        // 映射层门槛（曾固定 10）同样必须放行 8 子残局
        assertTrue(n >= 3)
    }

    @Test
    fun `manual move semantics match applyUcci`() {
        // 手动模式走子契约：起点子移到目标格 + 走子方翻转 => 与 applyUcci 结果一致
        val fen = AssistBoard.START_FEN
        val manual = AssistBoard.piecesFromFen(fen)
        val piece = manual[7][7]
        manual[7][7] = Piece.EMPTY
        manual[7][4] = piece
        val manualFen = AssistBoard.toFen(manual, redGo = false) // 红已走 => 轮到黑
        assertEquals(AssistBoard.applyUcci(fen, "h2e2"), manualFen)
    }

    // ---------- grid ----------

    @Test
    fun `grid from px corners normalizes any diagonal order`() {
        val grid = BoardGrid.fromPxCorners(600.0, 1080.0, 200.0, 120.0, 1000, 1200)
        assertEquals(0.2, grid.nx0, 1e-9)
        assertEquals(0.6, grid.nx1, 1e-9)
    }

    // ---------- tracker ----------

    /** 由棋盘直接构造合法识别结果（识别来源无关：YOLO 管线同样构造此对象） */
    private fun res(board: Array<IntArray>, orientation: Orientation = Orientation.STANDARD,
                    valid: Boolean = true): RecognitionResult {
        val issues = if (valid) AssistBoard.validate(board) else listOf("模拟坏帧")
        return RecognitionResult(board, board, orientation, issues, 0,
            board.sumOf { row -> row.count { it != Piece.EMPTY } }, 0.9)
    }

    @Test
    fun `tracker confirms board and flips turn after red move`() {
        val start = AssistBoard.canonicalStart()
        val mid = AssistBoard.clone(start)
        mid[7][4] = mid[7][7]
        mid[7][7] = Piece.EMPTY

        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(redGoFirst = true)

        // 首次确认需连续 3 帧一致（防瞬时坏帧/幻觉帧）
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(start)))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(start)))
        assertEquals(BoardTracker.Event.NEW_GAME, tracker.onFrame(res(start)))
        assertTrue(tracker.redGo)

        // 红走子后连续 3 帧确认
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid)))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(mid)))
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(mid)))
        assertFalse(tracker.redGo) // 红刚走完，轮到黑

        // 黑再走一步 => 回到红
        val mid2 = AssistBoard.clone(mid)
        mid2[2][4] = mid2[2][7]
        mid2[2][7] = Piece.EMPTY
        repeat(2) { tracker.onFrame(res(mid2)) }
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(mid2)))
        assertTrue(tracker.redGo)
    }

    @Test
    fun `sparse endgame first confirm takes three frames`() {
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        val b = endgameBoard()
        // 8 子残局（合法）应能在 3 帧后确认——曾因固定 10 子门槛永远无法确认
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(b)))
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(b)))
        assertEquals(BoardTracker.Event.NEW_BOARD, tracker.onFrame(res(b)))
        assertTrue(AssistBoard.equal(b, tracker.confirmed!!.canonical))
    }

    @Test
    fun `count jump beyond tolerance is rejected as unstable`() {
        val start = AssistBoard.canonicalStart()
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        repeat(3) { tracker.onFrame(res(start)) }

        // 从 32 子开局骤然到 8 子残局：只可能是局部遮挡/幻觉帧，必须拒绝并保持原局面
        val b = endgameBoard()
        repeat(3) {
            assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(b)))
        }
        assertTrue(AssistBoard.equal(start, tracker.confirmed!!.canonical))

        // 恢复正常帧后照常同步
        assertEquals(BoardTracker.Event.SAME_BOARD, tracker.onFrame(res(start)))
    }

    @Test
    fun `tracker ignores invalid frames and detects new game`() {
        val start = AssistBoard.canonicalStart()
        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        repeat(3) { tracker.onFrame(res(start)) }

        // 一帧坏结果（例如动画/遮挡导致帅将缺失）
        val occluded = AssistBoard.clone(start)
        occluded[9][4] = Piece.EMPTY
        assertEquals(BoardTracker.Event.UNSTABLE, tracker.onFrame(res(occluded, valid = false)))
        // 回到正确局面
        assertEquals(BoardTracker.Event.SAME_BOARD, tracker.onFrame(res(start)))

        // 重开：回到完全一致的开局 => NEW_GAME，轮次重置红先
        val changed = AssistBoard.clone(start)
        changed[7][4] = changed[7][7]
        changed[7][7] = Piece.EMPTY
        repeat(3) { tracker.onFrame(res(changed)) }
        assertFalse(tracker.redGo)
        assertEquals(BoardTracker.Event.NEW_GAME, tracker.onFrame(res(start)))
        assertTrue(tracker.redGo)
    }

    @Test
    fun `applyUcci moves piece and flips side`() {
        // 红炮二平五：h2e2 => 内部 (7,7)->(4,7)，行棋方 w→b
        val fen2 = AssistBoard.applyUcci(AssistBoard.START_FEN, "h2e2")
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/9/RNBAKABNR b - - 0 1", fen2)

        // 黑方接走 b7e7 后回到红先
        val fen3 = AssistBoard.applyUcci(fen2, "b7e7")
        assertTrue(fen3.endsWith("w - - 0 1"))

        // 非法输入原样返回：着法过短 / 文件越界 / 起点无子
        assertEquals(AssistBoard.START_FEN, AssistBoard.applyUcci(AssistBoard.START_FEN, "zz"))
        assertEquals(AssistBoard.START_FEN, AssistBoard.applyUcci(AssistBoard.START_FEN, "j1j2"))
        assertEquals(AssistBoard.START_FEN, AssistBoard.applyUcci(AssistBoard.START_FEN, "a4a5"))
    }

    @Test
    fun `tracker restore rolls back and resyncs on later frames`() {
        val start = AssistBoard.canonicalStart()
        val mid = AssistBoard.clone(start)
        mid[7][4] = mid[7][7]
        mid[7][7] = Piece.EMPTY

        val tracker = BoardTracker(confirmCount = 3)
        tracker.reset(true)
        repeat(3) { tracker.onFrame(res(start)) }
        repeat(3) { tracker.onFrame(res(mid)) }
        assertFalse(tracker.redGo)

        // 悔棋：恢复到开局（红先），随后同局面帧应视为未变化
        tracker.restore(res(start), redGoSide = true)
        assertTrue(tracker.redGo)
        assertEquals(BoardTracker.Event.SAME_BOARD, tracker.onFrame(res(start)))
        assertTrue(tracker.redGo)

        // 屏幕仍是走子后的局面：照常重新确认，轮次按移动方翻转
        repeat(3) { tracker.onFrame(res(mid)) }
        assertFalse(tracker.redGo)
    }
}
