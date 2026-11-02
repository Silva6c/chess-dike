package com.xqdk.chess.assist

/**
 * 空闲预计算（β）命中缓存：对方回合预案搜索（与常规同参）的结果存这里，对方
 * 实际落子后若命中预判线（其首着套用到父局面恰好得到识别确认的新局面），我方
 * 建议直接取自缓存——零等待，且不再发引擎请求（省一次搜索即省发热）。
 *
 * 线程模型：feed 在引擎线程（analysisListener.onSearchDone）、pick/clear 在
 * worker 线程；snapshot 为不可变整体引用的 @Volatile 原子换入换出，无需加锁
 * （与 currentFen 的跨线程约定同款）。
 *
 * 生存期：**跨 resetSuggestionState**——消费点就在局面确认 reset 之后，缓存若被
 * reset 清掉功能即失效；清空点在调用方（停止连线 / 开关关闭），引擎异常自愈后
 * 的陈旧缓存由"套中局面才算命中"天然兜底（applyUcci 全串比对，套不中即 miss）。
 */
class PonderCache {
    private data class Snapshot(val parentFen: String, val lines: List<AnalysisLine>)

    @Volatile private var snapshot: Snapshot? = null

    /** 存入一次对方回合预计算搜索的**最终**结果（onSearchDone，非中间 update） */
    fun feed(parentFen: String, result: AnalysisResult) {
        if (result.lines.isNotEmpty()) snapshot = Snapshot(parentFen, result.lines)
    }

    fun clear() {
        snapshot = null
    }

    /**
     * 命中查询：当前识别确认局面 currentFen 是否等于缓存父局面某预判线的首着
     * （pv[0]）套用所达局面。命中返回该预判线（调用方取 pv[1..] 为我方建议链，
     * 分数/深度沿用——预判线就是"对方走这着之后"的分析，评估直接有效），
     * 未命中返回 null。只考察 pv.size >= 2 的线：pv[1] 起才是我方着法链。
     */
    fun pick(currentFen: String): AnalysisLine? {
        val s = snapshot ?: return null
        for (line in s.lines) {
            val pv = line.pv
            if (pv.size < 2) continue
            val child = AssistBoard.applyUcci(s.parentFen, pv[0])
            if (child == currentFen) return line
        }
        return null
    }
}
