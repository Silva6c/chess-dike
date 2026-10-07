# ONNX 识别模型（需自行放置）

本目录存放 ONNX 两步式棋盘识别引擎的两个模型文件（共约 21.5MB），模型文件体积较大，
**不进 git 仓库**（见仓库根目录 `.gitignore`）。

克隆/下载源码后，请自行将以下两个文件放入本目录，否则识别引擎将自动回退到 YOLO：

| 文件名 | 大小 | 用途 |
|---|---|---|
| `4_v6-0301.onnx` | 10,704,184 B | RTMPose 棋盘四角关键点检测 |
| `nano_v3-0319.onnx` | 10,853,848 B | Swin 交叉点分类（16 类） |

## 来源

模型与算法来源：[Shirakawa-Kotone/chinese-chess-helper](https://github.com/Shirakawa-Kotone/chinese-chess-helper)
（OnnxRecognizer 原实现）。本工程的移植实现位于 `OnnxBoardRecognizer.kt` / `OnnxGeom.kt`。

## 模型校验

放置正确后，文件字节数应为上表数值；服务启动时若加载失败，状态栏会提示
"ONNX 引擎加载失败，已回退 YOLO 识别"。
