# Codex 第一检查点复核要求

日期：2026-10-20。这是实现期间快照的发现，尚非最终验收。Pi 请先修复并补回归测试，再继续检查点交付。不得只改文档把失败写成通过。

## 协议（主代理抽查已确认）

1. `HandshakeGate.onPlaintextFragment` 在 `PROTECTED_ONLY` 仍可接受明文 ACTION：依次 `[2,1] [2,2] [2,3] [2,30]` 最后必须拒绝。普通 protected 消息也不能在 auth_ok 前放行。门控只管阶段不等于验证 proof；实际认证仍须密码校验。此前 expectOrder 编译问题似已修复，重新构建确认。
2. `FragmentReassembler.accumulateOffset` 使用理论容量，短中间片会零填充：MTU23、count2、total13，首片只带0x41、末片只带0x42，当前可组装成41+11个零+42。必须拒绝非规范短片，或按实际累计长度严格收齐；绑定组装 flags，不得中途明文/密文混用；后续片到达时也执行超时检查，不能全依赖外部 tick。
3. `StrictJson.parseString` 的 `toIntOrNull(16)` 接受带符号的四字符：`\u+041`、`\u-001` 必须拒绝。明确并一致处理孤立 surrogate、非法 UTF-8，不允许跨端解释不同。
4. 增加同一个 Envelope receiver 的“篡改的高序号包失败，再接收合法低序号包仍成功”测试，证明失败不推进安全序号。校验 role/channel/session 混用拒绝。

## Agent（主代理抽查已确认）

5. `AgentCore.step(nowMs)` 把调用开始时间传到执行边界。模型回调里推进时钟600001ms再返回动作，旧帧和预算仍可通过。每次外部调用回来后必须重新取时钟，执行前读取当前可验证观察，验证epoch/尺寸/方向/新鲜度与原决定一致。模型期间旋转必须暂停。
6. `resumeAfterPause` 调 `budget.start` 清空预算，反复恢复可绕过任务时长/调用/动作/连续失败限制。任务恢复保留预算，与新任务初始化分开。
7. `verifier.verify` 过程中调用 `core.stop` 再返回Satisfied，目前会覆盖STOPPED为COMPLETED。所有外部调用后的成功/异常分支先校验generation，不能修改已停止或更新一代任务的状态。
8. `Accepted/Started` 不能等同 `Done`；保持单动作在途，未确认最终结果不能开始下一条输入。可以保守暂停查询，不能谎报执行完成。
9. `beginTask` 不可在同一设备会话里把commandId重置到1。保证会话内递增，真正新设备会话才重置；未知结果不得重发。
10. 预算耗尽当前仅改变state，必须撤销执行权并释放设备；其他停止/异常路径同样审计。新任务/恢复不得让旧回调使新任务policy失效。执行前检查和真正设备输出间需有明确的generation令牌契约，不能仅依赖FakeDevice帮核心兜底。

## 时序测试真实性

11. 现有100次循环多为同步时钟推进；`deviceDelayMs` 未用于执行，before/during device走同一hook；按 `output.generation >= stopGeneration` 判断会漏掉“STOP后输出旧generation”的危险情况。记录 revoke/execute/output/ack 事件顺序，按输出发生在撤销前后断言；使用CountDownLatch/barrier等可复现交错，覆盖模型、验证、执行入口、设备输出和回执各阶段。测试要能在错误实现上失败，不以随机循环次数冒充并发验证。

修复后把实际跑过的命令、退出码、测试数、尚未执行的CI/APK/真机验证写入 verification。代码和测试是复核重点。
