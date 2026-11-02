package com.quickfiles.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.quickfiles.app.storage.Incoming
import com.quickfiles.app.storage.StorageAccess
import com.quickfiles.app.ui.ExplorerViewModel
import com.quickfiles.app.ui.PendingTransfer
import com.quickfiles.app.ui.PickRequest
import com.quickfiles.app.ui.WorkspaceScreen
import com.quickfiles.app.ui.theme.GotganTheme

class MainActivity : ComponentActivity() {
    private var hasStorageAccess by mutableStateOf(false)
    private var settingsUnavailable by mutableStateOf(false)
    private lateinit var models: List<ExplorerViewModel>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed before composition: a recreated Activity must not briefly revoke an existing grant.
        hasStorageAccess = Environment.isExternalStorageManager()
        models = listOf("A", "B").map { tab ->
            ViewModelProvider(this, viewModelFactory { initializer { ExplorerViewModel(application, createSavedStateHandle(), tab) } })[tab, ExplorerViewModel::class.java]
        }
        enableEdgeToEdge()
        setContent {
            GotganTheme {
                WorkspaceScreen(
                    first = models[0],
                    second = models[1],
                    hasPermission = hasStorageAccess,
                    settingsUnavailable = settingsUnavailable,
                    onSettings = {
                        settingsUnavailable = !StorageAccess.openSettings(this)
                    },
                )
            }
        }
        // A picker request stays in force across recreation; received files are copied only once.
        preparePicker(intent)
        if (savedInstanceState == null) receive(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        preparePicker(intent)
        receive(intent)
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

    private fun activeIndex() = AppGraph.get(this).workspace.state.value.active

    /** ACTION_GET_CONTENT: browsing works as usual, but tapping a file returns it to the asking app. */
    private fun preparePicker(intent: Intent?) {
        if (intent?.action != Intent.ACTION_GET_CONTENT) { models.forEach { it.picker = null }; return }
        val types = (intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOfNotNull(intent.type)).filter { it.isNotBlank() }
        val request = PickRequest(types, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), deliver = ::deliverPicked, cancel = {
            setResult(RESULT_CANCELED); finish()
        })
        models.forEach { it.picker = request }
    }

    private fun deliverPicked(uris: List<Uri>) {
        if (uris.isEmpty() || isFinishing) return
        val result = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) result.data = uris.single()
        result.clipData = ClipData.newUri(contentResolver, "곳간", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        setResult(RESULT_OK, result)
        finish()
    }

    /** Share ("곳간에 저장") asks where to save; "open with 곳간" shows the file in the built-in viewer. */
    private fun receive(intent: Intent?) {
        intent ?: return
        val owner = activeIndex()
        val model = models[owner]
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (intent.action == Intent.ACTION_SEND) listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
                val items: List<Incoming> = streams.map { Incoming.Content(it) }.ifEmpty {
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { listOf(Incoming.Text(it, intent.getStringExtra(Intent.EXTRA_SUBJECT))) }.orEmpty()
                }
                if (items.isEmpty()) { model.notice("받은 내용이 없습니다."); return }
                model.receive(items, "곳간에 저장") { locations ->
                    AppGraph.get(this).workspace.prepare(PendingTransfer(owner, locations, move = false, received = true))
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                model.receive(listOf(Incoming.Content(uri)), "곳간으로 열기") { locations -> model.openReceived(locations.single()) }
            }
        }
    }
}
