# 第三方组件与许可义务清单（THIRD-PARTY NOTICES）

象棋迪克（XQDK）包含大量来自第三方开源项目的代码与资产。本清单按"复制即交付"
的许可义务逐项列明来源、许可与分发形态；个别组件的许可以官方仓库当前标注为准。

## 1. GPL 义务主体（随仓库分发的衍生代码与资产）

| 组件 | 来源 | 许可 | 分发形态与义务 |
|---|---|---|---|
| DroidFish 引擎框架基座 | https://github.com/peterosterlund2/droidfish | GPL-3.0 | `org.petero.droidfish` 引擎链路与 gamelogic 棋规为衍生代码，**整体以 GPL-3.0 发布**（仓库根 LICENSE）；对外分发 APK 时需提供对应源码 |
| Pikafish 引擎 + NNUE 权重 | https://github.com/official-pikafish/Pikafish | GPL-3.0 | `app/src/main/pikafish/` 预编译二进制随仓库分发 |
| YOLO 棋子检测权重 | https://github.com/Vincentzyx/VinXiangQi | GPL-3.0 | `assets/yolov5n_xq_fp16.tflite` 随仓库分发 |

## 2. 二次开发基座与参考实现

| 组件 | 来源 | 说明 |
|---|---|---|
| 象棋鱼 chinese-chess-android | https://github.com/zfdang/chinese-chess-android | 本项目在其基础上二次开发（界面与棋规基座来源；对弈打谱功能已裁撤，仅存历史渊源） |
| ONNX 两步式识别模型与算法 | https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition | RTMPose 角点 + Swin 分类；模型不随仓库分发（见 `assets/models/README.md`，自行放置，缺失时回退 YOLO） |
| chinese-chess-helper | https://github.com/Shirakawa-Kotone/chinese-chess-helper | 两步式识别的工程实现参考（OnnxRecognizer 原实现） |

## 3. 依赖库（构建期引入，版本见 gradle/libs.versions.toml 与 app/build.gradle.kts）

| 组件 | 版本 | 许可 |
|---|---|---|
| tensorflow-lite | 2.14.0 | Apache-2.0（本机缓存 POM 已核实） |
| onnxruntime-android | 1.31.1 | MIT（升级后以官方声明为准，1.x 全系 MIT） |
| sqliteassethelper | 2.0.1 | Apache-2.0 |
| androidx / material / kotlin stdlib 等 | 见 libs.versions.toml | Apache-2.0 |
| Robolectric（仅测试） | 4.14.1 | MIT |

## 4. 内嵌源码模块

| 组件 | 来源 | 许可 | 位置 |
|---|---|---|---|
| cpu_features | https://github.com/google/cpu_features | Apache-2.0（随附 LICENSE 已核实） | `app/src/main/cpp/nativeutil/cpu_features/` |

## 5. 不随仓库分发的第三方版权内容

- 桔库开局库（`app/src/main/assets/databases/`，.gitignore 排除）：第三方版权内容；
  缺失时连线自动降级为纯引擎模式。
- ONNX 识别模型（`app/src/main/assets/models/*.onnx`，.gitignore 排除）：见 §2，自行放置。

## 6. 义务摘要

- 本仓库衍生自 GPL-3.0 项目（DroidFish）且直接包含其衍生代码，故**整体以 GPL-3.0
  发布**；对外分发 APK（含修改版）时须提供完整对应源码并保留版权声明。
- 自行编译自用不触发对外分发义务。
