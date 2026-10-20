# Codex 独立验证记录

2026-10-20，第一开发基础版。Pi 实现，Codex 复核与云端构建监督。

- Codex 独立执行 `android/gradlew.bat -p android :core:test --rerun-tasks --no-daemon --console=plain`，退出码0，110项测试无失败。Windows Kotlin daemon 曾报告增量缓存错误，Gradle自动退回编译后成功；后续以云端干净环境验证APK。
- 执行 `python ci/audit-manifest.py`，退出码0。解析真实XML属性，忽略注释；源manifest无无障碍绑定/声明，BLE安装要求为可选，App无进程执行路径。云端须在构建后再次审计合并manifest。
- Codex补充：Key/配对凭证输入遮罩、设备/设置页FLAG_SECURE、修改endpoint清除旧Key、配对凭证严格hex与保存错误反馈、蓝牙权限检查优先于isEnabled。
- 开发APK：待GitHub Actions构建；尚未进行应用安装测试。
- MuMu：已启动并连接，Android15/API35，详见mumu-environment-check.md。模拟器环境可用不代表产品功能通过。
- 第二轮核心缺陷仍见codex-checkpoint-review-2.md；本基础版不放行真实控制。固件、BLE收发、MediaProjection、模型HTTP客户端、IME与校准未实现。

云端工作流放在父仓库 `.github/workflows/android-agent.yml`，通过独立分支运行；不会合并主分支。参考检出和原始Flash备份未写入或刷写。
