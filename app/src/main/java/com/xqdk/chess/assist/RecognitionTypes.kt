package com.xqdk.chess.assist

import kotlin.math.abs

/**
 * 识别管线的共享类型（纯 JVM，无 Android 依赖）：
 * 帧输入、朝向、单帧识别结果、稳定帧跟踪器。
 */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "frame pixel count mismatch" }
    }
}

enum class Orientation {
    /** 红方在下（标准朝向，屏幕下方阵营=红方） */
    STANDARD,
    /** 红方在上（换边/翻转朝向，屏幕下方阵营=黑方） */
    FLIPPED;
}

/**
 * 一帧识别结果。canonical 为项目内部约定（红恒定在 y=9），orientation 表示屏幕朝向。
 */
class RecognitionResult(
    val canonical: Array<IntArray>,
    val screenRaw: Array<IntArray>,
    val orientation: Orientation,
    val issues: List<String>,
    val unknownCells: Int,
    val recognizedPieces: Int,
    val avgScore: Double,
) {
    fun isValid(): Boolean = issues.isEmpty() && unknownCells == 0
}

/**
 * 稳定帧跟踪器：连续 confirmCount 帧出现同一局面才确认，并推断轮次/检测重开。
 */
class BoardTracker(private val confirmCount: Int = 3) {

    enum class Event {
        /** 稳定新局确认 */
        NEW_BOARD,
        /** 与当前确认局面相同（未变化） */
        SAME_BOARD,
        /** 检测到新对局（回到开局） */
        NEW_GAME,
        /** 帧不稳定/不合法，等待后续 */
        UNSTABLE,
    }

    /** 当前确认局面 */
    var confirmed: RecognitionResult? = null
        private set

    /** 红方走子方（true=红方走）；worker 写、主线程读（悔棋/更新棋局按钮） */
    @Volatile var redGo = true

    /** 连续未确认帧数（用于提示）；worker 写、主线程读 */
    @Volatile var unstableStreak = 0
        private set

    private var candidate: RecognitionResult? = null
    private var candidateHits = 0

    fun reset(redGoFirst: Boolean = true) {
        confirmed = null
        candidate = null
        candidateHits = 0
        unstableStreak = 0
        redGo = redGoFirst
    }

    /** 悔棋：恢复到历史局面（以给定结果与轮次重建跟踪状态，后续帧可正常重新确认） */
    fun restore(result: RecognitionResult, redGoSide: Boolean) {
        confirmed = result
        redGo = redGoSide
        candidate = null
        candidateHits = 0
        unstableStreak = 0
    }

    fun onFrame(res: RecognitionResult): Event {
        val prev = confirmed
        if (!res.isValid()) {
            unstableStreak++
            return Event.UNSTABLE
        }

        if (prev == null) {
            // 首次确认：连续 confirmCount 帧完全一致才确认（瞬时坏帧/幻觉帧很难
            // 连续多帧逐格一致）；确认时与开局完全一致才视为全新对局
            if (candidate != null && AssistBoard.equal(res.canonical, candidate!!.canonical)) {
                candidateHits++
            } else {
                candidate = res
                candidateHits = 1
            }
            if (candidateHits >= confirmCount) {
                confirmed = candidate!!
                val first = candidate!!
                candidate = null
                candidateHits = 0
                unstableStreak = 0
                return if (AssistBoard.matchStartCount(first.canonical) >= 32) Event.NEW_GAME else Event.NEW_BOARD
            }
            unstableStreak++
            return Event.UNSTABLE
        }

        // 重开检测：与开局完全一致（32 子）视为新对局（换局可能伴随大幅子数变化，
        // 因此放在子数跳变门限之前）
        if (AssistBoard.matchStartCount(res.canonical) >= 32 && !AssistBoard.equal(res.canonical, prev.canonical)) {
            confirmed = res
            redGo = true
            candidate = null
            candidateHits = 0
            unstableStreak = 0
            return Event.NEW_GAME
        }

        // 子数跳变门限：真实走子只 ±1（吃子 -1），检测噪声通常 ±2~3；与已确认局面
        // 相差过大只可能是局部遮挡/动画/幻觉帧，拒绝（换局走上面的重开检测）
        val tol = maxOf(2, prev.recognizedPieces / 10)
        if (abs(res.recognizedPieces - prev.recognizedPieces) > tol) {
            candidate = null
            candidateHits = 0
            unstableStreak++
            return Event.UNSTABLE
        }

        val same = AssistBoard.equal(res.canonical, prev.canonical)
        if (same) {
            candidate = null
            candidateHits = 0
            unstableStreak = 0
            return Event.SAME_BOARD
        }

        // 与候选一致则累计
        if (candidate != null && AssistBoard.equal(res.canonical, candidate!!.canonical)) {
            candidateHits++
        } else {
            candidate = res
            candidateHits = 1
        }
        if (candidateHits >= confirmCount) {
            val newBoard = candidate!!
            val moved = AssistBoard.movedSide(prev.canonical, newBoard.canonical)
            if (moved != null) {
                redGo = moved != 1 // 移动方是红 => 下一手是黑；移动方是黑 => 下一手是红
            }
            confirmed = newBoard
            candidate = null
            candidateHits = 0
            unstableStreak = 0
            return Event.NEW_BOARD
        }
        unstableStreak++
        return Event.UNSTABLE
    }
}
