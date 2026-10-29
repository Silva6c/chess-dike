# 象棋迪克 XQDK -- 开源免费的安卓版中国象棋学习工具

[![Build-XQDK](https://github.com/Silva6c/chess-dike/actions/workflows/android.yml/badge.svg)](https://github.com/Silva6c/chess-dike/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Silva6c/chess-dike)](https://github.com/Silva6c/chess-dike/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green)

## 介绍

象棋迪克是一个开源免费的安卓版中国象棋学习工具：聚焦"连线"功能——识别其他象棋 App
的棋盘画面（ONNX 角点式 / YOLO 双引擎），在悬浮窗实时给出皮卡鱼引擎与桔库开局库的走法建议。

**免责声明**：本软件（象棋迪克）为象棋学习辅助工具，仅供个人学习、复盘研究与技术交流使用。
仅通过系统录屏授权识别画面并以悬浮窗显示走法建议，不包含自动走子、触摸注入、无障碍服务等功能；
**严禁用于任何正式比赛、等级分对局、网络对局平台或赌博等违反平台规则与法律法规的场景**；
请于获取后 **24 小时内自行删除**。详见应用内免责声明全文。

## 推荐项目：内阁 Cabinet（同作者的另一条路线）

如果你喜欢本项目的"连线"功能，也欢迎看看同作者的 **[内阁 Cabinet](https://github.com/Silva6c/cabinet)**：
"手机做眼睛，电脑做脑子"——手机端录屏识别棋盘、悬浮窗展示建议，皮卡鱼引擎与开局库放在电脑端，
经 WSS（局域网 / Tailscale 组网）实时分析。

| | 象棋迪克（本项目） | 内阁 Cabinet |
|---|---|---|
| 架构 | **全离线单机**：识别、引擎、开局库全在手机，零外部依赖 | **双端协作**：手机识别 + 悬浮窗，电脑跑引擎与开局库，WSS 通信（局域网 / Tailscale） |
| 算力 | 受手机 SoC 封顶：神经网络推理与 NNUE 搜索抢同一颗 CPU | 吃满电脑算力：Hash 可开到 GB 级、线程全给，开局库命中秒回 |
| 手机负载 | **高**：屏幕采集 + ONNX 推理（4 线程）+ 皮卡鱼（全核）同时跑 | **低**：只做采集、识别与悬浮窗绘制 |
| 发热与降频 | **长时间使用会明显发热**；触发系统温控后 CPU 降频，**识别与出着速度都会下降**（见下方警告） | 手机基本不热，搜索负载在电脑侧 |
| 部署 | 装 APK 即用：免网络、免配对、免电脑 | 需一台常开的 Windows 电脑 + 一次扫码配对；可选用 Tailscale 组网 |
| 建议信息 | 走法箭头、中文着法、优劣势、深度、MultiPV 变招 | 更丰富：胜率 / WDL / 多路 PV / 算力悬浮窗直调 |
| **适用** | 随手练棋、无电脑环境、短时使用、开箱即用 | 深度复盘、长时间连续使用、追求最强引擎、有常开电脑 |
| **不适用** | 长时间高强度连续使用（发热降频，见下） | 无电脑 / 不愿部署 / 只想要一个 App 就用的场景 |
| 共同原则 | 只读屏幕、悬浮窗提示；**无自动走子、无触摸注入、无障碍服务** | 同左 |

> **⚠️ 发热与性能警告（重要）**：本项目把**识别（ONNX 4 线程）**与**皮卡鱼引擎（占满
> CPU 核心）**都放在手机上同时跑，CPU 接近满负荷。**长时间连续连线（尤其夏季、边充边用、
> 戴厚保护壳）会造成机身明显发热**；系统温控介入后 CPU 降频，**识别单帧耗时上升、建议更新
> 变慢，引擎同深度出着也更慢**——这是单机全栈方案的固有代价，不是 Bug。
>
> 缓解办法：避免边充边用；摘掉厚壳或加散热背夹；长时间使用中途暂停识别让手机休息；
> 对即时性要求高时选**时间制**（固定时长收束，节奏可预期）而非深度制；必要时降低深度档、
> 或切到较轻的 YOLO 引擎。
>
> 如果你的场景是**长时间、高强度**使用，建议改用[内阁 Cabinet](https://github.com/Silva6c/cabinet)：
> 引擎跑在电脑上，手机只做识别与显示，几乎不发热。

## 鸣谢

- 原项目"象棋鱼"（作者 zfdang，[GitHub](https://github.com/zfdang/chinese-chess-android)）——
  本项目在其基础上二次开发；
- [Vincentzyx/VinXiangQi](https://github.com/Vincentzyx/VinXiangQi) —— YOLO 棋子检测权重文件来源（GPL-3.0）；
- [yolo12138/Chinese_Chess_Recognition](https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition) ——
  ONNX 两步式识别引擎（RTMPose 角点 + Swin 分类）的模型与算法来源；
- [Shirakawa-Kotone/chinese-chess-helper](https://github.com/Shirakawa-Kotone/chinese-chess-helper) ——
  两步式识别的工程实现参考（OnnxRecognizer 原实现）；
- [Pikafish](https://github.com/official-pikafish/Pikafish) 引擎团队与
  [DroidFish](https://github.com/peterosterlund2/droidfish)（Peter Österlund）—— 引擎框架基座（GPL-3.0）。

## 新增功能：连线（屏幕识别 · 悬浮窗走法指导）

打开应用即达 **连线** 功能页，功能为：通过系统录屏授权识别其他象棋 App 的棋盘局面，
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
./gradlew :app:assembleArmv8-dotprodRelease      # 推荐：dotprod 引擎变体（需 ARMv8.2+ 真机）
./gradlew :app:assembleArmv8Release              # 兼容变体（不支持 dotprod 的老机器）
./gradlew :app:testArmv8-dotprodDebugUnitTest    # 单元测试
```

**大文件说明**（本仓库已内置/未内置）：

| 内容 | git 仓库 | 本地构建 | 说明 |
| --- | --- | --- | --- |
| Pikafish 引擎（`libpikafish-armv8.so` / `-dotprod.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | `app/src/main/pikafish/arm64-v8a/` |
| Pikafish NNUE 权重（`libpikafish.nnue.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | 同上，约 45MB |
| YOLO 棋子检测模型（`assets/yolov5n_xq_fp16.tflite`） | ✅ 分发 | ✅ 内置 | 权重来自 VinXiangQi |
| **ONNX 识别模型**（`assets/models/*.onnx`，共约 21.5MB） | ❌ `.gitignore` 排除 | 需自行放置 | 两步式识别引擎（RTMPose 角点 + Swin 分类，模型来自 Hugging Face Chinese_Chess_Recognition）；未放置时自动回退 YOLO，见 `assets/models/README.md` |
| **桔库开局库**（`assets/databases/*.obk.zip`） | ❌ `.gitignore` 排除 | ✅ 内置 | 第三方版权内容不入 git；缺失时连线**自动降级为纯引擎模式**，不影响使用 |

**签名**：release 构建优先使用 `app/cchess.release.jks`（密钥文件与密码
`CCHES_STORE_PWD` / `CCHES_KEY_PWD` 均自本地 `local.properties` 读取，**不随仓库
分发**）；密钥或密码缺失时（CI / 全新克隆）自动回退 debug 签名，保证可构建。
注意：debug 签名的包与既有正式发布包签名不同，**不能覆盖安装**，只能卸载重装。
如需以你自己的身份发布，请替换为自有 keystore。

**CI 自动出包**：推送 `stable-*` tag 自动触发构建（单元测试 + 双 flavor 出包），
版权物由构建时从本仓 `assets` 预发布 Release 拉取注入并挂到同名 Release；
本地构建则按上表自行放置大文件。

## 官方获取渠道与签名核验

- 本项目**唯一官方发布渠道**：[github.com/Silva6c/chess-dike 的 Releases](https://github.com/Silva6c/chess-dike/releases)，
  请只从该渠道获取更新。
- 本项目签名密钥历史上曾随上游仓库公开（既有状态的已知事实，作者确认维持该密钥
  以保障老用户覆盖安装升级）。因此，**任何非官方渠道分发的同签名 APK 都存在被
  篡改后重新打包签名的风险，一律不要安装**。
- 正式发布包的签名证书 SHA-256 指纹（`apksigner verify --print-certs <apk>` 可核验）：

  ```
  f4dbd277973ca30798b84109735c2bc976afd177f87d361bc95574e781372dee
  ```

**版本规则**：`versionCode` = git 提交数（`app/build.gradle.kts` 的 `gitCommitCount`，
无 git 环境兜底为 1，且带 43 的下限地板——保证历次交付版本号单调递增、老设备可覆盖升级）；
APK 产物名 `XQDK_<日期>_<versionCode>_<变体>.apk`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/技术报告.md](docs/技术报告.md) | 架构定稿：识别管线、引擎链路、悬浮窗、测试体系 |
| [docs/assist-helper.md](docs/assist-helper.md) | 连线功能使用说明与原理 |
| [docs/README.md](docs/README.md) | 文档地图 |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) | 第三方组件与许可义务清单（GPL 合规） |

## 开源免费

希望更多人能参与进来，一起打造一个好用的安卓Android版的中国象棋学习工具！

## 参考

#### 基于Android的中国象棋
https://github.com/kongxiangchx/ChineseChess

#### DroidFish Android Chess App
https://github.com/peterosterlund2/droidfish

#### cchess是一个Python版的中国象棋库
https://github.com/walker8088/cchess
