package org.petero.droidfish.player;

/**
 * 引擎事件回调。v3.0 裁撤对弈页后仅存错误上报一路：其余通知（引擎名/搜索结果/
 * 评估值/初始化完成）的唯一消费者已随对弈 UI 删除，接口收缩为单方法
 * （AnalysisEngine 的 4 个 no-op 覆写一并删除）。
 */
public interface EngineListener {
    void reportEngineError(String errMsg);
}
