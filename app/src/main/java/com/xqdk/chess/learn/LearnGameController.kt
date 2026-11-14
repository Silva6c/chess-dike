package com.xqdk.chess.learn

import com.xqdk.chess.assist.AnalysisEngine
import com.xqdk.chess.assist.AnalysisLine
import com.xqdk.chess.assist.AnalysisResult
import com.xqdk.chess.gamelogic.Board
import com.xqdk.chess.gamelogic.Move
import com.xqdk.chess.gamelogic.Piece
import com.xqdk.chess.gamelogic.Position
import com.xqdk.chess.gamelogic.Rule
import java.util.ArrayDeque

/**
 * 学习模式对局状态机（2026-11-09 用户拍板功能）。
 *
 * 教学流程：人类走子 → 评估搜索（P'=走后对方行棋方，一次搜索两用：分值判定 +
 * bestmove 即皮卡鱼应着）→ 优解（≥-0.3 兵）放行并立即回着；坏棋 → RED_WARNING
 * 红箭头警示（盘面保持我方着实落预览）→ CORRECTION：我方着**逻辑撤回**（棋子回
 * 起点实子、可正常互动），终点格以"幽灵棋子+灰虚线框"标记（纯视觉层，见
 * LearnBoardView），requestOneShotPv 三解绿箭头指导 + 负分，等待更正。
 * 更正=评估新着；重复被撤的同一着=执迷不悟→放行并复用缓存的红箭头应着。
 *
 * 【雷区】逻辑盘面（[board]）是唯一权威状态：被拒/被撤的着法**从不落盘**，
 * EVALUATING/RED_WARNING 期的"已落"只是 [previewMove] 视觉预览，CORRECTION 期的
 * "已撤"只是 [ghostMove] 视觉标记——view 层据此叠加渲染，点选互动恒映射到逻辑
 * 盘面，"走其他错误着法"的自由互动不受幽灵层影响。
 * 【雷区】引擎回调一律 FEN 守卫（悔棋/新局在搜中时结果过期即丢）；interrupted
 * 结果不得作为决策依据（重发同 FEN 请求）。
 */
class LearnGameController(
    private val engine: LearnEngine,
    private val scheduler: Scheduler,
    private val listener: Listener,
) : AnalysisEngine.Listener {

    /** 延迟调度抽象（生产=主线程 Handler，测试=同步直跑），隔离 Android 依赖 */
    fun interface Scheduler {
        fun postDelayed(r: Runnable, delayMs: Long)
    }

    /** 渲染事件：controller 任何状态变化后回调，activity 从快照拉取渲染（pull 模型） */
    interface Listener {
        fun onRender()
        fun onEngineReady()
        fun onEngineError(message: String)
    }

    enum class State { HUMAN_TURN, EVALUATING, RED_WARNING, CORRECTION, WAITING_REPLY, GAME_OVER }

    /** 三解指导项（中文着法 + 我方视角分文案，CORRECTION 态渲染用） */
    data class GreenSolution(val move: Move, val chsText: String, val scoreText: String)

    companion object {
        /**
         * 优解判定的容忍损失（我方视角 cp，正数）：本手评估 ≥ 上一手评估 - 0.8 兵
         * 即放行。演进：①绝对阈值（≥-0.3）在执黑时系统性误判（评估打在对方行棋方
         * P' 上，强引擎执红先手分恒正）→ 改相对上一手评估的损失判定；②30→80——
         * 每手天然损失 = 行棋方易位 tempo（约 0.2~0.3 兵）+ 深度 12 评估噪声
         * （±0.2），30cp 底噪即触顶，"准确着子依然有指导"（用户二次报告）。
         * 80cp 只纠 0.8 兵以上的实质性错误。基线 = 上一手放行时的评估（≈当前局面
         * outlook，引擎回着搜索分数充当），首着无基线按 0 起算。
         * 【雷区】mate 哨兵（±10000）不参与损失算术——被防守后的真值与哨兵差
         * 9000+ 会把准确着子误判成巨亏，mate 场景走 handleEvalDone 的专属分支。
         */
        const val GOOD_LOSS_CP = 80
        /** mate 哨兵参与损失算术前的钳制值（防哨兵与真值差 9000+ 误判） */
        private const val MATE_CLAMP = 1500
        /** 红箭头警示期时长（用户流程"给与红色走法→然后撤回"的节拍） */
        const val RED_WARNING_MS = 1000L
        /** mate 分在 cp 语义下的哨兵值（阈值判定统一用） */
        private const val MATE_CP = 10000
    }

    // ==== 权威状态（activity 只读拉取）====
    var state: State = State.GAME_OVER
        private set
    var myRed: Boolean = true
        private set
    var board: Board = Board()
        private set

    // 互动态
    var selected: Position? = null
        private set
    var legalTargets: List<Position> = emptyList()
        private set

    // 叠加视觉态（逻辑盘面之外的渲染层）
    /** EVALUATING/RED_WARNING：我方着"已落"预览（棋子画在终点、起点留空） */
    var previewMove: Move? = null
        private set
    /** CORRECTION：被撤着法的幽灵标记（终点半透明子+虚线框，起点实子恢复） */
    var ghostMove: Move? = null
        private set
    /** 皮卡鱼应着/警示箭头（RED_WARNING 粗；CORRECTION 细——执迷后果预告） */
    var redArrow: Move? = null
        private set
    /** CORRECTION 三解绿箭头 */
    var greenSolutions: List<GreenSolution> = emptyList()
        private set

    // 分数/走势
    /** 最近一次放行着我方视角分（mate 用 ±MATE_CP 哨兵；null=尚无） */
    var lastEvalCp: Int? = null
        private set
    var lastEvalMate: Int? = null
        private set
    /** 走势点（我方视角 cp 原值，mate=±MATE_CP 哨兵；绘图端自行钳制） */
    val trend = mutableListOf<Int>()

    /** 终局文案（GAME_OVER 时非空） */
    var resultText: String? = null
        private set

    /** 最近一步已落盘的着法（我方放行着或皮卡鱼回着；琥珀角标渲染用，悔棋后清） */
    var lastMove: Move? = null
        private set

    // 纠正周期内部态
    private var rejectedMove: Move? = null
    private var rejectedEvalCp: Int = 0
    private var rejectedMate: Int? = null
    private var punishingMove: Move? = null
    private var pendingEvalFen: String? = null
    private var pendingGuidanceFen: String? = null
    private var pendingReplyFen: String? = null
    /** 同一评估请求的失败重发计数（引擎无应着时防死循环） */
    private var evalRetries = 0

    /** 我方决策点 FEN 栈（悔棋用：每个 HUMAN_TURN 入栈，撤 2 层=回到上一决策点） */
    private val decisionPoints = ArrayDeque<String>()

    val canUndo: Boolean
        get() = (state == State.HUMAN_TURN && decisionPoints.size >= 2) || state == State.CORRECTION


    /** 逻辑盘面 → 90 子平铺数组（canonical piece[y*9+x]，LearnBoardView 渲染契约） */
    fun piecesArray(): IntArray = IntArray(90) { i -> board.getPieceByPosition(i % 9, i / 9) }

    val isBusy: Boolean
        get() = state == State.EVALUATING || state == State.RED_WARNING || state == State.WAITING_REPLY

    /** 当前分值文案（我方视角；RED_WARNING/CORRECTION 期显示被撤着的负分"纠正"分） */
    val scoreText: String?
        get() = when {
            state == State.RED_WARNING || state == State.CORRECTION ->
                formatScoreCp(rejectedEvalCp, rejectedMate)
            lastEvalCp != null -> formatScoreCp(lastEvalCp!!, lastEvalMate)
            else -> null
        }

    // ==== 生命周期 ====

    /** 开新局：重置全部状态（不重启引擎进程——position fen 全量，无残差）。
     *  我方执黑时红先 = 皮卡鱼先走：直接进 WAITING_REPLY 请求开局着法 */
    fun startNewGame(myRedSide: Boolean) {
        myRed = myRedSide
        board = Board() // 默认构造=标准开局，红先
        state = State.HUMAN_TURN
        selected = null
        legalTargets = emptyList()
        previewMove = null
        ghostMove = null
        redArrow = null
        greenSolutions = emptyList()
        lastEvalCp = null
        lastEvalMate = null
        lastMove = null
        trend.clear()
        resultText = null
        rejectedMove = null
        punishingMove = null
        pendingEvalFen = null
        pendingGuidanceFen = null
        pendingReplyFen = null
        evalRetries = 0
        decisionPoints.clear()
        decisionPoints.addLast(board.toFENString())
        if (!myRedSide) {
            // 红先：皮卡鱼执红先行（首个决策点在回着落盘后由 applyEngineReply 入栈）
            pendingReplyFen = board.toFENString()
            state = State.WAITING_REPLY
            engine.request(pendingReplyFen!!)
        }
        listener.onRender()
    }

    // ==== 对局进度快照（退出持久化 / 重进恢复，2026-11-10 用户反馈"退出自动清理局面"）====

    /**
     * 对局进度快照（纯 JVM 数据；序列化落盘在 LearnActivity 侧）。
     * 只保**权威状态**：视觉叠加层（预览/幽灵/箭头）与在途引擎工作不保存——
     * 恢复一律落在干净的决策点上。
     */
    data class SavedGame(
        /** 权威盘面 FEN（含行棋方） */
        val fen: String,
        val myRed: Boolean,
        /** 我方决策点 FEN 栈（悔棋链；awaitingReply 时栈尾=走子前盘面） */
        val decisionPoints: List<String>,
        /** 走势点（我方视角 cp 原值，含 mate 哨兵 ±MATE_CP） */
        val trend: List<Int>,
        val lastEvalCp: Int?,
        val lastEvalMate: Int?,
        /** 最后落盘着的 UCCI（琥珀角标渲染用；null=尚无） */
        val lastMoveUcci: String?,
        /** true=我方着已落盘、正等皮卡鱼回着（恢复后须重发回着请求） */
        val awaitingReply: Boolean,
        /** 终局文案（GAME_OVER 恢复用；null=对局进行中） */
        val resultText: String?,
    )

    /**
     * 捕捉当前进度快照（LearnActivity.onPause 落盘，任意状态可捕捉）：
     * 静默态盘面在决策点；EVALUATING/RED_WARNING/CORRECTION 的在途着从未落盘
     * （恢复=回到走子前）；WAITING_REPLY 的我方着已落盘（awaitingReply 标记）；
     * GAME_OVER 保留终局文案。
     */
    fun captureSave() = SavedGame(
        fen = board.toFENString(),
        myRed = myRed,
        decisionPoints = decisionPoints.toList(),
        trend = trend.toList(),
        lastEvalCp = lastEvalCp,
        lastEvalMate = lastEvalMate,
        lastMoveUcci = lastMove?.getUCCIString(),
        awaitingReply = state == State.WAITING_REPLY,
        resultText = resultText.takeIf { state == State.GAME_OVER },
    )

    /**
     * 从快照恢复（LearnActivity.onCreate，引擎实例已重新启动）。恢复语义：
     * GAME_OVER→原样呈现终局；awaitingReply→WAITING_REPLY 并**重发回着请求**
     * （新引擎实例无从继承旧请求）；其余→HUMAN_TURN（在途评估/纠正叠加层不恢复，
     * 着法本就未落盘）。盘面 FEN 非法时返回 false，调用方按无存档开新局。
     */
    fun restoreSave(s: SavedGame): Boolean {
        val restored = Board()
        if (!restored.restoreFromFEN(s.fen)) return false
        board = restored
        myRed = s.myRed
        // 悔棋栈逐条校验（存档损坏时丢弃坏点），空栈兜底当前盘面
        decisionPoints.clear()
        decisionPoints.addAll(s.decisionPoints.filter { Board().restoreFromFEN(it) })
        if (decisionPoints.isEmpty()) decisionPoints.addLast(s.fen)
        trend.clear()
        trend.addAll(s.trend)
        lastEvalCp = s.lastEvalCp
        lastEvalMate = s.lastEvalMate
        lastMove = s.lastMoveUcci?.let { moveFromUcci(it, s.fen) }
        resultText = s.resultText
        // 叠加层与在途态一律不恢复：恢复点=干净决策点
        selected = null
        legalTargets = emptyList()
        previewMove = null
        ghostMove = null
        redArrow = null
        greenSolutions = emptyList()
        rejectedMove = null
        punishingMove = null
        pendingEvalFen = null
        pendingGuidanceFen = null
        evalRetries = 0
        state = when {
            s.resultText != null -> {
                pendingReplyFen = null
                State.GAME_OVER
            }
            s.awaitingReply -> {
                pendingReplyFen = board.toFENString()
                engine.request(pendingReplyFen!!)
                State.WAITING_REPLY
            }
            else -> {
                pendingReplyFen = null
                // HUMAN_TURN 恒有"栈尾=盘面"决策点（存档异常时补一条，悔棋链不悬空）
                if (decisionPoints.lastOrNull() != s.fen) decisionPoints.addLast(s.fen)
                State.HUMAN_TURN
            }
        }
        listener.onRender()
        return true
    }

    // ==== 点选互动（恒映射逻辑盘面）====

    fun onCellTap(x: Int, y: Int) {
        if (state != State.HUMAN_TURN && state != State.CORRECTION) return
        val pos = Position(x, y)
        // 已选中时点合法落点 → 尝试走子
        if (selected != null && pos in legalTargets) {
            attemptMove(selected!!, pos)
            return
        }
        val piece = board.getPieceByPosition(x, y)
        if (Piece.isValid(piece) && Piece.isRed(piece) == myRed) {
            selected = pos
            legalTargets = Rule.PossibleToPositions(piece, x, y, board)
        } else {
            selected = null
            legalTargets = emptyList()
        }
        listener.onRender()
    }

    /** 我方着法入口（起点已由选中态保证是我方子） */
    private fun attemptMove(from: Position, to: Position) {
        val mv = Move(from, to)
        if (!Rule.isValidMove(mv, board)) return

        // 执迷判定（仅 CORRECTION 态、与被撤的那一着 from/to 全等）→ 直接放行，
        // 复用纠正期的评估分与缓存的红箭头应着（用户拍板口径）
        val rejected = rejectedMove
        val isStubborn = state == State.CORRECTION && rejected != null &&
            rejected.fromPosition == from && rejected.toPosition == to
        if (isStubborn) {
            selected = null
            legalTargets = emptyList()
            acceptHumanMove(mv, rejectedEvalCp, rejectedMate,
                engineReplyUcci = punishingMove?.getUCCIString())
            return
        }

        selected = null
        legalTargets = emptyList()
        // 旧纠正周期的叠加层随新评估一并清除（幽灵/红箭头/三解属于被取代的那一轮；
        // rejectedMove 同步清空——若新着也是坏棋，handleEvalDone 会挂新一轮纠正）
        ghostMove = null
        redArrow = null
        greenSolutions = emptyList()
        rejectedMove = null
        punishingMove = null
        // 评估搜索打在 P'（走后对方行棋方）上：一次搜索两用（分值 + bestmove 回着）。
        // 盘面不改——"已落"由 previewMove 视觉承担
        val fenAfter = applyMoveToScratch(board, mv).toFENString()
        previewMove = mv
        state = State.EVALUATING
        pendingEvalFen = fenAfter
        evalRetries = 0
        engine.request(fenAfter)
        listener.onRender()
    }

    // ==== 引擎回调（AnalysisEngine.Listener）====

    override fun onEngineReady() {
        // 引擎回调在引擎线程：统一投递主线程执行（controller 单线程模型，免去共享
        // 字段 @Volatile 标记；测试注入同步 scheduler 则同线程直跑）
        scheduler.postDelayed({ listener.onEngineReady() }, 0)
    }

    override fun onSearchUpdate(result: AnalysisResult) {
        // 决策只认 bestmove（onSearchDone）；渐进 info 行对教学流程无意义
    }

    override fun onEngineError(message: String) {
        scheduler.postDelayed({ listener.onEngineError(message) }, 0)
    }

    override fun onSearchDone(result: AnalysisResult) {
        scheduler.postDelayed({ dispatchSearchDone(result) }, 0)
    }

    private fun dispatchSearchDone(result: AnalysisResult) {
        when {
            result.fen == pendingEvalFen && state == State.EVALUATING -> handleEvalDone(result)
            result.fen == pendingGuidanceFen && state == State.CORRECTION -> handleGuidanceDone(result)
            result.fen == pendingReplyFen && state == State.WAITING_REPLY -> handleReplyDone(result)
        }
    }

    /** 评估搜索收束：一次搜索两用（分值判定 + bestmove 即回着） */
    private fun handleEvalDone(result: AnalysisResult) {
        // 【雷区】interrupted 结果不得作为决策依据（被打断的浅结果深度不足）：
        // 学习模式无闪电打断，interrupted 只可能来自引擎管线失败——重发同 FEN
        val line = result.bestLine
        val mv = previewMove
        if (mv == null) {
            // 防御（state 守卫下理论不可达）：复位避免卡死在 EVALUATING
            pendingEvalFen = null
            state = State.HUMAN_TURN
            listener.onRender()
            return
        }
        if (result.interrupted || line == null || result.bestMove == null) {
            if (++evalRetries > 2) {
                // 引擎持续无应着：退回走子前状态，不吞掉用户那步棋的互动
                pendingEvalFen = null
                previewMove = null
                state = State.HUMAN_TURN
                listener.onEngineError("引擎评估失败，请重试")
                listener.onRender()
                return
            }
            engine.request(result.fen)
            return
        }

        val (ourCp, ourMate) = ourScoreOf(line)
        // 相对上一手评估的损失判定（基线=上一手放行时的评估；首着无基线按 0 起算）。
        // mate 场景不走损失算术（哨兵毒化基线，见 GOOD_LOSS_CP 雷区注释）：
        // 我方将杀→优；被将杀→纠；基线为我方有杀局→只要不崩盘（tCp ≥ -损失上限）
        // 都放行；基线为被杀局→纠正无意义，放行。
        val baselineMate = lastEvalMate
        val baseline = (lastEvalCp ?: 0).coerceIn(-MATE_CLAMP, MATE_CLAMP)
        val isGood = when {
            ourMate != null && ourMate > 0 -> true
            ourMate != null && ourMate < 0 -> false
            baselineMate != null && baselineMate < 0 -> true
            baselineMate != null && baselineMate > 0 -> ourCp >= -GOOD_LOSS_CP
            else -> ourCp >= baseline - GOOD_LOSS_CP
        }
        if (isGood) {
            // 优解放行：bestmove 即皮卡鱼应着（同一搜索），不再发请求
            acceptHumanMove(mv, ourCp, ourMate, engineReplyUcci = result.bestMove)
        } else {
            // 坏棋：进入纠正周期。缓存分数/应着；盘面保持 M 已落预览（preview 不动）
            // 并亮红箭头，RED_WARNING_MS 后转 CORRECTION（撤预览挂幽灵+三解搜索）
            rejectedMove = mv
            rejectedEvalCp = ourCp
            rejectedMate = ourMate
            punishingMove = moveFromUcci(result.bestMove, result.fen)
            pendingEvalFen = null
            redArrow = punishingMove
            state = State.RED_WARNING
            scheduler.postDelayed({
                // 守卫：等待期内新局会把状态切走，此时过期不转
                if (state == State.RED_WARNING) enterCorrection()
            }, RED_WARNING_MS)
            listener.onRender()
        }
    }

    /** RED_WARNING → CORRECTION：撤"已落"预览（M 从未落盘，清预览即回逻辑盘面），
     *  改挂幽灵标记并发起三解搜索 */
    private fun enterCorrection() {
        previewMove = null
        ghostMove = rejectedMove
        state = State.CORRECTION
        pendingGuidanceFen = board.toFENString()
        engine.requestOneShotPv(pendingGuidanceFen!!, 3)
        listener.onRender()
    }

    /** 三解搜索收束：绿箭头 + 分文案 */
    private fun handleGuidanceDone(result: AnalysisResult) {
        if (result.interrupted) {
            engine.requestOneShotPv(result.fen, 3)
            return
        }
        pendingGuidanceFen = null
        greenSolutions = result.lines.take(3).mapNotNull { line ->
            val ucci = line.pv.firstOrNull() ?: return@mapNotNull null
            val mv = moveFromUcci(ucci, result.fen) ?: return@mapNotNull null
            // 中文描述按逻辑盘面 P 生成（三解是 P 的候选）
            val chs = Move(mv.fromPosition, mv.toPosition, board).getChsString()
            val (cp, mate) = ourScoreOf(line)
            GreenSolution(mv, chs, formatScoreCp(cp, mate))
        }
        listener.onRender()
    }

    /** 执迷放行的兜底回着（缓存 R 缺失时正常搜 P'）；回着搜索的分数同时充当
     *  下一手判定的基线（执黑首着的基线即来自引擎开局搜索，见 handleEvalDone） */
    private fun handleReplyDone(result: AnalysisResult) {
        pendingReplyFen = null
        val ucci = result.bestMove
        if (result.interrupted || ucci == null) {
            engine.request(result.fen)
            return
        }
        // 基线回填：mate 必须与 cp 成对保留（不加走势点，走势仅随放行的我方着法
        // 推进）。【雷区】只写 cp 不写 mate 会重演哨兵毒化——回着线上 mate=±2 时
        // lastEvalCp=±10000，下一手判定被钳到 ±1500 基线：我方杀局基线下几乎
        // 任何普通棋都误判纠正（2026-11-10 折线/基线审计发现的真 bug）
        result.bestLine?.let {
            val (cp, mate) = ourScoreOf(it)
            lastEvalCp = cp
            lastEvalMate = mate
        }
        applyEngineReply(ucci)
    }

    // ==== 落盘与终局 ====

    /**
     * 放行我方着法：落盘 + 记走势 + 判对方是否已无着可走（将杀/困毙=我方直接胜，
     * 无需回着）；否则应用回着（同一评估搜索的 bestmove / 执迷缓存的 R）。
     * @param engineReplyUcci 回着 UCCI；null = 回着需另发搜索（执迷缓存缺失兜底）
     */
    private fun acceptHumanMove(mv: Move, ourCp: Int, ourMate: Int?, engineReplyUcci: String?) {
        board = applyMoveToScratch(board, mv)
        lastEvalCp = ourCp
        lastEvalMate = ourMate
        trend.add(ourCp)
        lastMove = mv
        previewMove = null
        ghostMove = null
        redArrow = null
        greenSolutions = emptyList()
        rejectedMove = null
        punishingMove = null

        // 我方一着将杀/困毙对方 → 直接终局（引擎无应着）
        if (!Rule.hasLegalMove(!myRed, board)) {
            endGame(iWon = true)
            return
        }

        if (engineReplyUcci != null) {
            applyEngineReply(engineReplyUcci)
        } else {
            // 执迷缓存缺失兜底：正常搜 P' 取回着
            val fenAfter = board.toFENString()
            pendingReplyFen = fenAfter
            state = State.WAITING_REPLY
            engine.request(fenAfter)
            listener.onRender()
        }
    }

    /** 应用皮卡鱼回着并切回我方回合（终局判定：我方无着可走=皮卡鱼胜） */
    private fun applyEngineReply(ucci: String) {
        // bestmove (none) = 行棋方无着可走（正常路径已被 hasLegalMove 预检拦截，
        // 此处为防御性兜底）：按困毙/将杀语义判我方胜
        if (ucci == "(none)") {
            endGame(iWon = true)
            return
        }
        val reply = moveFromUcci(ucci, board.toFENString()) ?: run {
            engineReplyRejected()
            return
        }
        val movedPiece = board.getPieceByPosition(reply.fromPosition)
        if (!Rule.isValidMove(reply, board) || !Piece.isValid(movedPiece) ||
            Piece.isRed(movedPiece) == myRed
        ) {
            engineReplyRejected()
            return
        }
        board = applyMoveToScratch(board, reply)
        lastMove = reply
        if (!Rule.hasLegalMove(myRed, board)) {
            endGame(iWon = false)
            return
        }
        state = State.HUMAN_TURN
        decisionPoints.addLast(board.toFENString())
        listener.onRender()
    }

    /** 引擎回着非法/不可解析（正常 bestmove 恒合法，属引擎异常）：如实上报，我方照常行棋 */
    private fun engineReplyRejected() {
        state = State.HUMAN_TURN
        decisionPoints.addLast(board.toFENString())
        listener.onEngineError("引擎回着异常，已跳过该手")
        listener.onRender()
    }    private fun endGame(iWon: Boolean) {
        pendingEvalFen = null
        pendingGuidanceFen = null
        pendingReplyFen = null
        selected = null
        legalTargets = emptyList()
        previewMove = null
        ghostMove = null
        redArrow = null
        greenSolutions = emptyList()
        // 困毙=未被将军但无着可走，同样判负（Rule.hasLegalMove 已含该语义）
        val loserRed = if (iWon) !myRed else myRed
        val inDanger = kingInDanger(loserRed)
        resultText = if (iWon) {
            if (inDanger) "我方获胜 · 将杀" else "我方获胜 · 对方困毙"
        } else {
            if (inDanger) "皮卡鱼获胜 · 我方被将杀" else "皮卡鱼获胜 · 我方困毙"
        }
        state = State.GAME_OVER
        listener.onRender()
    }

    /** 输掉一方的将帅是否正被攻击（区分将杀/困毙） */
    private fun kingInDanger(loserRed: Boolean): Boolean {
        val king = if (loserRed) Piece.WSHUAI else Piece.BJIANG
        val pos = Rule.findJiangShuaiPos(king, board) ?: return false
        return Rule.isJiangShuaiInDanger(king, pos, board)
    }

    // ==== 悔棋 / 帮助方法 ====

    fun undo() {
        when (state) {
            State.CORRECTION -> {
                // 退出纠正：清幽灵/箭头，回到该决策点（盘面从未变，无需恢复）
                ghostMove = null
                redArrow = null
                greenSolutions = emptyList()
                rejectedMove = null
                punishingMove = null
                pendingGuidanceFen = null
                selected = null
                legalTargets = emptyList()
                state = State.HUMAN_TURN
                listener.onRender()
            }
            State.HUMAN_TURN -> {
                // 撤 2 层（皮卡鱼回着 + 我方着）回到上一决策点。
                // 【近似】lastEvalCp 取回撤后的走势末点（上一手放行时的评估），而非
                // 该决策点当时的回着搜索基线——差一个回着效应（tempo 级 ≤0.3 兵），
                // 在 80cp 容差内可忽略；精确基线需为决策点栈并行存基线，不值当
                if (decisionPoints.size < 2) return
                decisionPoints.removeLast()
                board = Board().apply { restoreFromFEN(decisionPoints.last()) }
                trend.removeLastOrNull()
                lastEvalCp = trend.lastOrNull()
                lastEvalMate = null
                lastMove = null
                selected = null
                legalTargets = emptyList()
                listener.onRender()
            }
            else -> Unit // 转场态（EVALUATING/RED_WARNING/WAITING_REPLY/GAME_OVER）不响应
        }
    }

    /** P 盘面套用着法返回新 Board（copy-on-write：原盘面不动，历史/引擎回看安全） */
    private fun applyMoveToScratch(src: Board, mv: Move): Board {
        val scratch = Board(src)
        val piece = scratch.getPieceByPosition(mv.fromPosition)
        scratch.setPieceByPosition(mv.fromPosition, Piece.EMPTY)
        scratch.setPieceByPosition(mv.toPosition, piece)
        scratch.bRedGo = !src.bRedGo
        return scratch
    }

    /** UCCI → Move（挂上 fen 对应盘面，供 getChsString 中文描述），非法返回 null */
    private fun moveFromUcci(ucci: String, fen: String): Move? {
        val scratch = Board().apply { restoreFromFEN(fen) }
        val mv = Move(Position(0, 0), Position(0, 0), scratch)
        if (!mv.fromUCCIString(ucci)) return null
        return mv
    }

    /** 引擎行分数（红方视角）→ 我方视角：(cp 哨兵口径, 我方 mate 步数或 null)。
     *  mate>0=红将杀：我方红为胜、我方黑为败；cp 按 myRed 换算（黑方执方时取反） */
    private fun ourScoreOf(line: AnalysisLine): Pair<Int, Int?> {
        val m = line.mateIn
        if (m != null) {
            val ours = if (myRed) m else -m
            return (if (ours > 0) MATE_CP else -MATE_CP) to ours
        }
        val cp = if (myRed) line.redScoreCp else -line.redScoreCp
        return cp to null
    }

    /** 我方视角分文案（mate 用 M 格式，cp 用 ± 兵） */
    private fun formatScoreCp(cp: Int, mate: Int?): String {
        if (mate != null) return if (mate > 0) "M$mate" else "-M${-mate}"
        return if (cp >= 0) "+%.2f".format(cp / 100.0) else "%.2f".format(cp / 100.0)
    }
}
