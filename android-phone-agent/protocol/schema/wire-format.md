# 安卓 Agent 线格式 v2（实施细化）

日期：2026-10-20。状态：**已实现编码器与共享向量**；未在真机/实物 BLE 验证。

本文件是 `plans/android-agent-protocol.md`（协议基线 v0.2）的字节级实施细化。凡是基线已明确的，
这里不重新定义；这里只固定基线留给实施阶段的字节布局。冲突时以基线为准，并在此记录偏差。

生成 ground truth 的脚本：`protocol/tools/gen_vectors.py`（Python 标准库 + `cryptography`，
只调用标准原语，无自研密码算法）。共享向量：`protocol/vectors/*.json`。
Kotlin 与 C++ 双方都必须在测试中逐字节复现这些向量。

## 1. 常量

| 项 | 值 |
|---|---|
| `wire_version` | 2 |
| 分片头 `FRAGMENT_HEADER_LEN` | 8 |
| 信封头 `ENVELOPE_HEADER_LEN` | 20（同时是 AES-GCM 的 AAD） |
| GCM tag | 16 字节（完整 tag，不截断） |
| 普通消息上限 | 1024（含信封） |
| 握手消息上限 | 512 |
| 控制消息上限 | 128 |
| 每片内容容量 | `mtu - 3 - 8`；MTU=23 时为 12 |
| 序列号 | u32，从 1 开始，逐方向/通道独立，不回绕 |
| 结果缓存 | 64 条，另保留 accepted 水位 |

服务与特征 UUID 与基线一致，不在此改写（`schema.json` 中有机器可读副本用于双方测试比对）。

## 2. 分片（BLE 传输层）

8 字节头，所有整数小端：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 1 | `wire_version` = 2 |
| 1 | 1 | `flags`：bit0 = PROTECTED（0 = 握手明文，1 = AEAD 信封）；bit1..7 必须为 0 |
| 2 | 2 | `transfer_id`（连接内，防误拼装，不承担防重放） |
| 4 | 1 | `fragment_index`（0 基，必须严格递增） |
| 5 | 1 | `fragment_count`（1..255） |
| 6 | 2 | `total_bytes`（完整逻辑消息长度，不是本片长度） |
| 8 | n | 本片内容 |

规则（实现已按此校验）：

1. 先编码完整逻辑消息，再分片；不对单片解析 JSON。
2. 每连接只有 2 个组装槽（普通 / 控制）。握手、认证、普通请求走普通槽；`heartbeat` / `stop` 及其回执走控制槽。
3. **规范片数**：`fragment_count` 必须等于 `ceil(total_bytes / (mtu-11))`，空消息为 1。
   片数偏小（想塞更多内容）或偏大（想留空片）都按 `BAD_FRAME` 拒绝。
4. **每片精确长度**：第 i 片必须携带 `min(mtu-11, total_bytes - i*(mtu-11))` 字节。
   除最后一片外，每片必须满载；**短中间片一律拒绝，不做零填充**（否则接收端会把
   填充字节当成消息内容）。
5. **flags 固定**：同一次组装内 `flags` 不可变；明文与密文分片混用按 `BAD_FRAME` 拒绝。
6. `transfer_id` 在组装未结束时改变 → `BAD_FRAME`（需显式取消旧 transfer 才能重开）。
7. `total_bytes` 必须 ≤ 该通道上限，超限 `TOO_LARGE`，不做无限分配。
8. `fragment_index` 必须等于期望下标；重复、乱序、跳号 → `BAD_FRAME`，清槽。
9. 完成条件：`next_index == fragment_count` 且累计长度 == `total_bytes`，否则 `BAD_FRAME`。
10. **组装超时在每一片到达时检查**（不只依赖外部定时器）：
    普通 2000 ms，控制 1000 ms（工程初值，MTU=23 压力测试后定稿）。
    超时后的下片按 `FRAGMENT_TIMEOUT` 拒绝并清槽；外部 `onTick` 仍可用于主动清槽。
11. 断连、会话失效、认证失败立即清空两个槽。

## 3. 握手与密钥派生

握手阶段只有**前三条**消息允许明文（`flags = 0`）：`client_hello` / `board_hello` / `app_auth`。
`auth_ok` **必须**用新会话密钥封装（`flags = 1`），因为它是板子对 App 证明的确认，必须在
已派生的会话密钥下完成，不能用明文回执。其余消息一律 `flags = 1`（protected）。

`transcript`（固定二进制，不依赖 JSON 字段顺序）：

| 偏移 | 长度 | 内容 |
|---|---|---|
| 0 | 8 | 魔数 ASCII `E32PHN2H` |
| 8 | 1 | `wire_version` = 2 |
| 9 | 1 | `board_id_len`（1..32） |
| 10 | n | `board_id`（UTF-8 原始字节） |
| 10+n | 32 | `client_nonce` |
| 42+n | 32 | `board_nonce` |
| 74+n | 4 | `features` u32 LE |

证明（HMAC-SHA256，标准库）：

```
board_proof = HMAC(secret, "esp32phone/v2/proof/board" || transcript)
app_proof   = HMAC(secret, "esp32phone/v2/proof/app"   || transcript || board_proof)
```

会话派生（HKDF-SHA256，`salt = SHA-256(transcript)`，`ikm = secret`）：

| 用途 | info | 输出 |
|---|---|---|
| `session_id` | `esp32phone/v2/session` | 8 字节 |
| 密钥 | `esp32phone/v2/key/{app,board}-{normal,control}` | 32 字节 |
| nonce 前缀 | `esp32phone/v2/nonce/{app,board}-{normal,control}` | 8 字节 |

App proof 绑定 board proof，防止反射；角色标签不同，防跨角色复用。任一验证失败 → `AUTH_FAILED`，
并在计数/速率限制内保持不可控制。`auth_ok` 的成功**不是**"已可输入"：USB 与校准状态另行判定。

明文窗口在第 3 条消息（`app_auth`）后即关闭：板子若从 `auth_ok` 开始之后收到任何 `flags = 0` 的
分片，一律按 `BAD_FRAME`（或 `NOT_AUTHENTICATED`）拒绝，不做兼容放宽。

## 4. 认证信封

20 字节头，小端，整头作为 AAD：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 1 | `version` = 2 |
| 1 | 1 | `message_type` |
| 2 | 8 | `session_id` |
| 10 | 4 | `sequence` |
| 14 | 4 | `command_id` |
| 18 | 2 | `payload_length`（明文长度） |
| 20 | n+16 | 密文 `n` 字节 + tag 16 字节 |

- nonce = `nonce_prefix[8] || sequence u32 LE`。每方向/通道独立密钥与前缀，序列号互不阻塞。
- 接收方先校验 `session_id` 与 `version`，再 `open`；`open` 成功后才允许影响状态或续租。
- `sequence` 必须严格大于该通道已接受的最大值且非 0；重复/倒退 → `REPLAY_DETECTED`。
- 明文长度必须 == `payload_length` 且 == 总计封包长度 − 20 − 16；不符 → `BAD_FRAME`。
- 明文必须是严格 UTF-8 JSON（拒绝重复键、尾随垃圾、深度 > 8、非对象根）。

## 5. 消息类型与错误码

机器可读定义见 `protocol/vectors/schema.json`。首版类型：握手 1..4；能力 10/11；心跳 20/21；
动作 30、`accepted` 31、`started` 32、`done` 33、`rejected` 34、`cancelled` 35、`error` 36；
`stop` 40、`stopped` 41；结果查询 50/51；`status` 60。

错误码 0..20（`OK`、`BAD_VERSION`、`BAD_FRAME`、`FRAGMENT_TIMEOUT`、`AUTH_FAILED`、
`NOT_AUTHENTICATED`、`REPLAY_DETECTED`、`UNKNOWN_TYPE`、`INVALID_JSON`、`INVALID_ACTION`、
`ARG_OUT_OF_RANGE`、`BUSY`、`COMMAND_CONFLICT`、`COMMAND_STALE`、`USB_UNAVAILABLE`、
`CALIBRATION_REQUIRED`、`RELEASE_FAILED`、`BUDGET_EXCEEDED`、`NOT_CONTROLLER`、`RATE_LIMITED`、
`TOO_LARGE`）。未知类型/错误码必须拒绝，不得按默认成功处理。

## 6. 动作参数（固件侧，HID 计数）

模型只产出 0..1000 的截图坐标；App 负责映射到 HID 计数（0..4096），固件不再做一次 0..1000 映射。

| 动作 | 字段 | 边界 |
|---|---|---|
| `tap` | `x_counts`,`y_counts`,`duration_ms` | 0..4096；30..1000 ms |
| `swipe` | `x0_counts`,`y0_counts`,`x1_counts`,`y1_counts`,`duration_ms` | 0..4096；50..3000 ms |
| `long_press` | `x_counts`,`y_counts`,`duration_ms` | 0..4096；400..3000 ms |
| `scroll` | `amount` | −10..10，非 0 |
| `type_ascii` | `text` | ≤128 个可打印 ASCII（0x20..0x7E），拒绝控制字符与非 ASCII |
| `key` | `key` | 仅 `ENTER`,`BACKSPACE`,`ESC`,`TAB`,`ARROW_UP/DOWN/LEFT/RIGHT` |

所有动作总时限 4000 ms（≤ 基线 8 s 上限的保守值，含归位）。超界一律 `ARG_OUT_OF_RANGE`，
不做静默截断。未知动作、未知参数、缺失参数 → `INVALID_ACTION`（不执行半条命令）。
`wait` 由 App 本地执行，不接受固件动作；`locate/calibrate` 只在 App 校准流程内，不对模型开放。

## 7. 命令去重

`command_id` 在会话内单调；缓存 64 条 `(id → payload_digest, 状态)`：

| 情况 | 结果 |
|---|---|
| 新 id | `EXECUTE`（并记入水位） |
| 同 id 同 payload | `DUPLICATE`（返回已有状态，不二次执行） |
| 同 id 不同 payload | `COMMAND_CONFLICT`（拒绝） |
| id ≤ 已淘汰水位且不在缓存 | `COMMAND_STALE`（绝不当新命令执行） |

payload 摘要用 SHA-256 前 16 字节比较（只在本机内存比较，不参与线格式）。
会话重建后水位清空，但 App 侧不得据此重放旧队列（基线 §7）。

8. 实施中相对基线的偏差与保守取舍

1. 动作总时限取 4 s（基线给 8 s 上限），便于在 MTU=23 压力测试前留出停止余量。
2. 组装超时先用 2000/1000 ms 工程初值，未实测。
3. 未实现的功能（BLE 收发、USB HID 输出、MediaProjection、IME、模型真实调用）在代码与
   `docs/implementation-status.md` 中标为不可用/禁用，不做空实现冒充可用。

## 9. 已知未完成项（第二轮复核，见 `docs/codex-checkpoint-review-2.md`）

以下与线格式直接相关，**尚未修复**，不得视为已满足：

1. `StrictJson.parseObject(String)` 入口仍会接受原始孤立 UTF-16 surrogate（ByteArray 入口已严格）。
   两端解析器必须一致拒绝，否则同一字节流在 Kotlin 与 C++ 侧可能有不同解释。
2. `Fragmenter` 超时诊断路径先 `reset()` 再计算 elapsed，导致日志里的时长不可信
   （判定本身正确，只是诊断信息不准）。
