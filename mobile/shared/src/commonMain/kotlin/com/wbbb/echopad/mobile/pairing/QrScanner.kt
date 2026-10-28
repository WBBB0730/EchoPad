package com.wbbb.echopad.mobile.pairing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 用相机扫描电脑端的配对码。没有相机权限或相机不可用时调用 [onUnavailable]。 */
@Composable
expect fun QrScanner(
    onScanned: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
)
