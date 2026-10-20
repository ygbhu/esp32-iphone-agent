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

当前未获得本项目 APK，没有执行应用安装、界面、MediaProjection 或 IME 验证，也没有读取模拟器中的用户聊天/账户。后续由用户的云端 CI 生成 APK 后再安装测试，不在本机安装 Android 平台 SDK。

待执行：CI APK 安装/启动、中文界面、能力缺失提示、Key 设置、权限拒绝及撤销、MediaProjection 生命周期、IME 启用与调试编辑框。每项以实际实现和执行结果更新，不因环境连接成功而视为通过。

ESP32 的 BLE、USB 枚举、校准、实际输入/释放以及停止时延仍需安卓真机。开发期间使用 ADB 安装和调试 APK，不允许将 ADB 变成产品的截图、Agent 或输入执行路线。
