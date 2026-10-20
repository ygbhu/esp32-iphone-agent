package com.esp32phone.agent

import android.app.Application
import com.esp32phone.agent.data.AppPreferences
import com.esp32phone.agent.data.SecretStore

/**
 * Application entry point.
 *
 * Deliberately does *nothing* automatically: no screen capture, no BLE scan, no task resume.
 * Every privileged action needs an explicit user gesture inside an Activity (baseline §13).
 */
class AgentApplication : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var secrets: SecretStore
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        secrets = SecretStore(this)
    }
}
