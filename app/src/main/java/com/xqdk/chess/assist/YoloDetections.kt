package com.xqdk.chess.assist

import com.xqdk.chess.gamelogic.Piece

/**
 * YOLO 棋子检测的纯 JVM 数据模型与类别表。
 *
 * 模型为 YOLOv5（640 输入），输出 [1,25200,20]：每行 4 个框参数(中心 x/y、宽高，640 像素域)
 * + 1 个 objectness + 15 个类别分。类别表与 VinXiangQi 训练定义一致：
 * 14 种棋子 + 1 个整体棋盘框（board）。
 */
class YoloDetection(
    /** 类别索引 0..14（见 LABELS） */
    val labelId: Int,
    /** 置信度 0..1 */
    val score: Double,
    /** 框中心与宽高（原始帧像素域，非模型输入域） */
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
) {
    val isBoard: Boolean get() = labelId == LABEL_BOARD
    /** 对应 Piece 常量；board 类别返回 -1 */
    val piece: Int get() = PIECE_CODES[labelId]

    companion object {
        const val LABEL_BOARD = 14

        // labelId 0..13 依次对应：
        // b_ma b_xiang b_shi b_jiang b_che b_pao b_bing r_che r_ma r_shi r_jiang r_xiang r_pao r_bing
        val PIECE_CODES = intArrayOf(
            Piece.BMA, Piece.BXIANG, Piece.BSHI, Piece.BJIANG, Piece.BJU, Piece.BPAO, Piece.BZU,
            Piece.WJU, Piece.WMA, Piece.WSHI, Piece.WSHUAI, Piece.WXIANG, Piece.WPAO, Piece.WBING, -1
        )
    }
}
