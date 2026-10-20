# Codex 第二轮核心复核

2026-10-20。110项测试绿色，但静态核验仍发现下列路径。仅为开发基础版，不通过真实输入放行。

## 需修复

1. AgentCore旧device异常回包仍可调用authority.stop/pause并撤销新generation；旧正常回包的stale分支也改state、pending及policy。复现：execute里先stop、再beginTask(new)，最后return Done或throw。旧回包应仅返回Stale，不写新任务任何状态（包括lastError）。旧screen抛异常/返回null也要先校验请求generation。model/verifier stale helper同样不应写新lastError。
2. revalidate在调用screen前取clock并检查bound帧，screen返回后只检查几何。最后一次screen hook推进clock2000ms再返回同几何帧，可放行过期输入。每次外部调用后重取clock、检查current及bound帧新鲜度/epoch/rotation/size；过期或未来current不得授权动作。新增精确回归。
3. InputPermit只isCurrent存在check-then-act竞态：最后检查通过→STOP撤销/释放→旧OUTPUT。应提供与authority.stop共同同步的本地提交临界区（例如withCurrentPermit或tryDispatch），sink必须在此边界提交输出；不要把App本地撤销夸大为板子在尚未收到STOP前已经停止。当前fake在检查和OUTPUT之间有空隙，须加barrier验证此交错。令牌应绑定动作且不可重复提交（或清楚定义用于多个短HID报告的可撤销permit，不误称single-use）。
4. 100组合matrix的VERIFY phase使用tap决策、无verifier hook，是虚覆盖；应实际调用Done+verifier，记录目标phase确实到达。名为late stop保护新policy的测试没有旧回调，应补真正旧任务回返的流程。测试按revoke与output顺序断言，别以绿色数量代替覆盖。
5. StrictJson.parseObject(String)对原始孤立UTF16 surrogate仍直接append；wire ByteArray路径已严格UTF8。统一String入口，补原始孤立surrogate测试；文档写清规范片数/固定flags/每片精确长度/到片超时规则。Fragmenter超时诊断先reset再计算elapsed导致时间日志不准。

## 分阶段操作

App骨架/Wrapper/CI齐备后可以先冻结源码，让Codex触发开发版云端编译；所有真实控制保持禁用。以上缺陷须在核心正式放行前修复，若本检查点来不及则明确未完成，不能声称第一轮复核已全通过。云端编译成功只是APK工程验证。
