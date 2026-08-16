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

1. **真机回归验证**：本次仅完成编译级验证，建议在 Pixel/Android 15+ 真机上重点回归：底部面板与手势条间距、1Hz 仿真时地图流畅度、搜索全流程键盘表现。
2. **「点击地图重选起点」防误触**：`SelectionMode.NONE` 下任何单击都会替换起点，可考虑改为长按重选或增加二次确认。
3. **图标 deprecation 清理**：`Icons.Filled.DirectionsRun`、`AltRoute` 等建议迁移到 `Icons.AutoMirrored` 版本。
4. **搜索结果与仪表盘互斥策略**目前是简单隐藏，若后续需要边搜索边看数据，可改为结果列表绝对定位浮层。
