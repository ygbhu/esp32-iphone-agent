# 上游来源与许可

日期：2026-10-20。

## 1. 只读参考检出

| 项目 | 路径 | 用途 |
|---|---|---|
| `esp32-iphone-agent` | `E:/study/esp32-phone/esp32-iphone-agent` | 主要逻辑参考（iPhone 版 Agent、HID、BLE 现状） |
| `esp32-android-agent` | `E:/study/esp32-phone/esp32-android-agent` | 仅参考，**不作为运行依赖**（电脑端 Python/ADB 路线被否决） |
| `jev-chat-jarvis` | `E:/study/esp32-phone/jev-chat-jarvis` | 仅参考消息分析/候选/确认思路 |

上游 commit（`esp32-iphone-agent`）：

```
68751e4d89abf64af338de39e5452da30d8f2173
2026-10-18 08:06:23 +0800
fix(readme): describe explicit model selection instead of automatic picking (#19)
```

**没有修改任何参考检出**：本检查点未在 `esp32-iphone-agent/` 内写入文件。
`.tools/arduino-cli` 与 `.tools/arduino-data` 是从其 `hardware/build/` **复制**出来的副本，
原件未动。

## 2. 本项目的代码来源

| 本项目文件 | 来源 |
|---|---|
| `protocol/schema/wire-format.md`、`protocol/vectors/*`、`protocol/tools/gen_vectors.py` | 新写（依据 `plans/android-agent-protocol.md` 的字节级细化） |
| `android/core/**`（协议 + Agent 核心 + harness） | 新写 Kotlin，未从 Swift/C 复制代码 |
| `android/app/**`（Compose 骨架、Readiness、SecretStore） | 新写 |
| `ci/**`、`docs/**` | 新写 |

思路参考（非代码复制）：

- `esp32-iphone-agent/ios/IPhoneAgent/Agent/AgentLoop.swift`：规划→执行→反馈的职责划分。
- `.../Agent/Pointer.swift`、`.../App/TouchCalibration.swift`：指针与校准概念。
- `.../Services/LLMClient.swift`：OpenAI 兼容调用形态。
- `esp32-iphone-agent/hardware/hid_bridge/hid_bridge.ino`、`ble_link.cpp`：BLE/HID 分工，
  其"同步执行 + 无完整去重"的问题在本项目协议中被显式修正（停止优先、command id 去重、
  控制通道独立分片槽）。

## 3. 许可

- 上游仓库顶层 `LICENSE` = MIT（Copyright (c) 2026 Henry Yuan）。
- `hardware/hid_bridge/src/airplay/**` 单独为 **GPL-3.0**（`LICENSE-GPL.md`）。
  本项目不包含、不链接、不分发该目录；安卓固件目标不启动 AirPlay/Wi-Fi/视频。
- Arduino-ESP32 core 为 LGPL-2.1，Arduino CLI 为 GPL-3.0：只作为**构建工具**放在
  `.tools/`（不提交、不分发）。
- 本项目自身许可尚未声明；对外发布前需由用户决定并补充 `LICENSE`。
