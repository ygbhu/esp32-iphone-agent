# 实现状态（第一检查点快照）

日期：2026-10-20。阶段：**M0 / 可构建工程基础**。

Codex 2026-10-20 更新：第二检查点与最后边界修复见 `codex-verification.md`、`checkpoint-2-report.md`。下面旧检查点描述以本更新为准。这一份写的是"实际存在什么"，不是计划。
任何标 ❌ 的能力在代码里都默认禁用，界面显示"未实现/需要操作"，不会用成功占位。

## 1. 结论摘要

| 项 | 状态 |
|---|---|
| 共享线格式（编码/分片/AEAD 封装）+ 跨语言向量 | ✅ 已实现，Kotlin 侧协议向量测试通过；最新完整测试统计见独立验证记录 |
| Agent 核心（generation/单动作/帧新鲜度/预算/停止/去重） | ⚠️ **已实现但不放行**：已完成第二检查点修复；真实控制依旧禁用（见 §6），不得称为"核心正式通过" |
| 原生 Kotlin/Compose APK 源码 | ✅ 已写；**本机未编译**（无 SDK，见 §4） |
| Gradle Wrapper（8.11.1） | ✅ 已生成并本地实跑成功 |
| 云端 CI（composite action + standalone workflow） | ✅ 已写；**已在父仓库 GitHub Actions 成功运行** |
| ESP32-S3 固件 | ❌ 未实现（下一独立检查点） |
| 真机 / 模拟器 UI / 投影 / IME / BLE / USB HID | ⚠️ MuMu 启动与假设备测试已验证；其余未验证 |

## 2. 已实现（有代码、有测试或可复现命令）

### 协议层 `android/core/src/main/kotlin/com/esp32phone/agent/protocol/`

- `WireFormat.kt`：版本 2、分片头 8 字节、信封头 20 字节、GCM tag 16、通道上限
  1024/512/128、UUID 分配、小端读写工具。
- `Fragmenter.kt`：编码器 + 单通道重组器。规范片数、每片精确长度（短片不零填充）、
  flags 中途不可变、**到片即检查组装超时**。
- `Handshake.kt`：固定二进制 transcript、HMAC-SHA256 双向证明（App proof 绑定 board proof）、
  HKDF-SHA256 派生 session id / 4 组密钥 / 4 个 nonce 前缀。
- `Envelope.kt`：AES-256-GCM 信封，20 字节头作 AAD，nonce = 前缀 + u32 序号，
  序号严格递增，**认证失败不推进接收序号**，跨方向/通道/会话拒绝。
- `HandshakeGate.kt`：明文窗口只允许前三条握手消息，`auth_ok` 起必须 AEAD；
  认证完成前拒绝任何 protected 业务消息。
- `StrictJson.kt`：严格 JSON（拒绝重复键、尾随垃圾、`NaN`、前导零、原始控制字符、
  非法 UTF-8、带符号 `\u` 转义、孤立 surrogate、深度 > 8）。
- `Crypto.kt`：只调用平台 JCE（`HmacSHA256`/`SHA-256`/`AES/GCM/NoPadding`）+ RFC 5869 HKDF 组合，
  **没有自研密码原语**。

### Agent 核心 `android/core/src/main/kotlin/com/esp32phone/agent/`

- `agent/AgentState.kt`：`StopController`（停止先递增 generation）、`InputPermit`、
  `ObservationPolicy`（epoch/旋转/尺寸/帧年龄/帧复用）、`AgentBudget`、状态与暂停原因枚举。
- `agent/AgentCore.kt`：单任务单动作循环。已落实：外部调用后重取时钟并重验观察、
  模型 `done` 需独立 verifier、`Accepted/Started ≠ Done`（进入 `ACTION_PENDING` 并封锁后续输入）、
  预算耗尽先撤销执行权再发停止、`resume` 保留预算、command id 会话内单调。
- `agent/Ports.kt`：`ScreenSource`/`ModelClient`/`DeviceSink`/`Verifier` 契约；
  `UnavailableVerifier` 是唯一随包发布的 verifier（明确返回 Unavailable）。
- `control/Actions.kt`：动作表与边界（坐标 counts 0..4096、时长、ASCII ≤128、按键闭集）、
  模型决策严格解析（拒绝未知动作/未知字段/越界，模型不能指定会话或命令号）。
- `control/CoordinateMapper.kt`：`UncalibratedMapper` 直接抛 `CALIBRATION_REQUIRED`，
  未校准绝不编造映射。
- `control/CommandLedger.kt`：64 条结果缓存 + accepted 水位，淘汰后旧 id 记 `STALE` 而非重执行。
- `harness/AgentHarness.kt`：假时钟/假屏幕/假模型/假设备 + 事件时间线（含 permit 复查）。

### 共享向量 `protocol/vectors/*.json`

由 `protocol/tools/gen_vectors.py`（Python 标准库 + `cryptography`，独立代码路径）生成，
`--check` 可验证仓库内文件未被手改。内容：schema 常量、分片正/负例与组装时序、
握手 transcript/proof/会话密钥、AES-GCM 信封正/负例、命令去重场景。

### Android App 骨架 `android/app/`

> **交接说明（本轮末）**：`ui/Screens.kt`、`ui/MainActivity.kt`、`ui/AgentViewModel.kt`、
> `readiness/ReadinessProbe.kt` 四个文件已由 Codex 接管复核修正（Key/secret 输入遮罩、
> 敏感页截屏保护、切 endpoint 清 Key、binding 严格 hex 与真实写入结果、蓝牙权限检查顺序）。
> Pi 不再修改这四个文件；下面描述的是交接时的状态，以 Codex 修改后的版本为准。

- 中文 Compose 界面：首页（真实就绪状态）、设备、设置、任务、测试五个入口。
- `ReadinessProbe`：逐项实际查询（BLE 特性/适配器/权限、绑定凭证、录屏、Key（服务地址和模型由构建预置）、
  输入法、校准）。**没有"默认就绪"分支**，未实现能力一律 `NOT_IMPLEMENTED` 并阻塞任务。
- `SecretStore`：Android Keystore AES-256-GCM 加密保存模型 Key 与板子绑定凭证（分开存储）。
- `AppPreferences`：非敏感配置；备份/设备迁移全部排除（`data_extraction_rules.xml`、
  `allowBackup=false`）。
- 测试页用假模型/假设备跑同一个 `AgentCore`，界面明确标注"假设备/假模型"。

### 构建与 CI

- `android/gradle/wrapper/`（Gradle 8.11.1）、`android/gradlew(.bat)`。
- `android/settings.gradle.kts`：固定插件版本；**未找到 Android SDK 时只配置 `:core`**，
  这样无 SDK 环境也能跑纯 Kotlin 测试。
- `.github/workflows/android-ci.yml`（本目录独立仓库用）、
  `ci/action.yml`（composite action，供父仓库 `uses: ./android-phone-agent/ci`）、
  `ci/root-workflow-sample.yml`（父仓库 root workflow 参考）、`ci/audit-manifest.sh`（静态审计）。

## 3. 明确的"未实现"清单（代码里没有对应实现，界面如实显示）

| 能力 | 代码现状 | 界面表现 |
|---|---|---|
| MediaProjection 截图 | 无前台服务、无 ImageReader | 录屏：未实现 |
| BLE 扫描/连接/握手/分片收发 | 只有协议编解码与测试向量，无 Android BLE 调用 | 蓝牙：未实现（或无 BLE 特性时"不可用"） |
| USB HID 输出 | 无固件、无 App 侧动作下发 | 板子绑定：需要操作/未实现 |
| 模型请求客户端 | 无 HTTP 客户端；服务地址和模型已预置，用户只填写 Key | 模型 Key：需要操作/未实现 |
| 中文 IME | 没有 `InputMethodService`（manifest 也未声明） | 中文输入：未实现 |
| 坐标校准 | 无校准界面，`UncalibratedMapper` 抛错 | 校准：需要操作 |
| 任务执行 | `开始任务` 按钮 `enabled = report.canLaunchTask`，恒为 false | 任务：不可执行并列出阻塞原因 |
| 固件 | `firmware/` 尚未建立 | — |

## 4. 本机未执行的事（不得当作已通过）

- **`assembleDebug` 未运行**：本机无 Android SDK；本机按用户要求不安装 SDK，APK 已由云端 CI 构建。
- **Pi 未操作模拟器**；Codex 已完成 MuMu 启动与假设备检查，见独立验证记录。
- **未接入真实网关**，未调用任何付费模型，未使用真实 Key。
- **未烧录/擦除设备**，未操作 COM 端口，未安装输入法，未改手机系统设置。
- **固件未编译**（下一检查点）；共享向量的 C++ 侧尚未实现。

## 5. 权限与边界审计

`android/app/src/main/AndroidManifest.xml`：

- 无 `AccessibilityService`、无 `BIND_ACCESSIBILITY_SERVICE`（`ci/audit-manifest.sh` 会失败拦截）。
- `android.hardware.bluetooth_le` 声明为 `required="false"`：无 BLE 的环境（如 MuMu）可安装，
  并显示"蓝牙不可用"，控制保持关闭；不伪造成通过。
- 申请 `INTERNET`、`FOREGROUND_SERVICE(_MEDIA_PROJECTION)`、`POST_NOTIFICATIONS`、
  `BLUETOOTH_SCAN/CONNECT`（API 31+）、旧的 `BLUETOOTH/BLUETOOTH_ADMIN/ACCESS_FINE_LOCATION`
  （`maxSdkVersion=30`）。
- 无悬浮窗、无通知读取、无使用情况访问、无 ADB/Root 路径。

## 6. 核心复核收尾

Pi 第二检查点修复五类问题，报告见 `checkpoint-2-report.md`。最后两个残留由 Codex 修复：

- 设备释放返回后，旧异常/预算分支重新核对任务身份，避免覆盖释放回调启动的新任务。
- permit 在提交锁内核对调用者期望 generation；假设备还核对 request 与 permit 的 generation。
- 新增四项回归，完整测试结果以 `codex-verification.md` 和最终云端报告为准。

这些是基础核心代码的本地边界验证，不是产品或硬件放行。AgentCore 的完整任务生命周期尚未定义任意多线程线性化契约；后续 Android 接入需固定单一执行调度并专门验证生命周期竞态。EventLog 的 revoke listener 在提交锁外，不能凭其时间线证明所有并发事件的严格线性顺序；锁/latch 测试只证明本地提交与撤权互斥。

## 7. 下一检查点

独立固件目标与 C++ 协议向量，然后逐项接入 BLE、MediaProjection、模型 HTTP、可选 IME 和校准。当前开发 APK 只演示界面和假模型/假设备；不得宣称可以操作微信。Pi 当前因上游模型服务 403 已空闲，Codex 接手本轮收尾，未持续后台开发。
