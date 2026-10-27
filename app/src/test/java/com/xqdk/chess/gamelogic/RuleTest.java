package com.xqdk.chess.gamelogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 棋规回归测试：覆盖 2026-10 修复的三类缺陷——
 * 宫区兵误判过河、自杀着/暴露王不拦截、吃王不拦截（含 FEN 载入的非常规局面）。
 * 注意：本测试为纯 JVM 运行，用例不得触达 Rule/Board 内部 android.util.Log 调用路径。
 */
public class RuleTest {

    /** 构造最小局面：清盘后按 (x,y) 放子 */
    private Board boardOf(Object... pieces) {
        Board board = new Board();
        board.clear();
        for (int i = 0; i + 2 < pieces.length; i += 3) {
            int x = (Integer) pieces[i];
            int y = (Integer) pieces[i + 1];
            int piece = (Integer) pieces[i + 2];
            board.setPieceByPosition(x, y, piece);
        }
        return board;
    }

    @Test
    public void testRedBingNotCrossedCannotMoveSideways() {
        // 红兵在 (4,5)（红方半场，未过河）：只能前进到 (4,4)
        Board board = boardOf(4, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 5, Piece.WBING);
        List<Position> moves = Rule.PossibleToPositions(Piece.WBING, 4, 5, board);
        assertEquals(1, moves.size());
        assertEquals(new Position(4, 4), moves.get(0));
    }

    @Test
    public void testRedBingCrossedCanMoveSideways() {
        // 红兵在 (4,4)（黑方半场，已过河）：可前进/左/右
        Board board = boardOf(4, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 4, Piece.WBING);
        List<Position> moves = Rule.PossibleToPositions(Piece.WBING, 4, 4, board);
        assertEquals(3, moves.size());
    }

    @Test
    public void testBlackZuInPalaceFromFENCannotMoveSideways() {
        // 回归：FEN 载入的黑卒位于黑方宫区 (4,2)，此前 InArea==1 判定把它当过河卒（可横走）
        Board board = boardOf(4, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 2, Piece.BZU);
        List<Position> moves = Rule.PossibleToPositions(Piece.BZU, 4, 2, board);
        // 黑卒只能向下（+y）前进到 (4,3)
        assertEquals(1, moves.size());
        assertEquals(new Position(4, 3), moves.get(0));
    }

    @Test
    public void testSuicideMoveRejected() {
        // 红车在 (4,5) 牵制黑车 (4,0) 与红帅 (4,9) 同列：横向移开即暴露红帅，必须被拒
        Board board = boardOf(4, 0, Piece.BJU, 4, 9, Piece.WSHUAI, 4, 5, Piece.WJU);
        Move move = new Move(new Position(4, 5), new Position(3, 5), board);
        assertFalse("移开受牵制车导致送帅，应判非法", Rule.isValidMove(move, board));

        // 同列垫挡移动（(4,5)->(4,6) 仍在本列挡住）则安全
        Move safeMove = new Move(new Position(4, 5), new Position(4, 6), board);
        assertTrue("同列内移动仍挡住攻击线，应判合法", Rule.isValidMove(safeMove, board));
    }

    @Test
    public void testKingCaptureRejected() {
        // 回归：吃将/帅此前几何合法，落子后对方无王导致状态判定空指针崩溃
        Board board = boardOf(4, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 5, Piece.WJU);
        Move captureKing = new Move(new Position(4, 5), new Position(4, 0), board);
        assertFalse("王不可被吃", Rule.isValidMove(captureKing, board));
    }

    @Test
    public void testFlyKingExposureRejected() {
        // 将帅已在同列且中间仅有己方一子：移开该子造成照脸，必须被拒
        Board board = boardOf(4, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 5, Piece.WSHI);
        Move expose = new Move(new Position(4, 5), new Position(3, 5), board);
        assertFalse("移开遮挡子造成将帅照脸，应判非法", Rule.isValidMove(expose, board));
    }

    @Test
    public void testNormalMoveStillValid() {
        // 烟雾：常规马步不受王安全过滤影响（两王错开文件，避免初始照脸）
        Board board = boardOf(3, 0, Piece.BJIANG, 4, 9, Piece.WSHUAI, 4, 6, Piece.WMA);
        Move horseMove = new Move(new Position(4, 6), new Position(2, 5), board);
        assertTrue(Rule.isValidMove(horseMove, board));
    }
}
