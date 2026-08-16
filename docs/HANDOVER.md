# StepShift 项目交接与运行指南 (HANDOVER.md)

## 1. 项目基础信息
- **项目名称**：StepShift (步移 / 运动仿真器)
- **核心定位**：全开源、基于 Android Root (SU) 权限的高精度运动轨迹、拟真步数与地理位置仿真工具
- **技术栈**：
  - 核心开发语言：Kotlin (2.2+)
  - 目标运行系统：Android 9.0 ~ Android 16 (API 28 ~ API 36)
  - UI 架构：Jetpack Compose + Material Design 3 (沉浸式 Edge-to-Edge)
  - 地图渲染：OpenStreetMap 引擎 (`osmdroid-android:6.1.20`) + 高德直连瓦片源 (`TileSourceManager`)
  - 地理编码搜索：Photon by Komoot (`photon.komoot.io`) + Nominatim 备用
  - 路网规划：OSRM Public Walking Routing 双镜像备灾
  - 异步与状态驱动：Kotlin Coroutines + StateFlow / SharedFlow (带重放缓冲)
  - 健康与运动分发：Android 标准计步广播 + 微信运动/腾讯健康兼容广播

---

## 2. 核心系统架构与模块职责

```
StepShift
├── 1. 物理动力学与拟真仿真引擎 (engine/)
│   ├── KinematicsCalculator.kt     // 球面大圆距离/航向角/真实步幅换算: Stride = Speed / (Cadence/60)
│   ├── NoiseGenerator.kt           // Box-Muller 算法高斯 GPS/海拔微漂移 (抗作弊模型)
│   ├── TrajectoryInterpolator.kt   // OSRM 折线多段距离累加与等距采样插值器
│   └── SimulationEngine.kt         // 1Hz 核心调度时钟与状态机 (IDLE/RUNNING/PAUSED/COMPLETED)
│
├── 2. 特权执行与框架注入 (root/)
│   ├── RootShellExecutor.kt        // 常驻交互式 su 管道、Sentinel Token 与断线自愈
│   └── RootLocationMock.kt         // 三通道底层框架注入 (GPS + Network + Fused Provider)
│
├── 3. 运动健康数据分发 (health/)
│   └── HealthDataManager.kt       // 步数、配速、卡路里、微信运动广播与计步器同步
│
├── 4. 后台保活与通知服务 (service/)
│   ├── MockForegroundService.kt    // 前台保活服务 (types=location)、Partial WakeLock、Doze 优化豁免
│   └── NotificationHelper.kt       // 实时动态通知栏 (配速/步数/剩余时间/操作按钮)
│
├── 5. 地图瓦片与网络服务 (network/ & ui/components/)
│   ├── TileSourceManager.kt        // 国内超高速高德矢量/卫星瓦片源与图层切换
│   ├── GeocodingApiClient.kt       // Photon 全球/国内高可用地名搜索与经纬度直达
│   ├── OsrmApiClient.kt            // 步行路由双镜像热备与离线大圆航线降级
│   └── CoordinateTransform.kt      // WGS-84 (真实GPS) 与 GCJ-02 (高德瓦片) 纳秒级转换矩阵
│
└── 6. 现代响应式用户界面 (ui/)
    ├── MainViewModel.kt            // 统一单向数据流与生命周期管理
    ├── StepShiftApp.kt             // 主 Scaffold 容器与顶部状态栏 (insets 单次消费、仪表盘按需显示)
    └── components/
        ├── MapViewContainer.kt     // 1Hz 动态视角追踪、手势拖拽自动解耦、overlay 差量更新与图标缓存
        ├── SearchBarOverlay.kt     // 悬浮式输入联想与起点/终点快捷设定 (选点自动收起键盘)
        ├── TelemetryDashboard.kt   // 赛博暗夜风格运动数据 HUD 仪表盘 (仅仿真激活时显示)
        ├── ControlPanel.kt         // 速度/步数/启停控制面板 (背景贴合屏幕底边与小白条无缝融合、可折叠细条+紧凑操作按钮)
        └── SettingsDialog.kt       // Root 权限强赋权、高斯漂移调节与自定义步频弹窗
```

---

## 3. 开发与编译环境要求

1. **JDK 版本**：Java 17 或 Java 21 (推荐使用 Android Studio 自带 JBR，路径位于 `C:\Program Files\Android\Android Studio\jbr`)
2. **Android Studio**：Android Studio Ladybug / Meerkat / Canary
3. **Gradle 包装器**：Gradle 9.x (已配置 `org.gradle.configuration-cache=false` 保证 CLI 编译稳定性)
4. **编译调试 APK 命令**：
   ```powershell
   # Windows PowerShell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat assembleDebug
   ```
   产物输出路径：`app\build\outputs\apk\debug\app-debug.apk`

---

## 4. Root 特权运行与系统权限配置

### 4.1 兼容的 Root 管理器
- **KernelSU** (v0.6.0+，支持 Sukisu Ultra)
- **Magisk** (v24.0+)
- **APatch** (v10.0+)

### 4.2 一键自动赋权
StepShift 内部集成了自动 Root 赋权机制。打开应用时，点击顶部状态栏的 **`ROOT: OK`** 徽标或进入“设置 -> 一键赋权”，系统将自动通过 `su` 管道执行以下系统级命令：
```bash
# 1. 开启系统全局位置总开关
cmd location set-location-enabled true
settings put secure location_mode 3
settings put secure mock_location 1

# 2. 赋予模拟位置 AppOp
appops set com.example.stepshift android:mock_location allow

# 3. 授予精准位置与后台常驻位置权限
pm grant com.example.stepshift android.permission.ACCESS_FINE_LOCATION
pm grant com.example.stepshift android.permission.ACCESS_COARSE_LOCATION
pm grant com.example.stepshift android.permission.ACCESS_BACKGROUND_LOCATION

# 4. 豁免系统 Doze 打瞌睡策略 (保证锁屏/息屏长久运行)
dumpsys deviceidle whitelist +com.example.stepshift
appops set com.example.stepshift RUN_IN_BACKGROUND allow
appops set com.example.stepshift RUN_ANY_IN_BACKGROUND allow
appops set com.example.stepshift WAKE_LOCK allow

# 5. 授予运动传感器与健康数据权限
appops set com.example.stepshift ACTIVITY_RECOGNITION allow
pm grant com.example.stepshift android.permission.ACTIVITY_RECOGNITION
pm grant com.example.stepshift android.permission.BODY_SENSORS
pm grant com.example.stepshift android.permission.HIGH_SAMPLING_RATE_SENSORS
pm grant com.example.stepshift android.permission.health.WRITE_STEPS
pm grant com.example.stepshift android.permission.health.WRITE_DISTANCE
pm grant com.example.stepshift android.permission.health.WRITE_TOTAL_CALORIES_BURNED
```

---

## 5. 快速操作与功能使用指南

1. **地图图层切换**：
   - 地图右侧提供了 **【图层按钮 🥞】**，支持一键在高德街道矢量图（国内直连 ~14ms）、高德卫星图和 OSM 官方图之间自由切换。
2. **路线选择与规划**：
   - **方式一（搜索框）**：顶部搜索地名（如“故宫”、“杭州西湖”），在下拉联想项中点击“设为起点”或“设为终点”；
   - **方式二（直接点选）**：在地图上依次点击起点（绿）与终点（红），系统自动调用 OSRM 规划步行路网；
   - **方式三（快捷示例）**：点击顶部导航栏右上角 **【示例路线 🛣️】**，一键生成北京天安门至王府井经典步行路线。
3. **参数个性化调节**：
   - 底部面板展开后可实时调节：**运动配速**（1.0 ~ 25.0 km/h）、**高斯抗作弊微漂移**开关、**步频/步长自适应**或自定义固定步频。
4. **动态视角追踪（Camera Tracking）**：
   - 点击右侧 **【视角追踪按钮 🧭】**，地图将进入 1Hz 自动跟随状态，随着跑步者移动平滑推进视角；
   - 手动滑动地图查看周围时会自动切为自由浏览；再次点击追踪按钮即可瞬间回正并恢复跟随。
5. **后台与息屏运行**：
   - 点击 **【开始运动仿真 ▶️】** 后，用户可随时退回手机桌面或直接熄屏；
   - 通知栏将常驻显示运动进度条、当前步数、累计里程与剩余时间，第三方运动健康 App（微信运动、Keep 等）将同步接收步数与位置更新。

---

## 6. ADB 自动化测试与日常运维命令

```powershell
# 1. 编译并推送到指定设备
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb -s 5B250DLCR002DB install -r "app\build\outputs\apk\debug\app-debug.apk"
adb -s 5B250DLCR002DB shell "am force-stop com.example.stepshift; am start -n com.example.stepshift/.MainActivity"

# 2. 检查前台服务运行状态
adb shell "dumpsys activity services com.example.stepshift | grep -E 'isForeground|startRequested'"

# 3. 检查实时常驻通知栏数据
adb shell "dumpsys notification --noredact | grep -A 8 'StepShift 正在运动仿真中'"

# 4. 实时抓取 StepShift 核心日志
adb logcat -s StepShift:* MockForegroundService:* OsmDroid:* StepShiftHealth:*
```

---

## 7. 相关开发文档索引
- **[docs/PLAN.md](file:///D:/Data/Development/StepShift/docs/PLAN.md)**：记录整体架构设计、数学动力学公式、数据流图与全部 M1~M10 里程碑完成进度。
- **[docs/PROBLEM.md](file:///D:/Data/Development/StepShift/docs/PROBLEM.md)**：详细记录 11 大关键技术难点排查、Android 14/15/16 避坑记录与解决方案。
- **[docs/Kimi.md](file:///D:/Data/Development/StepShift/docs/Kimi.md)**：2026-08-17 UI 深度优化交接文档，记录全部 UI Bug 修复明细、性能优化与后续建议。
- **[docs/HANDOVER.md](file:///D:/Data/Development/StepShift/docs/HANDOVER.md)**：即本文档，供后续开发者、运维人员快速交接与全流程部署使用。
