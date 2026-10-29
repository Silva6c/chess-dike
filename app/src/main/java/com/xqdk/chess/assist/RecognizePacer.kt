package com.xqdk.chess.assist

import java.util.Locale

/**
 * 识别节拍器（纯 JVM，移植自 Cabinet 同名类）：统一约束所有识别驱动源
 * （真实帧回调 / frameTick 兜底重放）。
 *
 * 背景：真机单帧推理 1~2s（上游动态量化版 10.9MB，旧环境 2 线程；2026-10-27 起
 * 纯 CPU 4 线程，单帧成本下降后本节拍器的 cost 自适应自动收紧间隔，规则数字
 * 无需跟调），此前固定 600ms 下限 + 上帧成本×2 的单步节流等于背靠背满载 →
 * 热节流拖垮全系统（用户感知"识别异常缓慢"）；且失败/非法路径零退避，前台没有
 * 棋盘（桌面/主菜单动画）时全图定位+分类无限烧。
 *
 * 规则：
 * 1. 两次识别最短间隔 = max(下限, 上次实际成本 × 2) × 退避系数，硬上限 8s——
 *    成本自适应：全图定位帧（RTMPose）贵则自动拉长，角点缓存复用帧快则自动缩短，
 *    确认期自然先慢后快；已确认后的零星识别（兜底 tick 命中走子）不受影响；
 * 2. 识别失败或结果非法 → 退避系数 ×1.5（指数累计，靠硬上限兜底），成功且合法 → 归 1；
 *    对局中已定位棋盘的瞬时失败（走子动画过渡帧）例外：不退避也不消耗 burst，
 *    见 [markEnd]；
 * 3. 首次调用放行（无历史即无约束），[reset] 供引擎切换/重新抓屏时立即放行。
 *
 * 【线程】所有方法只在 assist-worker 线程调用（帧回调/识别/兜底 tick 全串行
 * 在该线程，见 ScreenAssistService 线程模型雷区注释），无跨线程访问。
 */
class RecognizePacer(private val minFloorMs: Long) {

    companion object {
        /** 间隔硬上限：退避不应让"切回对局后恢复识别"等太久 */
        const val MAX_INTERVAL_MS = 8000L
        /** 每次失败/非法的退避倍率 */
        private const val BACKOFF_STEP = 1.5f
        /** 退避系数上限（floor=1000 时恰好顶到 8s 硬上限） */
        private const val BACKOFF_MAX = 8.0f
        /** burst 快速确认期的间隔下限：走子后首帧最多等这么久（burst 仅持续数帧，
         *  且仍有 cost×1.1 与退避保护，真机满负荷窗口 ~2s 可接受） */
        const val BURST_FLOOR_MS = 300L
    }

    private var lastRecEndTs = 0L
    private var lastRecCostMs = 0L
    private var backoffMul = 1.0f
    /** burst 快速确认期剩余次数：画面变化后连续多帧收紧间隔，逐次消耗，耗尽回归常规 */
    private var burstRemain = 0

    /** 当前允许的最短识别间隔（含成本自适应、burst 与退避） */
    fun minIntervalMs(): Long {
        val adaptive = if (burstRemain > 0) maxOf(BURST_FLOOR_MS, lastRecCostMs * 11 / 10)
                       else maxOf(minFloorMs, lastRecCostMs * 2)
        return minOf((adaptive * backoffMul).toLong(), MAX_INTERVAL_MS)
    }

    /**
     * 画面变化请求快速节奏（"棋局一变马上转发"的关键路径）：
     * 接下来 times 次识别间隔收紧为 cost×1.1（近似满负荷），足够完成 3 帧确认
     * 并上行；逐帧消耗，耗尽自动回归常规节奏（稳态省电）。
     */
    fun requestBurst(times: Int = 6) { burstRemain = maxOf(burstRemain, times) }

    /**
     * 清除退避系数（保留 burst 与成本历史）：切页级大变化时调用。
     * 此前主菜单等无棋盘页面的连续失败会把退避涨到 8s 封顶，用户随后切进对局页
     * 时即使画面变化请求了 burst，间隔仍被残留退避放大（300ms×8=2.4s），开局
     * 首次定位被拖慢数秒（Cabinet 实测进对局页后 2.6s 空窗）。切页是用户主动操作
     * 的强信号，退避理由（防无棋盘画面空转）对"用户刚到达的新页面"不成立。
     * 自激防护不依赖退避：burst 由真实帧差驱动、失败消耗 burst（见 [markEnd]），
     * burst 耗尽后回归退避节奏。
     */
    fun clearBackoff() { backoffMul = 1.0f }

    /** 是否处于 burst 快速期（frameTick 据此用更细的驱动粒度） */
    fun isBursting(): Boolean = burstRemain > 0

    /**
     * 当前节拍状态的可读描述（识别日志"节拍"列用）：burst 余量、退避系数，
     * 两者都无则"常规"。识别闸口放行瞬间取值 = 本帧被放行时依据的节拍。
     */
    fun describe(): String {
        val parts = ArrayList<String>(2)
        if (burstRemain > 0) parts.add("burst余$burstRemain")
        // Locale.US：个别设备默认 Locale 小数点是逗号，诊断列格式要稳定
        if (backoffMul > 1.01f) parts.add("退避×" + String.format(Locale.US, "%.1f", backoffMul))
        return if (parts.isEmpty()) "常规" else parts.joinToString(" ")
    }

    /** 距上次识别结束是否已到放行时间（now 可注入供单测） */
    fun canRecognizeNow(now: Long = System.currentTimeMillis()): Boolean =
        now - lastRecEndTs >= minIntervalMs()

    /**
     * 识别结束记账：costMs = 本次实际耗时（含预处理），ok = 结果可用且合法。
     * 失败/非法立即拉长间隔（无需等下一帧再发现）。
     *
     * transient = 已定位棋盘对局中的瞬时失败（走子动画的悬空子过渡帧等）：
     * 不退避、不消耗 burst——它们是确认路上的必要重试而非"画面异常"，惩罚只会
     * 把确认拖回慢节奏（Cabinet 实测过渡帧失败 2~3 次即烧光 burst、间隔落回
     * 下限，走子→确认多等 ~1s）。棋盘外的失败（无棋盘/切 App）仍退避+消耗，
     * 防止桌面动画场景背靠背空转。
     */
    fun markEnd(now: Long = System.currentTimeMillis(), costMs: Long, ok: Boolean, transient: Boolean = false) {
        lastRecEndTs = now
        lastRecCostMs = costMs.coerceIn(0L, MAX_INTERVAL_MS)
        if ((ok || !transient) && burstRemain > 0) burstRemain--
        if (ok) {
            backoffMul = 1.0f
        } else if (!transient) {
            backoffMul = minOf(backoffMul * BACKOFF_STEP, BACKOFF_MAX)
        }
    }

    /** 引擎切换/重新启动识别时归零：清退避、burst 与历史成本，立即放行 */
    fun reset() {
        lastRecEndTs = 0L
        lastRecCostMs = 0L
        backoffMul = 1.0f
        burstRemain = 0
    }
}
