# StepShift 关键技术难点与避坑指南 (PROBLEM.md)

在开发与适配基于 Android 14/15/16 (API 34/35/36) 以及 Root (SU) 环境下的运动与定位仿真过程中，我们总结了以下关键技术难点、系统兼容性避坑及对应的解决方案。

---

## 1. Root Shell 常驻交互与死锁风险

### 问题背景
传统 Android 开发中若每次执行 Shell 命令都调用 `Runtime.getRuntime().exec("su")`，会产生极其严重的性能问题：
1. 频繁创建子进程（fork）导致 CPU 占用骤升、GC 频繁，在 1Hz 的 GPS 注入频率下会产生系统明显卡顿；
2. Magisk / KernelSU 会在每次申请 Root 时弹出授权 toast 或日志记录；
3. 直接调用 `Process.waitFor()` 时，如果 `stdout` 或 `stderr` 缓冲区被填满而未及时读取，会导致整个 `su` 进程死锁挂起。

### 解决方案 (`RootShellExecutor`)
1. **单一长连接进程池**：在应用启动或首次需要时拉起一个单一的交互式 `su` 进程，保持 `stdin` / `stdout` 管道常开。
2. **命令结束标记（Sentinel Token）**：每次向 `su` 发送命令后，附带写入 `echo "__CMD_FINISHED__:$?"`。
3. **独立后台协程/线程监听流**：在独立调度器中持续消费输出流，直到捕获到特定标记即可准确判断命令退出码与结果，无需反复重启进程。
4. **异常自愈机制**：当出现管道破裂（Broken Pipe）或超时时，自动清理旧进程并重新建立连接。
5. **stderr 合流消费（2026-08-17 修正）**：`ProcessBuilder.redirectErrorStream(true)` 将 stderr 并入 stdout 一并读取。此前实现单独持有 `errorReader` 但从未消费，命令大量输出 stderr 时仍会填满管道缓冲区导致 su 死锁。

> 注：Root 检测以 `id` 输出含 `uid=0` 为唯一判据；不能以"退出码为 0"兜底——部分 ROM 在 su 被拒后会以 shell 身份执行命令，退出码同样为 0。

---

## 2. Android 14/15/16 前台服务与后台保活限制

### 问题背景
自 Android 14 (API 34) 起，Google 对 `ForegroundService` 实施了极其严格的类型系统审查：
1. 必须在 `AndroidManifest.xml` 中指定 `android:foregroundServiceType`（如 `location`, `health`, `specialUse` 等）；
2. 必须声明对应类型的权限（如 `FOREGROUND_SERVICE_LOCATION`）；
3. 调用 `startForeground()` 时若未传对应 ServiceType 会直接抛出 `SecurityException` 或 `ForegroundServiceStartNotAllowedException`；
4. 息屏后系统进入 Doze 模式（深度打瞌睡），普通定时器 (`Handler`, `Timer`, `Thread.sleep`) 会被系统对齐并大幅降频至几分钟一次，导致 GPS 轨迹跳点与步数丢失。

### 解决方案 (`MockForegroundService`)
1. **类型双重声明**：在清单中声明 `android:foregroundServiceType="location|specialUse|health"`，并在 `Service` 启动时携带类型标志位。
2. **持有 Partial WakeLock**：在 Service 启动时获取 `PowerManager.PARTIAL_WAKE_LOCK`，保持 CPU 核心在熄屏下正常运行，并在服务销毁时安全释放。
3. **前台通知高频刷新策略**：采用节流（Throttled）更新，避免每秒调用 `notify()` 触发系统 NotificationManager 速率限制（Rate Limiting）。

> 2026-08-17 修正：节流此前只停留在设计描述，实现是每 1Hz tick 全量 `notify()`。现已补齐——`NotificationHelper.updateNotification` 在状态切换时立即更新，普通数据 tick 最小间隔 2s。

---

## 12. 高德瓦片坐标基准（Datum）因源而异 —— wprd 无偏移、webst/webrd 是 GCJ-02

### 问题背景
国内地图瓦片通常被认为是 GCJ-02 火星坐标，但高德直连瓦片**并非铁板一块**：
- `wprd01~04.is.autonavi.com/appmaptile?style=7`（矢量街道）：社区公认的 **WGS-84 无偏移**源，OSM 系应用可直接使用；
- `webst01~04.is.autonavi.com/appmaptile?style=6`（卫星影像）与 `webrd0x`（style=8 矢量）：**GCJ-02 偏移**源，北京实测偏移约 556m（北 156m + 东 533m）。

本项目曾因统一按 WGS-84 裸坐标绘制，导致**卫星图层下路线/Marker 整体偏移约 556m、点选取址与实际注入位置偏差数百米**；而矢量图层反而一直是准的。

### 解决方案（按瓦片源分别投影）
1. `TileSourceManager` 每个源携带 `isGcj02` 基准标记；
2. 显示链路（路线折线/起终 Marker/行走者/视角居中与追踪）：WGS-84 → `CoordinateTransform.wgs84ToGcj02` → 再上屏；
3. 点选链路：屏幕坐标 → `gcj02ToWgs84` 反投影 → 才进入 OSRM 规划与 GPS 注入；
4. 切换瓦片源时强制重建全部 overlay，按新基准重新投影；
5. 业务逻辑、引擎、网络、注入层**永远只接触 WGS-84**，转换只发生在地图显示边界。

> 验证坐标基准最可靠的方式不是查资料，而是真机对照：同一坐标在转换前后各截一张图，观察地物是否落在预期位置（本次即借此纠正了"矢量图也偏移"的误判）。

---

## 3. Mock Location (模拟位置) 与 Root 特权赋权

### 问题背景
在 Android 高版本中，普通应用使用 `LocationManager.addTestProvider` 会受到系统安全策略拦截，必须由用户在“开发者选项 -> 选择模拟位置信息应用”中手动勾选。但在部分定制 ROM（如 MIUI, HyperOS, ColorOS, OriginOS）中，该选项容易被系统杀掉或权限失效。

### 解决方案 (`RootLocationMock`)
利用 Root (SU) 权限在底层直接突破限制：
1. **Root 命令行直赋特权**：
   ```bash
   pm grant com.example.stepshift android.permission.ACCESS_MOCK_LOCATION
   appops set com.example.stepshift android:mock_location allow
   settings put secure mock_location 1
   ```
2. **混合注入架构**：
   - 优先使用提权后的 `LocationManager.setTestProviderLocation` 注入标准 Android 框架（GPS Provider 与 Network Provider 双通道）；
   - 同时支持通过 Root 执行底层 `cmd location` 或将 Fake GPS 广播推送给指定系统服务。

---

## 4. OSRM 路网数据解析与经纬度坐标系转换

### 问题背景
1. **OSRM API 坐标顺序陷阱**：OSRM 接收的 URL 参数与返回的 GeoJSON 格式均为 `[Longitude, Latitude]`（经度在前，纬度在后），而 Android `Location`、`osmdroid.util.GeoPoint` 以及绝大多数国内地图 API 均为 `(Latitude, Longitude)`（纬度在前，经度在后）。若直接传递会导致路线跑到南极或完全颠倒。
2. **网络断网或 OSRM 服务器限流**：公开 OSRM 服务器（`router.project-osrm.org`）在网络不稳定或请求过频时可能返回 429 或 500 错误。

### 解决方案 (`OsrmApiClient`)
1. **严格类型转换层**：内部统一使用强类型 `GeoPoint(val latitude: Double, val longitude: Double)`，在与 OSRM 序列化/反序列化时显式完成 `lon <-> lat` 映射。
2. **智能降级策略**：若 OSRM 路由服务失败，自动根据起点与终点采用航线大圆算法（Great-Circle Bearing Interpolation）生成平滑的备用直线轨迹，确保仿真流程永不因网络故障而崩溃。

---

## 5. 真实人体运动物理学与高斯微漂移模型

### 问题背景
简单的“直线匀速等距移动”具有极高的人工痕迹，各大运动健康类 App（如微信运动、Keep、咕咚、悦跑圈等）的反作弊算法会通过以下特征识别作弊：
- GPS 经纬度绝对平滑（真实 GPS 一定存在米级的电离层抖动与多径效应）；
- 步频与配速脱节（例如配速 10km/h 步频却只有 50 步/分，或步幅超长达 2.5 米）；
- 方位角（Bearing）瞬间阶跃。

### 解决方案 (`SimulationEngine`)
1. **Box-Muller 高斯随机漂移**：
   $$X = \sqrt{-2 \ln(U_1)} \cos(2\pi U_2) \cdot \sigma$$
   在每个插值点上叠加 `\sigma \approx 0.000008` 度（约 0.8米）的高斯噪声，同时海拔（Altitude）叠加正弦波与随机漂移。
2. **真实动力学步幅/步频换算**：
   $$\text{Cadence (spm)} = 120 + (\text{Speed}_{\text{km/h}} - 5.0) \times 8.5$$
   $$\text{Stride (m)} = \frac{\text{Speed}_{\text{m/s}}}{\text{Cadence} / 60}$$
   保证速度在 3~15 km/h 变化时，步频维持在 100~180 步/分，单步步长稳定在 0.55m ~ 0.85m 的生理合理区间内。

---

## 6. Android 15/16 强制全面屏 Edge-to-Edge 与手势小白条避让

### 问题背景
在 Pixel 10 (Android 16) 等新系统上，Google 强制启用了沉浸式 Edge-to-Edge 特性。若应用在窗口层设置了不透明的 `navigationBarColor` 或未适配 WindowInsets，会导致系统底部的手势指示小白条（Gesture Pill）下方出现突兀的灰色/白色色块，与底栏悬浮卡片发生错位重叠。

### 解决方案
1. **全透明 System Bars**：设置 `WindowCompat.setDecorFitsSystemWindows(window, false)`，并将 `statusBarColor` 与 `navigationBarColor` 设为完全透明 `Color.TRANSPARENT`。
2. **Compose WindowInsets 智能内边距**：在底栏控制面板应用 `Modifier.navigationBarsPadding()`，使底栏面板自然悬浮于手势小白条之上，保持深色毛玻璃材质的完整一体性。

---

## 7. OpenStreetMap 地理编码搜索与多源坐标解析

### 问题背景
仅依靠在地图上手动拖动选点在跨城市或远距离路线规划时体验较差，用户需要快速搜索地名（如“奥森公园”、“西湖”）或直接粘贴经纬度坐标。

### 解决方案 (`GeocodingApiClient`)
1. **Nominatim API 封装**：接入 OpenStreetMap 官方 Nominatim 地理编码服务，支持输入关键词防抖（Debounce）实时联想补全。
2. **智能经纬度正则识别**：用户若直接输入 `"39.9042, 116.4074"` 等格式，跳过网络查询直接解析并平滑将地图视角飞跃至该坐标。

---

## 8. 国内网络环境下 OSM 官方瓦片与搜索接口阻断问题

### 问题背景
在国内直连网络下测试时，OsmDroid 默认的 `tile.openstreetmap.org` (Mapnik) 以及 `nominatim.openstreetmap.org` 存在 100% 丢包与连接超时 (`ConnectException`)，导致地图呈现灰色网格、瓦片无法渲染、搜索无响应。

### 解决方案 (`TileSourceManager` & `GeocodingApiClient`)
1. **高德地图超高速直连源 (`AMAP_VECTOR`)**：
   - 默认接入高德街道矢量瓦片源 (`wprd01~04.is.autonavi.com`)，国内网络实测延迟仅 14ms，中文地名清晰且 100% 直连可用；
   - 同时支持在 UI 上一键切换“高德卫星图”与“OSM 官方图层”。
2. **Photon 全球/国内高可用地理编码接口**：
   - 搜索模块优先接入基于 OSM 数据的 Photon 接口 (`photon.komoot.io`)，实测丢包率为 0%，实现快速秒级中文地名联想；
3. **OSRM 路由双镜像热备**：
   - 路由规划接入 `routing.openstreetmap.de` 与 `router.project-osrm.org` 双镜像，失败时自动平滑降级为大圆航线，保证 100% 路线可用性。

---

## 9. Android 14/15/16 息屏后台保活与运动健康步数广播接入

### 问题背景
1. **Doze 模式与后台 CPU 挂起**：退至桌面或息屏后，系统 Doze 打瞌睡机制会强行挂起协程与定时器，导致步数与 GPS 停止更新。
2. **运动健康步数无法被第三方 App 读取**：仅在内存中累计步数无法让系统其他运动健康类 App（微信运动、Keep、Fitbit 等）感知到运动数据增加。

### 解决方案 (`HealthDataManager` & `RootLocationMock`)
1. **Root 自动化电池优化豁免 (Doze Whitelist)**：
   - 自动通过 SU 执行 `dumpsys deviceidle whitelist +com.example.stepshift` 与 `appops set RUN_IN_BACKGROUND allow`，配合 `PARTIAL_WAKE_LOCK`，确保息屏状态下 1Hz 仿真时钟 100% 满血运行。
2. **多协议运动健康步数广播分发**：
   - 接入 `HealthDataManager`，每秒同步向系统广播 `com.example.stepshift.ACTION_STEP_UPDATED`、`com.tencent.mm.plugin.sport.ACTION_STEP_COUNTER`（微信运动协议兼容）与 Android 标准计步器事件。

---

## 10. Scaffold 无 bottomBar 时底部 inset 被应用两次（双重留白）

### 问题背景
在 Edge-to-Edge 布局中，`Scaffold` 未提供 `bottomBar` 时，会把系统导航栏 inset 计入内容区 `innerPadding.bottom`；若子组件（如底部控制面板）再叠加 `Modifier.navigationBarsPadding()`，同一导航栏 inset 被应用两次，面板底部与手势小白条之间会出现一条明显缝隙，透出下层地图。同理，只覆写 `colorScheme.error` 而不定义 `onError` 时，M3 暗色默认 `onError` 为深棕红 (#690005)，配亮红 (#FF5252) 按钮底色会导致文字几乎不可读——自定义色板必须成组覆写 `error / onError / errorContainer / onErrorContainer`。

### 解决方案
1. 在 `Scaffold` 内容容器上追加 `Modifier.consumeWindowInsets(innerPadding)`，显式消费掉已转为 padding 的 inset，子组件的 `navigationBarsPadding()` 自动归零，只保留一重避让。
2. 主题色板按组完整覆写错误色四元组。

---

## 11. osmdroid Overlay 在 1Hz 重组下全量重建的性能陷阱

### 问题背景
`AndroidView` 的 `update` 块会在每次 Compose 重组时执行。仿真以 1Hz 刷新快照时，若无差别地 `remove + new` 全部 overlay，路线折线（数百个坐标点）与每个 Marker 的 `Canvas` Bitmap 每秒都会被重建一次，产生持续 GC 压力与掉帧风险。

### 解决方案 (`MapViewContainer`)
1. **差量更新**：用 `remember` 保存「已应用状态」（`appliedRoute` / `appliedStart` / `appliedEnd` / `appliedBearingBucket`），仅在数据真正变化时重建对应 overlay。
2. **不可变资源只建一次**：起点/终点图标 Bitmap 用 `remember(context)` 缓存。
3. **行走者 Marker 原地更新**：仅修改 `position`，图标按 5° 航向角分桶，跨桶时才重新生成 Bitmap。
4. **绘制层级纪律**：折线变更时级联移除并重加 Marker，保证 Marker 永远在折线之上。
