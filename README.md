# 象棋迪克 XQDK -- 开源免费的安卓版中国象棋学习工具

## 介绍

象棋迪克是一个开源免费的安卓版中国象棋学习工具：内置 Pikafish 引擎的完整对弈与打谱，
以及基于 YOLO 屏幕识别的"连线"功能（识别其他象棋 App 局面并在悬浮窗给出走法建议）。

**免责声明**：本应用仅供个人学习、研究与文化交流使用，严禁用于商业用途或任何违反法律法规、
游戏平台规则的情形；请于获取后 **24 小时内自行删除**。

## 鸣谢

- 原项目"象棋鱼"（作者 zfdang，[GitHub](https://github.com/zfdang/chinese-chess-android)）——
  本项目在其基础上二次开发；
- [VinXiangQi](https://github.com/Vincentzyx/VinXiangQi) —— YOLO 棋子检测权重文件来源；
- [Pikafish](https://github.com/official-pikafish/Pikafish) 引擎团队与
  [DroidFish](https://github.com/peterosterlund2/droidfish)（Peter Österlund）—— 引擎与对弈框架基座（GPL-3.0）。

## 新增功能：连线（屏幕识别 · 悬浮窗走法指导）

首页新增 **连线** 入口，功能为：通过系统录屏授权识别其他象棋 App 的棋盘局面，
在悬浮窗中显示**已识别局面迷你棋盘**与**我方最佳走法建议**（复用内置皮卡鱼引擎分析）。

- 默认"靠近玩家的一侧（屏幕下方）＝我方"，自动识别我方执红还是执黑（含换边局），
  **只为我方给出指导**；另一方仅观察（显示其走法与预测）。
- 识别为只读：**没有自动走子、没有触摸注入、没有无障碍服务**，所有着法由玩家手动操作。
- 使用说明与原理：[docs/assist-helper.md](docs/assist-helper.md) ·
  技术报告：[docs/技术报告.md](docs/技术报告.md)
- 本功能为学习辅助工具，请求的权限为常规的屏幕录制（MediaProjection）与悬浮窗，
  与反作弊相关的敏感权限（无障碍服务等）一律不申请。

## 从源码构建

要求：Android Studio（或 Gradle 8.x + JDK 17）+ Android SDK 34 + NDK 25。

```bash
./gradlew :app:assembleArmv8-dotprod-Release     # 推荐：dotprod 引擎变体（需 ARMv8.2+ 真机）
./gradlew :app:assembleArmv8-Release             # 兼容变体（不支持 dotprod 的老机器）
./gradlew :app:testArmv8-dotprod-DebugUnitTest   # 单元测试
```

**大文件说明**（本仓库已内置/未内置）：

| 内容 | git 仓库 | 本地构建 | 说明 |
| --- | --- | --- | --- |
| Pikafish 引擎（`libpikafish-armv8.so` / `-dotprod.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | `app/src/main/pikafish/arm64-v8a/` |
| Pikafish NNUE 权重（`libpikafish.nnue.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | 同上，约 45MB |
| YOLO 棋子检测模型（`assets/yolov5n_xq_fp16.tflite`） | ✅ 分发 | ✅ 内置 | 权重来自 VinXiangQi |
| **桔库开局库**（`assets/databases/*.obk.zip`） | ❌ `.gitignore` 排除 | ✅ 内置 | 第三方版权内容不入 git；缺失时对弈/连线**自动降级为纯引擎模式**，不影响使用 |
| 内置棋谱书（`assets/XQF/`） | ❌ `.gitignore` 排除（仅留说明） | ✅ 内置 | 第三方版权内容；克隆者可自行导入 `.xqf` / `.pgn` |

**签名**：`app/cchess.release.jks` 与 `signingConfigs` 沿用上游项目的公开配置
（该密钥库与密码在上游开源仓库中已公开，非私密凭据）。如需以你自己的身份发布，
请替换为自有 keystore。

## 开源免费

希望更多人能参与进来，一起打造一个好用的安卓Android版的中国象棋学习工具！

## 参考

#### 基于Android的中国象棋
https://github.com/kongxiangchx/ChineseChess

#### DroidFish Android Chess App
https://github.com/peterosterlund2/droidfish

#### cchess是一个Python版的中国象棋库
https://github.com/walker8088/cchess
