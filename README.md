# StepShift

StepShift 是一个基于 Android Root (SU) 权限的运动与位置仿真工具，支持路线规划、真实步频拟真、GPS 微漂移模拟以及熄屏后台保活。

开发本项目的初衷是为 Android 开发者提供一套开源、透明且不依赖专有闭源 SDK 的运动健康与位置仿真方案，可在真机上模拟真实的步行/跑步轨迹与传感器数据。

---

## 主要功能

- **多源瓦片地图**：基于 osmdroid 渲染，内置高德街道图（国内直连低延迟）、高德卫星图以及 OpenStreetMap 官方源，支持动态切换与 WGS-84 / GCJ-02 坐标自动校准。
- **路网与路线规划**：接入 OSRM 公共步行路网接口，支持街道级路径规划，在离线或弱网下自动降级为大圆航线。
- **地名搜索与坐标定位**：集成 Photon (OSM) 地理编码服务，支持输入中英文地名联想或直接粘贴经纬度坐标。
- **物理运动与步频仿真**：根据配速实时推算步频（100~190 spm）与步长（0.55~0.85m），内置 Box-Muller 高斯随机漂移模型模拟真实 GPS 抖动。
- **1Hz 视角追踪**：提供实时视角跟随模式，跟随跑者平滑移动；手动拖拽地图时自动退出跟随，点击按键即可重新锁定。
- **三通道 Root Mock 注入**：利用常驻 `su` 会话将模拟位置同步注入至系统的 `gps`、`network` 及 Android 12+ 的 `fused` 融合定位通道。
- **后台与熄屏保活**：前台服务绑定 `location` 类型，持有 `PARTIAL_WAKE_LOCK`，并自动通过 Root 豁免系统 Doze 电池优化白名单。
- **运动健康数据同步**：每秒向系统广播步数、里程与卡路里增量，兼容微信运动及 Android 标准计步器广播。

---

## 系统与权限要求

- **操作系统**：Android 9.0 ~ Android 16 (API 28 ~ 36)
- **设备要求**：已获取 Root 权限（推荐使用 KernelSU / Sukisu Ultra、Magisk 或 APatch）
- **开发环境**：JDK 17 / 21 + Android Studio

---

## 编译与安装

```bash
# 克隆代码仓库
git clone https://github.com/ChuXiaJuShi/StepShift.git
cd StepShift

# 编译 Debug APK (Windows)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug

# 编译 Debug APK (Linux / macOS)
./gradlew assembleDebug

# 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用说明

1. **Root 授权**：初次打开应用，点击顶部状态栏的 `NO ROOT` 徽标或进入“设置 -> 一键赋权”，应用会自动申请 Root 并配置模拟位置 AppOps、系统定位开关及后台电池白名单。
2. **路线选择**：
   - 在顶部搜索栏输入地点名称，从联想列表中点击“设为起点”或“设为终点”；
   - 或在地图上直接点选两个坐标点；
   - 亦可点击右上角的路线图标直接加载预设的示例路线。
3. **调节参数**：展开底部控制面板，滑动设置配速（1.0 ~ 25.0 km/h），可开启/关闭 GPS 微漂移。
4. **开始仿真**：点击“开始运动仿真”，通知栏将常驻显示当前配速、步数、已耗时与剩余时间。此时可直接切至后台或锁屏。
5. **视角控制**：点击右侧导航图标可切换“自动跟随”与“自由查看”模式。

---

## 代码结构

```
app/src/main/java/com/example/stepshift/
├── engine/             # 核心仿真引擎 (运动学换算、高斯漂移、折线插值器)
├── health/             # 运动健康与步数广播分发器
├── model/              # 数据结构与仿真状态快照
├── network/            # OSRM 路网与 Photon 地理编码 API 客户端
├── root/               # 常驻 su 管道交互与 LocationManager 底层注入
├── service/            # 前台保活服务、WakeLock 与通知栏管理
├── ui/                 # Jetpack Compose 界面、地图容器与控制组件
└── utils/              # WGS-84 / GCJ-02 坐标系转换工具
```

详细的设计思路与技术细节可参考 `docs/` 目录：
- [docs/PLAN.md](docs/PLAN.md)：模块设计与物理公式推导
- [docs/PROBLEM.md](docs/PROBLEM.md)：Android 14~16 兼容性与网络排坑记录
- [docs/HANDOVER.md](docs/HANDOVER.md)：交接说明与运维指令

---

## 免责声明

本项目仅用于 Android 开发者测试、传感器仿真教学与技术研究。请勿将本项目用于任何违反法律法规或第三方平台服务协议的场景。

---

## 开源协议

本项目采用 [Apache-2.0 License](LICENSE) 协议开源。
