package com.quickfiles.app

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quickfiles.app.storage.StorageAccess
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quickfiles.app.ui.ExplorerScreen
import com.quickfiles.app.ui.ExplorerViewModel
import com.quickfiles.app.ui.WorkspaceScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.quickfiles.app.ui.theme.GotganTheme

class MainActivity : ComponentActivity() {
    private var hasStorageAccess by mutableStateOf(false)
    private var settingsUnavailable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed before composition: a recreated Activity must not briefly revoke an existing grant.
        hasStorageAccess = Environment.isExternalStorageManager()
        enableEdgeToEdge()
        setContent {
            GotganTheme {
                WorkspaceScreen(
                    first = viewModel<ExplorerViewModel>(key = "A", factory = viewModelFactory { initializer { ExplorerViewModel(application, createSavedStateHandle(), "A") } }),
                    second = viewModel<ExplorerViewModel>(key = "B", factory = viewModelFactory { initializer { ExplorerViewModel(application, createSavedStateHandle(), "B") } }),
                    hasPermission = hasStorageAccess,
                    settingsUnavailable = settingsUnavailable,
                    onSettings = {
                        settingsUnavailable = !StorageAccess.openSettings(this)
                    },
                )
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    override fun onResume() {
        super.onResume()
        // A special permission is granted/revoked outside the app, not through a runtime dialog.
        hasStorageAccess = Environment.isExternalStorageManager()
        if (hasStorageAccess) settingsUnavailable = false
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) RecentsCoordinator.foreground(this)
    }
}
