package com.xqdk.chess.learn

import com.xqdk.chess.assist.AnalysisEngine
import com.xqdk.chess.assist.AnalysisLine
import com.xqdk.chess.assist.AnalysisResult
import com.xqdk.chess.gamelogic.Piece
import com.xqdk.chess.gamelogic.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习模式状态机纯 JVM 单测（fake 引擎 + 可手动逐任务 flush 的调度器）。
 * 坐标 canonical：红恒 y=9；UCCI 'h2e2'=(1,7)→(4,7)（红炮二平五）。
 *
 * 【雷区】controller 的引擎回调统一经 Scheduler 投递（生产=主线程 FIFO）——测试里
 * RecorderScheduler 记录任务，[RecorderScheduler.flush] 每次**只跑一个任务**，
 * 与生产的主线程串行语义一致：先 flush 跑掉"评估收束"，再 flush 跑掉"红警示转纠正"。
 */
class LearnGameControllerTest {

    /** fake 引擎：记录请求，由测试直接回调 controller */
    private class FakeLearnEngine : LearnEngine {
        val requests = mutableListOf<Pair<String, Int?>>() // fen to oneShotPv
        var started = false
        var shutDown = false

        override fun start() { started = true }
        override fun request(fen: String) { requests.add(fen to null) }
        override fun requestOneShotPv(fen: String, pv: Int) { requests.add(fen to pv) }
        override fun shutdown() { shutDown = true }
    }

    /** 记录式调度器：flush 每次只执行队首一个任务（镜像主线程 FIFO 逐任务语义） */
    private class RecorderScheduler : LearnGameController.Scheduler {
        val tasks = ArrayDeque<Runnable>()
        override fun postDelayed(r: Runnable, delayMs: Long) { tasks.addLast(r) }
        fun flush() {
            if (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class RecorderListener : LearnGameController.Listener {
        var renders = 0
        val errors = mutableListOf<String>()
        var readyCount = 0
        override fun onRender() { renders++ }
        override fun onEngineReady() { readyCount++ }
        override fun onEngineError(message: String) { errors.add(message) }
    }

    /** 构造一次评估结果（P' 盘面：红方视角分数按约定写在 redScoreCp，走子方由 redGo 表达） */
    private fun evalResult(fen: String, redGo: Boolean, redScoreCp: Int, bestMove: String?) =
        AnalysisResult(fen, redGo, bestMove,
            listOf(AnalysisLine(1, 12, redScoreCp, null, bestMove?.let { listOf(it) } ?: emptyList())))

    private fun line(multiPv: Int, redScoreCp: Int, firstMove: String) =
        AnalysisLine(multiPv, 12, redScoreCp, null, listOf(firstMove, "h9g7"))

    private fun newController(): Quad {
        val engine = FakeLearnEngine()
        val scheduler = RecorderScheduler()
        val controller = LearnGameController(engine, scheduler, RecorderListener())
        return Quad(controller, engine, scheduler)
    }

    private class Quad(
        val c: LearnGameController,
        val engine: FakeLearnEngine,
        val scheduler: RecorderScheduler,
    )

    /** 标准开局：红炮二平五（(1,7)→(4,7)）并返回引擎评估请求的 fenAfter */
    private fun playCannonToCenter(q: Quad): String {
        q.c.onCellTap(1, 7)
        assertTrue(q.c.legalTargets.any { it.x == 4 && it.y == 7 })
        q.c.onCellTap(4, 7)
        assertEquals(LearnGameController.State.EVALUATING, q.c.state)
        return q.engine.requests.last().first
    }

    @Test
    fun `新局初始状态`() {
        val q = newController()
        q.c.startNewGame(true)
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertTrue(q.c.myRed)
        assertEquals(32, q.c.piecesArray().count { it != 0 })
        assertTrue(q.c.trend.isEmpty())
        assertNull(q.c.scoreText)
    }

    @Test
    fun `优解放行且回着复用同一评估搜索`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        assertEquals(Position(1, 7) to Position(4, 7),
            q.c.previewMove!!.fromPosition to q.c.previewMove!!.toPosition)

        // 评估结果：红方视角 +40（优解），bestmove=黑方应着 h9g7（马(7,0)→(6,2)）
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = 40, bestMove = "h9g7"))
        q.scheduler.flush()

        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertNull(q.c.previewMove)
        // 落盘：炮在 (4,7)、起点空；黑马已应着 (6,2)
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(4, 7))
        assertEquals(Piece.EMPTY, q.c.board.getPieceByPosition(1, 7))
        assertEquals(Piece.BMA, q.c.board.getPieceByPosition(6, 2))
        // 一次搜索两用：回着没有另发请求
        assertEquals(1, q.engine.requests.size)
        assertEquals(listOf(40), q.c.trend)
        assertEquals("+0.40", q.c.scoreText)
        assertNotNull(q.c.lastMove)
    }

    @Test
    fun `坏棋触发纠正全流_幽灵与三解`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenBefore = q.c.board.toFENString()
        val fenAfter = playCannonToCenter(q)

        // 坏棋：红方视角 -120（< -30 阈值）→ 第一笔 flush 跑评估收束 → RED_WARNING
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.RED_WARNING, q.c.state)
        assertEquals("-1.20", q.c.scoreText) // 红警示期即显示被撤着的负分
        assertNotNull(q.c.previewMove)
        assertNotNull(q.c.redArrow)
        assertNull(q.c.ghostMove)
        // 盘面未落：炮仍在 (1,7)
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(1, 7))

        // 第二笔 flush 跑"红警示转纠正"：撤预览挂幽灵，发起三解搜索（requestOneShotPv 3）
        q.scheduler.flush()
        assertEquals(LearnGameController.State.CORRECTION, q.c.state)
        assertNull(q.c.previewMove)
        assertEquals(Position(1, 7) to Position(4, 7),
            q.c.ghostMove!!.fromPosition to q.c.ghostMove!!.toPosition)
        val guidanceReq = q.engine.requests.last()
        assertEquals(fenBefore to 3, guidanceReq.first to guidanceReq.second)

        // 三解返回
        q.c.onSearchDone(AnalysisResult(fenBefore, true, null, listOf(
            line(1, 45, "h2e2"), line(2, 30, "b0c2"), line(3, 12, "e3e4"))))
        q.scheduler.flush()
        assertEquals(3, q.c.greenSolutions.size)
        assertEquals("炮二平五", q.c.greenSolutions[0].chsText)
        assertEquals("-1.20", q.c.scoreText) // 纠正期显示被撤着的负分
        // 逻辑盘面始终是走子前局面
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(1, 7))
        assertTrue(q.c.trend.isEmpty()) // 被拒的着不计走势
    }

    @Test
    fun `执迷不悟_重复同一坏棋放行并复用缓存应着`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush(); q.scheduler.flush() // → CORRECTION
        val before = q.engine.requests.size

        // CORRECTION 中原样重走 (1,7)→(4,7) → 放行，不重新评估、不另搜回着
        //（执迷路径不经 EVALUATING，onCellTap 内同步完成落盘与回着）
        q.c.onCellTap(1, 7)
        assertTrue(q.c.legalTargets.any { it.x == 4 && it.y == 7 })
        q.c.onCellTap(4, 7)
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertNull(q.c.ghostMove)
        assertEquals(before, q.engine.requests.size) // 复用缓存 R，零新请求
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(4, 7))
        assertEquals(Piece.BMA, q.c.board.getPieceByPosition(6, 2)) // 缓存应着 h9g7 已落
        assertEquals(listOf(-120), q.c.trend) // 被拒的着从不计走势，放行时记一次
    }

    @Test
    fun `更正后重新应对`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenBad = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenBad, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush(); q.scheduler.flush() // → CORRECTION

        // 走另一着：马(1,9)→(2,7)（b0c2）
        // 【回归锁】换着评估时旧纠正周期的叠加层（幽灵/红箭头/三解）一并清除
        q.c.onCellTap(1, 9)
        assertTrue(q.c.legalTargets.any { it.x == 2 && it.y == 7 })
        q.c.onCellTap(2, 7)
        assertEquals(LearnGameController.State.EVALUATING, q.c.state)
        assertNull(q.c.ghostMove)
        assertNull(q.c.redArrow)
        assertTrue(q.c.greenSolutions.isEmpty())
        val fenFix = q.engine.requests.last().first
        assertTrue(fenFix != fenBad)
        q.c.onSearchDone(evalResult(fenFix, redGo = false, redScoreCp = 60, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertNull(q.c.ghostMove)
        assertEquals(listOf(60), q.c.trend) // 被撤的坏棋从未计入
        assertEquals(Piece.WMA, q.c.board.getPieceByPosition(2, 7))
    }

    @Test
    fun `interrupted 结果重发同局面请求`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(AnalysisResult(fenAfter, false, null, emptyList(), interrupted = true))
        q.scheduler.flush()
        assertEquals(2, q.engine.requests.size) // 重发
        assertEquals(LearnGameController.State.EVALUATING, q.c.state)
    }

    @Test
    fun `评估持续失败退回走子前状态并报错`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        repeat(3) {
            q.c.onSearchDone(AnalysisResult(fenAfter, false, null, emptyList(), interrupted = true))
            q.scheduler.flush()
        }
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertNull(q.c.previewMove)
        // 炮未动（走子从未落盘）
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(1, 7))
        assertEquals(Piece.EMPTY, q.c.board.getPieceByPosition(4, 7))
        assertEquals(3, q.engine.requests.size) // 首发 + 两次重发
        // 炮未落盘已断言；错误上报断言见下一用例
    }

    @Test
    fun `评估失败报错给监听者`() {
        val engine = FakeLearnEngine()
        val scheduler = RecorderScheduler()
        val listener = RecorderListener()
        val c = LearnGameController(engine, scheduler, listener)
        c.startNewGame(true)
        c.onCellTap(1, 7); c.onCellTap(4, 7)
        val fenAfter = engine.requests[0].first
        repeat(3) {
            c.onSearchDone(AnalysisResult(fenAfter, false, null, emptyList(), interrupted = true))
            scheduler.flush()
        }
        assertEquals(1, listener.errors.size)
        assertTrue(listener.errors[0].contains("评估失败"))
        assertEquals(LearnGameController.State.HUMAN_TURN, c.state)
    }

    @Test
    fun `新局后过期评估回调被丢弃`() {
        val q = newController()
        q.c.startNewGame(true)
        val stale = playCannonToCenter(q)
        q.c.startNewGame(true) // 新局（此前请求已发）
        q.c.onSearchDone(evalResult(stale, redGo = false, redScoreCp = 40, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(32, q.c.piecesArray().count { it != 0 }) // 盘面未被过期结果污染
        assertTrue(q.c.trend.isEmpty())
    }

    @Test
    fun `悔棋撤两回完整流`() {
        val q = newController()
        q.c.startNewGame(true)
        val startFen = q.c.board.toFENString()
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = 40, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(listOf(40), q.c.trend)
        assertTrue(q.c.canUndo)

        q.c.undo()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(startFen, q.c.board.toFENString())
        assertTrue(q.c.trend.isEmpty())
        assertNull(q.c.scoreText)
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(1, 7))
    }

    @Test
    fun `纠正期悔棋退出纠正`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush(); q.scheduler.flush()
        assertEquals(LearnGameController.State.CORRECTION, q.c.state)

        q.c.undo()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertNull(q.c.ghostMove)
        assertNull(q.c.redArrow)
        assertTrue(q.c.greenSolutions.isEmpty())
        // 退出纠正后同一着重新评估（不再是执迷放行）
        q.c.onCellTap(1, 7); q.c.onCellTap(4, 7)
        assertEquals(LearnGameController.State.EVALUATING, q.c.state)
    }

    @Test
    fun `执黑正常好棋不触发纠正_相对损失判定`() {
        val q = newController()
        q.c.startNewGame(false) // 我方黑 → 红先 = 皮卡鱼先走
        // 消化引擎首着（红炮二平五 h2e2 = (7,7)→(4,7)，UCCI 文件从红方右手起数）；
        // 其搜索分数（红方视角 +30 → 我方 -30）成为下一手判定的基线
        q.c.onSearchDone(evalResult(q.engine.requests[0].first, redGo = true, redScoreCp = 30, bestMove = "h2e2"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(-30, q.c.lastEvalCp)
        assertEquals("-0.30", q.c.scoreText)

        // 【回归锁（2026-11-09 用户报告）】执黑时红方先手分恒正，绝对阈值会把
        // 正常好棋误判成坏棋（每手自动出提示）——改为相对基线的损失判定：
        // 我方 -40，损失仅 10cp（≥ 基线-30）→ 优解放行，不得触发纠正
        q.c.onCellTap(1, 2); q.c.onCellTap(4, 2) // 黑炮(1,2)→(4,2) 平中
        assertEquals(LearnGameController.State.EVALUATING, q.c.state)
        val fenAfter = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fenAfter, redGo = true, redScoreCp = 40, bestMove = "h2e2"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(listOf(-40), q.c.trend)

        // 大幅损失（红方视角 +250 → 我方 -250，损失 210cp）→ 纠正。
        // 注：(4,2) 炮身前 (4,3) 有己卒屏住纵向，横平 (3,2) 才是合法着
        q.c.onCellTap(4, 2); q.c.onCellTap(3, 2) // 黑炮(4,2)→(3,2)
        val fenBlunder = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fenBlunder, redGo = true, redScoreCp = 250, bestMove = "h2e2"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.RED_WARNING, q.c.state)
    }

    @Test
    fun `相对损失判定_红方损失15cp放行_损失175cp纠正`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = 40, bestMove = "h9g7"))
        q.scheduler.flush()
        // 基线 40：下一手评估 25（损失 15cp ≤ 80）→ 放行——锁定基线参与损失计算
        q.c.onCellTap(1, 9); q.c.onCellTap(2, 7) // 马 b0c2
        val fen2 = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fen2, redGo = false, redScoreCp = 25, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)

        // 基线 25：下一手评估 -150（损失 175cp > 80）→ 纠正。
        // 注：走马不走炮——(4,7) 炮身前 (4,6) 有己方兵，纵向被屏，(4,4) 并非合法落点；
        // 损失 35cp（-10）在新容差 80 内属"准确着子"，不再触发纠正（2026-11-09 用户报告）
        q.c.onCellTap(2, 7)
        assertTrue(q.c.legalTargets.any { it.x == 4 && it.y == 8 })
        q.c.onCellTap(4, 8) // 马(2,7)→(4,8)
        val fen3 = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fen3, redGo = false, redScoreCp = -150, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.RED_WARNING, q.c.state)
    }

    @Test
    fun `三解分数文案与我方视角`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenBad = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenBad, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush(); q.scheduler.flush()
        q.c.onSearchDone(AnalysisResult(q.c.board.toFENString(), true, null, listOf(
            line(1, 45, "h2e2"), line(2, -30, "b0c2"))))
        q.scheduler.flush()
        assertEquals(2, q.c.greenSolutions.size)
        assertEquals("+0.45", q.c.greenSolutions[0].scoreText)
        assertEquals("-0.30", q.c.greenSolutions[1].scoreText)
    }

    @Test
    fun `执黑开局皮卡鱼先行`() {
        val q = newController()
        q.c.startNewGame(false) // 我方黑 → 红先 = 皮卡鱼先走
        assertEquals(LearnGameController.State.WAITING_REPLY, q.c.state)
        assertEquals(1, q.engine.requests.size)
        assertEquals(q.c.board.toFENString() to null, q.engine.requests[0])
        // 引擎回着（红炮二平五 h2e2 = (7,7)→(4,7)）落盘后切到我方回合
        q.c.onSearchDone(evalResult(q.engine.requests[0].first, redGo = true, redScoreCp = 30, bestMove = "h2e2"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(Piece.WPAO, q.c.board.getPieceByPosition(4, 7))
        assertEquals(Piece.EMPTY, q.c.board.getPieceByPosition(7, 7))
        // 我方（黑）炮(1,2) 可正常点选走子
        q.c.onCellTap(1, 2)
        assertTrue(q.c.legalTargets.isNotEmpty())
    }

    @Test
    fun `mate 哨兵不毒化基线_防守后正常棋放行`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        // 第一手评估为"我方两步杀"：lastEvalCp=±10000 哨兵、lastEvalMate=2
        q.c.onSearchDone(AnalysisResult(fenAfter, false, "h9g7", listOf(
            AnalysisLine(1, 12, 0, 2, listOf("h9g7")))))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)

        // 对方防守后我方杀局消失、评估回落 +6.00——若哨兵参与损失算术
        //（600 ≥ 10000-80 不成立）会误判纠正；mate 分支应放行
        q.c.onCellTap(1, 9); q.c.onCellTap(2, 7) // 马 b0c2
        val fen2 = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fen2, redGo = false, redScoreCp = 600, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)

        // 被将杀（我方视角 mate -2）→ 必纠正。走马(2,7)→(4,8)（(4,7) 炮被 (4,6)
        // 己兵屏住纵向，(4,4) 非法——手摆着法必须过合法表的又一例）
        q.c.onCellTap(2, 7)
        assertTrue(q.c.legalTargets.any { it.x == 4 && it.y == 8 })
        q.c.onCellTap(4, 8)
        val fen3 = q.engine.requests.last().first
        q.c.onSearchDone(AnalysisResult(fen3, false, "h9g7", listOf(
            AnalysisLine(1, 12, 0, -2, listOf("h9g7")))))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.RED_WARNING, q.c.state)
    }

    @Test
    fun `引擎回着 none 按无着可走判我方胜`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenAfter = playCannonToCenter(q)
        // 优解 + bestmove (none)（正常路径已被 hasLegalMove 预检拦截，此处为防御兜底）
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = 40, bestMove = "(none)"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.GAME_OVER, q.c.state)
        assertTrue(q.c.resultText!!.contains("我方获胜"))
    }

    // ==== 进度快照恢复（2026-11-10"退出自动清理局面"bug 的回归锁）====

    /** 走一手优解炮二平五并完成回着（第一整周期：走势 [40]、决策点 2 层） */
    private fun playOneGoodCycle(q: Quad) {
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = 40, bestMove = "h9g7"))
        q.scheduler.flush()
    }

    @Test
    fun `进度快照恢复_轮我走态_盘面走势悔棋链还原`() {
        val q = newController()
        q.c.startNewGame(true)
        playOneGoodCycle(q)
        val save = q.c.captureSave()
        val fenSaved = q.c.board.toFENString()

        val q2 = newController()
        assertTrue(q2.c.restoreSave(save))
        assertEquals(LearnGameController.State.HUMAN_TURN, q2.c.state)
        assertEquals(fenSaved, q2.c.board.toFENString())
        assertEquals(listOf(40), q2.c.trend)
        assertEquals("+0.40", q2.c.scoreText)
        assertTrue(q2.c.canUndo)
        assertNotNull(q2.c.lastMove)
        // 恢复后悔棋链可用：撤回到开局
        q2.c.undo()
        assertEquals(LearnGameController.State.HUMAN_TURN, q2.c.state)
        assertEquals(32, q2.c.piecesArray().count { it != 0 })
        assertTrue(q2.c.trend.isEmpty())
    }

    @Test
    fun `进度快照恢复_等回着态_重发回着请求`() {
        val q = newController()
        q.c.startNewGame(false) // 执黑：皮卡鱼先走 → WAITING_REPLY
        assertEquals(LearnGameController.State.WAITING_REPLY, q.c.state)
        val save = q.c.captureSave()
        assertTrue(save.awaitingReply)

        val q2 = newController()
        assertTrue(q2.c.restoreSave(save))
        assertEquals(LearnGameController.State.WAITING_REPLY, q2.c.state)
        // 恢复后重发回着请求（新引擎实例无从继承旧请求）
        assertEquals(listOf(save.fen), q2.engine.requests.map { it.first })
        // 回着落盘 → 回到我方回合
        q2.c.onSearchDone(evalResult(save.fen, redGo = true, redScoreCp = 20, bestMove = "h2e2"))
        q2.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q2.c.state)
    }

    /** 回归锁（2026-11-10 折线/基线审计）：回着搜索的 mate 必须成对进基线——
     *  只写 cp 不写 mate 时哨兵 ±10000 被钳到 ±1500 假基线，我方杀局基线下
     *  几乎任何普通棋都误判纠正 */
    @Test
    fun `回着搜索的mate基线保留_不毒化下一手判定`() {
        val q = newController()
        q.c.startNewGame(false)
        val fen0 = q.engine.requests.last().first
        // 皮卡鱼回着搜索：红方视角"红被杀"mate -2（=我方黑有杀局）+ 回着 h2e2
        q.c.onSearchDone(AnalysisResult(fen0, true, "h2e2", listOf(
            AnalysisLine(1, 12, 0, -2, listOf("h2e2")))))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(2, q.c.lastEvalMate) // 我方（黑）视角杀局基线已保留

        // 我方黑走炮二平五；普通小优（+30）在"我方有杀局基线"分支下应放行
        //（哨兵毒化时基线被钳成 +1500，任何普通棋都会误纠）
        q.c.onCellTap(1, 2)
        assertTrue(q.c.legalTargets.any { it.x == 4 && it.y == 2 })
        q.c.onCellTap(4, 2)
        val fen1 = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fen1, redGo = true, redScoreCp = -30, bestMove = "h9g7"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.HUMAN_TURN, q.c.state)
        assertEquals(listOf(30), q.c.trend)
    }

    @Test
    fun `进度快照恢复_纠正态退出_回到轮我走且叠加层清空`() {
        val q = newController()
        q.c.startNewGame(true)
        val fenBefore = q.c.board.toFENString()
        val fenAfter = playCannonToCenter(q)
        q.c.onSearchDone(evalResult(fenAfter, redGo = false, redScoreCp = -120, bestMove = "h9g7"))
        q.scheduler.flush() // 评估收束 → RED_WARNING
        q.scheduler.flush() // 红警示计时 → CORRECTION
        assertEquals(LearnGameController.State.CORRECTION, q.c.state)
        assertNotNull(q.c.ghostMove)

        // 纠正期退出：在途着从未落盘，恢复=回到走子前的干净决策点
        val save = q.c.captureSave()
        val q2 = newController()
        assertTrue(q2.c.restoreSave(save))
        assertEquals(LearnGameController.State.HUMAN_TURN, q2.c.state)
        assertEquals(fenBefore, q2.c.board.toFENString())
        assertNull(q2.c.previewMove)
        assertNull(q2.c.ghostMove)
        assertNull(q2.c.redArrow)
        assertTrue(q2.c.greenSolutions.isEmpty())
    }

    @Test
    fun `进度快照恢复_终局态_保留终局文案`() {
        val q = newController()
        q.c.startNewGame(true)
        playOneGoodCycle(q)
        // 第二手：马(1,9)→(2,7)（沿用 mate 测试验证过的合法手），(none) 兜底判胜
        q.c.onCellTap(1, 9)
        assertTrue(q.c.legalTargets.any { it.x == 2 && it.y == 7 })
        q.c.onCellTap(2, 7)
        val fen2 = q.engine.requests.last().first
        q.c.onSearchDone(evalResult(fen2, redGo = false, redScoreCp = 40, bestMove = "(none)"))
        q.scheduler.flush()
        assertEquals(LearnGameController.State.GAME_OVER, q.c.state)

        val save = q.c.captureSave()
        assertNotNull(save.resultText)
        assertFalse(save.awaitingReply) // 终局态不得标记在途回着

        val q2 = newController()
        assertTrue(q2.c.restoreSave(save))
        assertEquals(LearnGameController.State.GAME_OVER, q2.c.state)
        assertEquals(q.c.resultText, q2.c.resultText)
        assertEquals(listOf(40, 40), q2.c.trend) // 两手放行各记一点
        // 终局态不再响应悔棋，只能开新局
        q2.c.undo()
        assertEquals(LearnGameController.State.GAME_OVER, q2.c.state)
    }
}
