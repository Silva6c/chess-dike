# 设计边界（Design Boundaries）

> 本文件声明象棋迪克（chess-dike）的**客观能力边界**：它是什么、不是什么、
> 如何自行验证。边界由代码的实际能力决定，不由本文件的声明决定——每条
> 结论都给出可独立核对的证据入口（源码路径 / 文档 / 构建配置），欢迎按
> 第 8 节自行审计。

## 1. 一句话定位

象棋迪克 = 手机端全离线单机的象棋学习辅助：**本地**录屏识别棋盘 + 本地
皮卡鱼引擎分析 + 悬浮窗建议。**软件止于"建议"**：分析结果只以悬浮窗呈现
给人眼，走子永远由使用者手动完成——从设计上不存在"识别 → 操作"的自动化
回路。

## 2. 只读输入

- 屏幕信息**仅**来自使用者显式授权的 MediaProjection 录屏：系统弹窗
  授权、运行中通知栏常驻可见、随时可停（悬浮窗关闭即停录）。
- 不读取其他应用的进程内存，不注入、不 hook、不抓包改包。
- 不注册无障碍服务（AccessibilityService），不模拟触摸/手势，不要求
  Root/Xposed。
- 一切分析仅基于屏幕上**使用者本人可见**的画面：不获取屏幕之外的任何
  信息——不读内存、不解析对局通信、不触碰对局刻意隐藏的数据。

## 3. 输出面向人眼

- 唯一输出形态是悬浮窗：迷你棋盘回显 + 建议箭头 + 候选着法与优势文本。
- 识别结果回显到迷你棋盘，使用者可随时核对软件的"理解"是否正确。
- 悬浮窗窗口带 `FLAG_SECURE`（ScreenAssistService.kt）：提示窗本身不进
  录屏合成——"看得见但不入镜"。
- **不提供任何机器可读的识别结果接口**：无广播、无 ContentProvider、
  无本地监听端口、无文件协议导出——外部自动化脚本无法把本应用当作
  "感知模块"消费。手机端唯一 `exported` 的组件是主入口 Activity（仅
  应用桌面启动入口，无深链、无对外动作），见
  [AndroidManifest.xml](app/src/main/AndroidManifest.xml)。

## 4. 权限清单（全部，共 5 项）

| 权限 | 实际用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮窗建议展示 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION` | 录屏识别运行期的前台服务与常驻通知（可见、可停） |
| `POST_NOTIFICATIONS` | 展示上述前台通知 |
| `INTERNET` | 仅用于手动"检查更新"查询 GitHub Releases（见第 5 节） |

不申请相机、定位、录音、通讯录、`QUERY_ALL_PACKAGES` 等任何与功能无关
的权限；`allowBackup="false"`、禁用明文流量（`usesCleartextTraffic=false`）。
全部声明见
[AndroidManifest.xml](app/src/main/AndroidManifest.xml)。

## 5. 网络与引擎边界

- **全离线单机**：识别、引擎分析、开局库查询全部在本机完成。本应用
  **不连接电脑端、没有任何对局平台通信**（与姊妹项目"内阁 Cabinet"的
  WSS 连线能力无关，不要混淆）。
- 唯一出站请求：连线页**手动**点"检查更新"时查询
  `api.github.com/repos/Silva6c/chess-dike/releases`（3 秒超时，只查
  版本号不下载任何文件；失败静默提示）。无自动检查、无遥测、无统计
  上报、无云库查询。
- 引擎为随包分发的皮卡鱼（Pikafish，GPL-3.0，预编译 `.so` 于
  `app/src/main/pikafish/`，完整性基线见
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) §7 的 SHA-256 表），
  经 UCI 文本协议在本机进程内调用。
- "空闲预计算"（β）只优化**本地引擎调度**：对方思考期以同参数提前搜索
  当前局面并缓存，命中即省一次本地搜索——不产生任何系统操作。
- 日志仅写本机：logcat 与用户手动开启的"性能诊断"落盘文件，无任何
  网络上传。

## 6. 永远不做

以下为本项目硬边界，不接受以任何理由加入：

- 自动点击 / 自动走子 / 自动对弈 / 自动确认
- 无障碍模拟、触摸注入、Root/Xposed/注入框架
- 读进程内存、改网络包、绕过反作弊、反检测 / 隐藏进程 / 伪装
- 识别验证码、绕过人机检测
- 提供机器可读的识别输出接口给外部自动化
- 后台静默录屏、与功能无关的敏感权限
- 为"改成自动走子 / 绕过检测"提供指导（贡献与 Issue 治理同理）

在 GPL-3.0 下 fork 与修改版合法存在，但它们**不是本项目官方发布物**，
其内容不代表本项目。官方发布物仅经本仓 GitHub Releases 分发，以
[README](README.md)「官方获取渠道与签名核验」所列证书指纹为准（v3.2
起的现行指纹；v3.1 及更早的旧签名包因密钥历史泄露一律不要信任）。

## 7. 使用边界（授权前提）

- 预设场景：**单机复盘、离线分析、个人学习**，或使用者拥有合法使用权
  / 已获运营方授权的环境。
- 不鼓励、不支持将本项目用于未授权的在线竞技对局；应用内免责声明与
  [README](README.md) 载有更严格的禁止条款（严禁正式比赛、等级分对局、
  网络对局平台）——以更严者为准。使用者需自行确认目标平台规则，并对
  自己的账号行为负责。
- 本文件描述设计事实，不构成法律意见。

## 8. 如何自行验证

1. **从源码构建**：见 [README](README.md)「从源码构建」。
2. **审权限**：[AndroidManifest.xml](app/src/main/AndroidManifest.xml)
   全部 `uses-permission` 共 5 项；在 `app/src/main/java` 源码检索
   `AccessibilityService` / `dispatchGesture` / `injectInputEvent` /
   `sendBroadcast` / `ServerSocket` 应零命中。
3. **审网络面**：全源码仅 `AppUpdateChecker.kt` 一处 HTTP 客户端
   （HttpURLConnection → api.github.com），无 OkHttp/WebSocket/云库。
4. **审输出接口**：`exported` 组件仅主入口 Activity（Manifest 逐个数）；
   悬浮窗实现位于 `app/src/main/java/com/xqdk/chess/assist/`
   （ScreenAssistService.kt + ui/ 视图，建议展示，无对外动作）。
5. **验引擎完整性**：[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
   §7 的皮卡鱼预编译 `.so` SHA-256 基线逐文件核对。
6. **验签名**：v3.2 起的证书指纹与核验命令见
   [README](README.md)「官方获取渠道与签名核验」。
7. **查构建**：GitHub Actions（android.yml / codeql.yml）公开可查，
   Release 挂包前有签名指纹门禁。

## 9. 版本边界留痕

每个 Release 的发布说明写清能力变化。权限清单、网络面、对外接口的任何
扩张都只会在源码中发生、随提交可审计，不做静默增加。若某版本出现与本
文件冲突的能力，以源码为准并视为缺陷，欢迎提 Issue。风险升高时优先
收窄分发（源码优先、限制二进制、归档旧版本），处置过程如实留痕。
