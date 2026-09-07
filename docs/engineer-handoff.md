# 象棋迪克（XQDK）「连线」功能 —— 工程交接文档

> 本文件供新接手的模型/AI 会话使用，无需参考旧对话即可继续开发。
> 最后更新：2026-09-07 下午（0-10：全仓死代码清理 + 挑刺修复，versionName 2.5，交付 `XQDK_20260907_1`（versionCode=1，versionName=2.5，与旧交付可覆盖安装））。
> 原始项目在用户 U 盘，**红线：绝不读写/删除 U 盘路径**（见「环境红线」）。

---

## 0-10. 2026-09-07 下午更新（全仓死代码清理 + 挑刺修复，versionName 2.5，交付 `XQDK_20260907_1`）

用户要求：挑刺、删除死代码、全量优化代码。扫描方法：Python 脚本对全仓做"标识符频次 vs 声明数"比对，
逐个人工核实引用链后再删（**注意 Kotlin 属性语法 `bd.drawNum` 编译为 `getDrawNum()` 但源码文本不匹配，
扫描器对 Java getter 有系统性误报，必须用属性名复查**；`grep | head` 截断曾差点把活类 CpuInfo 误判为死代码——
ComputerPlayer:165 调用 `CpuInfo.getCoresCount()` 在 head 截断之外）。

### A. 整文件删除（7 个，零引用核实）
- `utils/ZipUtils.java`（239 行）、`utils/HttpUtils.java`、`utils/StringUtils.java`（后两者仅被死类 CloudOpenBook 引用）、
  `openbook/CloudOpenBook.java`（云库查询从未接线）、`openbook/OpenBookListener.java`、`manuals/PGNManual.java`
  （202 行 PGN 解析从未接线）、`org/petero/droidfish/utils/FileUtil.java`（127 行）。
- 连带删除 OpenBook/OpenBookBase 中只为云库存在的 fenCode 查询链（`query(String,...)` + `get(String,boolean)`）。

### B. 方法/字段级死代码（生产零引用，逐一 grep 核实）
- assist：`RecognitionResult.pieceAt`、`AssistBoard.isRed` 包装、`MoveChinese.describe(Board,...)` 重载、
  `AnalysisLine.isMate`、`AnalysisResult.stopped`、`YoloDetection.label`（连同 LABELS 数组，注释化）、
  `MappedBoard.boardRect`（测试改断言 grid）、`ScreenAssistService.lastFrame`（只写不读）。
- openbook：BookData 的 word/winNum/note/source 字段及全部 getter/setter（BHOpenBook 的 setNote/setSource
  调用一并删除——写进字段从没人读）；BHOpenBook 删除 java.sql.Connection 幽灵字段、name 字段。
- manuals：XQFManual 删除 getEvent/getCategory/getDate/getRedDuration/getBlackDuration/getFormat/getAnnotator/
  getAuthor/getSite/hasEmptyMove（**保留 getRed/getBlack——ManualActivity 以 `manual.red` 属性语法访问**，
  编译器当场抓回一次误删）；XQFKey 删 setF32Keys/getFKeyBytes。
- utils：CpuInfo 删 getCpuCores/getCpuInfo（67→24 行，含废弃 Build.CPU_ABI 日志）；PathUtil 删 9 个死方法
  （154→41 行，含自递归 getExternalStorageAppFileDir(Context,String)）；CopyAssetsUtil 删 unZip；
  Settings 删 getString/setString；GameController 删 swapSides/isNonGameMode；ChessApp 删 getInstance；
  ChessView 删 thinkMood/thinkIndex/thinkFlag/thinkContent；gamelogic 删 Board.doMoves/doMoveFromString/
  doMovesFromUCCIStrings、Move.setPositions、Piece.nPieceTypes；ManualActivity 删 gamenote 死局部变量。
- org.petero.droidfish：ComputerPlayer 删 getMaxPV/computerBusy/computerLoaded/movesToSearch（原作者自己注明
  "原来遗留的函数，现在没用上"）/sameSearchId/SearchType+getSearchType；UCIEngineBase 删 hasOption；
  LocalPipe 删 isClosed；EngineUtil 删 sanitizeString/isSimdSupported。
- **保留**：Piece.getCharByValue/getValueByChar/swapColor（生产未用但 PieceTest 直接覆盖 pieceCharMap/
  pieceValueMap 回归，删函数需连带删测试，得不偿失）。

### C. 挑刺修复（真 bug）
1. **悬浮窗遮挡检测整体失效**：`config.frameSize` 只读从不写（startCapture 漏写），
   `overlayIntersectsBoard()` 恒 false → 面板拖到棋盘上时永不隐藏，YOLO 会把迷你棋盘录进帧里。
   修复：startCapture 中 `config.frameSize = w to h`。
2. **引擎进程死亡后 60s 忙等自旋**：AnalysisEngine.searchOnce 里 `readLineFromEngine(250) ?: continue`——
   LocalPipe 语义是超时返回空串、**null 仅在管道关闭/中断**（doStart 同样场景是立即抛错）。
   修复：null 直接 return（shutdown 后不再自旋）。
3. **BHOpenBook 游标泄漏**：rawQuery 的 Cursor 从不 close → 补 close()。
4. **跨线程可见性**：ScreenAssistService.yoloMissStreak/cropUnstableStreak（worker 写、主线程读）、
   BoardTracker.redGo/unstableStreak 补 @Volatile。
5. **parseInfo 越界隐患**：`score cp/mate` 分支只判 `i+1 < size` 就取 `tokens[i+2]` → 改判 `i+2 < size`。
6. **空 onTaskRemoved**：注释声称"用户清理后台任务时停止"但实现为空 → 整个 override 删除（无行为变化）。
7. **注释漂移**：cycleHash/CYCLE_HASH 枚举/AssistConfig.hashMb 三处仍写着"→64→128"（上一轮删档位未同步）→ 统一改为 256→512→1024→2048。
8. **重复代码收敛**：四处 `resetSuggestionState()`（onBoardConfirmed/FLIP_TURN/undoOneStep/applyManualBoard）、
   四处 MoveChinese try/catch 收敛为 `describeSafe()`；strengthButtonText 的平行数组 listOf(12,20,24) 改读
   strengthLevels；BHOpenBook String.format 多余实参。
9. **每帧开销**：AssistBoard.matchStartCount 每帧新建 canonicalStart() 开局数组 → 只读缓存 startBoard。

### D. 版本与验证
- versionName 2.4 → **2.5**；versionCode 仍为 git 提交数（=1；已交付各 APK 内部 versionCode 均为 1，
  `adb install -r` 覆盖无降级问题）。
- `testArmv8-dotprod-DebugUnitTest` 全绿；`assembleArmv8-dotprod-Release` 成功；模拟器冒烟：主界面 →
  连线页 → 录屏授权 → 悬浮窗面板（状态/变招/悔棋/手动/深度20/Hash/更新棋局/停止/关闭齐全，"手动"无遮挡）→
  Hash 连点 512→1024→2048 循环正确 → logcat 无 AndroidRuntime 崩溃，YOLO 对非棋盘画面稳定拒绝（0 子 < 3 门槛）。

---
## 0-9. 2026-09-07 上午更新（更名象棋迪克 + dotprod + Hash 档位 + About 重做，交付 `XQDK_20260907_8`）

用户五项要求：①面板按键美化（大小不一）、外框放松；②启用 dotprod 引擎（原版支持）；③免责声明
（24 小时删除/学习用途）；④改名"象棋迪克"（连包名）+ 新图标（G:\AI_coding\CN-chess\XQDK.png，只读）；
⑤About 重做：保留特色功能声明、删除原作者痕迹、鸣谢原作者与 YOLO 权重作者；⑥删除所有"象棋鱼"字眼
（鸣谢除外）。

### A. 包名与品牌迁移
- **包名 `com.zfdang.chess` → `com.xqdk.chess`**：移动 main/test 源码目录树；sed 批量替换 package/import/
  action 字符串（`ACTION_START = "com.xqdk.chess.assist.START"`）；gradle namespace+applicationId 同步；
  res XML 中的自定义类全名（overlay_panel.xml 的 OverlayChessView）一并更新；grep 验证 app/src 与 gradle
  零残留。`org.petero.droidfish`（GPL 遗留引擎层）保持不动。
  **教训：Git Bash 下 `grep -rl | while read` 会把 Windows 反斜杠路径当转义符吃掉，sed 批量替换必须用
  `find -exec`。**
- 显示名：strings.xml（en/zh-rCN）app_name → "象棋迪克"；连线页标题 → "象棋迪克 · 连线识别指导"；
  通知标题 → "象棋迪克·连线识别运行中"；APK 输出名 `ChessFish_...` → `XQDK_...`（updateApk.sh 同步）；
  versionName 2.3 → 2.4。
- **图标**：PIL 从 XQDK.png（800×800，黑底木雕"象"）生成 ic_launcher（圆角方 radius 14%）/
  ic_launcher_round（圆形遮罩）六密度（36/48/72/96/144/192）+ playstore-icon 512。
- **全新包名 = 全新应用身份**：旧包（com.zfdang.chess）不能覆盖升级，需卸载重装 + 重新授权悬浮窗/录屏。

### B. About 重做 + 免责声明（三处落点）
- About 按钮改本地 AlertDialog（不再打开 fish.zfdang.com）：品牌+版本（BuildConfig.VERSION_NAME）、
  特色功能声明（对弈/打谱 + AI 连线特性清单）、免责声明、鸣谢（唯一保留"象棋鱼"字眼处：原项目作者
  zfdang；YOLO 权重来自 VinXiangQi；基座 Pikafish/DroidFish GPL-3.0）。文案集中在
  `MainActivity.Companion`（DISCLAIMER / CREDITS 常量）。
- **首次启动免责弹窗**：`maybeShowDisclaimer()`（prefs "xqdk/disclaimer_agreed_v1"，不同意 → finishAffinity）。
- 帮助按钮/ WebviewActivity 默认 URL 改为本地 `file:///android_asset/help.html`（新增 assets/help.html）。
- 连线页合规声明追加"仅供学习研究，请于获取后24小时内自行删除"。

### C. 面板：Hash 档位按钮 + 按钮统一 + 外框放松
- 右列 5 键等宽等高（78dp 列内 match_parent×30dp）：深度20 / **Hash256** / 更新棋局 / 停止 / 关闭；
  左行 3 键固定 38×30dp（变招/悔棋/手动）——消除"大大小小"。
- **Hash 档位**：`hashLevels = [256,512,1024,2048]`，从 256 起步循环（用户指定，64/128 已删）；`AssistConfig.hashMb`
  持久化；`AnalysisEngine.setHash(mb)` 只更新 volatile 值，**在两次搜索之间**（searchOnce 开头）才下发
  `setoption`（UCI 不建议搜索中 setoption）；doStart 握手用 hashMb 替代硬编码 256。
- 左右列 171dp 死锁解除（wrap_content），状态 `maxLines=8` + minHeight 118dp 兜底；ensureOverlay 的
  panelH 估计 185→192dp（5 键右列高度）。

### D. dotprod 引擎交付
- 仓库本就自带两引擎变体（`app/src/main/pikafish/arm64-v8a/`：libpikafish-armv8.so 与
  libpikafish-armv8-dotprod.so），gradle flavor `armv8-dotprod-` 通过 BuildConfig.PIKAFISH_ENGINE_FILE 选择。
  交付构建切到 **`assembleArmv8-dotprod-Release`**（测试 `testArmv8-dotprod-DebugUnitTest`），零代码改动。
- 兼容性：dotprod 需 ARMv8.2+（骁龙855/麒麟990/天玑1000 及以上普遍支持）；老真机引擎启动即崩（SIGILL）
  则退回 `armv8-` 变体交付。

### E. 验证
- 单测 44/44（新包名下 dotprod flavor 任务）；模拟器全新安装冒烟：首启免责弹窗（同意记忆）→ 主界面 →
  About 对话框内容 → 连线授权重走 → 面板 5 键布局/Hash 点击 256→512 循环与状态栏大字提示。
- 真机验收：dotprod 引擎能否启动（logcat "analysis engine ready"）；新图标/名称；About/帮助本地化。

---

## 0-8. 2026-09-07 早更新（识别回归修复 + 状态栏主体化，交付 `_20260907_7`）

用户反馈："现在不能准确识别棋盘了，改坏了"（0-7 的残局修复引入回归）；"UI 再宽一点，字都看不清了——
**上面的状态栏中的字才是关键**"。

### A. 识别回归的真因（两层门槛不一致，0-7 只改了一层）
- **映射层还有第二道 10 子门槛**：`DetectionBoardMapper.map(dets, w, h, minPieces=10)`，
  `YoloBoardDetector.detect` 调用时未传参 → **0-7 在服务端把门槛降到 3 对 8 子残局根本没生效**
  （原始识别错误并未修复）。
- **同时服务端门槛降低确实破坏了正常局面**：映射层放行的是"原始检测 ≥10"，但去重/越界丢弃后
  `pieceCount`（落格数）可能只剩 3~9——这类垃圾帧以前被服务端 `<10` 拦掉，0-7 之后放进了跟踪器；
  叠加跟踪器"首帧即确认"（`prev==null` 直接 confirmed）的旧逻辑，**一帧垃圾就能变成当前局面**
  （sessionGrid 清空后的下一帧尤其危险）。

### B. 修复：门槛统一 + 跟踪器两道真防线
- `DetectionBoardMapper.map` 默认 `minPieces` **10→3**（注释写明语义：只挡"整屏无棋盘"，
  结构合法性交给 validate、瞬时坏帧交给多帧确认）。真机 8 子残局至此才真正可识别。
- `BoardTracker.onFrame`：
  1. **首次确认改连续 confirmCount 帧完全一致**（原 prev==null 一帧即确认；瞬时坏帧/幻觉帧
     很难连续 3 帧逐格一致）。确认时仍按 `matchStartCount>=32` 判 NEW_GAME/NEW_BOARD。
  2. **子数跳变门限**：`|res.recognizedPieces - prev.recognizedPieces| > max(2, prev/10)` → UNSTABLE。
     真实走子只 ±1（吃子 -1）、噪声 ±2~3；大跳变只可能是局部遮挡/动画/幻觉。放在重开检测
     （matchStartCount>=32）**之后**，换局的子数骤变走 NEW_GAME 不受影响。
- 单测 44/44：更新 3 个依赖"首帧即确认"的用例；新增"稀疏残局 3 帧确认""子数跳变拒绝""映射层
  放行 8 子残局（board 框在即可）"；残局 fixture 修正为物理合理布局（红在下）。

### C. 面板重排（状态文字=主体；教训：改 UI 前先问清"哪个元素是关键"）
- 布局（`overlay_panel.xml` 全量重写）：
  - 左列 **120dp×171dp**：`overlay_status` **13sp**（原 9sp）weight=1 占主体（约 8 行可视）；
    底部一行小按钮 **变招/悔棋/手动**（12sp、paddingH 5dp、marginH 2dp）。
  - 右列 **78dp×171dp**：深度20 / 更新棋局 / 停止 / 关闭（关闭仍在最后，二次确认不变）。
  - 棋盘 158×171 不变；面板总宽约 384dp（`panelW` 估算同步 dp(384)）。
- **右列必须固定宽度**：`wrap_content` 在悬浮窗测量下会被压成竖排（"深度20"一字一行）——
  悬浮窗小面板的列宽不要依赖 wrap_content。
- **位置钳制**：持久化 overlayPos 按旧面板尺寸归一化，面板加宽后整体出屏（右列被裁）。
  `ensureOverlay` 对 x/y 做 `coerceIn(0, screenW/H - panelW/H)`。
- 手动模式提示压缩为 4 行（首行含退出方式）。

### D. 模拟器回归（全部通过）
新布局视觉、状态大字、手动进出+播种、更新棋局翻转、关闭二次确认（武装→3s 复原→双击关闭）、
位置钳制。安装注意：`install -r` 大 APK 的安装会话可能晚于 `am start` 落盘（installPackageLI 强杀），
install 后 sleep ≥8s。

---

## 0-7. 2026-09-07 凌晨更新（残局识别修复 + 手动模式，交付 `_20260907_6`）

> ⚠️ 本节 A 的"根因"结论已被 0-8 修正：真机上有**两层**门槛（映射层 minPieces=10 未改、服务层改 3
> 反引入回归）；最终方案见 0-8 B。本节的手动模式/按钮/布局描述仍有效（布局已被 0-8 重排）。

用户反馈（截图 `G:\AI_coding\CN-chess\识别错误`，只读参考）：某残局界面（约 8 子：仕帅仕马 vs 将象象卒）
识别突然失效——截图1：迷你棋盘全空、状态"正在识别棋盘…"循环；截图2：同界面 3 分钟后识别出
**幽灵棋盘**（子力错移+幻影"砲"），引擎对错误局面思考。

### A. 根因：固定 10 子门槛（一处代码、两种故障）
- `handleFrame` 中 `mapped.pieceCount < 10` 把该帧当"未见棋盘"拒绝：**8 子残局就算完美识别也永远进不了
  跟踪器**（截图1）；而个别帧幻觉出 ≥10 个检测的垃圾结果反而不受此门槛拦截，数量恰好合法通过
  `validate()` 后被 3 帧确认成幽灵棋盘（截图2）。
- 修复：`MIN_RECOGNIZED_PIECES = 3`（companion const，残局低至 3 子仍可识别）；合法性把关交给已有的
  `AssistBoard.validate()`（帅/将各一、各类子数超限即拒）。**教训：数量下限门槛与 validate 的语义重叠
  却更粗糙，宁可放宽下限、由结构校验把关。**
- 顺手修：状态行与 headText 拼接导致"正在识别棋盘…"显示两遍——`fen.isEmpty()` 时 headText 置空。

### B. 紧急手动模式（`OverlayAction.MANUAL`，用户选定"走子+删子，不放子"）
- 触发：面板左列新按钮 **手动**。进入后 `manualMode=true`，`handleFrame` 开头
  `if (paused || manualMode) return`（帧识别完全暂停，日志可见 yolo 停转）。
- 种子局面 = `currentCanonical`；识别从未成功时 = `canonicalStart()`（从起始局面录入）。
  **进入即 pushManualHistory 快照种子局面**（与 history.last().fen 去重）。
- 小棋盘点格（`OverlayChessView.onCellTap(x,y)`，`center()` 反解 + flipped 映射，半格距命中）：
  - 点有子格 → 选中（金色高亮圈，`selectedCell`）；点空格无操作；
  - 已选中点**同格** → 删除该子（帅/将不可删；**不翻转轮次**——修正语义）；
  - 已选中点**异格** → 移动（含吃子），**翻转 `tracker.redGo`**（实战着法语义）。
  - 每次编辑经 `applyManualBoard`：**编辑后**局面入栈（`history.last` 恒等于当前显示局面，这是悔棋
    正确的前提——曾按"编辑前入栈"实现导致首次悔棋被 `size<=1` 拒绝）、重建 currentFen/清缓存、
    `postRender()+scheduleAnalysis()`（手动模式下 `recHealthy` 直通，不收识别健康度影响）。
- 退出：再点 **手动**（帧识别恢复）；`undoOneStep` 在手动模式下**不再置 `paused=true`**（manualMode
  本就拦帧，避免退出手动后莫名"已停止"）。
- 手动模式下开局库/引擎正常工作（模拟器实测：播种开局局面后命中"开局库 炮二平七 ✓ 胜率约4%"）。

### C. 按钮调整
- **"轮次"更名"更新棋局"**（行为保持：点按翻转走子方并重建/重分析——用户确认仅更名）。
- **关闭二次确认**（`OverlayPanelView` 内部）：首次点按 → 按钮文字"确认关闭?"（红色 #FF5252），
  `postDelayed(closeReset, 3000)` 超时复原；3 秒内再点才真正发 `CLOSE`。

### D. 布局：面板高度严格只由棋盘决定（修按钮被挤出屏幕）
- 状态文字变长（如引擎错误 4 行）会把左列按钮往下推出屏幕。修复：`overlay_panel.xml` 左列高度锁定
  **171dp**（=棋盘高），`overlay_status` 改 `height=0dp weight=1`（过长截断，ellipsize=end）；
  右列同样锁 171dp + `gravity=center_vertical`（右列三按钮总高超 171dp 曾是溢出元凶）。

### E. 验证
- 单测 41/41（新增：8 子残局过 validate+门槛、手动走子语义 = `applyUcci`、手动选中渲染两用例）。
- 模拟器全流程回归通过：面板布局/状态去重、手动进出（logcat yolo 停转/恢复）、选子高亮、走子翻轮次、
  开局库命中、悔棋恢复、删子不翻轮次、关闭二次确认（武装/超时复原/双击关闭）。
  注意 `install -r` 大 APK 的安装会话可能晚于后续 `am start` 才落盘（installPackageLI 强杀进程），
  验证脚本需在 install 后 sleep 数秒。
- 真机待验收：该残局界面应直接识别 8 子局面；幽灵棋盘可"手动"删子纠正后继续用。

---

## 0-6. 2026-09-07 深夜更新二（对齐对弈制式 + 开局库，交付 `_20260907_4`）

用户指出：变招/送子的解释不充分；原版对弈默认 Hash=256、默认固定深度 20、且**使用开局库**——要求仔细研究原版"对弈"并深度调优。研读结论与落地：

### A. 搜索制式对齐对弈（修"出棋极慢"）
- 原版默认 **`go depth 20`**（`Settings.go_depth=true/go_depth_value=20`），另有固定时间/无限选项。
  连线此前用 `go movetime 2500` 是制式错误：每次固定烧满 2.5s。已改 **`go depth N`**：
  简单局面/残局秒出，中局典型 1~4s，棋力恒定恰好 N 层；`searchOnce` 兜底超时放宽 60s（防呆）。
- 强度三档改深度制：**深度12 / 深度20（默认=对弈默认）/ 深度24**；`AssistConfig.searchDepth`
  替代 `searchTimeMs`（KEY "search_depth"，旧键废弃）；按钮显示"深度12/20/24"。
- `Threads` = 全部核心（对齐 `ComputerPlayer.setOptimizedThreads`，无上限）；`Hash` = **256**
  （对齐 `Settings.hash_size` 默认，纠正此前 64 的偏离）。

### B. 接入开局库（对弈同款）
- 原版对弈先查开局库再走引擎（`GameController: bhBook.query(vkey, isRedTurn(), BEST_SCORE)`，
  vkey = `Board.getZobrist(redGo)` = `Zobrist.getZobristFromBoard(int[][], redGo)`）。
- 连线侧：`scheduleAnalysis()`（worker 线程）我方回合先 `queryBook(canonical, redGo)`
  （`BHOpenBook`，assets 数据库"桔库09.09.2023精修库.obk"经 SQLiteAssetHelper 管理）；
  **命中 → 直接显示"开局库 X ✓"+胜率（绿箭头），不启动引擎（零等待）**；未中 → 引擎深度搜索。
  变招在库命中时循环库内候选（SQL limit 5，带 胜/和/负 战绩）。
- 注意：`BookData.winRate` 实为 **vwin 胜场数**（BHOpenBook 的字段映射），胜率需按
  `win/(win+draw+lose)` 现算（曾误按 0..1 显示成"600%"）。
- 开局库不依赖 arm64 引擎，**模拟器可完整验证**（已验证：开局局面秒出"开局库 炮二平七 ✓ 胜率4%"，
  该 4% 为库内 4胜/120和/0负 的真实战绩）。

### C. 送子硬防线（第三道）
- 展示任何建议/预案前，校验着法**起点格在当前 canonical 盘面上必须是我方棋子**
  （`fromIsOurs`，local fun）：轮次(redGo)错乱或残缺识别产生幽灵 FEN 时（用户截图里
  "深度245"即此类），不显示建议并提示"轮次可能识别有误，走一步后自动纠正"。
- 三道防线 = ①起点校验 ②识别失效抑制（recHealthy）③对方回合不画箭头。

### D. 其他
- 引擎错误信息截断 40 字符（超长 IOException 路径曾把左列 8 行占满，开局库文本被挤出可视区）；
  左列 maxLines 8→11。
- 变招标识：循环到第 2/3 候选时显示"备选N X"，开局库则"开局库备选N X"。
- **轮次手动纠正**（用户反馈"实际轮到我方，小棋盘却显示轮到对方"）：对方走子瞬间识别差分
  偶发失败（movedSide=null）会让 redGo 卡在旧值，直到我方再走一步才自愈——中间整整一步无建议。
  面板右列新增"轮次"按钮（`OverlayAction.FLIP_TURN`）：翻转 `tracker.redGo`、按新轮次重建
  currentFen、清空 book/analysis/成熟度状态、重新 scheduleAnalysis。

---

## 0-5. 2026-09-07 深夜更新（真机截图反馈五项，交付 `_20260907_3`）

用户提供了两组真机截图（`G:\AI_coding\CN-chess\荒谬走法`、`送子走法`，只读参考）：

### A. "我方打我方"的荒谬箭头（根因，截图实锤）
- 预案箭头把"我方应手 Y"画在"对方还没走 X"的当前盘面上——Y 的目标格此刻还压着
  我方的子（X 本应吃掉/移开它），视觉上就是我方吃我方。低强度档（深度浅、战术变化多）更常见。
- 修复：**对方回合不再画箭头**，只保留文字预案"若对方走X 我方应Y"（有条件语义由文字表达）。

### B. 建议"意志不坚定" → 成熟度指示（不同颜色箭头）
- 我方回合分析中建议会随搜索加深而变动。新增稳定跟踪：最佳着法连续
  `SUGGEST_STABLE_MS=1200ms` 未变化、或本次搜索已结束（bestmove）→ **定着**：绿箭头
  （OverlayChessView.mature，绿 2E7D32/A5D6A7）+ 文本"建议 X ✓"；未定着：蓝箭头 +
  "建议 X（暂定）"。新局面确认时重新计数。
- OverlayModel 增加 `mature` 字段。

### C. 点击棋盘 3 种建议 = MultiPV 变招（有意设计，保留）
- `AnalysisEngine(maxPv=3)`：引擎每次给出前 3 条候选变化，点棋盘/"变招"循环切换。保留。

### D. "送子"感 —— 陈旧建议抑制 + 识别失效可见化
- 截图中两条线索：状态栏"正在识别棋盘…"（识别当时正失败）且出现"深度245"异常值
  → 建议所依据的 FEN 很可能来自一次残缺/误读的识别。识别中断期间旧建议照常展示，
  用户跟着走就可能送子。
- 修复：**识别失效时抑制建议展示**——`recHealthy = yoloMissStreak==0 && tracker.unstableStreak<5`，
  不健康时不画建议箭头、不显示"建议"文本（状态栏已说明原因）；识别恢复自动重现。

### E. 引擎增强
- `Threads`：可用核心数上限 6→8；`Hash` 64→256MB。
- 引擎 `info string` 行（如 NNUE 加载自述）转发到 logcat（`AnalysisEngine` tag，Log.i）——
  真机若"感觉不强"，先查 `NNUE evaluation using` 是否出现：NNUE 没加载会退到古典评估，棋力大减。
- 快速档(1s)深度确实明显浅，关键局面建议用标准/强劲档。

---

## 0-4. 2026-09-07 晚更新（交付 `_20260907_2`）

### A. 修"卡在预测那一步、停止对我方指导"（根因）
- `AnalysisEngine` 搜索循环原有 `if (fen != lastFen) searchOnce(fen) else sleep(300)`：
  **同一 FEN 的重复请求被静默吞掉**。对局中来回换子形成重复局面时，我方回合的请求正好命中
  上一次搜过的 FEN → 引擎永远不再搜索 → 画面冻结在旧预测。已删除该短路，凡请求必重搜。

### B. 对方回合：算力产出"我方应对预案"（设计原则：对方不可控，算力全为我方）
- 对方回合引擎照常按当前思考强度分析当前局面（MultiPV），但悬浮窗展示完全是我方视角：
  **"若对方走X，我方应Y"**，箭头画我方应手 Y（蓝色）；原"对方可能走X（预测）"橙色箭头
  及 `predictMode` 全链路删除（OverlayModel/OverlayChessView/renderOverlay/渲染测试）。
- 我方应手的中文棋谱用新增纯函数 `AssistBoard.applyUcci(fen, ucci)`（应用对方候选着法得
  新 FEN，行棋方自动翻转）+ `MoveChinese.describe`；带单测。
- 我方走子确认后引擎立即切换（stop + bestmove ≈ 0.2s）到新局面全强度计算；
  我方建议的分析时长/档位不变（精度无损），对方思考时间被用于预演我方应对。

### C. 面板布局重排（棋盘 158×171 不变，高度只由棋盘决定）
- 顶部整行状态文字移入**左列顶部**（多行小字 9sp，maxLines 8），按钮分列两侧：
  左列 = 状态 + 变招/悔棋/停止；右列 = 强度/关闭。
- 悬浮窗文本改 `\n` 分行（`statusText = "$status\n$headText"`，各分支文案相应改短并自带换行）；
  通知栏/连线页仍用同一文本（通知多行可读，连线页单行可接受）。
- `OverlayPanelView` 的 `orientation = VERTICAL` 硬编码删除（XML 已改 horizontal）；
  `ensureOverlay` 估算 panelW 285→300dp、默认贴底偏移 392→190dp。
- 引擎强度档依据（答复用户）：标准 2.5s=项目原有默认；pikafish 迭代加深，时间×4≈深度+2~4 层，
  快 1s/标准 2.5s/强 5s 为速度↔棋力工程取舍；不用 Skill Level 降智，建议永远客观最优。

---

## 0-3. 2026-09-07 更新（删换手 + 修 UCI 协议错误，交付 `_20260907_1`）

### A. "UCI protocol error" 频发且功能失效 —— 看门狗误报（根因）
- `ExternalEngine.startupThread` 在启动 10 秒后检查 `startedOk && isRunning && !isConfigOk`，
  不满足即报 `R.string.uci_protocol_error`（"UCI 协议错误"）。`isConfigOk` **只能**由
  `initConfig(EngineConfig)` 置位（`UCIEngineBase:70`）。
- 游戏内 `ComputerPlayer.processEngineOutput` 在 uciok 后调用 `uci.initConfig(engineConfig)`
  （`ComputerPlayer.java:451`）；连线 `AnalysisEngine.doStart` 自己做握手却漏了这步 →
  真机上每次都是：握手成功→正常约 10 秒→看门狗报错→`engineListener.reportEngineError` →
  服务 `onEngineError` 置 `engineReady=false` → 之后 `requestAnalysis` 全部拒绝 → 功能失效。
- 修复：`doStart` 在 uciok 后补调 `eng.initConfig(ec)`；并按游戏内流程补发 `ucinewgame`；
  `readyok` 等待 8s→15s（慢设备 NNUE 加载保险，游戏内该步无超时）。
- 模拟器（x86_64）无法真跑引擎（只打 arm64 引擎二进制），此修复需真机验证：
  连线启动 >10 秒后不应再出现"UCI 协议错误"，建议箭头应持续可用。

### B. 删除"换手"按钮与 mySide 覆盖机制
- 随动设计下"我方=屏幕下方阵营"自动判定，手动覆盖已无意义（用户明确要求删除）。
- 删除：`OverlayAction.SWAP_SIDE`、面板"换手"按钮（xml+接线）、
  `AssistConfig.mySide` + `MY_SIDE_*` 常量 + `KEY_MY_SIDE`；
  `mySideIsRed()` 简化为 `currentOrientation == Orientation.STANDARD`。
- 保留 `OverlayModel/OverlayChessView.mySideIsRed`（"我方"色条颜色/位置仍需要，取自动值）。
- 面板按钮现为 左列 变招/悔棋/停止，右列 强度/关闭。

---

## 0-2. 2026-09-06 深夜更新（真机反馈第二轮四项，全部完成）

真机试玩 `_1` 版后的四项反馈与修复（交付 `ChessFish_20260906_2_armv8-Release.apk`，release 签名
与 `_1` 相同，可直接覆盖安装）：

### A. 箭头不显示 / 一直"引擎预热中" —— 分析引擎握手 bug（根因）
- `AnalysisEngine.doStart` **从未发送 `uci` 就等 `uciok`**（pikafish 只在收到 `uci` 后才回
  `uciok`，游戏内 `ComputerPlayer.java:395` 是发的），15 秒超时后报错，而
  `ScreenAssistService.onEngineError` 又把错误吞掉（只写日志）→ 状态永远停在"引擎预热中"、
  永远无建议箭头。此前 E2E 只验证了识别/翻转没验证引擎出招，故漏网。
- 修复：握手前发 `uci`；配置选项后发 `isready` 等 `readyok`（`ready` 仅在真正 readyok 后置位）；
  读到引擎输出 EOF（进程退出）立即失败；启动失败自动重试 1 次；
  `onEngineError` 上屏（`engineErrorText` 显示在状态栏+通知栏，onEngineReady 清除）。
- **注意**：x86_64 模拟器上引擎必然启动失败——引擎二进制只打 arm64-v8a
  （`src/main/pikafish/` 经 `nativeLibsToJar` 进 APK，abiFilters 无 x86_64 引擎），这是既有
  环境限制；真机 arm64 不受影响。引擎真实出招链路需真机复测（logcat 关键字
  `analysis engine ready` / `go movetime` / `bestmove`）。

### B. 迷你棋盘永远红在下 —— 双重翻转 bug（一行修复）
- 服务端 `onBoardConfirmed` 曾传 `screenRaw`（屏幕布局），而 `OverlayChessView` 契约是收
  **canonical**（红恒 y=9）并自行按 `flipped` 做 (x,y)→(8-x,9-y) 映射；两者叠加 = 恒定显示
  canonical（红在下）。STANDARD 时 screenRaw==canonical 所以看不出来。
- 修复：改传 `flatten(confirmed.canonical)`，字段改名 `currentPieces`。箭头坐标
  （`ucciToCells`，canonical）与棋子契约自此一致。新增**像素级回归测试**
  （`OverlayChessViewRenderTest`：flipped 渲染断言红帅在顶/黑将在底）。

### C. 悔棋按钮（纯本地回退，合规红线不变）
- 面板左列第二键"悔棋"（`OverlayAction.UNDO`）。服务端 `history: ArrayDeque<HistoryEntry>`
  （result + redGo + fen，上限 60），每次确认入栈。
- 每按一次：弹出当前 → `BoardTracker.restore(result, redGo)`（新接口）恢复上一局面 →
  重新请求分析（上一手之前的建议）→ **paused=true 暂停实时同步**（否则屏幕当前局面下一帧
  就会覆盖悔棋结果），状态"已悔棋一步（点『继续』恢复实时识别）"；『继续』恢复后自然与屏幕
  重新同步（历史重新积累）。空栈提示"没有可悔的局面"。
- 纯本地状态回退，无任何点击注入。

### D. 引擎强度调节（面板按钮，默认=当前默认强度）
- 面板右列首键"强度:快/中/强"循环（`OverlayAction.CYCLE_STRENGTH`）：
  快速 1000ms / 标准 2500ms（默认，与既有 `go movetime 2500` 一致）/ 强劲 5000ms。
- 持久化到 `AssistConfig.searchTimeMs`（该字段此前就有管道但从未有 UI）；
  `AnalysisEngine.searchTimeMs` 改 `@Volatile` + `setSearchTimeMs()` 热更新，下一次搜索生效。
- 不用 pikafish `Skill Level`（建议保持客观最优；如要"入门档"可后续加）。

### 健壮性 + 布局 + 测试
- 裁剪推理自锁修复：棋盘挪动后若残框内持续识别不稳定（UNSTABLE×10 且有裁剪框），
  清 `sessionGrid` 回全屏重新定位（模拟器实测曾卡死在该状态）。
- 面板按钮 2+2 → 3+3：左列 变招/悔棋/停止，右列 强度/换手/关闭（列宽 wrap_content，
  `ensureOverlay` 估算 panelW 285dp / 默认 y 392dp）。
- 单测 36/36 全绿（新增 tracker.restore 回退重同步用例、flip 像素断言）。
- 模拟器 E2E（x86_64）：标准/翻转朝向识别、32 子 FEN（开局 w + 合成"炮二平五"图 b）、
  强度循环、悔棋空栈提示/有栈回退/继续重同步、引擎错误上屏与重试日志；
  引擎真实出招需 arm64 真机验证（见 A）。

---

## 0. 2026-09-06 晚间更新（第一轮真机反馈四项改进，全部完成）

真机确认 YOLO 管线可识别后，用户提出四项需求，已全部完成：

### A. 悬浮窗迷你棋盘改为"原版微缩"样式 + ArrowShape 箭头
- `ui/OverlayChessView.kt` 重写：加载与主界面同一套资源（`R.drawable.chessboard` 1240×1340 底图 +
  14 张 140×140 棋子 PNG，`BitmapFactory.decodeResource` 惰性缓存），照搬 `ChessView.java` 的几何常量
  （PIECE_SIZE=110 / X_OFFSET=22 / Y_OFFSET=5 / INTERVAL=136）等比缩放绘制；
  箭头用 `com.xqdk.chess.utils.ArrowShape`（参数按视图 scale 缩放），我方蓝/预测橙。
- 面板加大：迷你棋盘 150dp 方形 → 158dp×171dp（原版比例），面板宽约 270dp，
  `ensureOverlay()` 的 panelW/dy 估算同步更新。
- 有 Robolectric 渲染快照测试 `OverlayChessViewRenderTest`（@GraphicsMode(NATIVE)），
  输出 `app/build/overlay_render_*.png` 供目检（标准/翻转/箭头/预测色全覆盖）。

### B. 迷你棋盘朝向"随动"
- 面板数据链改为 `screenRaw + orientation`（`OverlayModel.flipped`）：屏幕红在上时迷你棋盘同样红在上，
  箭头坐标在 View 内做 (x,y)→(8-x,9-y) 映射；底部永远=被识别屏幕的下方一侧（即"我方"侧）。
- "我方"色条按 `mySideIsRed == bottomIsRed` 决定画在顶/底。
- **勘误（0-2 节 B）**：本节设计的 `screenRaw + flipped` 数据链存在双重翻转（视图契约是
  canonical + 自行映射），实测"永远红在下"；已改为 canonical，见 0-2 节。

### C. 手动校准死代码全部删除（含旧模板管线）
- 删除文件：`ui/CalibrationView.kt`、`AutoCalibrator.kt`、`PieceTemplates.kt`、`TemplateStore.kt`、
  `assets/tt_templates.bin`、旧 `yolov5s_xq_fp16.tflite`、测试 `AutoCalibratorTest.kt`/`SyntheticFrame.kt`。
- `BoardRecognizer.kt` 整个删除，其中仍被 YOLO 管线使用的 `Frame`/`Orientation`/`RecognitionResult`/
  `BoardTracker` 迁移到新文件 `RecognitionTypes.kt`。
- `ScreenAssistService`：删 setCalibration/getCalibration/collectTemplates/grabFrameToFile/
  checkAndCollectStart/maybeSelectLibrary 与全部旧管线字段；YOLO 初始化失败仅提示（不再回退模板管线）；
  悬浮窗"校准"按钮删除（OverlayAction.CALIBRATE 移除）。
- `AssistActivity`+`assist_activity.xml`：删④抓帧/⑤采集/手动四点校准折叠区/微调按钮/红上红下选择；
  只留 ①②③ + 状态 + 合规声明。
- `AssistConfig`：删 `grid`，`calibrationFrameSize` 改名 `frameSize`。`BoardGrid` 精简为
  四个归一化字段 + `fromPxCorners`。
- 单测 31/31 全绿（AssistCoreTest 保留 AssistBoard 用例，tracker 用例改为直接构造 RecognitionResult）。

### D. 识别提速（模拟器实测单步确认 5~7s → ~1.5s；真机更快）
1. **抓帧节奏修复（最大头）**：原逻辑"悬浮窗存在即 1400ms 抓帧"，改为"仅面板实际遮挡棋盘
   （overlayIntersectsBoard 且非拖动）才 1400ms，否则 220ms"。
2. `MIN_RECOGNIZE_INTERVAL_MS` 400→250ms。
3. **换小模型** `assets/yolov5n_xq_fp16.tflite`（YOLOv5n，3.8MB，桌面 invoke 0.11s vs s 模型 0.40s）。
   精度先经 Python 验证：TTexample 32/32；自研 2/3/4/5.jpg 经生产过滤后映射全部正确
   （2/3.jpg 的一个误检落棋盘外被边界过滤）。转换链路同前（onnx2tf -fdosm → TFLiteConverter fp16）。
4. **裁剪推理**：已知棋盘框后仅送"棋盘外扩 1.2 格"区域（边长取 16 倍数并复用裁剪位图减少 GC），
   检测坐标加回偏移；识别失败清 sessionGrid 自动回全屏自愈。
5. `YoloBoardDetector.renderInput` 重写：`Bitmap.createBitmap+setPixels` 取裁剪区 → 单次
   `drawBitmap(src→dstRect)` 完成缩放；ARGB→float 在 JVM 内一次循环写入复用数组再
   `FloatBuffer.put` 整块入输入缓冲；decode 直接收 `Array<FloatArray>`（删除 2MB/帧 flat 拷贝）。
6. `imageToFrame` 整行 `buffer.get(byte[])` 批量读再解 RGBA（消每帧 ~780 万次单字节 JNI）。
7. 单步延迟构成（模拟器）：3 帧确认 × (~400ms 推理 + 开销) ≈ 1.5s；真机 arm64 预计 ≤1s。
   如需再快：加 `tensorflow-lite-gpu` GPU delegate 或进一步降低 confirmCount。

### 模拟器端到端（本轮回归）
- 32 子识别 + FEN 正确 + 走"炮八平五"后 `w→b` 翻转 ✓；识别节奏 ~500ms/帧；
- 悬浮窗面板目检：原版棋盘样式、按钮（变招/停止｜换手/关闭）、状态行正常。

---

## 0.1 背景：识别管线 YOLO 重构（2026-09-06 上午，方案细节仍有效，模型现为 n 款）

用户反馈"不能正确识别棋盘"（模板法对第三方 App 泛化差）。参考用户给的两个 YOLO 项目
（VinXiangQi / XiangQiLink，均为 C# 桌面端）后，**把识别主力换成了 YOLOv5 棋子目标检测**：

- **模型**：当前发货为 `app/src/main/assets/yolov5n_xq_fp16.tflite`（3.8MB，YOLOv5n；s 款 14.4MB 已删，精度不达标可按同链路转回 `中模型.onnx`）。转换自 VinXiangQi `中模型/小模型.onnx`（640×640，fp16 权重/fp32 激活，
  输入 NHWC float32 **RGB**，输出 [1,25200,20]）。
  - 来源：VinXiangQi v1.4.0 release 包 `Models/中模型.onnx`（GPL-3.0，模型版权归 VinXiangQi 项目；
    本项目为课程作业同时保留其来源声明）。**注意 XiangQiLink 的模型也是从这里来的**。
  - 转换链路（已验证数值一致，max diff 4.6e-4）：`onnx2tf -fdosm` 得 SavedModel →
    `tf.lite.TFLiteConverter` + `Optimize.DEFAULT + supported_types=[float16]` 导出。
    **不要用 onnx2tf 直出的 `_float16.tflite`**（它把激活也转成 fp16，CPU 内核 allocate 失败）。
  - 备选模型在 `D:\build\refs\models\`：`小模型.onnx`(yolov5n, 7.5MB, 低端机提速用，深色棋盘易误检)、
    `万能带旋转.onnx`(同 yolov5s，带旋转增广)；原始 zip `D:\build\refs\vxq140.zip`。
  - **关键坑：输入必须 RGB**；喂 BGR 会把红方棋子全部识别成黑方（实测）。
- **新增代码**（`com.xqdk.chess.assist`）：
  | 文件 | 职责 |
  |---|---|
  | `YoloDetections.kt` | 纯 JVM：检测框数据类 + 15 类别表（14 棋子→Piece 常量 + board） |
  | `YoloPostprocessor.kt` | 纯 JVM：25200×20 解码 → 置信度(默认0.6) → 类别感知 NMS → 宽高比(0.7~1.3)与"棋子宽度中位数"尺寸过滤（杀工具栏按钮误检/轮次标记小圆盘） |
  | `DetectionBoardMapper.kt` | 纯 JVM：board 框宽/8高/9 为格距（异常时棋子中心 bbox 兜底，≥20 子）；棋子中心 round 到交点；双王判朝向；输出 canonical + BoardGrid |
  | `YoloBoardDetector.kt` | Android：TFLite Interpreter（4 线程），Bitmap letterbox(114灰) → RGB float 缓冲 |
- **ScreenAssistService**：`handleFrame` 顶部 YOLO 分支——无需网格校准直接出局面；
  同格冲突取高分；`sessionGrid` 用 YOLO 棋盘框更新（悬浮窗遮挡判断与裁剪推理沿用）。
  检测器初始化失败（含 JVM 测试环境的 `UnsatisfiedLinkError`，必须 `catch Throwable`）仅提示。
  （2026-09-06 晚：旧模板兜底管线已删，见 0 节 C。）
- **验证**（CPU 4 线程，本机 onnxruntime/桌面 Python）：
  - 天天象棋官方开局截图 TTexample.jpg（1080×2400）：**32/32 子全中 + board 框**（模板法 30/32）；
    TFLite 与 ONNX 输出一致；映射结果 == 标准开局。
  - 自研棋盘中盘截图（docs/images/4.jpg）：26 子全对；2 个误检（工具栏▶按钮→越界丢弃、
    右缘"轮到红方"标记盘→被 0.6 置信度阈值杀掉）。
  - 单测 44 个全绿：新增 `YoloPipelineTest`（内嵌真实模型输出采样夹具，验证解码→NMS→过滤→映射全链路）
    与 `DetectionBoardMapperTest`（标准/翻转开局、越界丢弃、同格冲突、bbox 兜底、退化 board 框）。
- **性能**：本机 fp16 TFLite invoke≈0.4s/帧；中端手机预计 200~400ms，识别节流 400ms 下可接受。
  低端机吃紧时：转 `小模型.onnx`（yolov5n）替换 assets 即可，代码无需改动。
- **依赖**：`org.tensorflow:tensorflow-lite:2.14.0`（CPU + 默认 XNNPACK；未用 GPU/NNAPI delegate，
  需要时可加）。

---

## 1. 项目背景

- 原始项目：`zfdang/chinese-chess-fish-android`（安卓中国象棋（原项目名"象棋鱼"，鸣谢原作者 zfdang），Kotlin + 老式 Java 视图，AGP 8.8.2 / Gradle 8.10.2 / JDK 17）。
- 用户是大作业需求（课程作业）：给该项目加名为**「连线」**的功能 = 屏幕识别第三方象棋 App 的棋盘 + 悬浮窗走法指导。
- **合规红线（用户反复强调，不可逾越）**：
  - ❌ 不自动走子：无点击注入、无 AccessibilityService、无 Root/Xposed、不读写他人 App 数据。
  - ✅ 仅 MediaProjection（系统录屏授权）+ SYSTEM_ALERT_WINDOW（悬浮窗，TYPE_APPLICATION_OVERLAY）。
  - ✅ 定位为「识别 + 建议」，玩家手动走子；文档含"识别辅助学习、非作弊"声明。
- 首页新增**「连线」**入口按钮（MainActivity + activity_main.xml），点击进入 `AssistActivity`。

## 2. 功能需求（含用户后续迭代要求）

1. 自动识别棋盘：自动定位 9×10 网格（无人手校准）、自动判定朝向（红在上/红在下）、自动判定"我方"颜色（屏幕下方阵营=我方）。
2. 开局自动采集一次性模板（升级项）：用户把对局重置到开局 → 程序采集 32 个棋子模板并保存为"专属模板"（识别 avg=1.00）；**不要阻塞主流程**（见第 4 节三层模板）。
3. 悬浮窗（已按用户要求 4 轮迭代）：
   - 近似正方形、圆角（18dp）金边深色小面板；内容=迷你棋盘(150dp) + 左右两列按钮 + 顶部一行 10sp 文字。
   - 左侧按钮：变招、停止/继续；右侧：换手（翻转我方）、校准、关闭。
   - **面板任意部位可拖动**（onInterceptTouchEvent 实现，移动距离超过 slop 才拖动，短按=点击）。
   - 默认位置：屏幕**底部中央**空白区（不遮挡棋盘 → 抓帧时零闪烁）；拖动位置持久化（config.overlayPos）。
   - 迷你棋盘红方在下显示（标准视角），最佳走法画箭头：我方回合=蓝色"建议"；对方回合=登录**橙色"预测"箭头**（主流象棋软件式"红黑都提示"；对方回合文字标注"对方可能走 X（预测）"）。
   - 状态行示例：`识别中｜我方红方｜轮到我方走`、`建议 炮二平五｜红方优势 +0.35深度18｜…`。
4. **识别天天象棋等第三方 App**（用户核心诉求之一）：
   - 天天棋盘底色是**中央亮、四周暗渐变**，曾导致"全局中位背景"法把河界空格误判（match 19/32），
   - 用两版算法实验后定稿：**「格心盘 vs 环带（0.40~0.72 格半径）RGB 距离」**——环带几乎必是背景，天然抵抗渐变与相邻棋子；天天象棋开局检测实测 match=30/32、SATANDARD 正确；自研棋盘不回归。
   - 内建**天天象棋模板库** `app/src/main/assets/tt_templates.bin`（57KB，从用户提供的天天象棋官方开局截图 TTexample.jpg 提取的 14 类模板，提取器曾用 JVM 测试生成、现已删除该测试）。交叉验证：天天画面×天天库 avg=0.96（通用库 0.14）；自研画面×通用 0.46 > 天天 0.23。
   - **自动选库**：服务启动后每 2s 在"色差>45 的有子格"上分别用 天天库/通用库 求平均匹配分，选分高的（差>0.02 才切换并重建 recognizer）。日志 tag：`AssistService`/`lib select: tt=… builtin=…`。
5. 引擎分析：复用项目自带 pikafish（UCI），`AnalysisEngine` 独立进程，`position fen + go movetime 2500`，MultiPV=3。**注意：引擎原生库只有 arm64**（`libpikafish-armv8.so`），x86_64 模拟器上引擎必报"引擎握手超时"——这是预期，不影响识别/面板，arm64 真机可用。（模拟器验证时面板会显示"引擎预热中…"。）
6. **关闭与录屏耦合**：点"关闭"= 移除悬浮窗 + `releaseCapture()` 停录屏 + 停止指导；之后在连线页点③重新授权能完整重启（已实测闭环：capture started 新日志 + 识别恢复）。

## 3. 当前完成度（已验证）

- 纯 JVM 单测 44 个全绿（新增 `YoloPipelineTest`（真实模型输出夹具）、`DetectionBoardMapperTest`；
  原有 `AssistCoreTest`、`AutoCalibratorTest`、`AssistLaunchRobolectricTest`、`SyntheticFrame` 等全部保留；
  **不要添加引用 D:\build 本地文件的测试**——会破坏他人构建，诊断脚本已删除）。
- 模拟器（AVD `api34_phone`, android-34 google_apis x86_64, emulator-5554）端到端（YOLO 管线，2026-09-06）：
  - `yolo detector: ready` → 免校准直接识别；
  - 自研棋盘开局：`yolo: infer=273ms dets=35 pieces=32 avg=0.96 dropped=2` →
    `rec(yolo): pieces=32 orient=STANDARD issue=0` →
    `board confirmed: rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w` ✓
  - 走"炮八平五"后：`board confirmed: …/4C2C1/… b`（FEN 与轮次 w→b 均正确）✓
  - 悬浮窗正常显示迷你棋盘与状态行（x86_64 上引擎"预热中"为预期）；
  - 真实天天象棋截图离线验证：32/32 全中（模板法 30/32）；自研棋盘中盘截图 26 子全对。
- 交付 APK：`D:\build\CN-chess\app\build\outputs\apk\armv8-\release\`（同签名可覆盖安装；
  含 14.4MB YOLO 模型 asset）。旧版 `D:\build\APK-delivery\ChessFish_20260905_1_armv8-Release.apk` 为模板管线版本。

## 4. 三层模板策略（解决"用户直接用不了"的关键设计）

早期流程**强制"对局必须是开局+采集成功才显示悬浮窗"**——用户实际对局（中盘）永远不满足 → 表现为"悬浮窗不出来/不识别"。已改为：

```
onCreate:
  userLib = TemplateStore.loadUserLibrary(this)   // ① 专属（自举/手动采集）
  customLibrary = userLib != null
  builtinLib = TemplateStore.builtinLibrary(this) // ③ 通用（项目自带14个PNG）
  ttLibrary   = TemplateStore.loadTtLibrary(this) // ② 内建天天象棋库(assets)
  templates = userLib ?: ttLibrary ?: builtinLib  // 默认选优
  templatesCollected = true                        // 不阻塞：打开即识别
背景模型 bgStats 若为空 → 首帧现场 learnBackground。
无专属模板时每 2s 运行 maybeSelectLibrary()（天天库 vs 通用库按有子格均分选优）。
开局检测(checkAndCollectStart)降级为后台升级(节流 700ms)：检测到开局且连续3帧一致
  → collectStartTemplates 32 个 → 保存 → customLibrary=true → recognizer 重建 → 提示"专属模板已生成"。
```

- `BoardRecognizer.recognizeCells` 的"空格判定"：
  - 有背景模型：`hasPieceByColor = dist(stats,bg) > emptyDistance(45)` **且** `(score>=0.30 || hasDetail)`；
  - 无背景模型：`score >= minScore(0.42) && hasDetail`；
  - `hasDetail = stats.maxVal - stats.minVal >= 40`（棋子文字笔画 vs 纯木纹）——**防空格被模板高相关误判**（实测空格与模板 NCC 高达 0.67，绝不能只靠模板判空）。
- 实测色差分离：自研棋盘空格 0.3~19.5，棋子 90.9~122.3（阈值 45 在鸿沟正中）。

## 5. 已知问题 / 下一步（用户等待中）

1. **天天象棋官方 APK 只有 arm64-v8a**（`native-code: 'arm64-v8a'`），x86_64 模拟器只能转译 Java 层，大厅渲染引擎反复 ANR——**模拟器无法完成天天象棋端到端验证**。官方 APK 已下载到 `D:\build\ttxq.apk`（196MB，小米应用商店官方 CDN 直链：`http://app.mi.com/download/75420`，版本 4.4.5.2，包名 `com.tencent.qqgame.xq`）。
2. 下一步两条路（**先问用户再动**）：
   - 用户安卓手机 USB 连电脑（开发者模式+USB 调试）→ adb 安装新 APK（可一并装 ttxq.apk）→ 用户实际操作 + logcat 实时诊断；
   - 或安装雷电/夜神模拟器（带 ARM 转译，天天象棋可跑）→ 在雷电 adb 上验证。
3. 用户使用痛点备忘：① 悬浮窗权限/通知权限未授予（状态文字会提示）；② 早期版本"必须开局"导致卡死（已修，改三层模板）。
4. **模拟器交互注意**：`adb shell input tap` 的屏幕坐标与可见 UI 常存在 ~60~240px 系统性偏差（悬浮窗窗口 uiautomator dump 不到）——可靠办法：在 `OverlayPanelView.render()` 里临时 `getLocationOnScreen` 打日志取真实按钮坐标，或改用 dump（`uiautomator dump //sdcard//ui.xml` 必须用 `//sdcard//` 或 `MSYS_NO_PATHCONV=1` 防止 Git Bash 转义）。

## 6. 环境与红线（重要）

- **红线1**：原始项目在用户 U 盘（`G:\AI_coding\CN-chess\fish-android` 等 G 盘路径）。用户明确："U 盘垃圾我自己删，以后别碰 U 盘"——**G 盘只允许读 TTexample.jpg 级别文件，绝不写入/删除/移动 G 盘任何文件**。所有改动在 `D:\build\CN-chess`（git 未初始化，直接把改动文件清单给用户自行同步）。
- **红线2**：删除/清理任何东西前先列清单问用户（用户习惯）。
- 工具链（重要，直接在 Git Bash 里可用）：
  ```bash
  export JAVA_HOME="D:\\Android\\jdk-17.0.20.1+1"
  export GRADLE_USER_HOME="D:\\Android\\gradle-home"
  export PATH="/d/Android/Sdk/platform-tools:$PATH"
  cd /d/build/CN-chess
  ./gradlew :app:testArmv8-DebugUnitTest --console=plain        # 单测（任务名带连字符！）
  ./gradlew :app:assembleArmv8-Debug   --console=plain -q       # 调试包（含 x86_64 仅用于模拟器）
  ./gradlew :app:assembleArmv8-Release --console=plain -q       # 交付包
  ```
- APK 输出：`app/build/outputs/apk/armv8-/…/ChessFish_20260905_1_armv8-*.apk`（flavor 名就是 `armv8-` 带尾巴，任务名形如 `assembleArmv8-Debug`）。
- app/build.gradle.kts 中 `abiFilters += listOf("arm64-v8a","x86_64")`（x86_64 是给模拟器调试用的，交付只发 armv8 一个 flavor）；`testOptions.unitTests.all { maxHeapSize="2g" }`（ant 大帧测试需要）。
- 安卓17（API 37.1）镜像已删（用户确认）；唯一 AVD：`api34_phone`（启动：`D:\Android\Sdk\emulator\emulator.exe -avd api34_phone`，无头时加 `-no-window` 之类按需；当前实例 emulator-5554 常驻）。
- 模拟器的"投屏/触摸"验证套路：`adb shell am force-stop com.xqdk.chess` → `am start -n com.xqdk.chess/.assist.ui.AssistActivity` → tap ③(540,1078) → 授权弹窗 tap "Start now"(841,1499) → `am start -n com.xqdk.chess/.GameActivity` → `adb logcat -d | grep -E "AssistService"`。
- 调试帧导出开关：`adb shell touch /sdcard/assist_debug_save` → 服务下一帧把原始帧写到 `/sdcard/Android/data/com.xqdk.chess/files/assist_frame.raw`（ASFR 头，大端 int w/h + RGB），方便离线 JVM 分析（对应解析代码已删，可自行重建）。

## 7. 代码地图（D:\build\CN-chess\app\src\main\java\com\xqdk\chess\assist\）

| 文件 | 职责 |
|---|---|
| `ScreenAssistService.kt` | 前台服务：MediaProjection 抓帧(ImageReader)、三阶段状态机、悬浮窗管理、UI 单行文本、库选择、引擎交互 |
| `AutoCalibrator.kt` | 纯 JVM：detectGrid（等距线族投影+角比过滤）、toBoardGrid、detectStartPosition（**环带法**）、朝向推断 |
| `BoardRecognizer.kt` | 纯 JVM：recognize/recognizeCells（色差+细节量判空、模板选类型）、detectOrientation、BoardTracker（3帧确认/新局/w-b 翻转/规则校验）。`RecognitionResult` 含 issues/unknownCells/avgScore |
| `PieceTemplates.kt` | 灰度提取（圆形掩码32×32）、NCC 匹配、ColorStats（含 redRatio/max/min） |
| `BoardGrid.kt` / `AssistBoard.kt` | 归一化网格；canonical 棋盘（红恒在 y=9）、FEN 生成/合法性校验 |
| `TemplateStore.kt` | 专属模板二进制持久化；builtinLibrary（14 个 PNG 合成木底）；**loadTtLibrary（assets/tt_templates.bin）** |
| `AnalysisEngine.kt` | UCI pikafish 独立进程、multipv、fen+go 2500ms、red-score 转换 |
| `MoveChinese.kt` | UCCI→中文着法表述 |
| `ui/OverlayPanelView.kt` | 面板：行内模型 OverlayModel；全区域拖动（onInterceptTouchEvent）；按钮回调 |
| `ui/OverlayChessView.kt` | 迷你棋盘自绘：网格/棋子/箭头（我方蓝、预测橙）/我方色条 |
| `ui/AssistActivity.kt` + `ui/CalibrationView.kt` | 连线页（①②③步骤、状态轮询、手动校准兜底+采集按钮⑤（强制开局））、手动四点校准 |
| 资源 | `res/layout/assist_activity.xml`、`overlay_panel.xml`、`drawable/overlay_bg.xml`(18dp圆角金边)、`overlay_button_bg.xml`、`values/styles.xml`(OverlayButton)、`assets/tt_templates.bin` |

## 8. U 盘同步清单（交给用户自行同步；不代删/代写 G 盘）

- 新增：`app/src/main/java/com/xqdk/chess/assist/**`（含 2026-09-06 新增
  `YoloDetections.kt`/`YoloPostprocessor.kt`/`DetectionBoardMapper.kt`/`YoloBoardDetector.kt`）、
  `app/src/main/res/layout/assist_activity.xml`、`overlay_panel.xml`、
  `app/src/main/res/drawable/overlay_bg.xml`、`overlay_button_bg.xml`、`values/styles.xml`(OverlayButton)、
  **`app/src/main/assets/yolov5n_xq_fp16.tflite`（3.8MB，勿漏；tt_templates.bin 与 s 款模型已随旧管线删除）**、
  `app/src/test/java/…/assist/*`（含 `YoloPipelineTest.kt`/`DetectionBoardMapperTest.kt`，
  不含 D:\ 路径依赖）、`docs/assist-helper.md`(已写)。
- 修改：`AndroidManifest.xml`(权限+服务+`exported=true` 仅调试用)、`MainActivity.kt`+`activity_main.xml`("连线"入口)、
  `app/build.gradle.kts`(abiFilters/测试配置/**tensorflow-lite 依赖**)。
- 已有文档：`README` 更新过；`docs/assist-helper.md` 含合规声明与使用说明（已更新 YOLO 版）。
- 0-7 增量：修改 `assist/ScreenAssistService.kt`、`assist/ui/OverlayPanelView.kt`、`assist/ui/OverlayChessView.kt`、
  `res/layout/overlay_panel.xml`、`test/…/assist/AssistCoreTest.kt`、`OverlayChessViewRenderTest.kt`、
  `docs/engineer-handoff.md`、`docs/assist-helper.md`。
- 0-8 增量：修改 `assist/DetectionBoardMapper.kt`、`assist/RecognitionTypes.kt`、`assist/ScreenAssistService.kt`
  （ensureOverlay 位置钳制）、`res/layout/overlay_panel.xml`（重排）、`res/values/styles.xml`（按钮 12sp）、
  `test/…/assist/AssistCoreTest.kt`、`DetectionBoardMapperTest.kt`、docs 两份。

## 9. 关键历史 Bug（新模型务必知晓，避免重蹈）

1. SharedPreferences 在 Service 字段初始化 → mBase==null 崩溃 → 必须 `by lazy`。
2. ImageReader：**每个回调必须 acquire+close（含节流路径）**，否则双缓冲占满后不再派发 → 帧停摆（表现为"什么都没有"）。
3. 悬浮窗窗口会被 MediaProjection 镜像进采集帧（面板/黑块挡棋盘）→ FLAG_SECURE 只会变黑块、无效 → 方案：仅当面板矩形与棋盘矩形**相交**时分析帧前隐藏约 60ms/1.4s（拖动时抑制、恢复放 finally 防 alpha 卡死）。
4. 陈旧的 config.grid 会跨会话残留挡识别（match=15/FLIPPED 那次）→ 网格只在"采集验证成功"后持久化 + startcheck 连续 6 次失败清空重新定位。
5. 全局中位背景法被渐变底拉垮 → 环带法；局部邻域法被 9 连子"同化" → 环带法是正解（别改回去）。
6. 空格 vs 模板 NCC≈0.67 → 判空必须依赖背景色差(+细节量)，不要单独放宽 minScore。
7. 回收/拖动事件：`onInterceptTouchEvent` 的 MOVE 判断必须加 `abs()`（否则只能向上/单方向拖动）。
8. 面板"关闭"后 `setStatus→renderOverlay→ensureOverlay` 会把它重建出来 → 需 `overlayClosed` 标志。
9. Git Bash 会把 `/sdcard/...` 转成 `D:\sdcard\...` → 一律 `MSYS_NO_PATHCONV=1 adb shell …` 或 `//sdcard//…`。
10. 单帧棋子数下限门槛（曾固定 `<10`）会把稀疏残局（8 子）永远拒之门外，而幻觉出的 ≥10 检测反而放行 →
    下限只防"整屏无棋盘"（现 `MIN_RECOGNIZED_PIECES=3`），子力结构合法性交给 `validate()`。
11. 历史栈语义：`history.last` 必须**恒等于当前显示局面**（确认/手动编辑后都要把"新当前态"入栈），
    悔棋=弹出当前+恢复新的 last；按"编辑前入栈"实现会让首次悔棋空转。
10. Kotlin 编译坑：`FloatArray[i] /= x` 在某 Kotlin 版本报 "No set method providing array access" → 显式 `avg[i] = avg[i] / x`。
11. 天天象棋官方 APK arm64-only → x86_64 模拟器大厅 ANR（Java 层可走：协议/权限/启动页）。**已下载留存 D:\build\ttxq.apk**。
