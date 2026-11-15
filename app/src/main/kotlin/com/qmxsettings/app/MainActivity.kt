package com.qmxsettings.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.qmxsettings.app.ui.App
import com.qmxsettings.app.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(vm) }
    }

    override fun onResume() {
        super.onResume()
        // A radio may have been plugged in: refresh the list only (never auto-connect).
        vm.refreshPorts()
    }
}
