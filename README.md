# 象棋迪克 XQDK -- 开源免费的安卓版中国象棋学习工具

[![Build-XQDK](https://github.com/Silva6c/chess-dike/actions/workflows/android.yml/badge.svg)](https://github.com/Silva6c/chess-dike/actions/workflows/android.yml)
[![CodeQL](https://github.com/Silva6c/chess-dike/actions/workflows/codeql.yml/badge.svg)](https://github.com/Silva6c/chess-dike/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/Silva6c/chess-dike)](https://github.com/Silva6c/chess-dike/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green)

## 介绍

象棋迪克是一个开源免费的安卓版中国象棋学习工具：聚焦"连线"功能——识别其他象棋 App
的棋盘画面（ONNX 角点式 / YOLO 双引擎），在悬浮窗实时给出皮卡鱼引擎与桔库开局库的走法建议。

打开应用即达连线页：默认"靠近玩家的一侧（屏幕下方）＝我方"，自动判定我方执红还是执黑
（含换边局），**只为我方给出指导**；识别为只读，所有着法由玩家手动完成。连线页内置
**"性能诊断"开关**：开启后每次识别的分段耗时自动落盘到系统 Download 目录（md 文件，
应用内标明位置），识别变慢时可一键采集数据定位瓶颈；另有**手动"检查更新"按钮**
（不自动检查、不内置下载，仅查询 GitHub 正式版提示）。使用说明与原理见
[docs/assist-helper.md](docs/assist-helper.md)，架构与实现见 [docs/技术报告.md](docs/技术报告.md)。

**免责声明**：本软件（象棋迪克）为象棋学习辅助工具，仅供个人学习、复盘研究与技术交流使用。
仅通过系统录屏授权识别画面并以悬浮窗显示走法建议，不包含自动走子、触摸注入、无障碍服务等功能；
**严禁用于任何正式比赛、等级分对局、网络对局平台或赌博等违反平台规则与法律法规的场景**；
请于获取后 **24 小时内自行删除**。详见应用内免责声明全文。

## 版本安全线：自 v3.2 起（2026-10-31 密钥轮换）

**自 v3.2 起，本项目的发布包进入安全版本线**：每一个 Release 包都由轮换后的
全新 4096 位签名密钥构建签名（该密钥不公开、仅存于发布流水线的加密存储），
并经发布流水线指纹门禁核验——**证书不符的构建根本进不了 Release 页**。
请认准 **v3.2 及之后的版本**；指纹可用 `apksigner verify --print-certs` 自行
核对（见[下方核验节](#官方获取渠道与签名核验)）。

相对地，**v3.1 及更早的版本不在此安全线内**：其签名密钥（连同密码）曾随上游
仓库历史公开，任何人可伪造出同指纹的恶意包。由此请注意：

- **旧证书一律作废**——此后请勿安装任何旧证书签名的安装包（哪怕自称"新版"）；
- **从旧版本升级到 v3.2 需先卸载再安装**（仅此一次，之后恢复正常的覆盖升级）。

v3.2 同时完成一轮全面安全加固：

1. **应用内不存在"下载安装"路径**："检查更新"只查询 GitHub Releases 元数据并跳转
   官方下载页，下载与安装完全由你在官方渠道完成——应用内没有可被中间人替换包体的通道。
2. **权限与数据最小化**：仅录屏 / 悬浮窗 / 通知 / 网络四类权限；识别、引擎、开局库
   全部本地运行；无自动走子、无触摸注入、无无障碍服务；云备份已关闭。
3. **构建链持续加固**：CI 第三方组件全部锁定 commit 哈希、Gradle 发行包带官方校验和、
   依赖固定版本、CodeQL 每周自动代码扫描（见页面顶部 badge）。

## 推荐项目：内阁 Cabinet（同作者的另一条路线，重度使用首选）

如果你喜欢本项目的"连线"功能，同作者的 **[内阁 Cabinet](https://github.com/Silva6c/cabinet)**
值得认真一试——"手机做眼睛，电脑做脑子"：手机端录屏识别棋盘、悬浮窗展示建议，
皮卡鱼引擎与开局库放在电脑端，经 WSS（局域网 / Tailscale 组网）实时分析，
从 [内阁 Releases](https://github.com/Silva6c/cabinet/releases) 获取。

**为什么重度使用选它**：本项目全离线单机、装上就用，但识别与引擎挤在同一颗手机 CPU
上——长时间连线会发热降频（见下方警告）。内阁把 NNUE 搜索搬到电脑：Hash 可开到
GB 级、线程全给、开局库命中秒回，手机只做采集与显示**几乎不发热**，信息也更丰富
（胜率 / WDL / 多路 PV / 深度 / 节点数，算力参数悬浮窗直调）。电脑到手机的通信本身
也按高标准设计：WSS 加密 + 自签证书指纹锁定 + 配对令牌仅存哈希、可吊销。两个项目
出自同一作者，遵循同样的只读合规底线（无自动走子、无触摸注入、无无障碍服务）与
开源免费原则——**随手练棋用本项目，深度复盘与长时间使用用内阁**。

| | 象棋迪克（本项目） | 内阁 Cabinet |
|---|---|---|
| 架构 | **全离线单机**：识别、引擎、开局库全在手机，零外部依赖 | **双端协作**：手机识别 + 悬浮窗，电脑跑引擎与开局库，WSS 通信（局域网 / Tailscale） |
| 算力 | 受手机 SoC 封顶：神经网络推理与 NNUE 搜索抢同一颗 CPU | 吃满电脑算力：Hash 可开到 GB 级、线程全给，开局库命中秒回 |
| 手机负载 | **高**：屏幕采集 + ONNX 推理（4 线程）+ 皮卡鱼（半核）同时跑 | **低**：只做采集、识别与悬浮窗绘制 |
| 发热与降频 | 长时间使用会发热；触发系统温控后 CPU 降频，**识别与出着速度都会下降**（见下方警告） | 手机基本不热，搜索负载在电脑侧 |
| 部署 | 装 APK 即用：免网络、免配对、免电脑 | 需一台常开的 Windows 电脑 + 一次扫码配对；可选用 Tailscale 组网 |
| 建议信息 | 走法箭头、中文着法、优劣势、深度、MultiPV 变招 | 更丰富：胜率 / WDL / 多路 PV / 算力悬浮窗直调 |
| **适用** | 随手练棋、无电脑环境、短时使用、开箱即用 | 深度复盘、长时间连续使用、追求最强引擎、有常开电脑 |
| **不适用** | 长时间高强度连续使用（发热降频，见下） | 无电脑 / 不愿部署 / 只想要一个 App 就用的场景 |
| 共同原则 | 只读屏幕、悬浮窗提示；**无自动走子、无触摸注入、无障碍服务** | 同左 |

> **⚠️ 发热与性能警告（重要）**：本项目把**识别（ONNX 4 线程）**与**皮卡鱼引擎
> （核心数的一半，给识别与系统留核）**都放在手机上跑。识别侧另有自适应节拍器
> （画面变化后自动收紧、空闲时自动放宽）与停稳门槛（跳过走子动画帧），日常使用的
> 响应与发热已明显优化；但**长时间连续连线（尤其夏季、边充边用、戴厚保护壳）仍会
> 造成机身发热**；系统温控介入后 CPU 降频，**识别单帧耗时上升、建议更新变慢，
> 引擎同深度出着也更慢**——这是单机全栈方案的固有代价，不是 Bug。
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

## 从源码构建

要求：Android Studio（或 Gradle 8.x + JDK 17）+ Android SDK 34 + NDK 25。

```bash
./gradlew :app:assembleArmv8-dotprodRelease      # 推荐：dotprod 变体
./gradlew :app:assembleArmv8Release              # 兼容变体
./gradlew :app:testArmv8-dotprodDebugUnitTest    # 单元测试
```

两个变体都内置 `armv8` 与 `armv8-dotprod` 两个引擎文件，运行时按 CPU 特性
**自动选择**（dotprod 机型的 NNUE 推理走专用指令，快数倍）——装哪个变体都不会
选错引擎，flavor 仅作产物命名与回退值区分。

**大文件说明**（本仓库已内置/未内置）：

| 内容 | git 仓库 | 本地构建 | 说明 |
| --- | --- | --- | --- |
| Pikafish 引擎（`libpikafish-armv8.so` / `-dotprod.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | `app/src/main/pikafish/arm64-v8a/` |
| Pikafish NNUE 权重（`libpikafish.nnue.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | 同上，约 45MB |
| YOLO 棋子检测模型（`assets/yolov5n_xq_fp16.tflite`） | ✅ 分发 | ✅ 内置 | 权重来自 VinXiangQi |
| **ONNX 识别模型**（`assets/models/*.onnx`，共约 21.5MB） | ❌ `.gitignore` 排除 | 需自行放置 | 两步式识别引擎（RTMPose 角点 + Swin 分类，模型来自 Hugging Face Chinese_Chess_Recognition）；未放置时自动回退 YOLO，见 `assets/models/README.md` |
| **桔库开局库**（`assets/databases/*.obk.zip`） | ❌ `.gitignore` 排除 | 需自行获取放置 | 第三方版权内容不入 git；**缺失时连线自动降级为纯引擎模式**，不影响使用 |

**签名**：release 构建优先使用 `app/cchess.release.2026.jks`（密钥文件与密码
`CCHES_STORE_PWD` / `CCHES_KEY_PWD` 均自本地 `local.properties` 读取，**不随仓库
分发**；CI 经仓库 Secrets 注入同钥真签）；密钥或密码缺失时（全新克隆 / 未配置
Secrets 的 CI）自动回退 debug 签名保证可构建——v* tag 构建另有证书指纹
门禁，debug 签名包不会进入官方 Release。
如需以你自己的身份发布，请替换为自有 keystore。

**CI 自动出包**：推送 `v*` 语义化版本 tag（如 `v3.2`）自动触发构建（单元测试 +
双 flavor 出包），版权物由构建时从本仓 `assets` 预发布 Release 拉取注入并挂到
同名 Release；旧制 `stable-*` 日期 tag 已于 2026-10-31 废弃，历史 tag 不再触发
构建。本地构建则按上表自行放置大文件。

## 官方获取渠道与签名核验

- 本项目**唯一官方发布渠道**：[github.com/Silva6c/chess-dike 的 Releases](https://github.com/Silva6c/chess-dike/releases)，
  请只从该渠道获取更新。发布流水线内置签名指纹门禁：证书不符的构建无法进入
  Release 页（2026-10-31 起）。
- **签名密钥已于 2026-10-31 轮换**：旧签名密钥曾连同密码一起留在公开 git 历史
  中（任何人可复原其签名能力），已作废停用。由此请注意：
  - **v3.1 及更早的旧签名包一律不要信任**——旧证书指纹 `f4dbd2…72dee` 仅作历史
    记录（historical），攻击者可伪造出同指纹的恶意包；
  - 从旧版本升级到新签名版本需**先卸载再安装**（仅此一次，之后恢复覆盖升级）。
- **v3.2 及之后所有发布包**的签名证书 SHA-256 指纹（`apksigner verify --print-certs <apk>` 可核验）：

  ```
  9ca440393471915a0e6983c53db714133f36f7d327cb487b8a82451d6427721d
  ```

**版本规则**：`versionCode` = git 提交数（`app/build.gradle.kts` 的 `gitCommitCount`，
无 git 环境兜底为 1，且带 43 的下限地板——保证历次交付版本号单调递增、老设备可覆盖升级）；
`versionName` 不手写，由 git 标签推导（`git describe --tags --match "v*"` 去掉前导
`v`）——发布提交上 = `3.2`，标签后的开发构建 = `3.2-1-g<hash>`，无 git/标签回退
`0.0.0-dev`，版本号只有 tag 这一个事实源；
APK 产物名 `XQDK_<日期>_<versionCode>_<变体>-<构建类型>.apk`。

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
