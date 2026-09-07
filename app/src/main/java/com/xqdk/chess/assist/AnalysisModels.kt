package com.xqdk.chess.assist

/**
 * 引擎分析结果模型。
 * 注意：pikafish 的 info score 以"当前走子方（side to move）"视角给出，本模块统一换算为红方视角。
 */
class AnalysisLine(
    val multiPv: Int = 1,
    val depth: Int = 0,
    /** 红方视角分数（centipawn），正数红方有利 */
    val redScoreCp: Int = 0,
    /** 红方视角杀棋：正=红方将杀，负=黑方将杀；null=非杀棋 */
    val mateIn: Int? = null,
    /** 变着（UCCI 4 字母） */
    val pv: List<String> = emptyList(),
) {
    /** 显示的分数文本，如 "+2.60"、"M3"、"-M2" */
    fun scoreText(): String {
        val m = mateIn
        if (m != null) return if (m > 0) "M$m" else "-M${-m}"
        return if (redScoreCp >= 0) "+%.2f".format(redScoreCp / 100.0)
        else "%.2f".format(redScoreCp / 100.0)
    }
}

class AnalysisResult(
    val fen: String,
    val redGo: Boolean,
    val bestMove: String?,
    val lines: List<AnalysisLine>,
) {
    val bestLine: AnalysisLine? get() = lines.firstOrNull()

    /** 首个走法（bestmove 的 UCCI） */
    val bestUcci: String? get() = bestMove ?: bestLine?.pv?.firstOrNull()
}
