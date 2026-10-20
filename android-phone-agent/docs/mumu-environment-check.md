# MuMu 开发测试环境检查

检查日期：2026-10-20。检查者：Codex。只涉及开发测试环境，不是产品运行依赖。

- MuMu CLI：`D:/Program Files/Netease/MuMu/nx_main/mumu-cli.exe`，版本 `6.8.2.0`。
- ADB：同目录 `adb.exe`，版本 `1.0.41` / build `36.0.0-13206524`。
- 已启动现有实例 `0`（MuMu安卓设备），未新建、重置或更改实例配置。
- 实例调试地址：`127.0.0.1:16384`；ADB 同时列出 `emulator-5554`，开发操作应始终指定已核实的地址，避免选错目标。
- 实测 `ro.build.version.sdk=35`、`ro.build.version.release=15`。
- 实测 ABI 列表：`x86_64,arm64-v8a,x86`。
- 实测 `sys.boot_completed=1`。
- `pm list features` 宣告普通 Bluetooth、USB host、输入法能力，但此次输出没有 `android.hardware.bluetooth_le`；这些标记不能证明真实 BLE 射频或 USB HID 外设透传可用。

执行过的主要命令（Windows PowerShell）：

```powershell
$cli = 'D:\Program Files\Netease\MuMu\nx_main\mumu-cli.exe'
$adb = 'D:\Program Files\Netease\MuMu\nx_main\adb.exe'
& $cli info --vmindex all
& $cli control --vmindex 0 launch
& $cli adb --vmindex 0 --cmd connect
& $adb devices -l
& $adb -s '127.0.0.1:16384' shell getprop ro.build.version.sdk
& $adb -s '127.0.0.1:16384' shell getprop ro.build.version.release
& $adb -s '127.0.0.1:16384' shell getprop ro.product.cpu.abilist
& $adb -s '127.0.0.1:16384' shell getprop sys.boot_completed
& $adb -s '127.0.0.1:16384' shell pm list features
```

已从 Actions 39363733573 下载并安装 dev2，App 在 Android15/API35 成功启动。完成首页未就绪提示、测试页假设备正常单步与执行前停止检查；设置页 SECURE 窗口标志及黑屏截图通过。证据位于本机 `.supervision/artifacts/39363733573/`（不提交源码库）。

UIAutomator dump 进程 exit139，未获得XML；App仍运行。未启用无障碍或Root，没有输入模型Key/绑定凭证。MediaProjection、IME尚未实现；Keystore、权限拒绝/撤销还未做运行测试。后续最终APK的复验结果见 `codex-verification.md`。

ESP32 的 BLE、USB 枚举、校准、实际输入/释放以及停止时延仍需安卓真机。开发期间使用 ADB 安装和调试 APK，不允许将 ADB 变成产品的截图、Agent 或输入执行路线。
