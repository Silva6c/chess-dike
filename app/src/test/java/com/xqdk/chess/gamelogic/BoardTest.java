package com.xqdk.chess.gamelogic;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoardTest {

    @Test
    public void testToFENString() {
        Board board = new Board();
        String expectedFEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
        String actualFEN = board.toFENString();
        assertEquals(expectedFEN, actualFEN);
    }

    @Test
    public void testRestoreFromFEN() {
        Board board = new Board();
        board.randomizePieces();
        String fenString = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
        boolean result = board.restoreFromFEN(fenString);
        assertTrue(result);
        assertEquals(fenString, board.toFENString());
    }

    @Test
    public void testRestoreFromFENClearsOldPieces() {
        // 回归：先随机布子再载入"空盘 FEN"，此前会残留旧棋子（解析前未清盘）
        Board board = new Board();
        board.randomizePieces();
        String emptyFEN = "9/9/9/9/9/9/9/9/9/9 b - - 0 1";
        assertTrue(board.restoreFromFEN(emptyFEN));
        for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
                assertEquals(Piece.EMPTY, board.getPieceByPosition(x, y));
            }
        }
        assertEquals("9/9/9/9/9/9/9/9/9/9 b - - 0 1", board.toFENString());
    }

    @Test
    public void testGetPieceByPosition() {
        Board board = new Board();

        assertEquals(Piece.BJU, board.getPieceByPosition(0, 0));

        // 黑将在 y=0 行 x=4；红帅在 y=9 行 x=4（旧断言把 x/y 写反且越界）
        assertEquals(Piece.BJIANG, board.getPieceByPosition(4, 0));

        assertEquals(Piece.WSHUAI, board.getPieceByPosition(4, 9));

        // 越界返回 -1 的契约保持
        assertEquals(-1, board.getPieceByPosition(9, 4));
    }
}
