# 工具链（固定版本、来源与许可）

日期：2026-10-20。原则：**只写项目内 `.tools/`，不改系统 PATH、不动其他项目**。
本机 `java` 是 JDK 8（Oracle，系统自带），与本项目无关；项目一律使用自己的 JDK 17。

## 1. 固定版本

| 组件 | 版本 | 来源 | 许可 | 位置 |
|---|---|---|---|---|
| JDK | Temurin `17.0.20.1+1` | `https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse` | GPLv2 + Classpath Exception | `.tools/jdk/jdk-17.0.20.1+1` |
| Gradle | `8.11.1`（bin） | `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` | Apache-2.0 | `.tools/gradle/gradle-8.11.1` + Wrapper |
| Gradle Wrapper | 由 8.11.1 生成 | 同上 | Apache-2.0 | `android/gradle/wrapper/`（jar 43,583 B 随仓库提交） |
| Android Gradle Plugin | `8.7.3` | Google Maven | Apache-2.0 | 由 Gradle 解析（未 vendored） |
| Kotlin / Compose 编译器插件 | `2.0.21` | Maven Central | Apache-2.0 | 由 Gradle 解析 |
| Compose BOM | `2024.10.01` | Google Maven | Apache-2.0 | 由 Gradle 解析 |
| Python（仅向量生成） | `3.11.3` | 系统已有 | PSF | 系统安装 |
| `cryptography`（仅向量生成） | `49.0.0` | 系统已有 | Apache-2.0 / BSD | 系统 site-packages |
| Arduino CLI | `1.5.2-rc.1` | 从只读参考项目复制（`esp32-iphone-agent/hardware/build/tools/arduino-cli`） | GPL-3.0 | `.tools/arduino-cli/` |
| Arduino-ESP32 core | `3.3.12` | 从只读参考项目复制（`.../arduino-data/packages/esp32`） | LGPL-2.1 | `.tools/arduino-data/`（1.88 GB） |

**未安装 Android SDK。** `sdkmanager` 安装 `platforms;android-35` 会打印
`android-sdk-license` 法律文本并要求交互同意；按要求不代用户接受，因此停止。
云端 CI 由 `android-actions/setup-android` 在 runner 上安装（该行为属于用户的云端决定）。

## 2. `.tools/` 布局（全部被 `.gitignore` 排除）

```
.tools/
  dl/                     # 下载的原始压缩包
  jdk/jdk-17.0.20.1+1/
  gradle/gradle-8.11.1/   # 用于生成 wrapper
  gradle-home/            # GRADLE_USER_HOME（依赖缓存 + wrapper dists 预置）
  arduino-cli/            # arduino-cli.exe + LICENSE（供固件检查点使用）
  arduino-data/           # Arduino-ESP32 core 3.3.12 + 工具（供固件检查点使用）
  arduino-user/
```

## 3. 本地复现命令（PowerShell）

```powershell
$t = "E:/study/esp32-phone/android-phone-agent/.tools"
$env:JAVA_HOME = "$t/jdk/jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "$t/gradle-home"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 纯 Kotlin 核心测试（不需要 Android SDK）
E:/study/esp32-phone/android-phone-agent/android/gradlew.bat -p E:/study/esp32-phone/android-phone-agent/android :core:test --no-daemon --console=plain

# 有 Android SDK 时（本机没有）：APK
# echo "sdk.dir=<SDK路径>" > android/local.properties
# android\gradlew.bat -p android :app:assembleDebug
```

`android/settings.gradle.kts` 会检测 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME`：
找不到完整 SDK（含 `platforms/android-*`）时**不配置 `:app`**，只构建 `:core`，
并打印提示。这样"无 SDK 的机器也能跑协议/状态机测试"，而不会假装 APK 构建过。

## 4. 许可注意事项

- 参考仓库 `esp32-iphone-agent` 顶层是 MIT，但
  `hardware/hid_bridge/src/airplay/**` 是 GPL-3.0（`LICENSE-GPL.md`）。
  本项目**不链接 AirPlay**，也不复制该目录；固件检查点只复用 USB HID/BLE 思路与工具链。
- 本项目自带代码目前未声明许可，发布前由用户决定（`docs/upstream-provenance.md` 记录来源）。
