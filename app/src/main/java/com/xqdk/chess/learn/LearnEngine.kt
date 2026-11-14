package com.xqdk.chess.learn

import android.content.Context
import com.xqdk.chess.assist.AnalysisEngine

/**
 * 学习模式引擎抽象：只暴露"请求分析"面的 API，回调面由 [AnalysisEngine.Listener]
 * 承担（[LearnGameController] 直接实现该接口，由持有方接线）。生产实现
 * [PikafishLearnEngine] 包一层真机皮卡鱼；纯 JVM 测试注入 fake 记录请求、
 * 由测试代码直接回调 controller。
 */
interface LearnEngine {
    fun start()
    fun request(fen: String)
    fun requestOneShotPv(fen: String, pv: Int)
    fun shutdown()
}

/**
 * 生产实现：独立实例化一个皮卡鱼进程（与连线引擎互斥运行，见 LearnActivity 的
 * 停识别接线）。参数按用户拍板写死：深度 12（出招 ~1 秒级，纠正流程"评估+三解"
 * 两连搜不拖沓）、MultiPV=1（三解纠正走单次多路槽 requestOneShotPv，常规档不动）。
 */
class PikafishLearnEngine(private val context: Context) : LearnEngine {

    companion object {
        /** 学习模式固定搜索深度（用户拍板"不提供强度设置项"） */
        const val LEARN_DEPTH = 12
    }

    private var engine: AnalysisEngine? = null

    /** 持有方在创建 controller 后接线：回调面直通 controller */
    fun setListener(listener: AnalysisEngine.Listener) {
        check(engine == null) { "LearnEngine 重复接线" }
        engine = AnalysisEngine(
            context, listener,
            searchDepth = LEARN_DEPTH,
            maxPv = 1,
        )
    }

    override fun start() {
        engine?.start()
    }

    override fun request(fen: String) {
        engine?.request(fen)
    }

    override fun requestOneShotPv(fen: String, pv: Int) {
        engine?.requestOneShotPv(fen, pv)
    }

    override fun shutdown() {
        engine?.shutdown()
        engine = null
    }
}
