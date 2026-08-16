# StepShift UI 深度优化交接文档 (Kimi.md)

- **优化执行时间**：2026-08-17 03:24 ~ 04:00（本地时间）
- **执行工具**：Kimi Code CLI
- **优化范围**：`app/src/main/java/com/example/stepshift/ui/` 全部 Compose UI 层 + `MainViewModel` 中与 UI 交互相关的逻辑
- **验证结果**：`compileDebugKotlin` 与 `assembleDebug` 均 BUILD SUCCESSFUL（仅余少量历史 deprecation 警告，未新增警告）

---

## 1. 修复的 UI Bug（按严重程度排序）

### 1.1 「结束」按钮文字配色损坏（Theme.kt）
- **现象**：运行/暂停态的「结束」按钮为亮红底 (#FF5252) 配深棕红文字，对比度极差、几乎看不清。
- **根因**：`Theme.kt` 只覆写了 `error = DangerRed`，未定义 `onError` / `errorContainer` / `onErrorContainer`，Material3 暗色默认 `onError = #690005`（深棕红），亮红底 + 深红字直接糊掉。
- **修复**：明暗两套配色补齐 `onError = Color.Black`（黑字对 #FF5252 对比度约 8:1）及配套 `errorContainer` / `onErrorContainer`。同步修复了搜索联想「设为终点」按钮与 Root 异常徽标的容器配色。

### 1.2 底部控制面板双重 inset 留白（StepShiftApp.kt）
- **现象**：底部面板与系统手势小白条之间多出一条约 24~48dp 的缝隙，能看到地图从缝隙里透出来。
- **根因**：`Scaffold` 无 `bottomBar` 时会把系统导航栏 inset 计入 `innerPadding.bottom`（第一重），而 `ControlPanel` 内部又叠加了 `Modifier.navigationBarsPadding()`（第二重），同一 inset 被应用了两次。
- **修复**：在内容容器 `Box` 上追加 `Modifier.consumeWindowInsets(innerPadding)`，明确消费掉 Scaffold 已转为 padding 的 inset，子组件不会重复应用。面板现在干净地贴合在手势条上方，地图在手势条区域正常沉浸显示。

### 1.3 地图 Overlay 每秒全量重建（MapViewContainer.kt，性能 Bug）
- **现象**：仿真运行中地图以 1Hz 刷新，每一帧都把路线折线（数百个坐标点）、起点/终点 Marker、行走者 Marker 全部 `remove + new`，并为每个 Marker 重新 `Canvas` 绘制 Bitmap，造成持续的 GC 压力与掉帧风险。
- **根因**：`AndroidView` 的 `update` 块在每次重组时无差别重建所有 overlay。
- **修复**：引入「已应用状态」比对（`appliedRoute` / `appliedStart` / `appliedEnd` / `appliedBearingBucket`）：
  - 路线折线仅在 `routeResult` 变化时重建；
  - 起点/终点 Marker 仅在坐标变化时重建，且图标 Bitmap 用 `remember` 只创建一次；
  - 行走者 Marker 原地更新 `position`，图标仅在航向角变化超过 5° 时才重新生成；
  - 路线变更时级联强制重排，保证 Marker 始终绘制在折线之上。
- **附加改进**：路线新增深色衬底折线（#66101825，22px）垫在青色主线（#00E5FF，14px）下方，在卫星图与浅色街道图上的可读性显著提升。

### 1.4 搜索联想把仪表盘顶出屏幕（StepShiftApp.kt / SearchBarOverlay.kt）
- **现象**：搜索出结果时，280dp 的结果列表把遥测仪表盘一路顶到屏幕中下，配合弹出的软键盘几乎遮满整个地图；且点击「设为起点/终点」后软键盘不收起。
- **修复**：
  - 仪表盘可见性改为「仿真激活（RUNNING/PAUSED/COMPLETED）且无搜索结果展开」时才显示，搜索过程中地图完全无遮挡；
  - 选择搜索结果时通过 `LocalSoftwareKeyboardController` 主动收起键盘；
  - 修复结果列表最后一项之后多余的分割线（改用 `itemsIndexed` 按索引判断）。

### 1.5 IDLE 状态全零仪表盘常驻遮挡地图（StepShiftApp.kt）
- **现象**：未开始仿真时，顶部常驻一张「配速 0.0 / 步数 0 / 里程 0」的全零数据卡片，白白遮挡地图。
- **修复**：仪表盘仅在仿真激活后显示（`AnimatedVisibility` 平滑过渡），IDLE 状态由底部状态条承担引导职责，地图视野完整。

### 1.6 开始按钮状态机不完整（ControlPanel.kt）
- **现象**：① 路线规划进行中按钮文案显示「请先选点规划路线」，误导用户以为操作没生效；② 仿真完成（COMPLETED）后按钮仍显示「开始运动仿真」，语义不符。
- **修复**：开始按钮改为完整状态机——
  - 规划中：禁用 + 转圈 + 「正在规划路网...」；
  - 无路线：禁用 + 「请先选点规划路线」；
  - 已完成：Replay 图标 + 「重新开始仿真」（引擎 `start()` 对 COMPLETED 态自带归零重跑逻辑，直接复用）；
  - 就绪：「开始运动仿真」。

### 1.7 Root 徽标交互与状态色错误（StepShiftApp.kt）
- **现象**：「检测中...」（null 态）使用错误色的 Warning 图标，且此时点击徽标会误触发一整套 Root 赋权流程。
- **修复**：null 态改用中性 `Sync` 图标与 `onSurfaceVariant` 配色；检测中禁止点击；仅 `isRootAvailable == false` 时点击才触发赋权，文案同步改为「NO ROOT (点击赋权)」。

---

## 2. 清理与一致性修复

| 位置 | 内容 |
| :--- | :--- |
| `ControlPanel.kt` | 移除从未被 UI 使用的死参数 `onResetClick`、`onSelectModeChange`（调用方 `StepShiftApp` 同步清理） |
| `StepShiftApp.kt` | 移除从未接收消息的 `snackbarHostState` / `SnackbarHost` 死代码 |
| `MainViewModel.kt` | `updateSpeed` 上限由 30.0 收紧为 25.0，与控制面板滑杆 `1.0f..25.0f` 一致 |
| `MainViewModel.kt` | `onMapClick` 的 `NONE` 分支补齐重算逻辑：已存在终点时立即用旧终点重新规划，与 `SET_START` 分支行为对齐 |
| `SettingsDialog.kt` | 标题「⚙️ 参数与特权设置」的 emoji 改为 `Icons.Default.Settings` 矢量图标，与全局图标风格统一 |

---

## 3. 修改文件清单

```
app/src/main/java/com/example/stepshift/ui/theme/Theme.kt
app/src/main/java/com/example/stepshift/ui/StepShiftApp.kt
app/src/main/java/com/example/stepshift/ui/components/ControlPanel.kt
app/src/main/java/com/example/stepshift/ui/components/SearchBarOverlay.kt
app/src/main/java/com/example/stepshift/ui/components/MapViewContainer.kt
app/src/main/java/com/example/stepshift/ui/components/SettingsDialog.kt
app/src/main/java/com/example/stepshift/ui/MainViewModel.kt
```

同步更新的文档：`docs/PLAN.md`（新增 M10 里程碑）、`docs/PROBLEM.md`（新增第 10、11 条避坑记录）、`docs/HANDOVER.md`（组件描述与文档索引）。

---

## 4. 后续建议（本次未做，供下一任参考）

1. ~~真机回归验证~~（已于 2026-08-17 在 Pixel 10 / Android 16 真机完成，全部通过）。
2. **「点击地图重选起点」防误触**：`SelectionMode.NONE` 下任何单击都会替换起点，可考虑改为长按重选或增加二次确认。
3. **图标 deprecation 清理**：`Icons.Filled.DirectionsRun`、`AltRoute` 等建议迁移到 `Icons.AutoMirrored` 版本。
4. **搜索结果与仪表盘互斥策略**目前是简单隐藏，若后续需要边搜索边看数据，可改为结果列表绝对定位浮层。

---

# 第二轮优化（2026-08-17 03:46，真机反馈驱动）

## 5. 底部控制面板可折叠性与贴底融合重构

### 5.1 问题现象（真机反馈）
1. 底部面板（含「路网已就绪」状态条）**几乎无法折叠**：折叠把手触控区仅约 8dp 高、状态条内的箭头按钮仅 28dp，实际很难点中；且即便折叠后仍保留「状态条 + 50dp 大按钮」，依旧遮挡大片地图，难以操作地图。
2. 面板底边悬在导航条上方，与系统手势小白条之间夹着一条透出的地图，视觉上「面板与小白条是分开的」，非常突兀。

### 5.2 修复方案（ControlPanel.kt / StepShiftApp.kt）
1. **面板背景延伸至屏幕物理底边**：`ControlPanel` 从「Scaffold 内容 padding 盒内」移出为同级浮层（`StepShiftApp` 外层 Box 底部对齐），`navigationBarsPadding()` 从面板 `Surface` 下沉到其内部内容 `Column`——背景贴合屏幕底边、内容避让小白条，与手势条视觉无缝融合（Material 底部面板标准做法）。
2. **真正的可折叠细条**：
   - 折叠把手触控区加大到 24dp 高、整行宽可点；
   - 整条状态条改为可点击（`Surface(onClick=)`）切换收折，箭头图标仅作状态指示；
   - 折叠后只保留「把手 + 单行状态条」（约 90dp），速度滑杆、漂移开关、大按钮全部收起，地图几乎全露。
3. **折叠态紧凑操作按钮**（不丢失仿真控制）：状态条内嵌 34dp 图标小按钮——IDLE+路线就绪显示「开始」，RUNNING 显示「暂停+结束」，PAUSED 显示「继续+结束」，COMPLETED 显示「重开」。
4. 状态条文本加 `maxLines=1 + Ellipsis`，防止紧凑按钮挤压溢出。

### 5.3 真机验证记录（Pixel 10 / Android 16）
- 展开态：面板背景直贴屏幕底边，手势小白条叠在面板深色背景上，无地图缝隙 ✓
- 点击把手/状态条：面板在「展开 ↔ 细条」间平滑切换 ✓
- 折叠细条：示例路线规划后显示「路网已就绪 (2.01 km) + 绿色开始小按钮 + 重选」，地图操作区域大幅增加 ✓
- 折叠态点开始：仿真正常启动、仪表盘弹出、细条切换为「暂停+结束」紧凑按钮 ✓
- 运行中点状态条展开：完整大按钮（暂停/结束）恢复正常 ✓
- `assembleDebug` 编译打包通过 ✓

### 5.4 修改文件
```
app/src/main/java/com/example/stepshift/ui/components/ControlPanel.kt  (重构)
app/src/main/java/com/example/stepshift/ui/StepShiftApp.kt             (面板改为同级浮层)
```

---

# 第三轮优化（2026-08-17 04:21，功能深度审查与修复）

## 6. 功能级问题排查（全代码审查 + 真机验证）

### 6.1 P0：坐标基准（Datum）混乱 —— CoordinateTransform 从未接线
- **审查发现**：`CoordinateTransform`（WGS-84 ↔ GCJ-02）存在于代码库与文档中，但**从未被任何业务代码调用**。高德卫星源（`webst0x`，style=6）瓦片是 GCJ-02 火星坐标，而路线/Marker/行走者/点选全部使用 WGS-84 裸坐标 → 卫星图下路线偏移约 556m（北京），卫星图上点选的位置与实际注入的 GPS 偏差数百米。
- **关键实测结论（勿踩坑）**：本项目默认矢量源用的是 `wprd0x` 主机（style=7）——社区公认的 **WGS-84 无偏移**源；只有 `webst0x`（卫星）与 `webrd0x`（style=8）才是 GCJ-02。审查初期曾误判"矢量图也偏了"，真机对照（转换前后同坐标截图对比）证实矢量源本就无偏移。**按瓦片源分别标记基准**才是正确架构。
- **修复**：`TileSourceManager` 每个源带 `isGcj02` 标记（wprd 矢量=false、webst 卫星=true、OSM=false）；`MapViewContainer` 显示链路（路线/Marker/行走者/居中/追踪）按当前源做 WGS→GCJ 投影，点选链路做 GCJ→WGS 反投影；切换瓦片源时强制重建所有 overlay 重新投影。
- **验证**：JVM 单测（天安门 GCJ 偏移 556m ∈ [300,800]、往返误差 <1e-5°、国外坐标直通）全部通过；真机矢量图视图与修复前完全一致（无回归），卫星图路线精确贴合长安街与地面街巷。

### 6.2 P1：仿真完成后 WakeLock 与前台服务永不释放
- **问题**：`observeEngineState` 的 COMPLETED 分支是空注释。Partial WakeLock 空持有 → CPU 无法深睡，通知栏卡在错误的"已暂停"标题，一放几小时持续耗电。
- **修复**：COMPLETED → `releaseWakeLock()` + `stopForeground(DETACH)`（通知保留为完成总结卡片、可滑掉）；`ACTION_START` 重新 `acquireWakeLock()` 并重建 TestProvider（覆盖服务存活下的重启路径）；通知标题区分四态（仿真中/已暂停/已完成/待命），暂停/继续/结束按钮只在活跃状态出现。
- **验证**：0.71km@24km/h 短路线跑到完成，真机 `dumpsys power` 显示 `REL StepShift::KeepAliveWakeLock`、`Wake Locks: size=0`；服务记录无 `isForeground`（已脱离前台）；通知标题"仿真已完成 ✅"内容正确。

### 6.3 P1：运行/暂停中可改路线冲掉引擎状态
- **问题**：`onMapClick` 只挡 RUNNING 漏了 PAUSED；`onSearchResultSelected` 完全没有防护；`clearRoute` 在 PAUSED 下可用 → `engine.setRoute` 直接重置运行中的引擎，通知栏/服务/UI 三方状态错乱。
- **修复**：三处统一在 RUNNING/PAUSED 下拦截并 toast「仿真运行中，请先【结束】再修改路线」；`clearRoute(context)` 在 COMPLETED 后一并停掉残留服务；「重选」按钮在 PAUSED 时隐藏。
- **验证**：真机运行中点地图/搜索选点均弹出拦截 toast，路线与引擎状态不受影响。

### 6.4 P1：RootShellExecutor stderr 从未读取 + Root 检测误判
- **问题**：`errorReader` 创建后从未消费——命令若大量输出 stderr，管道缓冲填满会让 su 进程死锁（`readLine()` 永久阻塞且持有同步锁，所有 Root 操作卡死），与 PROBLEM.md 第 1 条声称的防护不符；`isRootAvailable` 的 `|| exitCode == 0` 使部分 ROM 上 su 被拒但以 shell 身份执行 `id` 也误判为已 Root。
- **修复**：`ProcessBuilder.redirectErrorStream(true)` 将 stderr 合流进 stdout 一并消费；Root 检测收紧为仅认 `uid=0`。

### 6.5 P2：通知栏 1Hz 全量 notify
- **问题**：每 1Hz tick 都 `notify()`，与 PROBLEM.md 第 2 条声称的节流不符，部分 ROM 会触发 SystemUI 速率限制。
- **修复**：`updateNotification` 加节流——状态切换立即更新，普通数据 tick 最小间隔 2s。

### 6.6 记录在案但未改动（避免范围蔓延，供后续参考）
1. `targetSteps` / `targetDistanceM`（目标步数/距离自动完成）引擎已支持但 UI 无入口；`updateTargetSteps/updateTargetDistance/updateCadence/resetSimulation` 为未被调用的 ViewModel API。
2. 1Hz ticker 用 `delay(1000)` 存在累计时钟漂移（约 0.3%），内部计数自洽、影响极小。
3. 离线降级路线的耗时估算固定按 5km/h，不随当前配速变化（仅 toast 文案偏差）。
4. `NoiseGenerator` 海拔 `coerceAtLeast(0.0)` 对负海拔地区会钳制（国内无影响）。
5. 微信运动广播对现代版本微信的实际有效性有限（需系统签名级权限），属产品层面局限。

### 6.7 修改文件
```
app/src/main/java/com/example/stepshift/ui/components/TileSourceManager.kt  (isGcj02 按源标记)
app/src/main/java/com/example/stepshift/ui/components/MapViewContainer.kt   (双向投影 + 切源重建)
app/src/main/java/com/example/stepshift/service/MockForegroundService.kt    (COMPLETED 释放/重启重建)
app/src/main/java/com/example/stepshift/service/NotificationHelper.kt       (四态标题 + 节流)
app/src/main/java/com/example/stepshift/ui/MainViewModel.kt                 (运行守卫 + clearRoute 清理)
app/src/main/java/com/example/stepshift/ui/StepShiftApp.kt                  (clearRoute 传 context)
app/src/main/java/com/example/stepshift/ui/components/ControlPanel.kt       (PAUSED 隐藏重选)
app/src/main/java/com/example/stepshift/root/RootShellExecutor.kt           (stderr 合流 + 严格检测)
app/src/test/java/com/example/stepshift/CoordinateTransformTest.kt          (新增单测)
```
