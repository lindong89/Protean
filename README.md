# Protean

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84)
![Framework](https://img.shields.io/badge/Framework-LSPosed-orange)

基于 LSPosed 的位置模拟模块。通过 Hook 系统定位服务实现虚拟定位，**不需要修改目标应用**，也无法被集成进其他 App。

> **本项目是 [fuqiuluo/Portal](https://github.com/fuqiuluo/Portal) 的修改版**，遵循 Apache License 2.0。
> 应用名已改为 **Protean**（包名 `com.ld.protean`），并在原版基础上做了若干修复与增强，详见 [与原版的差异](#与原版的差异)。

## ⚠️ 免责声明

- 本项目仅供**学习研究、开发调试与软件测试**使用：例如在没有真实移动条件的测试环境里验证定位相关逻辑。
- **禁止**将本项目用于任何违法用途，包括但不限于考勤作弊、虚假打卡、伪造行程证据、规避监管或欺骗他人。
- 使用本项目产生的一切后果由使用者自行承担，与作者无关。
- 本项目遵循 [Apache License 2.0](LICENSE)，按"原样"提供，不附带任何担保。

## 功能

- [x] 位置模拟：地图选点 / 手动输入经纬度 / 关键字搜索 / 逆地理编码
- [x] 路线模拟：沿路线移动，自动计算方位角
- [x] 摇杆移动，可设置移动速度
- [x] 定位精度、海拔高度可配置
- [x] GNSS 模拟：模拟卫星数量、AGPS 开关、NMEA 语句开关
- [x] 传感器劫持、网络降级为 CDMA
- [x] 禁用融合定位 / 允许获取当前位置 / 允许注册位置监听器（按需开关）
- [x] **禁用 WiFi 扫描列表**、**反定位拉回**（解决部分 App 把蓝点拉回真实位置的问题）
- [x] 历史位置、历史路线
- [x] 三语界面（简体中文 / English / 日本語），支持按应用单独切换语言
- [x] 内置地图瓦片渲染，可切换图源

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 8.0（API 26）及以上 |
| 权限 | Root + [LSPosed](https://github.com/LSPosed/LSPosed)（或兼容的 Xposed 框架） |
| 作用域 | 在 LSPosed 中勾选 **「系统框架」**（`android`，uid 1000） |
| 构建 | JDK 17、Android SDK 35 |

> 定位服务、融合定位都在 `system_server` 内，所以作用域选「系统框架」即可覆盖；
> `一体化位置信息`（`com.android.location.fused`）与系统框架共用 uid 1000，勾选系统框架即已包含。

## 安装

1. 从 [Releases](https://github.com/lindong89/Protean/releases) 下载对应 ABI 的 APK（真机一般选 `arm64`）。
   > 附件为 **debug 签名**构建，可直接覆盖安装同签名的旧版本。
2. 安装后在 LSPosed 中启用本模块，作用域勾选 **系统框架**。
3. **重启手机**（模块注入 `system_server`，改动模块代码后必须重启才生效）。
4. 打开 Protean，授予定位权限，选点后点「启动模拟」。

也可以在 Actions 页面下载 CI 构建的产物：每次向 `master` 推送或手动触发 `Build Apks` 时，云端会构建三个 ABI 的 debug 包。

## 从源码构建

```bash
# 三个 ABI 的 debug 包（app = 全 ABI、arm64、x86_64）
./gradlew :app:assembleDebug

# 单元测试
./gradlew :app:testArm64DebugUnitTest
```

产物位于 `app/build/outputs/apk/<flavor>/debug/`。

> 版本号形如 `1.0.4.r<提交数>.<短哈希>`，只取决于 git 状态；`versionCode` 是构建时刻的时间戳，
> 因此重复构建的包名可以相同但内容不同——判断"这个包是哪版代码"请以提交号为准。

## 与原版的差异

**应用侧**

- 应用改名为 Protean，包名 `moe.fuqiuluo.portal` → `com.ld.protean`
- **移除百度定位与地图 SDK**（`BaiduLBS_Android.jar`、`libBaiduMapSDK_*`、`liblocSDK8b` 等），地图改为
  [osmdroid](https://github.com/osmdroid/osmdroid) 自绘瓦片，底图用 Esri ArcGIS 在线服务，关键字搜索与
  逆地理编码用 [Photon](https://photon.komoot.io/)（均无需 API Key）
- 换用 Protean 官方图标与通知图标（通知不再使用系统占位图）
- 补齐三语文案与 `localeConfig`，主题改为浅蓝配色

**模块侧（Xposed）**

- 修正 hook 作用域：原先 `android` / `com.android.phone` 等分支在入口处被提前 return 跳过（属死代码），
  现改为集合判断，并补齐 `LocationProviderManagerHook`、`GnssHook`、`MiuiBlurLocationProviderHook` 的挂载
- 解耦 AGPS 开关：测试 Provider 与 GNSS 批量接口的防护不再被 `enableAGPS` 短路
- 重写 GNSS 星状态回调匹配（按参数类型扫描方法，失败时打印真实方法签名，便于适配新系统）
- 新增 **反定位拉回**：以固定间隔持续把所有已注册监听器上的模拟位置重播一遍，把被 App 用自家定位/网络定位
  拉回的真实位置再压回去（默认关闭，代价是耗电）

## 已知限制

- **反定位拉回**：对"App 使用系统定位通道"的情况有效，对完全自建定位链路的 App 不一定有效；开启后耗电增加
- 部分机型的 GNSS 星状态回调接口在新系统上有变动，模块会在日志中提示实际方法签名，便于后续适配
- 依赖 Root + LSPosed，无法在未 Root 设备上使用

## 致谢

- [fuqiuluo/Portal](https://github.com/fuqiuluo/Portal)：本项目的上游，原作者
- [CrackerCat/Portal](https://github.com/CrackerCat/Portal)：本仓库的直接基线
- [GoGoGo](https://github.com/ZCShou/GoGoGo)
- [osmdroid](https://github.com/osmdroid/osmdroid)（替换百度地图 SDK）
- [Photon](https://photon.komoot.io/)（关键字搜索与逆地理编码，需联网）
- [Esri ArcGIS 在线底图](https://server.arcgisonline.com/arcgis/rest/services)（无需 API Key）

## 许可证

[Apache License 2.0](LICENSE)。本仓库是对上游项目的修改版，保留原始版权声明；修改内容见
[与原版的差异](#与原版的差异)。
