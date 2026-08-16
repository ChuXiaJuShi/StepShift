# StepShift 架构设计与开发规划 (PLAN.md)

## 1. 项目愿景与定位
**StepShift** 是一款全开源、基于 Android Root (SU) 权限的高精度运动与位置仿真应用。
利用开源 OpenStreetMap (osmdroid) 地图引擎与 OSRM 真实路网规划，结合严谨的物理运动学插值引擎与 Root 特权指令流，实现真实步频、步幅、微漂移 GPS 轨迹的高仿真实时模拟，支持熄屏后台保活运行。

---

## 2. 整体系统架构图

```
+-----------------------------------------------------------------------------------+
|                               Jetpack Compose UI                                  |
|   +--------------------------+   +--------------------------------------------+   |
|   |   OpenStreetMap MapView  |   |     Control Panel & Live Telemetry Panel   |   |
|   | (osmdroid + Markers/Line)|   | (Speed/Cadence/Steps/Drift/Start/Pause/Stop)|   |
|   +--------------------------+   +--------------------------------------------+   |
+----------------------------------------^------------------------------------------+
                                         | StateFlow / UI Events
+----------------------------------------v------------------------------------------+
|                               MainViewModel                                       |
+----------------------------------------^------------------------------------------+
                                         | Service Connection / Binder
+----------------------------------------v------------------------------------------+
|                     MockForegroundService (前台保活服务)                          |
|   - Partial WakeLock (保证熄屏不降频)                                             |
|   - High-Priority Notification (实时显示步数、距离、进度、控制按键)               |
+----------------------------------------^------------------------------------------+
                                         |
+----------------------------------------v------------------------------------------+
|                  SimulationEngine (多节点轨迹与步数引擎)                          |
|   +-----------------------+  +--------------------+  +------------------------+   |
|   | TrajectoryInterpolator|  | KinematicsCalculator|  |  NoiseGenerator (漂移) |   |
|   | (折线路段按秒平滑推进)   |  | (步频/步幅/速度换算) |  | (高斯扰动与海拔漂移)    |   |
|   +-----------------------+  +--------------------+  +------------------------+   |
+--------------------^---------------------------------------------^----------------+
                     |                                             |
+--------------------v-------------+             +-----------------v----------------+
|     OsrmApiClient (路网接口)     |             | RootShellExecutor / LocationMock |
| - OSRM Walking Routing API       |             | - 常驻 su 进程流交互与超时控制   |
| - GeoJSON 坐标流解析与路线纠偏   |             | - Mock Provider 提权与 Root 注入 |
+----------------------------------+             +----------------------------------+
```

---

## 3. 核心模块划分

| 模块名称 | 包名路径 | 核心职责 |
| :--- | :--- | :--- |
| **Model** | `com.example.stepshift.model` | 经纬度数据结构、仿真配置、实时快照与状态机枚举 |
| **Network** | `com.example.stepshift.network` | OSRM 步行路线 API 请求封装、GeoJSON 解析与备用航线生成 |
| **Engine** | `com.example.stepshift.engine` | 轨迹折线距离/方位角插值、真实人体运动学生成、GPS 高斯漂移模拟 |
| **Root** | `com.example.stepshift.root` | 常驻 `su` 管道通信、Root 状态探测、底层 Mock 注入与特权赋权 |
| **Service** | `com.example.stepshift.service` | 前台保活 Service、WakeLock 电源锁、实时通知栏交互面板 |
| **UI** | `com.example.stepshift.ui` | Material 3 界面、osmdroid 地图组件、控制面板、仪表盘与设置对话框 |

---

## 4. 关键功能规格

1. **路网规划 (OSRM API)**：
   - 步行路线请求：`https://router.project-osrm.org/route/v1/walking/{lon1},{lat1};{lon2},{lat2}?overview=full&geometries=geojson&steps=true`
   - 解析返回的 GeoJSON `coordinates` (经度, 纬度) 转换为高精度 `GeoPoint` 列表。
   - 自动计算总里程（米）和预计耗时。

2. **多节点平滑插值算法**：
   - 使用球面大圆航线（Haversine / Spherical Cosine）计算相邻两点的距离与初识方位角（Bearing `0° ~ 360°`）。
   - 依据当前配速（如 `5.0 km/h` = `1.388 m/s`），每 1000ms 推进固定距离，跨越节点时自动计算剩余距离并在下一折线段继续推进。

3. **人体运动学与物理步数联动**：
   - 步频动态估算：步行 `100~120 步/分`，慢跑 `140~160 步/分`，快跑 `170~190 步/分`。
   - 步幅动态估算：`步幅(米) = 速度(米/秒) / 步频(步/秒)`（约 `0.6m ~ 0.85m`）。
   - 每秒精确计算步数增量与累计步数。

4. **GPS 微漂移模拟 (Gaussian Noise)**：
   - 采用 Box-Muller 算法产生标准正态分布随机数，叠加约 `±0.5m ~ 1.5m` 的真实 GPS 经纬度与海拔微小抖动，防止固定坐标被反作弊系统识别。

5. **Root (SU) 级特权注入机制**：
   - 管理长连接 `su` 进程交互式管道，避免频繁 fork 进程造成性能损耗与系统卡顿。
   - 自动执行 `pm grant <package> android.permission.ACCESS_MOCK_LOCATION` 与 `appops set <package> android:mock_location allow` 强制授予特权。
   - 双模 Mock 策略：既可通过 Android Framework LocationManager TestProvider 直注系统底层，亦可通过 Root Shell 执行底层指令。

6. **Android 14/15/16 深度保活**：
   - 注册前台服务类型 `location` + `specialUse` / `health`。
   - 持有 `PARTIAL_WAKE_LOCK`，确保在灭屏、深度睡眠（Doze Mode）模式下计时器与 GPS 发送不降频。

---

## 5. 开发进度与里程碑

- [x] **M1: 基础工程与环境就绪** (Gradle 9.x, Android 16 CompileSdk, Jetpack Compose, Material 3, osmdroid 6.1.20, OkHttp, Gson)
- [x] **M2: 数据模型与物理运动学引擎** (GeoPoint, 轨迹多段插值, 步频步幅计算, 高斯漂移生成器)
- [x] **M3: OSRM 开源路网接口与解析** (步行路由请求, GeoJSON 解析, 容错降级机制)
- [x] **M4: Root SU 交互管道与 Mock 特权模块** (常驻 su 会话, 异步指令队列, 提权注入器)
- [x] **M5: 前台保活服务与通知栏交互** (WakeLock 管理, 状态通知栏更新, 广播控制)
- [x] **M6: Compose UI 仪表盘与 osmdroid 地图交互** (起点/终点选点, 路线高亮, 实时运动蓝点与朝向角, 速度调节底栏)
- [x] **M7: 联调验证、编译与技术文档输出** (docs/PLAN.md, docs/PROBLEM.md, docs/HANDOVER.md)
- [x] **M8: 全局地理编码搜索与沉浸式 Edge-to-Edge UI** (Nominatim 地点搜索/经纬度直达、当前定位居中、手势白条融合与面板收折)
- [x] **M9: 国内高可用瓦片源与 1Hz 动态视角跟随交互** (高德矢量/卫星直连源、Photon 搜索、1Hz 动态摄像机追踪、手势拖拽自动解除/按键重绑)
- [x] **M10: UI 深度优化与交互修复** (修复错误色配色、底部双重 inset 留白、1Hz 地图 overlay 全量重建性能问题；仪表盘按需显示、搜索键盘自动收起、开始按钮完整状态机、清理死代码；详见 docs/Kimi.md)
