# 安卓手机 Agent（ESP32-S3 USB HID）

原生 Kotlin + Compose 的安卓 App，配合 ESP32-S3 板子作为 USB HID 输入执行器。
手机负责截图（MediaProjection）与调用用户自己的视觉模型网关；板子负责 BLE 收指令、
USB HID 产出鼠标/键盘输入。**不使用无障碍服务、ADB、Root 或电脑常驻 Agent。**

- 设计基线：`../plans/android-agent-development-plan.md`、`../plans/android-agent-protocol.md`、
  `../plans/android-agent-implementation-checklist.md`
- 线格式（字节级）：[`protocol/schema/wire-format.md`](protocol/schema/wire-format.md)
- 实现状态（真实完成/未完成）：[`docs/implementation-status.md`](docs/implementation-status.md)
- 验证记录（命令与退出码）：[`docs/verification.md`](docs/verification.md)
- 工具链、来源与许可：[`docs/toolchain.md`](docs/toolchain.md)、[`docs/upstream-provenance.md`](docs/upstream-provenance.md)
- 板子与恢复备份：[`docs/board-and-recovery.md`](docs/board-and-recovery.md)

## 当前状态（一句话）

**协议编解码与 Agent 状态机有真实单元测试（110 项通过），APK 源码与 CI 已就绪但本机未编译，
固件、BLE、HID、截图、IME、真机全部未实现或未验证。控制保持禁用。**

## 目录

```text
android/
  core/     # 纯 Kotlin（JVM）：协议编解码、Agent 核心、假设备 harness —— 可无 SDK 测试
  app/      # Compose APK 骨架：就绪检测、设置、设备、任务、测试页
protocol/
  schema/   # wire-format.md（字节布局、边界、偏差说明）
  vectors/  # 跨语言共享测试向量（Kotlin/C++ 都要逐字节复现）
  tools/    # gen_vectors.py（独立 ground truth 生成器，--check 校验）
ci/         # composite action + 父仓库 root workflow 参考 + manifest 审计脚本
docs/       # 状态、验证、工具链、来源、板子与恢复
```

## 构建

本机（无需 Android SDK，只跑协议/状态机测试）：

```powershell
$t = "<repo>/.tools"
$env:JAVA_HOME = "$t/jdk/jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "$t/gradle-home"
android\gradlew.bat -p android :core:test --no-daemon --console=plain
```

云（Codex 触发，见 `ci/root-workflow-sample.yml`）：root workflow 调用
`uses: ./android-phone-agent/ci`，产出 debug APK + 单元测试报告 + manifest 审计结果。

## 不做什么

- 不给任何手机品牌/型号做白名单，也不宣称任何机型已通过。
- 不伪造连接、就绪或成功状态：未实现的能力在界面上显示"未实现"并阻塞任务。
- 不上传截图/聊天到自建服务端；没有账号、支付、计费。
- 不自动发送微信消息（M6 后续单独验收）。
