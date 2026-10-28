package com.wbbb.echopad.mobile.typing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.ui.EchoPadColors

private val SPECIAL_KEYS = listOf(
    "退格" to KeyPress.Key.KEY_BACKSPACE,
    "回车" to KeyPress.Key.KEY_ENTER,
    "空格" to KeyPress.Key.KEY_SPACE,
    "Tab" to KeyPress.Key.KEY_TAB,
    "Esc" to KeyPress.Key.KEY_ESCAPE,
    "←" to KeyPress.Key.KEY_LEFT,
    "→" to KeyPress.Key.KEY_RIGHT,
    "↑" to KeyPress.Key.KEY_UP,
    "↓" to KeyPress.Key.KEY_DOWN,
    "删除" to KeyPress.Key.KEY_DELETE,
    "Home" to KeyPress.Key.KEY_HOME,
    "End" to KeyPress.Key.KEY_END,
    "上翻页" to KeyPress.Key.KEY_PAGE_UP,
    "下翻页" to KeyPress.Key.KEY_PAGE_DOWN,
)

private val SHORTCUTS = listOf(
    "复制" to ShortcutAction.Shortcut.SHORTCUT_COPY,
    "粘贴" to ShortcutAction.Shortcut.SHORTCUT_PASTE,
    "剪切" to ShortcutAction.Shortcut.SHORTCUT_CUT,
    "撤销" to ShortcutAction.Shortcut.SHORTCUT_UNDO,
    "重做" to ShortcutAction.Shortcut.SHORTCUT_REDO,
    "全选" to ShortcutAction.Shortcut.SHORTCUT_SELECT_ALL,
    "切换应用" to ShortcutAction.Shortcut.SHORTCUT_SWITCH_APP,
)

/** 输入框、特殊键和快捷操作（docs/prd/typing.md）。 */
@Composable
fun KeyboardPanel(composer: Composer, connected: Boolean, modifier: Modifier = Modifier) {
    val text by composer.text.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = composer::edit,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = EchoPadColors.Text,
                    unfocusedTextColor = EchoPadColors.Text,
                    focusedBorderColor = EchoPadColors.TextMuted,
                    unfocusedBorderColor = EchoPadColors.SurfaceRaised,
                    cursorColor = EchoPadColors.Text,
                ),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { composer.send() }, enabled = connected && text.isNotEmpty()) {
                Text("发送")
            }
        }
        KeyRow(SPECIAL_KEYS, enabled = connected) { key ->
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            composer.press(key)
        }
        KeyRow(SHORTCUTS, enabled = connected) { shortcut ->
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            composer.shortcut(shortcut)
        }
    }
}

@Composable
private fun <T> KeyRow(keys: List<Pair<String, T>>, enabled: Boolean, onPress: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((label, key) in keys) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EchoPadColors.SurfaceRaised)
                    .clickable(enabled = enabled) { onPress(key) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (enabled) EchoPadColors.Text else EchoPadColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
