package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Board
import com.xqdk.chess.gamelogic.Move

/**
 * UCCI 走法 -> 中文着法（复用项目 gamelogic 的 Move.getChsString 规则）。
 */
object MoveChinese {
    /** 返回如 "炮二平五 (h2e2)"；解析失败则原样返回 UCCI */
    fun describe(fen: String, ucci: String): String {
        val board = Board()
        if (!board.restoreFromFEN(fen)) return ucci
        val m = Move(board)
        if (m.fromUCCIString(ucci)) {
            return "${m.getChsString()} ($ucci)"
        }
        return ucci
    }
}
