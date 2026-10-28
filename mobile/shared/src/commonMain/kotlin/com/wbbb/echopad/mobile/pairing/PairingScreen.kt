package com.wbbb.echopad.mobile.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wbbb.echopad.mobile.ui.EchoPadColors

@Composable
fun PairingScreen(onScanned: (String) -> Unit, onScanUnavailable: () -> Unit) {
    var scanning by rememberSaveable { mutableStateOf(false) }

    if (scanning) {
        Box(Modifier.fillMaxSize()) {
            QrScanner(
                onScanned = {
                    scanning = false
                    onScanned(it)
                },
                onUnavailable = {
                    scanning = false
                    onScanUnavailable()
                },
                modifier = Modifier.fillMaxSize(),
            )
            TextButton(
                onClick = { scanning = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            ) {
                Text("取消", color = EchoPadColors.Text)
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("EchoPad", style = MaterialTheme.typography.headlineMedium, color = EchoPadColors.Text)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { scanning = true }) {
                Text("扫码配对")
            }
        }
    }
}
