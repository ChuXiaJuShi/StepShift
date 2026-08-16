# StepShift (步移) 🏃📍

<p align="center">
  <b>全开源、基于 Android Root (SU) 特权的高精度运动轨迹、拟真步频与地理位置仿真器</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2016%20(API%2028~36)-3DDC84?logo=android&logoColor=white" alt="Android Version" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Map-OpenStreetMap%20%7C%20AutoNavi-00E5FF" alt="Map Source" />
  <img src="https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk%20%7C%20APatch-FF5252" alt="Root Supported" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License" />
</p>

---

## 🌟 核心特性 (Features)

- 🗺️ **多源高可用地图与图层切换**：
  - 基于 OpenStreetMap 开源引擎，默认接入国内超高速直连**高德街道矢量瓦片**（实测延迟仅 14ms，0% 丢包）；
  - 支持在 UI 上一键无缝切换“高德矢量街道图”、“高德高清卫星图”与“OSM 官方标准图层”；
  - 针对高德卫星图自动执行 WGS-84 ↔ GCJ-02 纳秒级坐标双向投影，彻底杜绝火星坐标偏移。

- 🔍 **全功能地理编码搜索与坐标直达**：
  - 接入基于 OSM 数据的 **Photon by Komoot** 全球/国内高可用检索服务，支持中英文地名毫秒级联想；
  - 智能识别直接输入的经纬度坐标（如 `39.9087, 116.3975`），支持一键“设为起点”或“设为终点”。

- 🛣️ **真实街道步行路网规划**：
  - 接入 OSRM Walking API 真实人行道路网规划，折线精准贴合街巷人行步道；
  - 具备多镜像故障热备机制，网络受限时自动平滑降级为球面大圆航线（Great-Circle Bearing）。

- 🏃 **人体动力学与拟真步数计算**：
  - 根据实时运动配速（1.0 ~ 25.0 km/h）自动计算人体生理匹配步频（100 ~ 190 spm）与单步步长（0.55m ~ 0.85m）；
  - 内置 **Box-Muller 高斯微漂移模型**，在轨迹上叠加微米级自然抗作弊 GPS 与海拔抖动。

- 🧭 **1Hz 动态视角追踪与手势解耦**：
  - 开启追踪模式后，摄像机以 1Hz 频率平滑跟随运动者行进，奔跑者图标始终居中；
  - 手势拖拽地图时自动解耦切换至自由浏览模式，点击追踪按钮瞬间回正重锁定。

- ⚡ **底层三通道 Root Mock 框架注入**：
  - 采用常驻交互式 `su` 管道通信与 Sentinel Token 机制，避免频繁 fork 进程卡顿；
  - 注入通道全面覆盖 **`GPS_PROVIDER` + `NETWORK_PROVIDER` + `FUSED_PROVIDER`**（Android 12/14/15/16 融合定位），系统及各类第三方 App 100% 识别。

- 🔋 **Android 16 深度保活与息屏运行**：
  - 前台服务绑定 `FOREGROUND_SERVICE_LOCATION` 高优先级通知，持有 `PARTIAL_WAKE_LOCK`；
  - 自动通过 Root 豁免系统 **Doze（打瞌睡）电池优化白名单**，退至后台或锁屏熄屏后依然每秒精准递增步数与坐标。

- 📊 **多通道运动健康数据分发**：
  - 每秒向系统分发 `ACTION_STEP_UPDATED` 广播，兼容微信运动/腾讯健康 `ACTION_STEP_COUNTER` 计步协议与标准 Android 计步器广播。

---

## 🏗️ 架构设计 (Architecture)

```
StepShift
├── 1. 物理动力学与运动引擎 (engine/)
│   ├── KinematicsCalculator.kt     // 球面大圆距离/航向角/步频步幅动力学换算
│   ├── NoiseGenerator.kt           // Box-Muller 高斯微漂移 (自然抗作弊)
│   ├── TrajectoryInterpolator.kt   // OSRM 折线多段距离累加与等距采样插值器
│   └── SimulationEngine.kt         // 1Hz 核心调度时钟与状态机
│
├── 2. 特权执行与框架注入 (root/)
│   ├── RootShellExecutor.kt        // 常驻 su 管道通信、错误流合并与严格 uid=0 校验
│   └── RootLocationMock.kt         // 三通道底层框架注入 (GPS + Network + Fused)
│
├── 3. 运动健康数据分发 (health/)
│   └── HealthDataManager.kt       // 步数、里程、卡路里与微信运动广播分发
│
├── 4. 后台保活与通知服务 (service/)
│   ├── MockForegroundService.kt    // 前台保活服务 (types=location)、WakeLock 管理与生命周期释放
│   └── NotificationHelper.kt       // 实时动态通知栏 (配速/步数/剩余时间/操作按键)
│
├── 5. 地图瓦片与网络服务 (network/ & utils/)
│   ├── TileSourceManager.kt        // 国内高速高德矢量/卫星瓦片源与图层配置
│   ├── GeocodingApiClient.kt       // Photon 全球/国内高可用地名搜索
│   ├── OsrmApiClient.kt            // 步行路网双镜像热备与大圆航线降级
│   └── CoordinateTransform.kt      // WGS-84 与 GCJ-02 高精度双向坐标转换
│
└── 6. 响应式用户界面 (ui/)
    ├── MainViewModel.kt            // 统一单向数据流与状态机守卫
    ├── StepShiftApp.kt             // 主 Scaffold 容器与顶部状态栏
    └── components/
        ├── MapViewContainer.kt     // 1Hz 动态视角追踪、手势拖拽解耦、差量 Overlay 渲染
        ├── SearchBarOverlay.kt     // 悬浮输入联想与起点/终点快捷设定
        ├── TelemetryDashboard.kt   // 赛博暗夜风格运动数据 HUD 仪表盘
        ├── ControlPanel.kt         // 速度/步数/启停控制面板 (全面屏底部手势条贴合)
        └── SettingsDialog.kt       // 参数调节与一键 Root 强权自启弹窗
```

---

## 🚀 快速上手 (Quick Start)

### 1. 环境准备
- **JDK**：Java 17 或 Java 21 (推荐使用 Android Studio 自带 JBR)
- **编译工具**：Gradle 9.x (已内置 `gradlew` 包装器)
- **手机系统**：Android 9.0 ~ Android 16 (已在 **Pixel 10 / Android 16** 真机完整测试)
- **Root 环境**：已安装 **KernelSU** (含 Sukisu Ultra)、**Magisk** 或 **APatch**

### 2. 编译并安装
```bash
# 克隆仓库
git clone https://github.com/ChuXiaJuShi/StepShift.git
cd StepShift

# Windows PowerShell 编译打包
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug

# 安装到连接的手机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动应用
adb shell am start -n com.example.stepshift/.MainActivity
```

---

## 📱 使用指南 (User Guide)

1. **Root 授权**：打开 StepShift，若顶部显示 `NO ROOT (点击赋权)`，点击即可自动通过 SU 管道完成模拟位置特权、位置总开关与电池优化白名单配置。
2. **选择路线**：
   - 方式一：在顶部搜索框输入地点（如“*故宫*”、“*西湖*”），在下拉框中选择“设为起点”或“设为终点”；
   - 方式二：在地图上直接点击两点；
   - 方式三：点击右上角 **【示例路线 🛣️】** 快捷加载经典路线。
3. **开始运动仿真**：
   - 调节底部滑动条设置心仪配速（1.0 ~ 25.0 km/h）；
   - 点击底部 **【开始运动仿真 ▶️】**，蓝色跑者箭头即刻沿路网平滑行进；
   - 您可以随时将应用退回后台或锁屏熄屏，通知栏将持续更新运动数据并保持位置注入。
4. **视角追踪与自由浏览**：
   - 点击右侧 **【视角追踪按钮 🧭】** 开启自动跟随；手动滑动地图查看周围路况时自动切为自由模式，再次点击按键立即回正。

---

## 📖 技术文档 (Documentation)

项目更详细的架构细节、数学公式推导与技术避坑指南已归档至 `docs/` 目录：
- **[docs/PLAN.md](docs/PLAN.md)**：整体架构设计、物理运动学公式推导、数据流图与全部里程碑记录。
- **[docs/PROBLEM.md](docs/PROBLEM.md)**：11 大关键技术难点排查、Android 14/15/16 避坑经验与解决方案。
- **[docs/HANDOVER.md](docs/HANDOVER.md)**：项目交接、日常部署与 ADB 运维指南。

---

## ⚖️ 免责声明 (Disclaimer)

本项目仅供 Android 系统开发、移动端传感器仿真、地理信息系统（GIS）教学与个人技术研究交流使用。请勿将本项目用于任何违反法律法规或破坏第三方平台服务协议的用途。开发者不对使用者因违规使用本软件所造成的任何后果承担责任。

---

## 📄 开源协议 (License)

本项目采用 [Apache-2.0 License](LICENSE) 开源协议。
