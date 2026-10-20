# 第二检查点报告：核心复核-2 修复

日期：2026-10-20。范围：只改 `android/core/**`、`protocol/schema/wire-format.md` 与本文档。
未改 App、CI、README、参考仓库；未操作云端或模拟器；未做固件或 M1 新功能。

## 1. 结果

- `./gradlew :core:test --rerun-tasks` → **exit 0，133 tests / 0 failures**（10 个测试类）。
- 7 项变异检查（把缺陷逐个重新注入）→ **7/7 被新测试抓住**（见 §4）。
- 未修复项：无（本轮复核-2 的 5 类全部关闭）。**但“核心正式放行”仍不成立**：
  BLE/USB HID/截图/IME/真机均未实现，本轮只证明纯 Kotlin 状态机与协议编解码。

## 2. 逐项修复

### 2.1 旧回调污染新 generation（复核-2 第 1 项）

`AgentCore` 新增 `taskGeneration`（当前任务代际）与 `owned(gen) = gen == taskGeneration &&
authority.isCurrent(gen)`：

- `stale()` 只在 `generation == taskGeneration` 时写 `lastError`；代际已换则**不写任何字段**。
- 旧设备异常不再调用 `authority.stop()`：先判 `owned()`，为 false 就只返回 `Stale`，
  不会撤销新 generation、不会多发一次设备 stop。
- 旧 `screen` 异常 / 返回 null 的分支同样先判 `owned()`，不再 pause 新任务。
- 旧 `model` 异常、旧 `verifier` 返回同理：只返回 `Stale`。
- 未知结果的命令记入 `orphanedCommands`（诊断用途，带上代际与命令号），明确**不是任务状态**，
  状态机从不读它，也不阻止新任务。
- 新任务启动后旧回调到达 → `state` 仍是 `IDLE`、`lastError == null`、无 pending、
  policy 未被清（回归见 §3）。

### 2.2 最后一次截图返回后未重取时钟（复核-2 第 2 项）

`revalidate()` 现在：

1. 调用截图**前**：检查所有权、任务时长、bound 帧年龄；
2. 调用截图；
3. **重新读时钟**，然后重新检查所有权、任务时长、bound 帧年龄、`current` 是否未来时间、
   `current` 年龄、epoch、旋转、尺寸。

因此“最后一次截图卡 2 秒再返回同几何帧”会在步骤 3 被拒绝；earlier 的检查不会替代它。

### 2.3 permit 的 check-then-act（复核-2 第 3 项）

`StopController` 增加私有监视器，`stop()` / `beginNewTask()` 与
`InputPermit.tryDispatch()` 共用：

```kotlin
internal fun dispatchIfCurrent(expectedGeneration: Long, emit: () -> Unit): Boolean =
    synchronized(commitLock) {
        if (stopped || generation.get() != expectedGeneration) return false
        emit()
        true
    }
```

- `InputPermit` 绑定 `generation + commandId + SHA-256(payload)[0..16)`；
  `FakeDevice`（遵循真实 `DeviceSink` 契约）先校验摘要与 permit 匹配，再通过
  `tryDispatch` 提交，**没有“检查→写”的缝隙**。
- 语义写清楚：permit 是**可撤销的多报告许可**，不是单次令牌（一次滑动需要多份短报告）；
  撤销后每次 `tryDispatch` 都失败。
- 契约写明：监视器内只允许短非阻塞本地写入；截图/模型/验证器/设备 ACK 都在锁外。
- 文档明确：这是 **App 本地**保证，**不能**推导出板子在收到 STOP 前已经停止输出；
  300 ms / 1.5 s 仍是待实测目标（`wire-format.md` §9）。

### 2.4 时序矩阵虚覆盖（复核-2 第 4 项）

- 矩阵扩到 `8 阶段 × 2(注入/不注入 stop) × 4 时钟场景 × 4 设备结果 = 256` 个确定性组合。
- `VERIFY` 阶段改为真实 `done` 决策 + 真实验证器调用，并断言验证器被调用、stop 在验证器内
  发生时结果必须是 `Stale` 且状态 `STOPPED`。
- 新增覆盖守卫 `each injected phase is really reached`：断言 OBSERVE/MODEL/VERIFY/
  EXECUTE_ENTRY/OUTPUT/ACK 六个注入点确实被触达（防止 hook 失效后矩阵空转）。
- 新增 `the no stop control group still executes` / `... completes a verified done`：
  确认无 stop 的干净路径真的写出一条报告 / 真的完成任务，避免“全部暂停也算通过”。
- 删除名为 “late stop 保护新 policy” 但无旧回调的虚测试，替换为真正有旧任务回返的用例。

### 2.5 解析与诊断（复核-2 第 5 项）

- `StrictJson` 的 **String 入口**现在与 UTF-8 字节入口同规则：高代理必须紧跟低代理，
  孤立高/低代理一律 `INVALID_JSON`；合法代理对保留。
- `Fragmenter` 超时诊断改为**先记录 elapsed 再 reset**，日志时间不再变成绝对时钟值。
- `wire-format.md` 补全：规范片数、每片精确长度（短片不补零）、flags 全程固定、
  到片即检查超时；新增 §9 本地提交临界区、§10 已关闭复核项。

## 3. 新增/修改的测试（不虚增计数）

| 测试类 | 数量 | 说明 |
|---|---:|---|
| `AgentGenerationIsolationTest`（新） | 18 | 旧回调（device Done/异常、screen 异常/null、model 异常、verifier 迟到）、慢截图/未来时间戳/过期 current、提交临界区三例、permit 绑定、120 次并发交错 |
| `AgentStopTimingMatrixTest` | 11 | 256 组合矩阵 + 覆盖守卫 + 对照组 + 5 个 latch 交错 |
| `AgentCoreTest` | 34 | 原有用例；其中“旧回调保护新 policy”改为真正的旧任务回返 |
| `ProtocolReviewRegressionTest` | 19 | 新增原始孤立 surrogate、超时诊断时间两条 |
| 其余 6 类 | 51 | 未改动（共享向量、动作、去重、schema） |
| **合计** | **133** | 上轮 110 → 本轮 133（+23，全部为新断言或改写后的真实覆盖） |

## 4. 变异检查（证明测试不是空跑）

脚本 `.tools/mutation-check.ps1`（只在项目 `.tools/`，不入库）逐个把缺陷重新注入并只跑
对应回归，然后恢复原文件。结果：

| 变异 | 重新注入的缺陷 | 结果 |
|---|---|---|
| M1 | 死代际回调无条件写 `lastError` | CAUGHT |
| M2 | 旧设备异常重新撤销新 generation | CAUGHT |
| M3 | `revalidate` 用截图前的时钟判断新鲜度 | CAUGHT |
| M4 | permit 改为 check-then-act（无共享锁） | CAUGHT |
| M5 | String 入口重新接受原始孤立 surrogate | CAUGHT |
| M6 | 超时 elapsed 在 `reset()` 之后计算 | CAUGHT |
| M7 | 矩阵 VERIFY 阶段不再调用验证器 | CAUGHT |

M3 第一次**未被抓住**（我的用例只在较早的一次截图里老化帧，下一次 revalidate 的入口读时钟
掩盖了缺陷）；已把老化点改到**最后一次**截图后，M3 变为 CAUGHT。这一点记录在此，
因为它正是“测试要能在错误实现上失败”的例子。

## 5. 实际执行的命令

```
set JAVA_HOME=<repo>/.tools/jdk/jdk-17.0.20.1+1
set GRADLE_USER_HOME=<repo>/.tools/gradle-home

android\gradlew.bat -p android :core:test --no-daemon --console=plain --rerun-tasks
  → exit 0, 133 tests, 0 failures

powershell -File .tools/mutation-check.ps1
  → 7/7 CAUGHT，恢复后整套再次 exit 0
```

## 6. 仍未完成（不得当作已通过）

- **真机/硬件**：BLE 射频、USB HID 枚举与输出、停止时延（300 ms / 1.5 s 目标）、
  拔插与释放均为**未测**。
- **APK 运行时**：MediaProjection、前台服务、IME、Keystore 实际读写、MuMu 上的 UI
  均未执行（APK 由云端 CI 产出，本轮无新 APK）。
- **固件**：ESP32-S3 目标、C++ 侧共享向量、默认拒绝输入入口均**未实现**。
- **模型**：未接入真实网关，未调用任何付费模型。
- 本轮的 `StopController` 锁只覆盖 App 进程内；跨进程/跨设备语义没有验证。
