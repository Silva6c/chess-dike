package com.xqdk.chess.learn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xqdk.chess.R
import com.xqdk.chess.assist.AssistConfig
import com.xqdk.chess.assist.ScreenAssistService

/**
 * 学习模式页（2026-11-09 用户拍板）：应用内棋盘 · 皮卡鱼人机对弈 + 走子纠正教学。
 * 只提供：悔棋 / 开新局 / 走势曲线 / 执红黑；唯一"建议"是走坏棋后的自动纠正
 * （幽灵棋子 + 三绿箭头），不提供其他任何功能。
 *
 * 线程模型：controller 的引擎回调统一投递主线程（LearnGameController.Scheduler），
 * 本页所有渲染与按钮均在主线程；引擎实例在本页 onDestroy 时 shutdown（互斥纪律）。
 * 对局进度持久化：onPause 快照落盘（learn_game prefs）、onCreate 有档即恢复——
 * 退出重进不清局面（2026-11-10 用户反馈修复）。
 *
 * 双引擎互斥（用户拍板）：进本页即绑定连线服务，onServiceConnected 时若识别仍在
 * 录屏会话中则 stopGuidance()——省一份 256MB Hash + NNUE 的双进程并发。
 */
class LearnActivity : AppCompatActivity() {

    private lateinit var controller: LearnGameController
    private lateinit var engine: PikafishLearnEngine

    private lateinit var boardView: LearnBoardView
    private lateinit var tvState: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvSolutions: TextView
    private lateinit var tvResult: TextView
    private lateinit var trendView: TrendChartView
    private lateinit var btnNewGame: Button
    private lateinit var btnUndo: Button
    private lateinit var btnSide: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 对局进度存档（独立 prefs 文件：会话数据，不混入 AssistConfig 配置面） */
    private val learnPrefs by lazy {
        getSharedPreferences("learn_game", Context.MODE_PRIVATE)
    }

    // ==== 双引擎互斥：绑定连线服务并停止识别 ====
    private var assistBound = false
    private val assistConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = binder as? ScreenAssistService.CastBinder
            assistBound = true
            // 仅录屏会话中才需要停（bind-only 空转时服务无会话，stopGuidance 虽幂等
            // 但会 stopSelf 空耗一次服务生命周期）
            if (svc?.service()?.isCapturing() == true) svc.service().stopGuidance()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** controller 渲染事件 → 拉取快照整页刷新 */
    private val renderer = object : LearnGameController.Listener {
        override fun onRender() {
            // controller 已保证全部回调在主线程（Scheduler 投递），此处直渲染
            render()
        }

        override fun onEngineReady() {
            render()
        }

        override fun onEngineError(message: String) {
            Toast.makeText(this@LearnActivity, message, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn)

        boardView = findViewById(R.id.learn_board)
        tvState = findViewById(R.id.tv_learn_state)
        tvScore = findViewById(R.id.tv_learn_score)
        tvSolutions = findViewById(R.id.tv_solutions)
        tvResult = findViewById(R.id.tv_result)
        trendView = findViewById(R.id.trend_chart)
        btnNewGame = findViewById(R.id.btn_new_game)
        btnUndo = findViewById(R.id.btn_undo)
        btnSide = findViewById(R.id.btn_side)

        val config = AssistConfig(this)
        engine = PikafishLearnEngine(applicationContext)
        controller = LearnGameController(
            engine = engine,
            scheduler = { r, delayMs -> mainHandler.postDelayed(r, delayMs) },
            listener = renderer,
        )
        engine.setListener(controller)
        engine.start()
        // 有进度存档则恢复（退出重进不清局面）；无存档/存档损坏→按执方开新局
        val saved = loadSavedGame()
        if (saved == null || !controller.restoreSave(saved)) {
            controller.startNewGame(config.learnSide != "black")
        }

        boardView.onCellTap = { x, y -> controller.onCellTap(x, y) }

        btnNewGame.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("开新局")
                .setMessage("当前对局将丢弃并重新开始，确定？")
                .setPositiveButton("开新局") { _, _ -> controller.startNewGame(controller.myRed) }
                .setNegativeButton("取消", null)
                .show()
        }
        btnUndo.setOnClickListener { controller.undo() }
        btnSide.setOnClickListener {
            val nextRed = !controller.myRed
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("切换执方")
                .setMessage("切换为执${if (nextRed) "红" else "黑"}方将开新局，确定？")
                .setPositiveButton("切换并开新局") { _, _ ->
                    config.learnSide = if (nextRed) "red" else "black"
                    controller.startNewGame(nextRed)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 双引擎互斥：进学习模式自动停识别（用户拍板）
        val intent = Intent(this, ScreenAssistService::class.java)
        assistBound = bindService(intent, assistConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        // 退出即存档（返回/切后台/页面被杀都安全）：captureSave 任意状态可捕捉，
        // 开新局后存的是新局快照，无需显式清理旧档
        saveGameSnapshot(controller.captureSave())
    }

    override fun onDestroy() {
        if (assistBound) unbindService(assistConnection)
        engine.shutdown()
        super.onDestroy()
    }

    // ==== 对局进度持久化（独立 prefs：对局是会话数据，不混入 AssistConfig 配置面）====

    private fun saveGameSnapshot(s: LearnGameController.SavedGame) {
        learnPrefs.edit()
            .putString(KEY_SAVE_FEN, s.fen)
            .putBoolean(KEY_SAVE_MY_RED, s.myRed)
            .putString(KEY_SAVE_DECISIONS, s.decisionPoints.joinToString("\n"))
            .putString(KEY_SAVE_TREND, s.trend.joinToString(","))
            .putInt(KEY_SAVE_LAST_CP, s.lastEvalCp ?: Int.MIN_VALUE)
            .putInt(KEY_SAVE_LAST_MATE, s.lastEvalMate ?: 0)
            .putString(KEY_SAVE_LAST_MOVE, s.lastMoveUcci ?: "")
            .putBoolean(KEY_SAVE_AWAITING, s.awaitingReply)
            .putString(KEY_SAVE_RESULT, s.resultText ?: "")
            .apply()
    }

    /** 读存档；无档或字段损坏（如走势串混入非数字）返回 null 按无存档开新局 */
    private fun loadSavedGame(): LearnGameController.SavedGame? {
        val fen = learnPrefs.getString(KEY_SAVE_FEN, null) ?: return null
        return try {
            LearnGameController.SavedGame(
                fen = fen,
                myRed = learnPrefs.getBoolean(KEY_SAVE_MY_RED, true),
                decisionPoints = (learnPrefs.getString(KEY_SAVE_DECISIONS, "") ?: "")
                    .split('\n').filter { it.isNotBlank() },
                trend = (learnPrefs.getString(KEY_SAVE_TREND, "") ?: "")
                    .split(',').filter { it.isNotBlank() }.map { it.toInt() },
                lastEvalCp = learnPrefs.getInt(KEY_SAVE_LAST_CP, Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE },
                lastEvalMate = learnPrefs.getInt(KEY_SAVE_LAST_MATE, 0).takeIf { it != 0 },
                lastMoveUcci = learnPrefs.getString(KEY_SAVE_LAST_MOVE, "")
                    ?.takeIf { it.isNotBlank() },
                awaitingReply = learnPrefs.getBoolean(KEY_SAVE_AWAITING, false),
                resultText = learnPrefs.getString(KEY_SAVE_RESULT, "")
                    ?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // 对局进度存档键（learn_game prefs）：lastEvalCp 哨兵 MIN_VALUE=null；
        // mateIn 恒 |m|≥1，0 即 null；UCCI/终局文案空串即 null
        private const val KEY_SAVE_FEN = "save_fen"
        private const val KEY_SAVE_MY_RED = "save_my_red"
        private const val KEY_SAVE_DECISIONS = "save_decisions"
        private const val KEY_SAVE_TREND = "save_trend"
        private const val KEY_SAVE_LAST_CP = "save_last_cp"
        private const val KEY_SAVE_LAST_MATE = "save_last_mate"
        private const val KEY_SAVE_LAST_MOVE = "save_last_move"
        private const val KEY_SAVE_AWAITING = "save_awaiting"
        private const val KEY_SAVE_RESULT = "save_result"
    }

    /** 拉取 controller 快照整页刷新（controller 已保证主线程调用） */
    private fun render() {
        boardView.pieces = controller.piecesArray()
        boardView.flipped = !controller.myRed
        boardView.previewMove = controller.previewMove
        boardView.ghostMove = controller.ghostMove
        boardView.redArrow = controller.redArrow
        boardView.redArrowDim = controller.state == LearnGameController.State.CORRECTION
        boardView.greenArrows = controller.greenSolutions.map { it.move }
        boardView.lastMove = controller.lastMove
        boardView.selected = controller.selected
        boardView.legalTargets = controller.legalTargets

        tvState.text = when (controller.state) {
            LearnGameController.State.HUMAN_TURN -> "轮到你走"
            LearnGameController.State.EVALUATING -> "评估中…"
            LearnGameController.State.RED_WARNING -> "这步欠佳 · 红箭头是皮卡鱼的应着"
            LearnGameController.State.CORRECTION ->
                if (controller.greenSolutions.isEmpty()) "纠正中… 参考绿色箭头更正"
                else "参考绿色箭头更正（灰框为被撤的那步）"
            LearnGameController.State.WAITING_REPLY -> "皮卡鱼思考中…"
            LearnGameController.State.GAME_OVER -> controller.resultText ?: "对局结束"
        }
        tvScore.text = controller.scoreText?.let { "我方 $it" } ?: ""
        tvScore.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    controller.scoreText?.startsWith("-") == true -> R.color.xq_warn
                    controller.scoreText != null -> R.color.xq_jade
                    else -> R.color.xq_muted
                },
            )
        )
        if (controller.state == LearnGameController.State.CORRECTION && controller.greenSolutions.isNotEmpty()) {
            tvSolutions.text = controller.greenSolutions
                .mapIndexed { i, s -> "${i + 1}. ${s.chsText} ${s.scoreText}" }
                .joinToString("    ")
            tvSolutions.visibility = android.view.View.VISIBLE
        } else {
            tvSolutions.visibility = android.view.View.GONE
        }
        trendView.points = controller.trend
        tvResult.visibility =
            if (controller.state == LearnGameController.State.GAME_OVER) android.view.View.VISIBLE
            else android.view.View.GONE
        if (controller.state == LearnGameController.State.GAME_OVER) tvResult.text = controller.resultText

        btnUndo.isEnabled = controller.canUndo
        btnSide.text = if (controller.myRed) "执红" else "执黑"
    }
}
