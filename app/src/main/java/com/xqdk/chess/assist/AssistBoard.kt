package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece

/**
 * 纯 JVM 的中国象棋棋盘抽象。
 * 约定与项目 gamelogic.Board 一致：piece[y][x]，y=0 为黑方底线，y=9 为红方底线（红在下即标准视角）。
 * 本文件不依赖任何 Android 类，便于单元测试。
 */
object AssistBoard {
    const val W = 9
    const val H = 10

    /** 标准开局 FEN（xqbase 格式，红先） */
    const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    /** 标准开局局面，piece[y][x]，红在 y=9 底部 */
    fun canonicalStart(): Array<IntArray> {
        val p = Array(H) { IntArray(W) }
        for (x in 0 until W) {
            p[0][x] = when (x) {
                0, 8 -> Piece.BJU
                1, 7 -> Piece.BMA
                2, 6 -> Piece.BXIANG
                3, 5 -> Piece.BSHI
                else -> Piece.BJIANG
            }
        }
        p[2][1] = Piece.BPAO
        p[2][7] = Piece.BPAO
        for (x in 0 until W step 2) p[3][x] = Piece.BZU
        for (x in 0 until W step 2) p[6][x] = Piece.WBING
        p[7][1] = Piece.WPAO
        p[7][7] = Piece.WPAO
        for (x in 0 until W) {
            p[9][x] = when (x) {
                0, 8 -> Piece.WJU
                1, 7 -> Piece.WMA
                2, 6 -> Piece.WXIANG
                3, 5 -> Piece.WSHI
                else -> Piece.WSHUAI
            }
        }
        return p
    }

    fun clone(p: Array<IntArray>): Array<IntArray> = Array(H) { p[it].clone() }

    fun equal(a: Array<IntArray>, b: Array<IntArray>): Boolean {
        for (y in 0 until H) for (x in 0 until W) if (a[y][x] != b[y][x]) return false
        return true
    }

    /** piece[y][x] -> xqbase FEN 盘面 + 走子方 */
    fun toFen(pieces: Array<IntArray>, redGo: Boolean): String {
        val sb = StringBuilder()
        for (y in 0 until H) {
            var zeros = 0
            for (x in 0 until W) {
                val p = pieces[y][x]
                if (p == Piece.EMPTY) zeros++
                else {
                    if (zeros > 0) { sb.append(zeros); zeros = 0 }
                    sb.append(Piece.pieceCharMap[p])
                }
            }
            if (zeros > 0) sb.append(zeros)
            if (y < H - 1) sb.append('/')
        }
        return "$sb ${if (redGo) "w" else "b"} - - 0 1"
    }

    fun piecesFromFen(fen: String): Array<IntArray> {
        val p = Array(H) { IntArray(W) }
        val board = fen.trim().split(' ')[0]
        var x = 0
        var y = 0
        for (c in board) {
            when {
                c == '/' -> { x = 0; y++ }
                c in '0'..'9' -> { x += c - '0' }
                else -> { p[y][x] = Piece.pieceValueMap[c] ?: Piece.EMPTY; x++ }
            }
        }
        return p
    }

    /**
     * 应用一个 ucci 着法（如 "h2e2"）到 FEN 局面，返回行棋方翻转后的新 FEN。
     * 着法非法或起点无子时原样返回。
     */    fun applyUcci(fen: String, ucci: String): String {
        if (ucci.length < 4) return fen
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        if (fx !in 0 until W || fy !in 0 until H || tx !in 0 until W || ty !in 0 until H) return fen
        val board = piecesFromFen(fen)
        val piece = board[fy][fx]
        if (piece == Piece.EMPTY) return fen
        board[ty][tx] = piece
        board[fy][fx] = Piece.EMPTY
        val nextRedGo = fen.trim().split(' ').getOrNull(1) != "w"
        return toFen(board, nextRedGo)
    }

    /**
     * 推断"刚走子的一方"：对比旧/新局面。返回红(1)、黑(0)或 null(无法确定)。
     * 规则：新增棋子（目标格）所属阵营即移动方；无新增（重排/异常）则尝试用移除方补。
     */
    fun movedSide(old: Array<IntArray>, new: Array<IntArray>): Int? {
        var addedSide: Int? = null
        var removedSide: Int? = null
        for (y in 0 until H) for (x in 0 until W) {
            val o = old[y][x]
            val n = new[y][x]
            if (o == n) continue
            if (n != Piece.EMPTY) {
                val s = if (Piece.isRed(n)) 1 else 0
                if (addedSide != null && addedSide != s) return null
                addedSide = s
            }
            if (o != Piece.EMPTY) {
                val s = if (Piece.isRed(o)) 1 else 0
                if (removedSide != null && removedSide != s) return null
                removedSide = s
            }
        }
        return addedSide ?: removedSide
    }

    /** 硬合法性校验：每个阵营的棋子数量必须在合法范围内，帅/将各一。返回问题列表，空表示通过 */
    fun validate(pieces: Array<IntArray>): List<String> {
        val issues = mutableListOf<String>()
        val counts = IntArray(Piece.BZU + 1)
        for (y in 0 until H) for (x in 0 until W) {
            val p = pieces[y][x]
            if (p != Piece.EMPTY) counts[p]++
        }
        if (counts[Piece.WSHUAI] != 1) issues.add("红帅数量异常:${counts[Piece.WSHUAI]}")
        if (counts[Piece.BJIANG] != 1) issues.add("黑将数量异常:${counts[Piece.BJIANG]}")
        val limits = mapOf(2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 5, 9 to 2, 10 to 2, 11 to 2, 12 to 2, 13 to 2, 14 to 5)
        for ((k, v) in limits) {
            if (counts[k] > v) issues.add("${Piece.getNameByValue(k)}数量超限:${counts[k]}")
        }
        var total = 0
        for (c in counts) total += c
        if (total > 32) issues.add("总子数超限:$total")
        return issues
    }

    /** 与开局对照一致的子数（用于"开局检测"）；开局局面只读缓存，避免每帧重建 */
    private val startBoard = canonicalStart()

    fun matchStartCount(pieces: Array<IntArray>): Int {
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            if (pieces[y][x] != Piece.EMPTY && pieces[y][x] == startBoard[y][x]) n++
        }
        return n
    }
}
