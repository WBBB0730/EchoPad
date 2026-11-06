package com.wbbb.echopad.mobile.typing

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ModifierKey
import com.wbbb.echopad.mobile.protocol.OperatingSystem
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.ui.EchoPadColors
import echopad.shared.generated.resources.Res
import echopad.shared.generated.resources.backspace
import echopad.shared.generated.resources.content_copy
import echopad.shared.generated.resources.content_cut
import echopad.shared.generated.resources.content_paste
import echopad.shared.generated.resources.keyboard_arrow_down
import echopad.shared.generated.resources.keyboard_arrow_left
import echopad.shared.generated.resources.keyboard_arrow_right
import echopad.shared.generated.resources.keyboard_arrow_up
import echopad.shared.generated.resources.keyboard_command_key
import echopad.shared.generated.resources.keyboard_control_key
import echopad.shared.generated.resources.keyboard_keys
import echopad.shared.generated.resources.keyboard_option_key
import echopad.shared.generated.resources.keyboard_return
import echopad.shared.generated.resources.music_note
import echopad.shared.generated.resources.play_pause
import echopad.shared.generated.resources.redo
import echopad.shared.generated.resources.select_all
import echopad.shared.generated.resources.shift
import echopad.shared.generated.resources.skip_next
import echopad.shared.generated.resources.skip_previous
import echopad.shared.generated.resources.space_bar
import echopad.shared.generated.resources.undo
import echopad.shared.generated.resources.volume_down
import echopad.shared.generated.resources.volume_off
import echopad.shared.generated.resources.volume_up
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * 一个按键。有标准图标（Material Symbols Rounded）的显示图标，没有的显示 [label]；[label] 同时是图标的无障碍描述。
 * 按下时对手机键盘的输入调用 [send]；[repeats] 为 true 时，按住像电脑键盘一样连续触发。
 */
private class KeyCap(
    val label: String,
    val icon: DrawableResource? = null,
    val repeats: Boolean = false,
    val send: KeyboardInput.() -> Unit,
)

private fun specialKey(label: String, key: KeyPress.Key, icon: DrawableResource? = null, repeats: Boolean = false) =
    KeyCap(label, icon, repeats) { press(key) }

private fun shortcutKey(label: String, action: ShortcutAction.Shortcut, icon: DrawableResource? = null) =
    KeyCap(label, icon) { shortcut(action) }

private fun mediaKey(label: String, key: KeyPress.Key, icon: DrawableResource) = KeyCap(label, icon) { media(key) }

/** 常驻的退格和回车，回车在最右边。 */
private val PINNED_KEYS = listOf(
    specialKey("退格", KeyPress.Key.KEY_BACKSPACE, Res.drawable.backspace, repeats = true),
    specialKey("回车", KeyPress.Key.KEY_ENTER, Res.drawable.keyboard_return),
)

/** 一组按键按竖屏排好时每排的个数；横屏一排放不下时按列切成几块（见 [SwitchableKeys]）。 */
private const val FULL_COLUMNS = 6

/** 文本操作：常驻那排按键没选中任何一组时显示，按 macOS 编辑菜单的顺序排列。 */
private val TEXT_SHORTCUTS = listOf(
    listOf(
        shortcutKey("撤销", ShortcutAction.Shortcut.SHORTCUT_UNDO, Res.drawable.undo),
        shortcutKey("重做", ShortcutAction.Shortcut.SHORTCUT_REDO, Res.drawable.redo),
        shortcutKey("剪切", ShortcutAction.Shortcut.SHORTCUT_CUT, Res.drawable.content_cut),
        shortcutKey("复制", ShortcutAction.Shortcut.SHORTCUT_COPY, Res.drawable.content_copy),
        shortcutKey("粘贴", ShortcutAction.Shortcut.SHORTCUT_PASTE, Res.drawable.content_paste),
        shortcutKey("全选", ShortcutAction.Shortcut.SHORTCUT_SELECT_ALL, Res.drawable.select_all),
    ),
)

/**
 * "按键"那组，照着电脑键盘排：左边一列 Esc 在 Tab 上面，Home 和 End、PgUp 和 PgDn 各成一对，
 * 右边方向键是倒 T 形，Del 在右上角。翻页和删除用电脑键盘上印的缩写，"删除"容易被当成退格。
 * 退格、回车常驻，不重复放；空格放进来，不弹出手机键盘也能按。
 */
private val SPECIAL_KEYS = listOf(
    listOf(
        specialKey("Esc", KeyPress.Key.KEY_ESCAPE),
        specialKey("Home", KeyPress.Key.KEY_HOME),
        specialKey("End", KeyPress.Key.KEY_END),
        specialKey("空格", KeyPress.Key.KEY_SPACE, Res.drawable.space_bar),
        specialKey("上", KeyPress.Key.KEY_UP, Res.drawable.keyboard_arrow_up, repeats = true),
        specialKey("Del", KeyPress.Key.KEY_DELETE, repeats = true),
    ),
    listOf(
        specialKey("Tab", KeyPress.Key.KEY_TAB),
        specialKey("PgUp", KeyPress.Key.KEY_PAGE_UP),
        specialKey("PgDn", KeyPress.Key.KEY_PAGE_DOWN),
        specialKey("左", KeyPress.Key.KEY_LEFT, Res.drawable.keyboard_arrow_left, repeats = true),
        specialKey("下", KeyPress.Key.KEY_DOWN, Res.drawable.keyboard_arrow_down, repeats = true),
        specialKey("右", KeyPress.Key.KEY_RIGHT, Res.drawable.keyboard_arrow_right, repeats = true),
    ),
)

/** "媒体"那组，和 Mac 键盘上 F7–F12 的顺序一样：上一首、播放/暂停、下一首、静音、音量减、音量加。 */
private val MEDIA_KEYS = listOf(
    listOf(
        mediaKey("上一首", KeyPress.Key.KEY_PREVIOUS_TRACK, Res.drawable.skip_previous),
        mediaKey("播放/暂停", KeyPress.Key.KEY_PLAY_PAUSE, Res.drawable.play_pause),
        mediaKey("下一首", KeyPress.Key.KEY_NEXT_TRACK, Res.drawable.skip_next),
        mediaKey("静音", KeyPress.Key.KEY_MUTE, Res.drawable.volume_off),
        mediaKey("音量 −", KeyPress.Key.KEY_VOLUME_DOWN, Res.drawable.volume_down),
        mediaKey("音量 +", KeyPress.Key.KEY_VOLUME_UP, Res.drawable.volume_up),
    ),
)

/** 一个修饰键：Mac 上用 ⌃ ⌥ ⇧ ⌘ 的图标，[macLabel] 是图标的无障碍描述；Windows 上写键盘上印的 [windowsLabel]。 */
private class ModifierCap(val key: ModifierKey, val macLabel: String, val macIcon: DrawableResource, val windowsLabel: String)

/** 修饰键那一排，按 Apple 菜单里写组合键的顺序排。 */
private val MODIFIER_CAPS = listOf(
    ModifierCap(ModifierKey.MODIFIER_KEY_CONTROL, "Control", Res.drawable.keyboard_control_key, "Ctrl"),
    ModifierCap(ModifierKey.MODIFIER_KEY_ALT, "Option", Res.drawable.keyboard_option_key, "Alt"),
    ModifierCap(ModifierKey.MODIFIER_KEY_SHIFT, "Shift", Res.drawable.shift, "Shift"),
    ModifierCap(ModifierKey.MODIFIER_KEY_META, "Command", Res.drawable.keyboard_command_key, "Win"),
)

/** "修饰"按钮的图标：Mac 上用 ⌘；Windows 上没有对应的图标，为 null，按钮上写 Ctrl（DESIGN.md）。 */
fun modifiersToggleIcon(system: OperatingSystem): DrawableResource? =
    Res.drawable.keyboard_command_key.takeIf { system != OperatingSystem.OPERATING_SYSTEM_WINDOWS }

/** 按住连发时两次触发的间隔（毫秒）。 */
private const val REPEAT_INTERVAL = 50L

private val KEY_SHAPE = RoundedCornerShape(10.dp)

/** 按键之间的间距，所有按键行都一样，上下对齐。 */
private val KEY_SPACING = 6.dp

private val KEY_HEIGHT = 40.dp

/** 常驻那排按键可以切换成的一组，由左下角的按钮选中；都没选中时是文本操作（DESIGN.md）。 */
enum class KeyGroup(val label: String, val icon: DrawableResource) {
    /** 特殊键和切换应用。 */
    SpecialKeys("按键", Res.drawable.keyboard_keys),
    MediaKeys("媒体", Res.drawable.music_note),
}

/** 这一组按竖屏排好的每一排，每排 [FULL_COLUMNS] 个。 */
private val KeyGroup.rows
    get() = when (this) {
        KeyGroup.SpecialKeys -> SPECIAL_KEYS
        KeyGroup.MediaKeys -> MEDIA_KEYS
    }

/** 常驻的退格和回车：[size] 大小的圆形按键，回车在最右边（docs/prd/typing.md）。 */
@Composable
fun PinnedKeys(keyboard: KeyboardInput, connected: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (key in PINNED_KEYS) {
            KeyButton(key, keyboard, enabled = connected, shape = CircleShape, iconSize = 24.dp, modifier = Modifier.size(size))
        }
    }
}

/**
 * 常驻的按键（docs/prd/typing.md）：没选中任何一组时是文本操作，选中时是 [group] 那一组。
 * 每排 [columns] 个，等宽铺满；一组的键排成几排就占几排，最多 [maxLines] 排，再放不下时左右滑动翻页。
 * 一排放不下 [FULL_COLUMNS] 个时，把这一组按列切成几块上下排，翻页时整块翻，方向键这样的一块不会被拆开。
 */
@Composable
fun SwitchableKeys(
    group: KeyGroup?,
    keyboard: KeyboardInput,
    connected: Boolean,
    columns: Int,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    require(FULL_COLUMNS % columns == 0) { "一排的个数要能整除 $FULL_COLUMNS：$columns" }
    val rows = group?.rows ?: TEXT_SHORTCUTS
    val lines = (0 until FULL_COLUMNS / columns).flatMap { block ->
        rows.map { it.subList(block * columns, (block + 1) * columns) }
    }
    val linesPerPage = minOf(maxLines, lines.size)
    val pages = lines.chunked(linesPerPage)
    // 换了一组就从第一页开始。
    key(group) {
        HorizontalPager(rememberPagerState { pages.size }, modifier.fillMaxWidth(), pageSpacing = KEY_SPACING) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                for (line in pages[page]) {
                    KeyLine(line, keyboard, enabled = connected)
                }
                // 不满一页时空着的排也占位置，翻页时高度不变。
                repeat(linesPerPage - pages[page].size) { Spacer(Modifier.height(KEY_HEIGHT)) }
            }
        }
    }
}

/** 一排等宽的按键，和其他排上下对齐。 */
@Composable
private fun KeyLine(keys: List<KeyCap>, keyboard: KeyboardInput, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
        for (key in keys) {
            KeyButton(key, keyboard, enabled = enabled, shape = KEY_SHAPE, iconSize = 20.dp, modifier = Modifier.weight(1f).height(KEY_HEIGHT))
        }
    }
}

/**
 * 修饰键那一排（docs/prd/typing.md）：4 个等宽，和常驻的按键一样的样式，按 [system] 显示。
 * 点一下点亮、用完自动松开，双击或长按锁定，再点一下松开。点亮时反色，锁定时在符号下面再加一条短横线（DESIGN.md）。
 */
@Composable
fun ModifierKeysRow(keyboard: KeyboardInput, system: OperatingSystem, connected: Boolean, modifier: Modifier = Modifier) {
    val states by keyboard.modifiers.states.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis.milliseconds
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
        for (cap in MODIFIER_CAPS) {
            val state = states[cap.key]
            val color = when {
                state != null -> EchoPadColors.Background
                connected -> EchoPadColors.Text
                else -> EchoPadColors.TextMuted
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(KEY_HEIGHT)
                    .clip(KEY_SHAPE)
                    .background(if (state != null) EchoPadColors.Text else EchoPadColors.SurfaceRaised)
                    .combinedClickable(
                        enabled = connected,
                        hapticFeedbackEnabled = false,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            keyboard.modifiers.lock(cap.key)
                        },
                        onClick = {
                            // 锁定用更明显的触感（DESIGN.md）。
                            val locked = keyboard.modifiers.tap(cap.key, doubleTapTimeout) == ModifierState.Locked
                            haptics.performHapticFeedback(
                                if (locked) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.KeyboardTap,
                            )
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (system == OperatingSystem.OPERATING_SYSTEM_WINDOWS) {
                    KeyText(cap.windowsLabel, color)
                } else {
                    Icon(painterResource(cap.macIcon), contentDescription = cap.macLabel, tint = color, modifier = Modifier.size(20.dp))
                }
                // 没锁定时也占着位置，锁定前后符号不跳动。
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(width = 12.dp, height = 2.dp)
                        .clip(CircleShape)
                        .background(if (state == ModifierState.Locked) color else Color.Transparent),
                )
            }
        }
    }
}

/** 一个按键。需要连发的键按住超过长按时间后连续触发，直到松开或开始左右滑动翻页。 */
@Composable
private fun KeyButton(
    key: KeyCap,
    keyboard: KeyboardInput,
    enabled: Boolean,
    shape: Shape,
    iconSize: Dp,
    modifier: Modifier,
) {
    val onPress = { key.send(keyboard) }
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var holding by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress)
    val press = {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        onPress()
    }

    // 长按触发第一下后开始连发；松开或左右滑动翻页时按下状态结束，连发随之停止。只在第一下震动（DESIGN.md）。
    LaunchedEffect(holding, pressed) {
        if (!holding) return@LaunchedEffect
        if (!pressed) {
            holding = false
            return@LaunchedEffect
        }
        while (true) {
            delay(REPEAT_INTERVAL)
            currentOnPress()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(EchoPadColors.SurfaceRaised)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onLongClick = if (key.repeats) {
                    {
                        press()
                        holding = true
                    }
                } else {
                    null
                },
                hapticFeedbackEnabled = false,
                onClick = press,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (enabled) EchoPadColors.Text else EchoPadColors.TextMuted
        if (key.icon != null) {
            Icon(painterResource(key.icon), contentDescription = key.label, tint = color, modifier = Modifier.size(iconSize))
        } else {
            KeyText(key.label, color)
        }
    }
}

/** 按键上的文字。键宽是固定的，文字放不下时缩小字号，保持一行。 */
@Composable
private fun KeyText(label: String, color: Color) {
    val style = MaterialTheme.typography.bodyMedium
    Text(
        label,
        color = color,
        style = style,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
