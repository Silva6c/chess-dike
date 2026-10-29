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

/**
 * 引擎 info 行的中间累加态：同一 multiPv 的后续行（更深深度）会覆盖同键条目。
 * 【雷区】scoreCp/mateIn 保存的是**引擎原值（走子方视角）**，换算为红方视角只发生在
 * [toAnalysisLine]——先换算再累加会导致黑走时下一行 info 又以走子方视角覆盖，方向错乱。
 */
internal class MutableAnalysisLine(val multiPv: Int) {
    var depth = 0
    /** 走子方视角分数（引擎 info 原值） */
    var scoreCp = 0
    /** 走子方视角杀棋步数（引擎 info 原值，正=走子方将杀） */
    var mateIn: Int? = null
    var pv: List<String> = emptyList()

    /** 换算为红方视角：pikafish score 以走子方视角给出，黑方行棋时取反（原实现未换算，
     *  黑走时悬浮窗"红方优势/劣势"与 GOOD/BAD 色调、杀棋方向全部反转） */
    fun toAnalysisLine(redGo: Boolean): AnalysisLine = AnalysisLine(
        multiPv, depth,
        if (redGo) scoreCp else -scoreCp,
        mateIn?.let { if (redGo) it else -it },
        pv)
}

/**
 * 解析引擎 `info multipv N depth D score cp C ... pv X` 行。
 * 【雷区】pv 过滤条件是 token 长度恰为 4：UCCI 着法恒 4 字符（如 h2e2）；
 * info 行尾可能携带当前搜索的 pv 之外的注释 token（如 "string" 段、 "(none)"），
 * 放宽或收紧此条件都会把垃圾 token 混进着法链。
 * 返回 null = 无有效 score/mate 的行（如 currmove/nodes 统计行、引擎自述 string 行），
 * 调用方直接跳过。
 */
internal fun parseInfo(tokens: List<String>): MutableAnalysisLine? {
    var multiPv = 1
    var depth = 0
    var scoreCp: Int? = null
    var mateIn: Int? = null
    var pv: List<String> = emptyList()
    var i = 1
    while (i < tokens.size) {
        when (tokens[i]) {
            "depth" -> if (i + 1 < tokens.size) { depth = tokens[i + 1].toIntOrNull() ?: depth; i++ }
            "multipv" -> if (i + 1 < tokens.size) { multiPv = tokens[i + 1].toIntOrNull() ?: multiPv; i++ }
            "score" -> {
                if (i + 2 < tokens.size) {
                    when (tokens[i + 1]) {
                        "cp" -> { scoreCp = tokens[i + 2].toIntOrNull() ?: 0; i += 2 }
                        "mate" -> { mateIn = tokens[i + 2].toIntOrNull(); scoreCp = 0; i += 2 }
                    }
                }
            }
            "pv" -> { pv = tokens.subList(i + 1, tokens.size).filter { it.length == 4 }; i = tokens.size }
            "currmove" -> i = tokens.size // 不需要
        }
        i++
    }
    if (scoreCp == null && mateIn == null) return null
    return MutableAnalysisLine(multiPv).apply {
        this.depth = depth
        this.scoreCp = scoreCp ?: 0
        this.mateIn = mateIn
        this.pv = pv
    }
}
