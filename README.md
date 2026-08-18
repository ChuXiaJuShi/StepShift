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
- **独立步数修改**：不依赖路线仿真，可单独设定任意虚拟步数并按需推送到多个渠道（可多选）：
  - **系统健康 (Health Connect)**：以 `StepsRecord` 写入系统 Health Connect，系统步数页与 HC 生态应用可见（先删后写不重复累计，清除时可完整回收）；
  - **小米运动 (Zepp) 云同步**：经 Zepp 账号云端上传步数，微信运动/QQ/支付宝绑定小米运动数据源后排行榜生效；
  - **LSPosed 传感器注入**：配套 `:xposed` 模块 hook 目标应用计步传感器读数，直接改写为虚拟步数。模块安装/激活状态可在 App 内自检（设置页实时显示，步数弹窗渠道行同步提示）。
- **独立定位修改**：可单独设定虚拟位置（地图选点/手动坐标/同步真实位置），开启「定点注入」后系统定位被 1Hz 持续锁定；虚拟位置、虚拟步数与注入开关跨启动持久化。
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

# (可选) 编译 LSPosed 步数注入模块 APK
# 产物: xposed/build/outputs/apk/debug/xposed-debug.apk
./gradlew :xposed:assembleDebug
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
6. **单独改步数**：状态卡点击“改步数”，输入目标值并勾选推送渠道：
   - **系统健康**：写入 Health Connect（首次使用需在系统「Health Connect → 数据管理 → 数据源和优先级」中添加 StepShift）；
   - **小米运动**：填入 Zepp 账号密码（仅保存本机），并在微信「我 → 设置 → 通用 → 辅助功能 → 微信运动 → 数据来源」中绑定小米运动；
   - **LSPosed**：需按下节部署 LSPosed 环境并启用配套模块。
7. **单独改定位**：状态卡点击“改定位”，可同步真实位置、地图选点或手动输入坐标；开启「定点注入」后其他应用的定位被持续锁定到虚拟位置。

---

## (可选) 部署 LSPosed 步数注入模块

适用于没有/不想使用 Zepp 账号的场景，直接改写目标应用的传感器读数：

```bash
# 1. KernelSU / SukiSU 系先安装 ZygiskNext（Magisk 自带 Zygisk 可跳过）
adb push Zygisk-Next.zip /sdcard/
adb shell su -c 'ksud module install /sdcard/Zygisk-Next.zip'   # 重启

# 2. 安装 LSPosed 框架（Android 16 推荐 JingMatrix 分支 v1.11.0+）
adb push LSPosed.zip /sdcard/
adb shell su -c 'ksud module install /sdcard/LSPosed.zip'       # 重启
adb install /data/adb/modules/zygisk_lsposed/manager.apk        # 管理器需手动安装

# 3. 安装本项目的步数注入模块
adb install xposed/build/outputs/apk/debug/xposed-debug.apk
```

然后在 **LSPosed 管理器 → 模块 → StepShift 步数注入模块** 中启用模块，作用域勾选目标应用（微信 `com.tencent.mm` / QQ `com.tencent.mobileqq` / 支付宝 `com.eg.android.AlipayGphone`；若要 App 内显示激活状态，需同时勾选 StepShift 自身），强制停止并重启目标应用。此后在 StepShift 中应用虚拟步数，目标应用的计步读数即为虚拟值（模块经 `/data/local/tmp/stepshift_steps.txt` 共享文件获取数值）。

**状态自检**：打开 StepShift「设置 → LSPosed 步数注入模块」，可看到三种状态——`未安装模块` / `已安装 · 未激活`（附"打开管理器"直达按钮）/ `已激活 · 传感器钩子运行中`；设置页同时显示 Health Connect 写入授权状态与应用/模块版本号。

回滚：`ksud module uninstall zygisk_lsposed && ksud module uninstall zygisksu` 后重启。

---

## 代码结构

```
app/src/main/java/com/example/stepshift/
├── engine/             # 核心仿真引擎 (运动学换算、高斯漂移、折线插值器)
├── health/             # 步数广播分发器 + Health Connect StepsRecord 写入
├── model/              # 数据结构与仿真状态快照
├── network/            # OSRM 路网 / Photon 地理编码 / Zepp 云同步客户端
├── root/               # 常驻 su 管道交互与 LocationManager 底层注入
├── service/            # 前台保活服务 (路线仿真 + 定点注入双驱动)、WakeLock 与通知栏管理
├── ui/                 # Jetpack Compose 界面、地图容器、控制组件与 Override 状态卡
└── utils/              # WGS-84 / GCJ-02 坐标系转换 + LSPosed 模块自检端点 (util/LsposedStatus.kt)

xposed/                 # LSPosed 步数注入模块 (hook 计步传感器读数)
└── src/main/java/com/example/stepshift/xposed/
    └── StepSpoofHook.kt
```

---

## 免责声明

本项目仅用于 Android 开发者测试、传感器仿真教学与技术研究。请勿将本项目用于任何违反法律法规或第三方平台服务协议的场景。

---

## 开源协议

本项目采用 [Apache-2.0 License](LICENSE) 协议开源。
