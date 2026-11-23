package com.wbbb.echopad.mobile.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit, onUnreadable: () -> Unit): (() -> Unit)? {
    val resolver = LocalContext.current.contentResolver
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnUnreadable by rememberUpdatedState(onUnreadable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        // 选好的文件马上打开，之后在后台读完。
        val files = try {
            resolver.openOutgoingFiles(picked)
        } catch (e: Exception) {
            currentOnUnreadable()
            return@rememberLauncherForActivityResult
        }
        currentOnPicked(files)
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
