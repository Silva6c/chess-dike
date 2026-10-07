package com.xqdk.chess.assist

import android.content.Context
import com.xqdk.chess.assist.ui.OverlayTheme

/**
 * 连线功能的持久化配置（SharedPreferences）。
 */
class AssistConfig(context: Context) {
    private val sp = context.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)

    /**
     * 棋盘识别引擎："onnx"（角点检测+全盘分类，对未收录皮肤泛化好，默认）
     * 或 "yolo"（检测式，onnx 加载失败时自动回退的目标）。
     */
    var recognizerEngine: String
        get() = sp.getString(KEY_RECOGNIZER_ENGINE, "onnx") ?: "onnx"
        set(value) { sp.edit().putString(KEY_RECOGNIZER_ENGINE, value).apply() }

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

    /**
     * 引擎变招路数（MultiPV，1-3；悬浮窗左列"N变"按钮循环）。
     * 1 路 = 全部算力集中最佳着（最强）；2-3 路附带给备选候选。默认 3（保留候选展示）。
     */
    var multiPv: Int
        get() = sp.getInt(KEY_MULTI_PV, 3).coerceIn(1, 3)
        set(value) { sp.edit().putInt(KEY_MULTI_PV, value.coerceIn(1, 3)).apply() }

    /** 悬浮窗主题（OverlayTheme.key：深色玻璃/纯黑 OLED/浅色纸感，连线页切换） */
    var overlayTheme: String
        get() = sp.getString(KEY_OVERLAY_THEME, null) ?: OverlayTheme.DARK.key
        set(value) { sp.edit().putString(KEY_OVERLAY_THEME, value).apply() }

    companion object {
        private const val KEY_RECOGNIZER_ENGINE = "recognizer_engine"
        private const val KEY_FRAME_W = "frame_w"
        private const val KEY_FRAME_H = "frame_h"
        private const val KEY_CONFIRM = "confirm_count"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_SEARCH_DEPTH = "search_depth"
        private const val KEY_HASH_MB = "hash_mb"
        private const val KEY_MULTI_PV = "multi_pv"
        private const val KEY_OVERLAY_THEME = "overlay_theme"
    }
}
