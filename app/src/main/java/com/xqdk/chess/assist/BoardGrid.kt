package com.xqdk.chess.assist

/**
 * 棋盘格点映射：由"棋盘左上角交点"与"右下角交点"两个对角交点的屏幕坐标（归一化 0..1）表达
 * 9×10 网格的覆盖范围。纯 JVM，无 Android 依赖。
 *
 * 连线功能中由 YOLO 检出的棋盘外框换算而来，用于悬浮窗遮挡判断等场景。
 */
class BoardGrid(
    val nx0: Double,
    val ny0: Double,
    val nx1: Double,
    val ny1: Double,
) {
    init {
        validateOrThrow()
    }

    private fun validateOrThrow() {
        if (nx0 < 0 || ny0 < 0 || nx1 > 1 || ny1 > 1) {
            throw IllegalArgumentException("grid points must be inside normalized screen: ($nx0,$ny0)-($nx1,$ny1)")
        }
        if (nx1 <= nx0 || ny1 <= ny0) {
            throw IllegalArgumentException("grid corners reversed: ($nx0,$ny0)-($nx1,$ny1)")
        }
    }

    companion object {
        /**
         * 由两个对角交点的像素坐标构建（左上/右下、右上/左下等任意组合都可，内部统一为左上/右下）。
         */
        fun fromPxCorners(xa: Double, ya: Double, xb: Double, yb: Double,
                          frameW: Int, frameH: Int): BoardGrid {
            val x0 = minOf(xa, xb)
            val x1 = maxOf(xa, xb)
            val y0 = minOf(ya, yb)
            val y1 = maxOf(ya, yb)
            return BoardGrid(x0 / frameW, y0 / frameH, x1 / frameW, y1 / frameH)
        }
    }
}
