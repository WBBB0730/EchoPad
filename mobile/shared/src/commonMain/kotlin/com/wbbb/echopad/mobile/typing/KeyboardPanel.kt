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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.ui.EchoPadColors

/** 手机键盘上没有的特殊键；退格、回车、空格用手机键盘自己的。 */
private val SPECIAL_KEYS = listOf(
    "Esc" to KeyPress.Key.KEY_ESCAPE,
    "Tab" to KeyPress.Key.KEY_TAB,
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

private val MEDIA_KEYS = listOf(
    "上一首" to KeyPress.Key.KEY_PREVIOUS_TRACK,
    "播放/暂停" to KeyPress.Key.KEY_PLAY_PAUSE,
    "下一首" to KeyPress.Key.KEY_NEXT_TRACK,
    "音量 −" to KeyPress.Key.KEY_VOLUME_DOWN,
    "静音" to KeyPress.Key.KEY_MUTE,
    "音量 +" to KeyPress.Key.KEY_VOLUME_UP,
)

/** 手机键盘上方的一排特殊键和一排快捷操作（docs/prd/typing.md）。 */
@Composable
fun KeyboardPanel(keyboard: KeyboardInput, connected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyRow(SPECIAL_KEYS, enabled = connected, onPress = keyboard::press)
        KeyRow(SHORTCUTS, enabled = connected, onPress = keyboard::shortcut)
    }
}

/** 一排媒体键（docs/prd/typing.md）。 */
@Composable
fun MediaPanel(keyboard: KeyboardInput, connected: Boolean, modifier: Modifier = Modifier) {
    KeyRow(MEDIA_KEYS, enabled = connected, onPress = keyboard::press, modifier = modifier.padding(horizontal = 12.dp))
}

@Composable
private fun <T> KeyRow(
    keys: List<Pair<String, T>>,
    enabled: Boolean,
    onPress: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((label, key) in keys) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EchoPadColors.SurfaceRaised)
                    .clickable(enabled = enabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onPress(key)
                    }
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
