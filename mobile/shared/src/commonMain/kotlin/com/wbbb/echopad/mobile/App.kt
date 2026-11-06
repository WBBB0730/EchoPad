package com.wbbb.echopad.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.pairing.PairingScreen
import com.wbbb.echopad.mobile.touchpad.TouchpadScreen
import com.wbbb.echopad.mobile.ui.EchoPadColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** [pairingLinks] 是从系统打开的 `echopad://pair` 链接，例如用系统相机扫码。 */
@Composable
fun App(platform: Platform, pairingLinks: Flow<String> = emptyFlow()) {
    val viewModel = viewModel { EchoPadViewModel(platform) }
    val state by viewModel.connection.state.collectAsStateWithLifecycle()
    val touchpadSettings by viewModel.touchpadSettings.collectAsStateWithLifecycle()
    val update = viewModel.updater?.state?.collectAsStateWithLifecycle()?.value
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel, pairingLinks) { pairingLinks.collect(viewModel::onPairingLink) }
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(it) } }
    // 只在 App 处于前台时检查更新（docs/prd/in-app-update.md）。
    LaunchedEffect(viewModel, lifecycle) {
        val updater = viewModel.updater ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { updater.checkPeriodically() }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = EchoPadColors.Background)) {
        Scaffold(
            containerColor = EchoPadColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (state) {
                    ConnectionState.Starting -> Unit
                    ConnectionState.Unpaired -> PairingScreen(
                        onScanned = viewModel::onPairingLink,
                        onScanUnavailable = { viewModel.notify("无法使用相机扫码") },
                    )
                    ConnectionState.Connecting, is ConnectionState.Connected, is ConnectionState.VersionMismatch -> TouchpadScreen(
                        state = state,
                        connection = viewModel.connection,
                        dictation = viewModel.dictation,
                        keyboard = viewModel.keyboard,
                        settings = touchpadSettings,
                        onSettingsChange = viewModel::setTouchpadSettings,
                        onScanned = viewModel::onPairingLink,
                        onNotice = viewModel::notify,
                        update = update,
                        onCheckForUpdates = viewModel::checkForUpdates,
                        onApplyUpdate = viewModel::applyUpdate,
                    )
                }
            }
        }
    }
}
