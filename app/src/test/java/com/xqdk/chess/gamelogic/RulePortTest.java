package com.xqdk.chess.gamelogic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rule.java 移植冒烟（2026-11-09 自 chess-fish 移植，改包名零逻辑改动——与原版
 * diff 仅包名/头注释）。
 * 【雷区】Board 默认构造 = 标准开局全套棋子：构造残局测试板必须先 clear() 再摆子，
 * 否则开局残留子会污染着法生成与将杀判定（首轮测试曾因此全部误报失败）。
 * 盘面坐标 canonical：红恒 y=9（黑在上 y=0）。
 */
public class RulePortTest {

    private static Position pos(int x, int y) {
        return new Position(x, y);
    }

    private static boolean contains(List<Position> list, int x, int y) {
        for (Position p : list) {
            if (p.x == x && p.y == y) return true;
        }
        return false;
    }

    @Test
    public void 开局各兵种的合法着法集() {
        Board b = new Board(); // 开局盘面直接可用

        // 车(0,9)：纵向 (0,8)(0,7)（(0,6) 己方兵拦），横向被己方马挡住
        List<Position> ju = Rule.PossibleToPositions(Piece.WJU, 0, 9, b);
        assertEquals(2, ju.size());
        assertTrue(contains(ju, 0, 8));
        assertTrue(contains(ju, 0, 7));

        // 马(1,9)：腿 (1,8) 空 → (2,7)(0,7)；(3,8) 因腿 (2,9) 己方相被蹩
        List<Position> ma = Rule.PossibleToPositions(Piece.WMA, 1, 9, b);
        assertEquals(2, ma.size());
        assertTrue(contains(ma, 2, 7));
        assertTrue(contains(ma, 0, 7));

        // 炮(1,7)：横 5+1、纵进 (1,8)、隔己车 (1,9)？否——纵下 (1,8) 后 (1,9) 己车为
        // 屏、屏后出界无吃；纵上 (1,6)(1,5)(1,4)(1,3) 四空 + 屏 (1,2) 黑炮后吃 (1,0) 黑马
        List<Position> pao = Rule.PossibleToPositions(Piece.WPAO, 1, 7, b);
        assertEquals(12, pao.size());
        assertTrue(contains(pao, 1, 0));  // 隔黑炮屏吃黑马
        assertTrue(contains(pao, 1, 3));  // 纵进空格
        assertTrue(contains(pao, 4, 7));  // 炮二平五
        assertFalse(contains(pao, 1, 2)); // 屏本身不可落
        assertFalse(contains(pao, 1, 9)); // 屏后出界无吃

        // 兵(0,6) 未过河：只能进一步
        List<Position> bing = Rule.PossibleToPositions(Piece.WBING, 0, 6, b);
        assertEquals(1, bing.size());
        assertTrue(contains(bing, 0, 5));

        // 帅(4,9)：只可进一步 (4,8)（左右被己方仕占）
        List<Position> shuai = Rule.PossibleToPositions(Piece.WSHUAI, 4, 9, b);
        assertEquals(1, shuai.size());
        assertTrue(contains(shuai, 4, 8));

        // 仕(3,9)：斜一步进宫心 (4,8)
        List<Position> shi = Rule.PossibleToPositions(Piece.WSHI, 3, 9, b);
        assertEquals(1, shi.size());
        assertTrue(contains(shi, 4, 8));

        // 相(2,9)：田字两步 (0,7)(4,7)，象眼 (1,8)(3,8) 均空
        List<Position> xiang = Rule.PossibleToPositions(Piece.WXIANG, 2, 9, b);
        assertEquals(2, xiang.size());
        assertTrue(contains(xiang, 0, 7));
        assertTrue(contains(xiang, 4, 7));
    }

    @Test
    public void isValidMove_蹩马腿无效() {
        Board b = new Board();
        b.clear();
        b.setPieceByPosition(pos(4, 4), Piece.WMA);
        // (4,4)→(5,6) 是马步 (dx=1,dy=2)，马腿在 (4,5)
        b.setPieceByPosition(pos(4, 5), Piece.BZU);
        Move mv = new Move(pos(4, 4), pos(5, 6));
        assertFalse(Rule.isValidMove(mv, b));
        b.setPieceByPosition(pos(4, 5), Piece.EMPTY);
        assertTrue(Rule.isValidMove(mv, b));
    }

    @Test
    public void 将杀判定() {
        Board b = new Board();
        b.clear();
        b.setPieceByPosition(pos(4, 0), Piece.BJIANG);
        b.setPieceByPosition(pos(3, 9), Piece.WSHUAI);
        // 车(4,2) 沿 x=4 将军；(0,0) 车封横线：王逃 (3,0)/(5,0) 仍被横线攻击
        //（模拟走子后 (4,0) 变空，扫描延伸），(4,1) 被 (4,2) 攻击——黑无着可走
        b.setPieceByPosition(pos(4, 2), Piece.WJU);
        b.setPieceByPosition(pos(0, 0), Piece.WJU);
        assertTrue(Rule.isJiangShuaiInDanger(Piece.BJIANG, pos(4, 0), b));
        assertFalse(Rule.hasLegalMove(false, b));
        assertTrue(Rule.hasLegalMove(true, b));
    }

    @Test
    public void 困毙判定() {
        Board b = new Board();
        b.clear();
        b.setPieceByPosition(pos(4, 0), Piece.BJIANG);
        b.setPieceByPosition(pos(4, 1), Piece.BSHI);
        // 车(4,4) 封 x=4（士在 (4,1) 挡住，将不受将；士一动即露将）；车(3,4)(5,4) 封逃点
        b.setPieceByPosition(pos(4, 4), Piece.WJU);
        b.setPieceByPosition(pos(3, 4), Piece.WJU);
        b.setPieceByPosition(pos(5, 4), Piece.WJU);
        b.setPieceByPosition(pos(3, 9), Piece.WSHUAI);
        // 未被将军但无着可走 = 困毙（同样判负）
        assertFalse(Rule.isJiangShuaiInDanger(Piece.BJIANG, pos(4, 0), b));
        assertFalse(Rule.hasLegalMove(false, b));
        assertTrue(Rule.hasLegalMove(true, b));
    }
}
