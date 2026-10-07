package com.xqdk.chess.assist.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable

/**
 * 悬浮窗调色板（对齐 Cabinet 美学方案）：
 * 三档主题各持一套"语义色 + 材质"（底/描边/芯片/按钮），视图侧只按语义取色、
 * 不出现字面色值——换肤即换一整块语义。
 *
 * 伪玻璃三件套：半透明底 ＋ 1dp 高光描边 ＋ 顶部微渐变。
 * 真背景模糊在物理上不可得：Android 悬浮窗（FLAG_SECURE ＋ 覆盖他人应用）
 * 无法采样身后内容，这里不做假。
 *
 * 【对比度契约】面板是半透明的，身后是被识别 App 的任意内容（亮色界面 worst），
 * 所有文字色按"最坏背景透过面板后的合成底"校验过 WCAG 对比度（小字 ≥4.5:1、
 * 大字/图形 ≥3:1）：深色两档玻璃底不低于 90%/95% 不透明，浅色档文字整体加深。
 * 历史教训：72% 玻璃 + 次级灰在白底 App 身后实测对比度仅 2.1~2.5:1，
 * 候选点阵的弱化点甚至 1.03:1（隐形）——弱化级文字已并入次级（muted）。
 */
class QiPalette(
    /** 面板玻璃底（含透明度；深色档 ≥0xE6/浅色档 0xE6/纯黑档 0xF2，保证文字可读） */
    val panel: Int,
    val panelStroke: Int,
    /** 顶部微渐变起始白（0 = 不加高光，纯黑档不发光） */
    val panelTopHighlight: Int,
    /** 文字两级：主 / 次级（原"弱化级"已并入次级——最坏背景下弱化级不可读） */
    val fg: Int,
    val muted: Int,
    /** 主状态大字（琥珀系，浅色档自动加深） */
    val headline: Int,
    /** 优势玉字与状态点 */
    val jade: Int,
    val jadeDot: Int,
    val danger: Int,
    val amber: Int,
    /** 值芯片（深度/哈希）：无描边、6% 白底 */
    val chipBg: Int,
    val chipBgPressed: Int,
    /** 动作按钮：1dp 描边半透明底 */
    val actionBg: Int,
    val actionBgPressed: Int,
    val actionStroke: Int,
    val actionStrokePressed: Int,
    /** 停止按钮的琥珀警示描边 */
    val stopStroke: Int,
    val stopStrokePressed: Int,
    /** 细分割线 */
    val divider: Int,
)

/** 悬浮窗主题（连线页"悬浮窗主题"按钮循环切换）：深色玻璃 / 纯黑 OLED / 浅色纸感 */
enum class OverlayTheme(val key: String, val label: String, val palette: QiPalette) {

    /** 深色玻璃（默认）：通用档，半透明 90% + 暖白高光（低于 90% 时亮色 App
     *  身后文字对比度跌破 4.5:1，见类头对比度契约） */
    DARK("overlay_dark", "深色玻璃", QiPalette(
        panel = 0xE61B1E24.toInt(),
        panelStroke = 0x24FFFFFF, panelTopHighlight = 0x12FFFFFF,
        fg = 0xFFE8E6E1.toInt(), muted = 0xFFA8ADB6.toInt(),
        headline = 0xFFFFE082.toInt(), jade = 0xFF81C784.toInt(), jadeDot = 0xFF4CAF50.toInt(),
        danger = 0xFFFF9E97.toInt(), amber = 0xFFFFC107.toInt(),
        chipBg = 0x0FFFFFFF, chipBgPressed = 0x1CFFFFFF,
        actionBg = 0x1AFFFFFF, actionBgPressed = 0x29FFFFFF,
        actionStroke = 0x29FFFFFF, actionStrokePressed = 0x47FFFFFF,
        stopStroke = 0xB3FFC107.toInt(), stopStrokePressed = 0xFFFFC107.toInt(),
        divider = 0x1AFFFFFF,
    )),

    /** 纯黑 OLED：夜间省电档，黑底不发光（无高光渐变） */
    BLACK("overlay_black", "纯黑 OLED", QiPalette(
        panel = 0xF2000000.toInt(),
        panelStroke = 0x17FFFFFF, panelTopHighlight = 0,
        fg = 0xFFE8E6E1.toInt(), muted = 0xFFA8ADB6.toInt(),
        headline = 0xFFFFE082.toInt(), jade = 0xFF81C784.toInt(), jadeDot = 0xFF4CAF50.toInt(),
        danger = 0xFFFF9E97.toInt(), amber = 0xFFFFC107.toInt(),
        chipBg = 0x0FFFFFFF, chipBgPressed = 0x1CFFFFFF,
        actionBg = 0x14FFFFFF, actionBgPressed = 0x24FFFFFF,
        actionStroke = 0x24FFFFFF, actionStrokePressed = 0x3DFFFFFF,
        stopStroke = 0xB3FFC107.toInt(), stopStrokePressed = 0xFFFFC107.toInt(),
        divider = 0x17FFFFFF,
    )),

    /** 浅色纸感：强光环境护眼档，文字整体加深保证对比（身后深色内容为 worst） */
    LIGHT("overlay_light", "浅色纸感", QiPalette(
        panel = 0xE6ECEFF1.toInt(),
        panelStroke = 0x1F000000, panelTopHighlight = 0x1AFFFFFF,
        fg = 0xFF263238.toInt(), muted = 0xFF3F545E.toInt(),
        headline = 0xFF9A5B00.toInt(), jade = 0xFF1B6B32.toInt(), jadeDot = 0xFF2E7D32.toInt(),
        danger = 0xFFA01818.toInt(), amber = 0xFF744500.toInt(),
        chipBg = 0x12000000, chipBgPressed = 0x1F000000,
        actionBg = 0x14000000, actionBgPressed = 0x20000000,
        actionStroke = 0x24000000, actionStrokePressed = 0x38000000,
        stopStroke = 0xCC744500.toInt(), stopStrokePressed = 0xFF744500.toInt(),
        divider = 0x1F000000,
    ));

    /** 面板背景：玻璃底 + 上沿描边 +（深色/浅色档）顶部微渐变。
     *  注意：悬浮窗 root 的 background 运行期替换不生效——真背景层放 XML 内子 View
     *  （bgLayer），每个 view 各取一份 Drawable 实例，禁止共享（Cabinet 踩坑教训） */
    fun background(ctx: Context): Drawable {
        val r = 20f * ctx.resources.displayMetrics.density
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(palette.panel)
            setStroke(dp(ctx, 1f), palette.panelStroke)
        }
        if (palette.panelTopHighlight == 0) return base
        // 三段停靠：高光在前 50% 渐隐（近似视觉稿的 42% 渐隐点）
        val top = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.panelTopHighlight, 0x00FFFFFF, 0x00FFFFFF),
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(base, top))
    }

    /** 值芯片底（深度/哈希）：每个 view 各取一份实例，禁止共享 Drawable */
    fun chipBackground(ctx: Context): Drawable = pressable(
        shape(ctx, 6f, palette.chipBg, 0, 0),
        shape(ctx, 6f, palette.chipBgPressed, 0, 0),
    )

    /** 动作按钮底：默认描边或指定描边（停止按钮传 stopStroke 得到琥珀警示边） */
    fun actionBackground(
        ctx: Context,
        stroke: Int = palette.actionStroke,
        strokePressed: Int = palette.actionStrokePressed,
    ): Drawable = pressable(
        shape(ctx, 6f, palette.actionBg, stroke, dp(ctx, 1f)),
        shape(ctx, 6f, palette.actionBgPressed, strokePressed, dp(ctx, 1f)),
    )

    private fun shape(ctx: Context, radiusDp: Float, fill: Int, stroke: Int, strokePx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    private fun pressable(normal: Drawable, pressed: Drawable): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun fromKey(key: String): OverlayTheme =
            entries.firstOrNull { it.key == key } ?: DARK
    }
}
