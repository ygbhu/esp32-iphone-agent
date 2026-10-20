package com.esp32phone.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.esp32phone.agent.agent.AgentBudget
import com.esp32phone.agent.agent.AgentCore
import com.esp32phone.agent.agent.AgentState
import com.esp32phone.agent.agent.DeviceOutcome
import com.esp32phone.agent.agent.StepResult
import com.esp32phone.agent.agent.StopController
import com.esp32phone.agent.agent.UnavailableVerifier
import com.esp32phone.agent.harness.FakeDevice
import com.esp32phone.agent.harness.FakeModel
import com.esp32phone.agent.harness.FakeScreenSource
import com.esp32phone.agent.harness.ManualClock
import com.esp32phone.agent.harness.tapAction
import com.esp32phone.agent.readiness.Capability
import com.esp32phone.agent.readiness.CapabilityState

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun StatusPill(state: CapabilityState) {
    val color = when (state) {
        CapabilityState.READY -> Color(0xFF2E7D32)
        CapabilityState.NEEDS_ACTION -> Color(0xFFE65100)
        CapabilityState.UNAVAILABLE -> Color(0xFFB71C1C)
        CapabilityState.NOT_IMPLEMENTED -> Color(0xFF455A64)
    }
    val label = when (state) {
        CapabilityState.READY -> "就绪"
        CapabilityState.NEEDS_ACTION -> "需要操作"
        CapabilityState.UNAVAILABLE -> "不可用"
        CapabilityState.NOT_IMPLEMENTED -> "未实现"
    }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(color, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun CapabilityCard(capability: Capability) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(capability.title, style = MaterialTheme.typography.titleSmall)
                StatusPill(capability.state)
            }
            Text(capability.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun HomeScreen(viewModel: AgentViewModel) {
    val report = viewModel.readiness
    SectionTitle("真实就绪状态")
    Text(
        "这些是当前设备/构建的实际状态，不是演示。未实现的能力保持关闭。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    report.capabilities.forEach { CapabilityCard(it) }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (report.canLaunchTask) "可以开始任务" else "无法开始任务（${report.blockingReasons.size} 项阻塞）",
                style = MaterialTheme.typography.titleSmall,
            )
            if (!report.canLaunchTask) {
                Text(
                    "任务执行保持禁用：本构建尚未实现截图、BLE 与 HID 通路。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { viewModel.refresh() },
                enabled = true,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("重新检测") }
            Button(
                onClick = { },
                enabled = report.canLaunchTask,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("开始任务") }
        }
    }
}

@Composable
fun DeviceScreen(viewModel: AgentViewModel) {
    SectionTitle("设备绑定")
    Text(
        "每台板子有独立的 32 字节绑定凭证；模型 Key 与它分开保存。生产环境经二维码交付，开发期经 USB 读取。",
        style = MaterialTheme.typography.bodySmall,
    )
    var secret by remember { mutableStateOf("") }
    var board by remember { mutableStateOf(viewModel.boardId) }

    OutlinedTextField(
        value = board,
        onValueChange = { board = it },
        label = { Text("板子 ID（例如 esp32phone-1bc5）") },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it },
        label = { Text("绑定凭证（64 位十六进制）") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = {
            viewModel.saveBoardId(board)
            if (secret.isNotBlank()) viewModel.importBindingSecret(secret.trim())
            secret = ""
        }) { Text("保存") }
        OutlinedButton(
            onClick = { viewModel.clearDeviceBinding() },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("清除绑定") }
    }
    Text(
        "注意：保存凭证不等于已认证。BLE 握手、能力协商与 USB HID 通路尚未实现，控制保持禁用。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    viewModel.statusMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SettingsScreen(viewModel: AgentViewModel) {
    SectionTitle("模型与网关")
    Text(
        "Key 使用 Android Keystore 加密保存；明文不进入日志、备份或截图。发布配置尚未提供，下面的地址/模型为开发用。",
        style = MaterialTheme.typography.bodySmall,
    )

    Text(
        "当前 Key：" + (viewModel.maskedKey ?: "未设置"),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    var key by remember { mutableStateOf("") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("用户 Key（写入后清空输入框）") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = {
            viewModel.saveKey(key)
            key = ""
        }) { Text("保存 Key") }
        OutlinedButton(
            onClick = { viewModel.clearKey() },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("清除 Key") }
    }

    var endpoint by remember { mutableStateOf(viewModel.endpoint) }
    OutlinedTextField(
        value = endpoint,
        onValueChange = { endpoint = it },
        label = { Text("网关地址（开发用，可留空）") },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        singleLine = true,
    )
    var model by remember { mutableStateOf(viewModel.model) }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("模型名（开发用，可留空）") },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
    )
    Button(
        onClick = {
            viewModel.saveEndpoint(endpoint)
            viewModel.saveModel(model)
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("保存高级配置") }

    Text(
        "连通性测试按钮将在模型客户端实现后提供；现在不会发出任何网络请求。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    viewModel.statusMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun TaskScreen(viewModel: AgentViewModel) {
    SectionTitle("任务")
    val report = viewModel.readiness
    Text(
        if (report.canLaunchTask) "当前可以执行" else "当前不可执行：需要先满足全部就绪项",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (report.blockingReasons.isNotEmpty()) {
        report.blockingReasons.forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        "单任务、单控制者。停止会先撤销执行代际，迟到响应不得复活输入。任务记录默认只保存动作类型、耗时与分类错误。",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Button(onClick = { }, enabled = false, modifier = Modifier.padding(top = 8.dp)) {
        Text("开始（未实现）")
    }
    Button(onClick = { }, enabled = false, modifier = Modifier.padding(top = 8.dp)) {
        Text("停止（未连接设备）")
    }
}

/**
 * Debug harness: runs the real [AgentCore] against 假模型/假设备 only.
 *
 * This proves the state machine wiring and is labelled accordingly — it is not a phone, not a
 * model and not the firmware, and must never be presented as an end-to-end result.
 */
@Composable
fun DiagnosticsScreen() {
    SectionTitle("测试（假模型 + 假设备）")
    Text(
        "使用内置假模型与假设备驱动同一个 AgentCore，验证停止/迟到响应语义。不会访问网络，也不会产生真实输入。",
        style = MaterialTheme.typography.bodySmall,
    )
    var log by remember { mutableStateOf(listOf<String>()) }

    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = {
            log = runHarness(stopBeforeExecute = false)
        }) { Text("正常单步") }
        Button(
            onClick = { log = runHarness(stopBeforeExecute = true) },
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("执行前停止") }
    }
    Column(modifier = Modifier.padding(top = 12.dp)) {
        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun runHarness(stopBeforeExecute: Boolean): List<String> {
    val clock = ManualClock(1_000)
    val authority = StopController()
    val screen = FakeScreenSource(clock)
    val model = FakeModel(tapAction())
    val device = FakeDevice(clock, authority)
    val core = AgentCore(
        clock = clock,
        screen = screen,
        model = model,
        device = device,
        budget = AgentBudget(),
        authority = authority,
        verifier = UnavailableVerifier,
    )
    val lines = mutableListOf<String>()
    core.beginTask("点击测试按钮", clock.nowMs())
    if (stopBeforeExecute) {
        device.onExecute = { core.stop("用户停止") }
    }
    clock.advance(5)
    val result = core.step(clock.nowMs())
    lines += "步骤结果：${result::class.simpleName}"
    lines += "状态：${core.state.name}"
    lines += "HID 输出次数：${device.outputs.size}（假设备）"
    lines += "停止请求次数：${device.stopCalls}"
    if (result is StepResult.Paused) lines += "暂停原因：${core.pauseReason}"
    if (result is StepResult.Executed) lines += "命令号：${result.commandId}"
    if (core.state == AgentState.PAUSED && core.pauseReason != null) lines += "暂停：${core.pauseReason}"
    if (device.outcome is DeviceOutcome.Unknown) lines += "设备回报未知"
    return lines
}
