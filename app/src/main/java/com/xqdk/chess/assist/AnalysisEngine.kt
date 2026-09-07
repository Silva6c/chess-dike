package com.xqdk.chess.assist

import android.content.Context
import android.util.Log
import com.xqdk.chess.gamelogic.Piece
import org.petero.droidfish.engine.EngineConfig
import org.petero.droidfish.engine.UCIEngine
import org.petero.droidfish.engine.UCIEngineBase
import org.petero.droidfish.player.EngineListener

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 分析引擎：直接驱动一个独立 pikafish UCI 进程（与游戏内引擎进程互不干扰）。
 * 线程模型：单一串行线程执行"初始化/搜索"，保证同一时刻只有一个 position+go 在跑；
 * 收到 bestmove 或 search 超时后自动空闲，等待下一个局面。
 */
class AnalysisEngine(
    private val context: Context,
    private val listener: Listener,
    searchDepth: Int = 20,
    private val maxPv: Int = 3,
) {
    interface Listener {
        fun onEngineReady()
        fun onSearchUpdate(result: AnalysisResult)
        fun onSearchDone(result: AnalysisResult)
        fun onEngineError(message: String)
    }

    private val tag = "AnalysisEngine"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analysis-engine").apply { isDaemon = true }
    }
    private val engineListener = object : EngineListener {
        override fun reportEngineError(errMsg: String?) {
            Log.w(tag, "engine error: $errMsg")
            listener.onEngineError(errMsg ?: "引擎错误")
        }
        override fun notifyEngineName(engineName: String?) {}
        override fun notifySearchResult(searchId: Int, bestMove: String?, nextPonderMove: String?) {}
        override fun notifyEvalResult(searchId: Int, eval: Float) {}
        override fun notifyEngineInitialized() {}
    }

    @Volatile private var engine: UCIEngine? = null
    @Volatile private var ready = false
    @Volatile private var started = false

    @Volatile private var pendingFen: String? = null
    private val lock = Object()

    /** 搜索深度（与对弈"固定深度"同制式），可热更新，对下一次搜索生效 */
    @Volatile private var searchDepth: Int = searchDepth.coerceIn(6, 40)

    /** 引擎 Hash（MB，与对弈设置同口径），可热更新，对下一次搜索生效 */
    @Volatile private var hashMb: Int = 256
    @Volatile private var appliedHash = -1

    /** 热更新搜索深度（引擎强度档位），对下一次搜索生效 */
    fun setSearchDepth(depth: Int) {
        searchDepth = depth.coerceIn(6, 40)
    }

    /** 热更新 Hash（面板"Hash"按钮）：只在引擎空闲的两次搜索之间下发 setoption */
    fun setHash(mb: Int) {
        hashMb = mb
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        executor.execute {
            // 启动失败自动重试一次；仍失败才上报（服务端会把错误显示到悬浮窗状态栏）
            var attempt = 0
            while (!ready && !shuttingDown && attempt < 2) {
                attempt++
                try {
                    doStart()
                } catch (e: Exception) {
                    Log.e(tag, "engine start failed (attempt $attempt)", e)
                    if (attempt >= 2) {
                        listener.onEngineError("引擎启动失败：${e.message ?: "未知原因"}（请重新连线重试）")
                    }
                }
                if (!ready && !shuttingDown && attempt < 2) {
                    runCatching { engine?.shutDown() }
                    engine = null
                    try { Thread.sleep(1200) } catch (e: InterruptedException) { return@execute }
                }
            }
        }
    }

    private fun doStart() {
        val ec = EngineConfig().apply { workDir = context.filesDir.absolutePath }
        val eng: UCIEngine = UCIEngineBase.getEngine("pikafish", ec, engineListener)
            ?: throw IllegalStateException("无法创建 pikafish 引擎")
        engine = eng
        eng.initialize()

        // pikafish 只在收到 "uci" 后才输出 uciok（与游戏内 ComputerPlayer 的握手一致）
        eng.writeLineToEngine("uci")

        // 初始化握手：读 uciok，注册所有 option 行
        var uciok = false
        var elapsed = 0L
        val dl = 200L
        while (!uciok && elapsed < 10000) {
            // null = 引擎输出流已关闭（进程退出）：立即失败，不再空等超时
            val line = eng.readLineFromEngine(dl.toInt()) ?: throw IllegalStateException("引擎进程意外退出")
            elapsed += dl
            if (line.isEmpty()) continue
            val tokens = line.trim().split(Regex("\\s+"))
            when {
                tokens[0] == "uciok" -> uciok = true
                tokens[0] == "id" || tokens[0] == "option" -> eng.registerOption(tokens.toTypedArray())
            }
        }
        if (!uciok) throw IllegalStateException("引擎握手超时（uciok），多为 CPU/兼容性问题")

        // 与游戏内 ComputerPlayer 一致：uciok 后必须调用 initConfig 标记配置完成，
        // 否则 ExternalEngine 的启动看门狗会在 10 秒后误报 "UCI protocol error"
        eng.initConfig(ec)

        // 配置：权重/线程/Hash/多路PV（与对弈 setOptimizedThreads/applyEngineSetting 同口径）
        eng.setOption("EvalFile", "libpikafish.nnue.so")
        eng.setOption("Threads", Runtime.getRuntime().availableProcessors())
        eng.setOption("Hash", hashMb)
        eng.setOption("MultiPV", maxPv)
        appliedHash = hashMb

        // 与游戏内流程一致：新对局标记 + 就绪握手（显式发送 isready，引擎才会回复 readyok）
        eng.writeLineToEngine("ucinewgame")
        eng.writeLineToEngine("isready")
        var readyOk = false
        elapsed = 0L
        while (!readyOk && elapsed < 15000) {
            val line = eng.readLineFromEngine(dl.toInt()) ?: throw IllegalStateException("引擎进程意外退出")
            elapsed += dl
            if (line.trim() == "readyok") readyOk = true
        }
        if (!readyOk) throw IllegalStateException("引擎就绪握手失败（readyok）")
        ready = true
        Log.i(tag, "analysis engine ready")
        listener.onEngineReady()

        // 进入搜索循环：等待 request() 投递局面，无任务时休眠。
        // 注意：不做"同局面跳过"——重复局面（对局来回换子/悔棋重发）必须重新搜索，
        // 否则会永远停在旧结果上（表现为"卡在预测那一步、停止对我方指导"）。
        while (!shuttingDown) {
            val fen = pendingFen
            if (fen == null) {
                synchronized(lock) {
                    try { lock.wait(300) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
                continue
            }
            pendingFen = null
            searchOnce(fen)
        }
    }

    @Volatile private var shuttingDown = false

    /** 阻塞搜索一局局面直到 bestmove 或超时。支持外部 fetch 新局面（stop）。 */
    private fun searchOnce(fen: String) {
        val eng = engine ?: return
        val lines = LinkedHashMap<Int, MutableAnalysisLine>()
        // Hash 档位变更：仅在两次搜索之间下发（UCI 规范不建议搜索中 setoption）
        if (appliedHash != hashMb) {
            eng.setOption("Hash", hashMb)
            appliedHash = hashMb
        }
        eng.writeLineToEngine("position fen $fen")
        // 深度制（与对弈默认 "go depth 20" 同款）：算到目标深度自然收束，简单局面秒出
        eng.writeLineToEngine("go depth $searchDepth")

        // 兜底防呆（对弈制式本无超时）：极端情况下 60s 强制收束
        val deadline = System.currentTimeMillis() + 60000
        // bestmove 行标志结束；或因新周期 stop 导致 bestmove 立即返回
        while (System.currentTimeMillis() < deadline) {
            // null = 引擎输出流已关闭（进程退出/关停）：立即结束，避免空转至超时
            val line = eng.readLineFromEngine(250) ?: return
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            if (tokens[0] == "info") {
                // 引擎自述行（如 NNUE 加载信息）转发到 logcat，便于远程诊断棋力问题
                if (tokens.contains("string")) Log.i(tag, "engine: ${line.trim()}")
                parseInfo(tokens)?.let { ml ->
                    lines[ml.multiPv] = ml
                    val redGo = try {
                        fen.split(" ")[1] != "b"
                    } catch (e: Exception) { true }
                    val res = AnalysisResult(fen, redGo, null, lines.values.sortedBy { it.multiPv }.map {
                        it.toAnalysisLine()
                    })
                    listener.onSearchUpdate(res)
                }
            } else if (tokens[0] == "bestmove") {
                val bm = if (tokens.size > 1) tokens[1] else null
                val redGo = try { fen.split(" ")[1] != "b" } catch (e: Exception) { true }
                val res = AnalysisResult(fen, redGo, bm,
                    lines.values.sortedBy { it.multiPv }.map { it.toAnalysisLine() })
                listener.onSearchDone(res)
                return
            }
        }
        // 超时未收 bestmove：发给 stop 再等一次
        eng.writeLineToEngine("stop")
        val dl2 = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < dl2) {
            val line = eng.readLineFromEngine(250) ?: return
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            if (tokens[0] == "bestmove") {
                val bm = if (tokens.size > 1) tokens[1] else null
                val redGo = try { fen.split(" ")[1] != "b" } catch (e: Exception) { true }
                listener.onSearchDone(AnalysisResult(fen, redGo, bm,
                    lines.values.sortedBy { it.multiPv }.map { it.toAnalysisLine() }))
                return
            }
        }
        listener.onSearchDone(AnalysisResult(fen, true, null,
            lines.values.sortedBy { it.multiPv }.map { it.toAnalysisLine() }))
    }

    private class MutableAnalysisLine(val multiPv: Int) {
        var depth = 0
        var scoreCp = 0
        var mateIn: Int? = null
        var pv: List<String> = emptyList()
        fun toAnalysisLine(): AnalysisLine = AnalysisLine(multiPv, depth, scoreCp, mateIn, pv)
    }

    private fun parseInfo(tokens: List<String>): MutableAnalysisLine? {
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

    /**
     * 请求分析新局面。若引擎正忙于旧局面会先 stop（旧搜索的 bestmove 会很快返回并丢弃，旧结果由调用方按 FEN 比对丢弃）。
     * 调用线程任意；实际执行在串行引擎线程。
     */
    fun request(fen: String) {
        pendingFen = fen
        val eng = engine
        if (eng != null && ready) {
            // 仅在引擎线程工作忙时打断当前搜索；写命令是原子的短行，跨线程安全
            eng.writeLineToEngine("stop")
        }
        synchronized(lock) { lock.notifyAll() }
    }

    fun shutdown() {
        shuttingDown = true
        ready = false
        synchronized(lock) { lock.notifyAll() }
        try {
            engine?.shutDown()
        } catch (e: Exception) {
            Log.w(tag, "shutDown failed", e)
        }
        engine = null
        executor.shutdownNow()
    }
}
