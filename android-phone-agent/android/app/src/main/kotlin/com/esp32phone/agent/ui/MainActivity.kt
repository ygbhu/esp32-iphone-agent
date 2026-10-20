package com.esp32phone.agent.ui

import android.os.Bundle
import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.esp32phone.agent.ui.theme.AgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                AgentApp()
            }
        }
    }
}

private enum class Tab(val title: String, val glyph: String) {
    HOME("首页", "首"),
    DEVICE("设备", "板"),
    SETTINGS("设置", "设"),
    TASK("任务", "任"),
    DIAGNOSTICS("测试", "测"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApp(viewModel: AgentViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(tab, window) {
        if (tab == Tab.SETTINGS || tab == Tab.DEVICE) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("安卓 Agent · 开发构建") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            viewModel.refresh()
                        },
                        icon = { Text(entry.glyph) },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (tab) {
                Tab.HOME -> HomeScreen(viewModel)
                Tab.DEVICE -> DeviceScreen(viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel)
                Tab.TASK -> TaskScreen(viewModel)
                Tab.DIAGNOSTICS -> DiagnosticsScreen()
            }
        }
    }
}
