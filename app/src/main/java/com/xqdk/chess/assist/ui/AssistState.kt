package com.xqdk.chess.assist.ui

/**
 * 连线页仪器面语义状态（美学终稿 §3.2/§4.2 点阵四态）。
 * 由服务状态文案经 [AssistStateMap.fromStatusText] 纯函数映射得出，便于单测；
 * "已确认·玉"并入运行态表达（服务状态文案无可靠的"局面确认"判据，不虚构信号）。
 */
enum class AssistState {
    /** 待机/已停止：待机墨 10% 恒静 */
    IDLE,

    /** 识别中：琥珀逐点 80ms 错峰（行波） */
    RUNNING_WAVE,

    /** 运行中的等待类状态（思考/预热/手动等）：玉绿恒亮 */
    RUNNING_STILL,

    /** 引擎异常/识别不稳定：警示红 */
    ERROR,
}

/** 服务状态文案 → 点阵语义状态（纯函数，无 Android 依赖） */
object AssistStateMap {

    /** 异常类关键词：引擎异常/初始化失败/识别不稳定（警示红） */
    private val ERROR_WORDS = listOf("异常", "失败", "不稳定")

    /** 终止类关键词：未启动/已关闭/已停止（待机墨恒静） */
    private val IDLE_WORDS = listOf("未启动", "已关闭", "已停止", "尚未")

    /** 识别进行中：点阵进入琥珀逐点行波 */
    private val WAVE_WORDS = listOf("识别中", "正在识别", "识别棋盘")

    fun fromStatusText(text: String): AssistState = when {
        ERROR_WORDS.any { text.contains(it) } -> AssistState.ERROR
        IDLE_WORDS.any { text.contains(it) } -> AssistState.IDLE
        WAVE_WORDS.any { text.contains(it) } -> AssistState.RUNNING_WAVE
        else -> AssistState.RUNNING_STILL
    }
}
