package com.wbbb.echopad.mobile.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.wbbb.echopad.mobile.ui.EchoPadColors

/** 设置面板最下面一行：版本号与检查更新。已有可以安装的更新时写"立即更新"；检查或下载期间只置灰，不换文字。 */
@Composable
fun AboutRow(state: UpdateState, onCheck: () -> Unit, onUpdate: () -> Unit) {
    val busy = state.phase == UpdatePhase.Checking || state.phase == UpdatePhase.Downloading
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "版本 ${state.currentVersion}",
            color = EchoPadColors.Text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = if (state.prompt) onUpdate else onCheck, enabled = !busy) {
            Text(
                if (state.prompt) "立即更新" else "检查更新",
                color = if (busy) EchoPadColors.TextMuted else EchoPadColors.Text,
            )
        }
    }
    if (state.phase == UpdatePhase.Failed) {
        Text("更新失败，请重试", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 更新弹窗：列出当前版本之后、到新版本为止的更新日志，确认后才安装（docs/prd/in-app-update.md）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(state: UpdateState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EchoPadColors.Surface) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                "EchoPad ${state.availableVersion} 可用",
                color = EchoPadColors.Text,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "当前版本 ${state.currentVersion}",
                color = EchoPadColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(
                modifier = Modifier.padding(top = 16.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.changelog.isEmpty()) {
                    Text("无更新日志", color = EchoPadColors.Text, style = MaterialTheme.typography.bodyMedium)
                }
                for (entry in state.changelog) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.version,
                                color = EchoPadColors.Text,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            entry.date?.let {
                                Text(it, color = EchoPadColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        // 原始 HTML 不渲染，链接用系统浏览器打开（库默认）
                        Markdown(entry.body, colors = markdownColor(text = EchoPadColors.Text))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = EchoPadColors.TextMuted)
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onConfirm()
                    },
                ) {
                    Text(if (state.installable) "更新" else "前往下载", color = EchoPadColors.Text)
                }
            }
        }
    }
}
