package com.xqdk.chess.assist.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.xqdk.chess.R
import kotlin.math.abs

/** 悬浮窗显示模型 */
class OverlayModel(
    /** 单行信息：状态/行权 + 建议或预测（服务端拼好） */
    val statusText: String = "识别中…",
    /** canonical 布局 90 子（红恒在 y=9），null 表示尚无局面 */
    val pieces: IntArray? = null,
    /** 推荐走法起止（canonical 内部坐标 x,y） */
    val arrowFrom: Pair<Int, Int>? = null,
    val arrowTo: Pair<Int, Int>? = null,
    /** 被识别屏幕红方在上（迷你棋盘随动：同样红在上显示） */
    val flipped: Boolean = false,
    val mySideIsRed: Boolean = true,
    /** 建议已"定着"（成熟）：箭头用绿色；false 为暂定（蓝箭头） */
    val mature: Boolean = false,
    /** 已停止指导（按钮显示“继续”） */
    val paused: Boolean = false,
    /** 引擎强度按钮文案（“深度12/20/24”，服务端生成） */
    val strengthText: String = "深度20",
    /** 引擎 Hash 按钮文案（“Hash256/512/…”，服务端生成） */
    val hashText: String = "Hash256",
    /** 紧急手动模式：小棋盘点按用于选子/走子/删子，而非切换候选 */
    val manualMode: Boolean = false,
    /** 手动模式下当前选中的格（canonical x,y），迷你棋盘绘制高亮 */
    val selectedCell: Pair<Int, Int>? = null,
)

enum class OverlayAction {
    /** 停止/继续指导 */
    PAUSE,
    /** 关闭连线（停止录取+移除面板） */
    CLOSE,
    /** 切换候选着法（变招） */
    CYCLE_CANDIDATE,
    /** 悔棋：回退到上一个已确认局面 */
    UNDO,
    /** 引擎强度档位切换（固定深度） */
    CYCLE_STRENGTH,
    /** 引擎 Hash 档位循环（256→512→1024→2048，与对弈设置同口径） */
    CYCLE_HASH,
    /** 更新棋局（原"轮次"）：翻转"我方/对方"走子方并按新轮次重建/重分析 */
    FLIP_TURN,
    /** 紧急手动模式开关：识别失效时在小棋盘上手工维护局面 */
    MANUAL,
}

class OverlayPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val chess: OverlayChessView
    private val status: TextView
    private val btnPause: TextView
    private val btnStrength: TextView
    private val btnHash: TextView
    private val btnClose: TextView

    var onAction: ((OverlayAction) -> Unit)? = null
    /** 手动模式：小棋盘点格（canonical x,y），由服务端处理选子/走子/删子 */
    var onCellTap: ((x: Int, y: Int) -> Unit)? = null
    /** 拖动开始/结束（用于抑制取帧时的面板隐藏动作） */
    var onDragStateChange: ((Boolean) -> Unit)? = null

    // 关闭二次确认：误触风险大，首次点按进入待确认态，3 秒内再点才真正关闭
    private var closeArmed = false
    private val closeNormalColor: Int
    private val closeReset = Runnable { resetCloseState() }

    private fun resetCloseState() {
        closeArmed = false
        btnClose.text = "关闭"
        btnClose.setTextColor(closeNormalColor)
    }

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var onMove: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        chess = findViewById(R.id.overlay_chess)
        status = findViewById(R.id.overlay_status)
        btnPause = findViewById(R.id.overlay_btn_pause)
        btnStrength = findViewById(R.id.overlay_btn_strength)
        btnHash = findViewById(R.id.overlay_btn_hash)
        btnClose = findViewById(R.id.overlay_btn_close)
        closeNormalColor = btnClose.currentTextColor

        findViewById<TextView>(R.id.overlay_btn_pause).setOnClickListener { onAction?.invoke(OverlayAction.PAUSE) }
        findViewById<TextView>(R.id.overlay_btn_change).setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
        findViewById<TextView>(R.id.overlay_btn_undo).setOnClickListener { onAction?.invoke(OverlayAction.UNDO) }
        findViewById<TextView>(R.id.overlay_btn_manual).setOnClickListener { onAction?.invoke(OverlayAction.MANUAL) }
        btnStrength.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_STRENGTH) }
        btnHash.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_HASH) }
        findViewById<TextView>(R.id.overlay_btn_turn).setOnClickListener { onAction?.invoke(OverlayAction.FLIP_TURN) }
        btnClose.setOnClickListener {
            if (!closeArmed) {
                closeArmed = true
                btnClose.text = "确认关闭?"
                btnClose.setTextColor(0xFFFF5252.toInt())
                postDelayed(closeReset, 3000)
            } else {
                removeCallbacks(closeReset)
                closeReset.run()
                onAction?.invoke(OverlayAction.CLOSE)
            }
        }
        chess.onNextCandidate = { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
        chess.onCellTap = { x, y -> onCellTap?.invoke(x, y) }
    }

    fun render(model: OverlayModel) {
        status.text = model.statusText
        btnPause.text = if (model.paused) "继续" else "停止"
        btnStrength.text = model.strengthText
        btnHash.text = model.hashText
        chess.pieces = model.pieces
        chess.arrowFrom = model.arrowFrom
        chess.arrowTo = model.arrowTo
        chess.flipped = model.flipped
        chess.mySideIsRed = model.mySideIsRed
        chess.mature = model.mature
        chess.manualMode = model.manualMode
        chess.selectedCell = model.selectedCell
    }

    /**
     * 全区域拖动：按住面板任意位置移动即可拖动（按钮点击不受影响——小于滑动阈值的
     * 点按会正常分发为点击）。[onMove] 在拖动结束时调用（用于持久化位置）。
     */
    fun setDragHandle(params: WindowManager.LayoutParams, wm: WindowManager, onMove: () -> Unit) {
        this.params = params
        this.wm = wm
        this.onMove = onMove
    }

    /** MOVE 超过阈值后拦截事件，交给 onTouchEvent 移动整个窗口 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = params?.x ?: 0
                startY = params?.y ?: 0
                dragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)) {
                    dragging = true
                    onDragStateChange?.invoke(true)
                    return true
                }
                return dragging
            }
        }
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val p = params
            if (dragging && p != null) {
                p.x = startX + (ev.rawX - downX).toInt()
                p.y = startY + (ev.rawY - downY).toInt()
                wm?.updateViewLayout(this, p)
                return true
            }
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                dragging = false
                onDragStateChange?.invoke(false)
                onMove?.invoke()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
