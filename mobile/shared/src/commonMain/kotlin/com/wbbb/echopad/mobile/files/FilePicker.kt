package com.wbbb.echopad.mobile.files

import androidx.compose.runtime.Composable

/**
 * 用系统的文件选择器选要发给电脑端的文件，可以多选（docs/prd/send-files.md）。返回打开选择器的函数，平台还不支持时（iOS 版还没有）为 null。
 * 选好的文件打开以后交给 [onPicked]，有文件读不了时调用 [onUnreadable]；用户没选就什么都不调用。
 */
@Composable
expect fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit, onUnreadable: () -> Unit): (() -> Unit)?
