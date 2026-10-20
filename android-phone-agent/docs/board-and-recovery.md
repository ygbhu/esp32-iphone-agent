# 板子配置与恢复备份

日期：2026-10-20。本文件只记录**已由上游项目实测记录**的硬件事实，以及恢复材料位置。
本检查点没有连接、烧录或擦除任何设备。

## 1. 已确认的硬件（来自上游 flash 报告，只读）

来源：`esp32-iphone-agent/hardware/tools/out/flash-report-2026-10-19.json`（上游 commit
`68751e4…`）。

| 项 | 值 |
|---|---|
| 芯片 | ESP32-S3 |
| 物理 Flash | 16 MB |
| PSRAM | 8 MB（OPI） |
| 上游固件分区布局 | 8 MB（`PartitionScheme=default_8MB`） |
| 上游 Arduino-ESP32 core | 3.3.12 |
| NimBLE-Arduino（上游） | 2.5.1 |
| USB HID 枚举 | 上游记录 `USBHIDEnumerated: true` |
| BLE 名称（上游） | `ESP32 Agent 1BC5`（板子标识后缀 `1bc5`） |
| 上游烧录端口 | COM8；运行串口 COM10（**本检查点未使用任何 COM 口**） |
| AirPlay + iPhone 实测 | `AirPlayWithIPhoneTested: false`（上游也未测通） |

**注意**：上游"USB HID 枚举成功"是其 iPhone 固件的结果，**不是**安卓固件的证据。
本项目的安卓固件尚不存在。

## 2. 恢复备份（只读，不得删除）

```
esp32-iphone-agent/hardware/tools/out/backups/
  esp32s3-9c139e8a1bc4-before.bin     16,777,216 B（16 MB 全量镜像，烧录前备份）
  esp32s3-9c139e8a1bc4-before.json       294 B（同一份报告的元数据）
```

恢复方式（**待用户执行，Pi 不执行**）：用 `esptool` 以 16 MB 布局写回该 `.bin`，
或按上游 `hardware/flash.sh` 的流程回滚。恢复前必须确认 Flash 大小与分区表一致，
不要"用满 16 MB"式改分区。

## 3. 本项目固件目标规划（未实现）

```text
firmware/
  src/                 # 安卓固件：BLE GATT 控制 + USB HID，不链接 AirPlay
  tests/               # 主机侧 C++ 测试（复用 protocol/vectors/*.json）
  board-profiles/      # ESP32-S3 / 16MB flash / 8MB PSRAM / USB-OTG 配置
```

首版固件必须满足：

1. 独立 BLE 服务 UUID（`50e00000-…`），不复用上游 iOS 服务语义。
2. 未完成认证与动作协议前**默认拒绝所有输入**（开机即不可控制，而不是可控制后再收紧）。
3. 不启动 Wi-Fi、AirPlay、视频通道。
4. 动作状态机非阻塞，任何步骤都能被 stop/心跳/USB 异常打断。
5. HID 写入口统一走认证会话，不保留旧裸 BLE 输入旁路。

## 4. 与手机侧的关系

- 板子作为 USB HID 鼠标/键盘；手机侧不设品牌白名单，按系统能力自检。
- App 的 `ReadinessProbe` 在无 BLE 硬件（如 MuMu 模拟器）时显示"蓝牙不可用"并保持控制关闭。
- 当前无安卓真机，OTG 供电、USB 枚举、校准精度、停止时延全部**未测**。
