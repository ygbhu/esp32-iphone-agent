package com.esp32phone.agent.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.esp32phone.agent.AgentApplication
import com.esp32phone.agent.readiness.ReadinessProbe
import com.esp32phone.agent.readiness.ReadinessReport

/**
 * Holds only *observed* state. Nothing here assumes a device, a model or a permission is
 * available: every value is refreshed by an actual platform probe.
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AgentApplication

    var readiness: ReadinessReport by mutableStateOf(
        ReadinessProbe.probe(application, app.preferences, app.secrets),
    )
        private set

    var maskedKey: String? by mutableStateOf(app.secrets.maskedApiKey())
        private set

    var boardId: String by mutableStateOf(app.preferences.boundBoardId)
        private set

    var statusMessage: String? by mutableStateOf(null)
        private set

    fun refresh() {
        readiness = ReadinessProbe.probe(getApplication(), app.preferences, app.secrets)
        maskedKey = app.secrets.maskedApiKey()
        boardId = app.preferences.boundBoardId
    }

    fun saveKey(value: String) {
        statusMessage = if (app.secrets.saveApiKey(value)) {
            "已加密保存 Key（明文不写入日志/备份）"
        } else {
            "Key 为空或保存失败"
        }
        refresh()
    }

    fun clearKey() {
        app.secrets.clearApiKey()
        statusMessage = "已清除 Key"
        refresh()
    }

    fun importBindingSecret(hex: String) {
        val bytes = runCatching {
            require(hex.length == 64) { "绑定凭证必须是 64 个十六进制字符（32 字节）" }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "绑定凭证仅允许十六进制字符"
            }
            ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrElse {
            statusMessage = "凭证格式错误：${it.message}"
            return
        }
        statusMessage = if (app.secrets.saveBindingSecret(bytes)) {
            "已保存绑定凭证（尚未验证：握手未实现）"
        } else {
            "绑定凭证加密保存失败，未完成绑定"
        }
        refresh()
    }

    fun saveBoardId(value: String) {
        app.preferences.boundBoardId = value
        boardId = app.preferences.boundBoardId
        refresh()
    }

    fun clearDeviceBinding() {
        app.secrets.clearBindingSecret()
        app.preferences.boundBoardId = ""
        statusMessage = "已清除本机保存的绑定凭证"
        refresh()
    }

    fun clearStatus() {
        statusMessage = null
    }
}
