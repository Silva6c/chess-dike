package com.xqdk.chess.assist

import android.content.Context

/**
 * 连线功能的持久化配置（SharedPreferences）。
 */
class AssistConfig(context: Context) {
    private val sp = context.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)

    /** 最近一次识别时的帧尺寸（悬浮窗遮挡判断用：把归一化棋盘框换算回像素） */
    var frameSize: Pair<Int, Int>
        get() {
            val w = sp.getInt(KEY_FRAME_W, 0)
            val h = sp.getInt(KEY_FRAME_H, 0)
            return w to h
        }
        set(value) {
            sp.edit().putInt(KEY_FRAME_W, value.first).putInt(KEY_FRAME_H, value.second).apply()
        }

    /** 稳定确认帧数（1..8） */
    var confirmCount: Int
        get() = sp.getInt(KEY_CONFIRM, 3).coerceIn(1, 8)
        set(value) { sp.edit().putInt(KEY_CONFIRM, value.coerceIn(1, 8)).apply() }

    /** 悬浮窗上次位置（左上角，归一化 0..1），未设置则默认 */
    var overlayPos: Pair<Float, Float>
        get() = sp.getFloat(KEY_OVERLAY_X, -1f) to sp.getFloat(KEY_OVERLAY_Y, -1f)
        set(value) {
            sp.edit().putFloat(KEY_OVERLAY_X, value.first).putFloat(KEY_OVERLAY_Y, value.second).apply()
        }

    /** 搜索深度（与对弈"固定深度"制式一致，默认 20） */
    var searchDepth: Int
        get() = sp.getInt(KEY_SEARCH_DEPTH, 20)
        set(value) { sp.edit().putInt(KEY_SEARCH_DEPTH, value.coerceIn(6, 40)).apply() }

    /** 引擎 Hash（MB，与对弈设置同口径；面板按钮循环 256→512→1024→2048） */
    var hashMb: Int
        get() = sp.getInt(KEY_HASH_MB, 256)
        set(value) { sp.edit().putInt(KEY_HASH_MB, value).apply() }

    companion object {
        private const val KEY_FRAME_W = "frame_w"
        private const val KEY_FRAME_H = "frame_h"
        private const val KEY_CONFIRM = "confirm_count"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_SEARCH_DEPTH = "search_depth"
        private const val KEY_HASH_MB = "hash_mb"
    }
}
