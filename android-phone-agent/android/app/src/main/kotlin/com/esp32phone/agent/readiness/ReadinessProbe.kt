package com.esp32phone.agent.readiness

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.esp32phone.agent.data.AppPreferences
import com.esp32phone.agent.data.SecretStore

public enum class CapabilityState {
    /** Verified working. Only set when the feature is actually implemented and the check passed. */
    READY,

    /** Implemented but a required user action/permission is missing. */
    NEEDS_ACTION,

    /** Environment cannot support it (hardware/profile/permission denied for good). */
    UNAVAILABLE,

    /** This build does not contain the feature at all. Never shown as success. */
    NOT_IMPLEMENTED,
}

public data class Capability(
    val key: String,
    val title: String,
    val state: CapabilityState,
    val detail: String,
)

public data class ReadinessReport(
    val capabilities: List<Capability>,
    val canLaunchTask: Boolean,
    val blockingReasons: List<String>,
)

/**
 * Real, on-device capability probe.
 *
 * Every value comes from an actual platform query; anything not implemented in this build is
 * reported as [CapabilityState.NOT_IMPLEMENTED] and keeps [ReadinessReport.canLaunchTask]
 * false. There is no "assume ok" branch anywhere in this file.
 */
public object ReadinessProbe {

    public fun probe(context: Context, preferences: AppPreferences, secrets: SecretStore): ReadinessReport {
        val capabilities = buildList {
            add(bluetooth(context))
            add(deviceBinding(context, preferences, secrets))
            add(screenCapture())
            add(modelKey(context, preferences, secrets))
            add(inputMethod(context))
            add(calibration(context, preferences))
        }
        val blocking = capabilities
            .filter { it.state != CapabilityState.READY }
            .map { "${it.title}：${it.state.label()}（${it.detail}）" }
        return ReadinessReport(
            capabilities = capabilities,
            canLaunchTask = blocking.isEmpty(),
            blockingReasons = blocking,
        )
    }

    private fun CapabilityState.label(): String = when (this) {
        CapabilityState.READY -> "就绪"
        CapabilityState.NEEDS_ACTION -> "需要操作"
        CapabilityState.UNAVAILABLE -> "不可用"
        CapabilityState.NOT_IMPLEMENTED -> "未实现"
    }

    private fun bluetooth(context: Context): Capability {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        return when {
            !hasFeature ->
                Capability(
                    "ble",
                    "蓝牙 BLE",
                    CapabilityState.UNAVAILABLE,
                    "设备未声明 android.hardware.bluetooth_le；控制将保持关闭",
                )
            adapter == null ->
                Capability("ble", "蓝牙 BLE", CapabilityState.UNAVAILABLE, "系统没有蓝牙适配器")
            missing.isNotEmpty() ->
                Capability(
                    "ble",
                    "蓝牙 BLE",
                    CapabilityState.NEEDS_ACTION,
                    "缺少权限：" + missing.joinToString { it.substringAfterLast('.') },
                )
            !runCatching { adapter.isEnabled }.getOrDefault(false) ->
                Capability("ble", "蓝牙 BLE", CapabilityState.NEEDS_ACTION, "请确认蓝牙已打开且连接权限可用")
            else ->
                Capability(
                    "ble",
                    "蓝牙 BLE",
                    CapabilityState.NOT_IMPLEMENTED,
                    "适配器就绪，但本构建未包含 BLE 扫描/连接/认证实现",
                )
        }
    }

    private fun deviceBinding(
        context: Context,
        preferences: AppPreferences,
        secrets: SecretStore,
    ): Capability {
        val board = preferences.boundBoardId
        return when {
            !secrets.hasBindingSecret() ->
                Capability(
                    "binding",
                    "板子绑定",
                    CapabilityState.NEEDS_ACTION,
                    "尚未导入该板子的绑定凭证（每台板子独立）",
                )
            board.isEmpty() ->
                Capability("binding", "板子绑定", CapabilityState.NEEDS_ACTION, "已保存凭证但未记录板子 ID")
            else ->
                Capability(
                    "binding",
                    "板子绑定",
                    CapabilityState.NOT_IMPLEMENTED,
                    "已保存 $board 的凭证；握手与 USB HID 通路尚未实现",
                )
        }
    }

    private fun screenCapture(): Capability = Capability(
        "projection",
        "录屏授权",
        CapabilityState.NOT_IMPLEMENTED,
        "本构建未包含 MediaProjection 前台服务，不能截图",
    )

    private fun modelKey(context: Context, preferences: AppPreferences, secrets: SecretStore): Capability {
        val endpoint = preferences.endpointOverride.ifEmpty { BuildConfigEndpoint.DEFAULT }
        val model = preferences.modelOverride.ifEmpty { BuildConfigEndpoint.MODEL }
        val hasKey = secrets.hasApiKey()
        return when {
            !hasKey -> Capability("model", "模型 Key", CapabilityState.NEEDS_ACTION, "请在设置中填写用户 Key")
            endpoint.isEmpty() -> Capability(
                "model",
                "模型 Key",
                CapabilityState.NEEDS_ACTION,
                "已保存 Key，但网关地址未配置（本构建未预置发布配置）",
            )
            model.isEmpty() ->
                Capability("model", "模型 Key", CapabilityState.NEEDS_ACTION, "已保存 Key，但模型名未配置")
            else -> Capability(
                "model",
                "模型 Key",
                CapabilityState.NOT_IMPLEMENTED,
                "凭证与配置就绪；模型请求客户端尚未实现",
            )
        }
    }

    private fun inputMethod(context: Context): Capability {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ).orEmpty()
        val ours = "${context.packageName}/.ime.AgentImeService"
        return if (enabled.contains(ours)) {
            Capability("ime", "中文输入法", CapabilityState.NOT_IMPLEMENTED, "已选中本输入法，但本构建不含 IME 服务")
        } else {
            Capability(
                "ime",
                "中文输入法",
                CapabilityState.NOT_IMPLEMENTED,
                "本构建未提供可选输入法；中文输入不可用",
            )
        }
    }

    private fun calibration(context: Context, preferences: AppPreferences): Capability =
        if (preferences.calibrationValid) {
            Capability("calibration", "坐标校准", CapabilityState.NOT_IMPLEMENTED, "标记为已校准，但校准与 HID 通路未实现")
        } else {
            Capability("calibration", "坐标校准", CapabilityState.NEEDS_ACTION, "尚未校准；坐标动作被禁用")
        }
}

/** Release gateway configuration is intentionally empty until real values are supplied. */
public object BuildConfigEndpoint {
    public val DEFAULT: String = com.esp32phone.agent.BuildConfig.DEFAULT_ENDPOINT
    public val MODEL: String = com.esp32phone.agent.BuildConfig.DEFAULT_MODEL
}
