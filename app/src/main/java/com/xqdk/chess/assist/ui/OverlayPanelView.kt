package com.xqdk.chess.assist.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.xqdk.chess.R
import kotlin.math.abs

/** 状态语义档（颜色由当前主题调色板解析，见 QiPalette）：
 *  运行=玉 / 等待=琥珀 / 停止=灰 / 异常=警示红 / 手动=琥珀 */
enum class OverlayStateKind { RUNNING, WAITING, IDLE, ERROR, MANUAL }

/** 文本语义色调（优势行 / 候选行）：GOOD=玉、BAD=警示、WARN=琥珀、NONE=主文字 */
enum class QiTone { NONE, GOOD, BAD, WARN }

/**
 * 悬浮窗显示模型（结构化槽位，"一眼原则"，对齐 Cabinet）：
 * 顶部状态行（识别中 · 手动模式）→ 遮挡提醒 → 主状态大字（轮到我方走/已停止）
 * → 优势 → 候选/预案（尾部点阵），每格独立排版，不再是一整段多行小字。
 */
class OverlayModel(
    /** 顶部状态行：识别中 / 手动模式（次级小字，语义点同色） */
    val stateText: String = "",
    val stateKind: OverlayStateKind = OverlayStateKind.IDLE,
    /** 遮挡提醒行：悬浮窗盖住已定位棋盘时显示；null 隐藏（独立小行，不挤占状态行） */
    val occludedText: String? = null,
    /** 主状态大字：轮到我方走 / 已停止指导 / 引擎异常…（含 setStatus 覆盖文案） */
    val headlineText: String = "识别中",
    /** 优势行：红方优势 +0.8 / 胜率约 4%；null 隐藏 */
    val advantageText: String? = null,
    val advantageTone: QiTone = QiTone.NONE,
    /** 候选/预案行：建议 炮二平五 ✓ / 若对方走X 我方应Y；null 隐藏 */
    val candidateText: String? = null,
    val candidateTone: QiTone = QiTone.NONE,
    /** 候选点阵（1 基序号与总数；总数 ≤ 1 不显示），折进候选行尾部 */
    val candidateIndex: Int = 0,
    val candidateCount: Int = 0,
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
    /** 已停止指导（按钮显示"继续"） */
    val paused: Boolean = false,
    /** 紧急手动模式：小棋盘点按用于选子/走子/删子，而非切换候选 */
    val manualMode: Boolean = false,
    /** 手动模式下当前选中的格（canonical x,y），迷你棋盘绘制高亮 */
    val selectedCell: Pair<Int, Int>? = null,
    /** 引擎深度档值（"20"）；null = 显示弱化占位 */
    val depthText: String? = null,
    /** 引擎 Hash 档值（"256"） */
    val hashText: String? = null,
    /** 识别引擎档值（"ONNX"/"YOLO"）；null = 显示弱化占位 */
    val engineText: String? = null,
    /** 引擎变招路数（MultiPV 1-3）：左列档位按钮文字 "N变" */
    val multiPv: Int = 3,
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
    /** 引擎深度档切换（固定深度循环） */
    CYCLE_STRENGTH,
    /** 引擎 Hash 档循环（256→512→1024→2048，与对弈设置同口径） */
    CYCLE_HASH,
    /** 更新棋局（原"轮次"）：翻转"我方/对方"走子方并按新轮次重建/重分析 */
    FLIP_TURN,
    /** 紧急手动模式开关：识别失效时在小棋盘上手工维护局面 */
    MANUAL,
    /** 识别引擎热切换（ONNX ↔ YOLO）：切换后局面重新确认 */
    CYCLE_ENGINE,
    /** 引擎变招路数（MultiPV）1→2→3→1 循环；1 路算力最全 */
    CYCLE_MULTIPV,
}

class OverlayPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val chess: OverlayChessView
    private val stateDot: View
    private val state: TextView
    private val occluded: TextView
    private val status: TextView
    private val advantage: TextView
    private val divider: View
    private val candidate: TextView
    private val btnPause: TextView
    private val btnClose: TextView
    private val btnStrength: TextView
    private val btnHash: TextView
    private val btnEngine: TextView
    private val btnChange: TextView
    private val btnMultiPv: TextView
    private val btnUndo: TextView
    private val btnManual: TextView
    private val btnTurn: TextView

    /**
     * 真正的背景层 = XML 里的 overlay_root：悬浮窗 root 的 background 运行期
     * 替换不生效（首次之后静默失效，TYPE_APPLICATION_OVERLAY 窗口实测行为），
     * 子 View 的背景可正常替换——换肤一律改这一层，窗口 root 自身保持透明
     * （Cabinet 踩坑教训 ①）。
     */
    private lateinit var bgLayer: View

    /** 当前主题调色板（applyTheme 注入；render 只按语义取色，不出现字面色值） */
    private var palette: QiPalette = OverlayTheme.DARK.palette

    /** 最近一次渲染模型：主题切换后按新调色板重绘动态色（点/点阵/芯片值） */
    private var lastModel: OverlayModel? = null

    // ==== 渲染稳态零分配（120ms 节流后每秒仍 ~8 次 render，以下缓存让稳态 render 无新建对象）====

    /** 状态点 Drawable 复用（原先每次 render 新建 GradientDrawable）；单视图持有改色即可 */
    private val dotSolid = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val dotHollow = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x00000000)
    }
    private val dotStrokeW = (1.5f * resources.displayMetrics.density).toInt()

    /** 芯片文本缓存（view → label+value）：值不变零分配跳过；applyTheme 清空强制按新调色板重绘 */
    private val chipCache = HashMap<TextView, Pair<String, String?>>()

    /** 候选行缓存（文本+色调+点阵参数）：均未变则跳过 Spannable 重建 */
    private var lastCandidateKey: String? = null
    /** 变招档位按钮当前显示的档位值（文本仅在变化时构造） */
    private var lastMultiPv = -1

    var onAction: ((OverlayAction) -> Unit)? = null
    /** 手动模式：小棋盘点格（canonical x,y），由服务端处理选子/走子/删子 */
    var onCellTap: ((x: Int, y: Int) -> Unit)? = null
    /** 拖动开始/结束（遮挡黄字拖动豁免等） */
    var onDragStateChange: ((Boolean) -> Unit)? = null

    // 关闭二次确认：误触风险大，首次点按进入待确认态，3 秒内再点才真正关闭
    private var closeArmed = false
    private var closeNormalColor = 0
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
        bgLayer = findViewById(R.id.overlay_root)
        chess = findViewById(R.id.overlay_chess)
        stateDot = findViewById(R.id.overlay_state_dot)
        state = findViewById(R.id.overlay_state)
        occluded = findViewById(R.id.overlay_occluded)
        status = findViewById(R.id.overlay_status)
        advantage = findViewById(R.id.overlay_advantage)
        divider = findViewById(R.id.overlay_divider)
        candidate = findViewById(R.id.overlay_candidate)
        btnPause = findViewById(R.id.overlay_btn_pause)
        btnClose = findViewById(R.id.overlay_btn_close)
        btnStrength = findViewById(R.id.overlay_btn_strength)
        btnHash = findViewById(R.id.overlay_btn_hash)
        btnEngine = findViewById(R.id.overlay_btn_engine)
        btnChange = findViewById(R.id.overlay_btn_change)
        btnMultiPv = findViewById(R.id.overlay_btn_multipv)
        btnUndo = findViewById(R.id.overlay_btn_undo)
        btnManual = findViewById(R.id.overlay_btn_manual)
        btnTurn = findViewById(R.id.overlay_btn_turn)
        closeNormalColor = btnClose.currentTextColor

        btnPause.setOnClickListener { onAction?.invoke(OverlayAction.PAUSE) }
        btnChange.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
        btnUndo.setOnClickListener { onAction?.invoke(OverlayAction.UNDO) }
        btnManual.setOnClickListener { onAction?.invoke(OverlayAction.MANUAL) }
        btnTurn.setOnClickListener { onAction?.invoke(OverlayAction.FLIP_TURN) }
        btnStrength.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_STRENGTH) }
        btnHash.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_HASH) }
        btnEngine.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_ENGINE) }
        btnMultiPv.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_MULTIPV) }
        btnClose.setOnClickListener {
            if (!closeArmed) {
                closeArmed = true
                btnClose.text = "确认?" // 23dp 网格按钮，确认文案需紧凑
                btnClose.setTextColor(palette.danger)
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
        lastModel = model

        // 状态行：语义点 + 次级小字（Drawable 复用，只改色/描边）
        val hollow = model.stateKind == OverlayStateKind.IDLE
        val dot = dotColor(model.stateKind)
        if (hollow) {
            dotHollow.setStroke(dotStrokeW, dot)
            stateDot.background = dotHollow
        } else {
            dotSolid.setColor(dot)
            stateDot.background = dotSolid
        }
        state.text = model.stateText
        state.setTextColor(palette.muted)

        // 遮挡提醒行（琥珀小字，独立于状态行——移开棋盘即消失，无 TTL 沿变化显隐）
        val occ = model.occludedText
        if (occ.isNullOrEmpty()) {
            occluded.visibility = View.GONE
        } else {
            occluded.visibility = View.VISIBLE
            occluded.text = occ
            occluded.setTextColor(palette.amber)
        }

        // 主状态大字
        status.text = model.headlineText
        status.setTextColor(palette.headline)

        // 优势行
        val adv = model.advantageText
        if (adv.isNullOrEmpty()) {
            advantage.visibility = View.GONE
        } else {
            advantage.visibility = View.VISIBLE
            advantage.text = adv
            advantage.setTextColor(toneColor(model.advantageTone, palette.jade))
        }

        // 候选/预案行（点阵折进尾部：当前候选=琥珀实点，其余=次级点——
        // 弱化色在最坏背景下会隐形，2026-10 对比度审计后并入次级灰）
        // 缓存比对：文本/色调/点阵参数均未变则跳过 Spannable 重建（稳态零分配）
        val cand = model.candidateText
        if (cand.isNullOrEmpty()) {
            candidate.visibility = View.GONE
        } else {
            candidate.visibility = View.VISIBLE
            val candKey = "$cand|${model.candidateTone}|${model.candidateIndex}|${model.candidateCount}"
            if (lastCandidateKey != candKey) {
                lastCandidateKey = candKey
                val sb = SpannableStringBuilder(cand)
                sb.setSpan(
                    ForegroundColorSpan(toneColor(model.candidateTone, palette.fg)),
                    0, sb.length, 0,
                )
                if (model.candidateCount in 2..9) {
                    sb.append("  ")
                    for (i in 1..model.candidateCount) {
                        val start = sb.length
                        sb.append("●")
                        sb.setSpan(
                            ForegroundColorSpan(if (i == model.candidateIndex) palette.amber else palette.muted),
                            start, sb.length, 0,
                        )
                        if (i != model.candidateCount) sb.append(" ")
                    }
                }
                candidate.text = sb
            }
        }

        // 值芯片：标签灰 + 值琥珀（等宽由 tnum 保证）；setText 前比对防频跳（根因 4）
        setChipText(btnStrength, "深度", model.depthText)
        setChipText(btnHash, "哈希", model.hashText)
        setChipText(btnEngine, "引擎", model.engineText)
        val pauseText = if (model.paused) "继续" else "停止"
        if (btnPause.text != pauseText) btnPause.text = pauseText

        // 变招档位按钮："N变"（档位缓存比对，文本仅在变化时构造）
        if (lastMultiPv != model.multiPv) {
            lastMultiPv = model.multiPv
            btnMultiPv.text = "${model.multiPv}变"
        }

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

    /**
     * 应用背景主题（连线页"悬浮窗主题"切换）：背景层是 XML 的 overlay_root 子 View
     * （陷阱 ①：窗口 root 背景运行期替换不生效）。换肤 = 换一整块语义：
     * 底/描边/芯片/按钮材质 + 全部语义文字色，最后按新调色板重绘一遍当前模型
     * （动态色如状态点、点阵、芯片值随主题同步）。
     * 每个视图各取一份 Drawable 实例，禁止共享（陷阱 ③）。
     */
    fun applyTheme(theme: OverlayTheme) {
        palette = theme.palette
        bgLayer.background = theme.background(context)

        state.setTextColor(palette.muted)
        occluded.setTextColor(palette.amber)
        status.setTextColor(palette.headline)
        candidate.setTextColor(palette.fg)
        advantage.setTextColor(palette.jade) // render 会按语义覆盖
        divider.setBackgroundColor(palette.divider)

        // 值芯片（每个 view 各取一份 Drawable 实例，禁止共享）
        btnStrength.background = theme.chipBackground(context)
        btnHash.background = theme.chipBackground(context)
        btnEngine.background = theme.chipBackground(context)

        // 动作按钮：停止 = 琥珀警示描边（唯一带语义描边的动作）
        btnPause.background = theme.actionBackground(context, palette.stopStroke, palette.stopStrokePressed)
        for (v in listOf(btnChange, btnMultiPv, btnUndo, btnManual, btnTurn, btnClose)) {
            v.background = theme.actionBackground(context)
            v.setTextColor(palette.fg)
        }

        // 关闭二次确认可能正处于待确认态：按新主题保留状态
        closeNormalColor = palette.fg
        if (closeArmed) btnClose.setTextColor(palette.danger) else btnClose.setTextColor(closeNormalColor)

        // 换肤后强制按新调色板重绘动态色（芯片双色/候选行 Span）——缓存只比较文本，
        // 颜色不在比较内，不清缓存则动态色停留在旧调色板
        chipCache.clear()
        lastCandidateKey = null

        lastModel?.let { render(it) }
    }

    /** 语义色调 → 当前主题色（NONE = 主文字） */
    private fun toneColor(tone: QiTone, noneColor: Int): Int = when (tone) {
        QiTone.GOOD -> palette.jade
        QiTone.BAD -> palette.danger
        QiTone.WARN -> palette.amber
        QiTone.NONE -> noneColor
    }

    private fun dotColor(kind: OverlayStateKind): Int = when (kind) {
        OverlayStateKind.RUNNING -> palette.jadeDot
        OverlayStateKind.WAITING, OverlayStateKind.MANUAL -> palette.amber
        OverlayStateKind.ERROR -> palette.danger
        OverlayStateKind.IDLE -> palette.muted
    }

    /**
     * 值芯片双色文本："深度" 灰 + 值 琥珀（未就绪时占位"-"也用次级灰——
     * 原弱化色在最坏背景合成底上仅 ~1.2:1 属不可读）。
     * 缓存比对（label+value 均未变即跳过）：稳态 render 零分配。
     * 顺修历史 bug：原先按 view.text 纯文本比对，Span 颜色不在比较内——换肤后
     * 文本相同即跳过重设，芯片颜色停留在旧调色板；现由 applyTheme 清缓存兜底。
     */
    private fun setChipText(view: TextView, label: String, value: String?) {
        val last = chipCache[view]
        if (last != null && last.first == label && last.second == value) return
        chipCache[view] = label to value
        val sb = SpannableStringBuilder(label)
        sb.setSpan(ForegroundColorSpan(palette.muted), 0, sb.length, 0)
        sb.append(" ")
        val vs = sb.length
        sb.append(value ?: "—")
        sb.setSpan(
            ForegroundColorSpan(if (value != null) palette.amber else palette.muted),
            vs, sb.length, 0,
        )
        view.text = sb
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
