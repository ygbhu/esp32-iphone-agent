# 验证记录（第一检查点）

日期：2026-10-20。所有命令在本机 `E:/study/esp32-phone/android-phone-agent` 执行。
**云端构建、APK 安装、模拟器 UI、真机、BLE、USB HID 全部未执行。**

## 1. 环境

| 项 | 值 |
|---|---|
| 主机 | Windows，工作目录 `E:/study/esp32-phone/android-phone-agent` |
| JDK | Temurin `17.0.20.1+1`（项目内 `.tools/jdk/jdk-17.0.20.1+1`，未改系统 PATH） |
| Gradle | `8.11.1`（Wrapper `distributionUrl` 固定，本地 dist 预置在 `.tools/gradle-home`） |
| Kotlin / 插件 | `2.0.21`（JVM + Android + Compose 编译器插件） |
| AGP | `8.7.3`，`compileSdk 35` / `minSdk 30` / `targetSdk 35`，Java 17 |
| Python（向量生成） | `3.11.3` + `cryptography 49.0.0` |
| Android SDK | **未安装**（许可文本未由用户同意；Pi 不代为接受） |

## 2. 实际执行的命令与结果

### 2.1 共享向量可复现

```
python protocol/tools/gen_vectors.py --check
```
→ exit `0`，5 个文件全部 `ok`（`schema/fragmentation/handshake/envelope/command_ledger.json`）。

### 2.2 纯 Kotlin 核心单元测试（真实执行，非演示）

```
set JAVA_HOME=<project>/.tools/jdk/jdk-17.0.20.1+1
set GRADLE_USER_HOME=<project>/.tools/gradle-home
android\gradlew.bat -p android :core:test --no-daemon --console=plain
```
→ exit `0`，`BUILD SUCCESSFUL`。

统计（来自 `android/core/build/test-results/test/*.xml`）：

| 测试类 | 数量 | 失败 |
|---|---:|---:|
| `agent.AgentCoreTest` | 34 | 0 |
| `agent.AgentStopTimingMatrixTest` | 8 | 0 |
| `control.ActionsTest` | 14 | 0 |
| `control.CommandLedgerTest` | 7 | 0 |
| `protocol.EnvelopeVectorsTest` | 8 | 0 |
| `protocol.FragmentationVectorsTest` | 9 | 0 |
| `protocol.HandshakeVectorsTest` | 7 | 0 |
| `protocol.ProtocolReviewRegressionTest` | 17 | 0 |
| `protocol.SchemaVectorsTest` | 6 | 0 |
| **合计** | **110** | **0** |

其中 `AgentStopTimingMatrixTest` 生成 **≥100 个确定性组合**（阶段 × 停止时机 × 时钟跳变 ×
设备结果 × 旋转），另有 5 个真实线程 + `CountDownLatch` 交错用例（模型调用中、验证中、
设备入口、HID 报告后回执前、可复现性）。

### 2.3 Gradle Wrapper 可用性

```
android\gradlew.bat -p android --version --no-daemon   → exit 0, Gradle 8.11.1 / Launcher JVM 17.0.20.1
android\gradlew.bat -p android :core:test              → exit 0
```

### 2.4 未执行（无退出码可报）

| 命令 | 原因 |
|---|---|
| `./gradlew :app:assembleDebug` | 本机无 Android SDK（许可未同意），`settings.gradle.kts` 因此不配置 `:app` |
| `ci/audit-manifest.sh` | 本机 `bash` 是 WSL 引导器，报 `HCS_E_SERVICE_NOT_AVAILABLE`（Hyper-V 未启用），无法执行；脚本仅静态审阅 |
| `.github/workflows/android-ci.yml` / `ci/action.yml` | 未推送、未运行，云端由 Codex 触发 |
| `adb install` / 模拟器 UI / MediaProjection / IME / BLE / USB HID | 无 APK、不操作模拟器、无真机 |

## 3. 已修复的复核缺陷（第一轮 `docs/codex-checkpoint-review.md`）

| 编号 | 缺陷 | 回归测试 |
|---|---|---|
| 1 | 明文 ACTION 绕过门控 | `ProtocolReviewRegressionTest`：`plaintext action after the handshake is rejected`、`auth_ok must be protected…`、`protected messages before the handshake…`、`handshake order is enforced` |
| 2 | 短分片零填充 / flags 混用 / 超时只靠 tick | `short middle fragment is rejected instead of zero padded`、`non canonical fragment count is rejected`、`flags cannot change mid assembly`、`timeout is enforced when the next fragment arrives`、`control slot timeout on arrival is shorter` |
| 3 | `\u` 转义接受正负号、孤立 surrogate | `signed hex escapes are rejected`、`surrogate pairs are combined and lone surrogates rejected`、`invalid utf8 and raw control characters are rejected` |
| 4 | 认证失败仍推进接收序号 | `tampered high sequence does not advance the receiver`、`envelope from another role or channel is rejected…`、`envelope from a different session is rejected` |
| 5 | 旧 `nowMs` 放行过期帧/预算 | `a slow model response cannot use an observation that aged out`、`…cannot outlive the task duration budget`、`rotation during the model call pauses…`、`projection epoch change during the model call pauses` |
| 6 | `resume` 重置预算 | `resume keeps the model call budget` / `…task deadline` / `…action count` |
| 7 | verifier 迟到覆盖 STOPPED | `a stop during verification wins over a satisfying verdict`、并发用例 `concurrent stop while blocked inside verification` |
| 8 | `Accepted/Started` 当作 Done | `accepted is not done and blocks further input`、`started is not done either`、`pending command can be confirmed or explicitly abandoned` |
| 9 | commandId 在新任务重置 | `command ids keep increasing across tasks in the same device session`、`only a new device session resets the command id watermark` |
| 10 | 预算耗尽不撤销执行权/不释放设备 | `budget exhaustion revokes authority and releases the device`、`device exception revokes releases and reports unknown`、`rejected action raises the consecutive failure budget` |
| 11 | 100 组合矩阵断言方向/时序不真实 | 矩阵改为按事件顺序断言（`outputsWithDeadGeneration`、REVOKE 先于 STOP_REQUEST、permit 覆盖率），并新增 5 个 latch 交错用例 |

## 4. 静态审阅（未运行，仅人工核对）

- `AndroidManifest.xml`：无 accessibility 服务/权限；`bluetooth_le` 为 `required="false"`；
  无 ADB/root；无品牌白名单（`ci/audit-manifest.sh` 会把这三条变成 CI 失败条件）。
- `android/app/build.gradle.kts`：无 release 签名配置（仓库不含签名密钥）。
- `.gitignore`：排除 `.tools/`、`.supervision/`、`**/build/`、`local.properties`、`*.jks`。

## 5. 仍未通过（第二轮 `docs/codex-checkpoint-review-2.md`）

5 项 Agent 核心/解析缺陷**未修复**（详见 `docs/implementation-status.md` §6）：
旧回调写新 generation 状态、`revalidate` 末次 screen 后不重验年龄、permit 的
check-then-act 竞态、矩阵 VERIFY 阶段虚覆盖与旧回调测试缺失、String 入口孤立 surrogate 与
分片文档/超时日志顺序。

**因此：本轮只能声称"协议编解码与工程骨架可构建性有证据"，
不能声称"Agent 核心正式通过"或"第一轮复核全部关闭"。**
