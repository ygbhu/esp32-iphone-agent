# Codex 独立验证记录

2026-10-20。Pi 实现基础核心，Codex 复核、修正 App 和监督云端构建。

## 云端与 APK

- 父仓库：https://github.com/ygbhu/esp32-iphone-agent ，开发分支 `codex/android-agent-foundation`；原 iPhone 内容保留，未合并主分支、未发布 Release。
- Actions 39363733573 成功构建 dev2 APK，云端第一检查点 110 项测试通过；下载后在 MuMu Android15/API35 安装、启动成功。
- 已修正 CI 对过期 Android tools 包的依赖、Kotlin setter 签名冲突，以及 Android14+ 读取受限 IME 设置导致的启动崩溃（改用 InputMethodManager 公共 API）。
- 本机只使用 JDK 跑纯 Kotlin 测试，未安装 Android SDK；APK 始终使用 GitHub Actions 构建。
- 每次 CI debug 签名临时生成，目前跨 run 安装需卸载无数据的开发包；发布前仍需固定签名和升级验证。

## MuMu dev2 实测

- 首页显示未就绪项，真实控制按钮关闭；测试页明确是假模型/假设备。
- 正常单步：Executed，VERIFYING，假输出1；执行前停止：Stale，STOPPED，假输出0。
- 初次截图保护断言取错页面（仍为测试页），不算通过。重新切设置页后 dumpsys window 确认 SECURE，ADB 截图全黑；已保存 mumu-settings-actual.png。此结论仅限当前 MuMu。
- UIAutomator dump 工具 exit139，无 UI XML；App 持续运行。没有启用无障碍、Root 或输入真实 Key/绑定凭证；Keystore 读写尚未实测。

## 核心检查点

- 第一检查点 Codex 独立跑110项通过。第二检查点 Pi 报告133项通过并执行7类变异检查，见 checkpoint-2-report.md。
- 最后审查发现慢 requestStop 返回覆盖新任务、旧 step 领取新 generation permit 两项残留。Pi 收到任务后上游模型服务返回403，未完成修复；Codex接手，补任务身份检查、锁内 expectedGeneration 校验、sink generation 校验与4项回归。
- Codex 独立重跑完整 :core:test --rerun-tasks：137 tests / 0 failures / 0 errors / 0 skipped，exit0；新增四项回归全部通过。最终云端结果待该版本构建完成补录；真实控制不放行。
- EventLog listener 在提交锁外，不能据此宣称完整并发线性顺序已被证明；AgentCore 后续接入需固定调度并验证任务生命周期。当前锁只保证本地 permit 提交与撤权互斥，不能证明板子收到 STOP 前已停止。

## 未实现与未验收

MediaProjection、BLE收发、ESP32固件/USB HID、模型HTTP、可选IME和校准尚未实现；无安卓真机，没有硬件停止时延证据。未调用真实模型、未刷写板子、未写参考检出或原始Flash备份。静态 manifest 审计检查无无障碍声明、BLE安装特性可选，最终CI会审计合并manifest。
