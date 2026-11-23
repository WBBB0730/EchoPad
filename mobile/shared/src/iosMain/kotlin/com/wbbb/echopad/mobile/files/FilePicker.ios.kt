package com.wbbb.echopad.mobile.files

import androidx.compose.runtime.Composable

// iOS 版再做发送文件（docs/prd/send-files.md）。
@Composable
actual fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit, onUnreadable: () -> Unit): (() -> Unit)? = null
