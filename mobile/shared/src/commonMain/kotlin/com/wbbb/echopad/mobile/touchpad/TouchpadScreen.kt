package com.wbbb.echopad.mobile.touchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.DesktopEntry
import com.wbbb.echopad.mobile.connection.OutdatedApp
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.dictation.rememberAudioCapture
import com.wbbb.echopad.mobile.pairing.ScanOverlay
import com.wbbb.echopad.mobile.protocol.OperatingSystem
import com.wbbb.echopad.mobile.typing.KeyGroup
import com.wbbb.echopad.mobile.typing.KeyboardInput
import com.wbbb.echopad.mobile.typing.ModifierKeysRow
import com.wbbb.echopad.mobile.typing.PinnedKeys
import com.wbbb.echopad.mobile.typing.SwitchableKeys
import com.wbbb.echopad.mobile.typing.modifiersToggleIcon
import com.wbbb.echopad.mobile.typing.phoneKeyboard
import com.wbbb.echopad.mobile.ui.EchoPadColors
import com.wbbb.echopad.mobile.update.AboutRow
import com.wbbb.echopad.mobile.update.UpdateSheet
import com.wbbb.echopad.mobile.update.UpdateState
import echopad.shared.generated.resources.Res
import echopad.shared.generated.resources.keyboard
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** 看得见的滚动条的宽度（DESIGN.md）。 */
private val SCROLL_STRIP_WIDTH = 48.dp

@Composable
fun TouchpadScreen(
    state: ConnectionState,
    connection: DesktopConnection,
    dictation: DictationController,
    keyboard: KeyboardInput,
    settings: TouchpadSettings,
    onSettingsChange: (TouchpadSettings) -> Unit,
    onScanned: (String) -> Unit,
    onNotice: (String) -> Unit,
    /** 应用内更新的状态；平台不支持时为 null。 */
    update: UpdateState?,
    onCheckForUpdates: (force: Boolean) -> Unit,
    onApplyUpdate: () -> Unit,
) {
    val connected = state is ConnectionState.Connected
    // 转屏后手机键盘是否打开、常驻那排按键选中了哪一组都保持不变。
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var keyGroup by rememberSaveable { mutableStateOf<KeyGroup?>(null) }
    // 修饰键那一排是否显示，和上面选中哪一组互不影响；每个修饰键的状态在 [KeyboardInput] 里，转屏后也保持。
    var modifiersShown by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var updateOpen by rememberSaveable { mutableStateOf(false) }
    var desktopsOpen by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val desktops by connection.desktops.collectAsStateWithLifecycle()
    val activeId by connection.activeId.collectAsStateWithLifecycle()
    val activeName = desktops.find { it.id == activeId }?.name
    val output = remember(connection, keyboard) { TouchpadOutput(keyboard.modifiers) { connection.send(it) } }
    SideEffect { output.speed = settings.speed }
    // 有修饰键生效时，手机键盘换成不组字的输入，打的字和修饰键组成组合键。
    val modifierStates by keyboard.modifiers.states.collectAsStateWithLifecycle()
    // 修饰键按当前电脑的系统显示；断线时沿用上次连上时的。
    var system by remember { mutableStateOf(OperatingSystem.OPERATING_SYSTEM_MACOS) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected) system = state.operatingSystem
    }

    // 用系统返回手势收起手机键盘时，键盘按钮也恢复成没打开。
    val imeVisible by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    LaunchedEffect(keyboardOpen) {
        if (!keyboardOpen) return@LaunchedEffect
        snapshotFlow { imeVisible }.dropWhile { !it }.first { !it }
        keyboardOpen = false
    }

    val connectionLabel = @Composable { modifier: Modifier ->
        // 点电脑名打开电脑列表，在那里切换、取消配对或添加电脑。
        ConnectionLabel(state, activeName, modifier.clickable { desktopsOpen = true })
    }
    val settingsButton = @Composable {
        TextButton(onClick = { settingsOpen = true }) {
            Text("设置", color = EchoPadColors.TextMuted)
            // 新版本可用时一直显示，直到装上。
            if (update?.prompt == true) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(EchoPadColors.Connected))
            }
        }
    }
    val talkButton = @Composable { modifier: Modifier -> TalkButton(dictation, connected, onNotice, modifier) }
    val composingText = @Composable { modifier: Modifier -> ComposingText(keyboard, modifier) }
    // 常驻的按键：默认是文本操作，选中左下角的一组就换成那一组（DESIGN.md）。
    val switchableKeys = @Composable { columns: Int, maxLines: Int ->
        SwitchableKeys(keyGroup, keyboard, connected, columns, maxLines)
    }
    // 一排圆形按键，左右对称：左边选中或取消选中一组按键，中间的键盘按钮撑满，右边常驻的退格和回车（DESIGN.md）。
    val roundKeys = @Composable { size: Dp ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (group in KeyGroup.entries) {
                RoundToggle(group.label, group.icon, selected = keyGroup == group, Modifier.size(size)) {
                    keyGroup = if (keyGroup == group) null else group
                }
            }
            RoundToggle("键盘", Res.drawable.keyboard, selected = keyboardOpen, Modifier.weight(1f).height(size)) {
                keyboardOpen = !keyboardOpen
            }
            PinnedKeys(keyboard, connected, size = size)
        }
    }
    // 修饰键那一排，打开"修饰"时才显示（DESIGN.md）。
    val modifierKeys = @Composable {
        if (modifiersShown) ModifierKeysRow(keyboard, system, connected)
    }
    // "修饰"按钮只负责显示和隐藏修饰键那一排；隐藏时全部松开，免得看不见的修饰键带到下一次操作上。
    val modifiersToggle = @Composable { modifier: Modifier ->
        val icon = modifiersToggleIcon(system)
        RoundToggle(if (icon == null) "Ctrl" else "修饰", icon, selected = modifiersShown, modifier) {
            modifiersShown = !modifiersShown
            if (!modifiersShown) keyboard.modifiers.releaseAll()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 按整个页面的宽高判断横竖屏；弹出手机键盘只缩小下面这一层。
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().imePadding().phoneKeyboard(keyboard, open = keyboardOpen, plain = modifierStates.isNotEmpty())) {
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    TouchSurface(
                        output = output,
                        twoFingerScroll = settings.twoFingerScroll,
                        stripScroll = settings.stripScroll,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Start))
                            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    )
                    Column(
                        modifier = Modifier.width(260.dp).fillMaxHeight().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            connectionLabel(Modifier.weight(1f).padding(start = 12.dp))
                            settingsButton()
                        }
                        switchableKeys(3, 2)
                        modifierKeys()
                        roundKeys(40.dp)
                        Row(
                            Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            modifiersToggle(Modifier.size(40.dp))
                            talkButton(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                composingText(Modifier.padding(bottom = 12.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    connectionLabel(Modifier.weight(1f))
                    settingsButton()
                }
                // 右侧的系统手势区域只留给滚动条：竖着滑归滚动条，横着滑仍是系统的返回（docs/prd/touchpad.md）。
                val gestureEnd = WindowInsets.systemGestures.only(WindowInsetsSides.End).asPaddingValues()
                    .calculateEndPadding(LocalLayoutDirection.current)
                TouchSurface(
                    output = output,
                    twoFingerScroll = settings.twoFingerScroll,
                    stripScroll = settings.stripScroll,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 避开左侧的系统手势区域，免得滑动时触发返回。
                        .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Start))
                        .padding(start = 12.dp),
                    stripReach = gestureEnd + 12.dp,
                )
                composingText(Modifier)
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 竖屏选中"按键"时排成两排，一次全显示，不用翻页。
                    switchableKeys(6, 2)
                    modifierKeys()
                    roundKeys(56.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modifiersToggle(Modifier.size(56.dp))
                        talkButton(Modifier.weight(1f).height(56.dp))
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = settings,
            onSettingsChange = onSettingsChange,
            update = update,
            onCheckForUpdates = onCheckForUpdates,
            onUpdate = {
                settingsOpen = false
                updateOpen = true
            },
            onDismiss = { settingsOpen = false },
        )
    }
    if (updateOpen && update?.prompt == true) {
        UpdateSheet(state = update, onConfirm = onApplyUpdate, onDismiss = { updateOpen = false })
    }
    if (desktopsOpen) {
        DesktopListSheet(
            desktops = desktops,
            activeId = activeId,
            onSwitch = connection::switchTo,
            onUnpair = connection::unpair,
            onAdd = {
                desktopsOpen = false
                scanning = true
            },
            onDismiss = { desktopsOpen = false },
        )
    }
    if (scanning) {
        ScanOverlay(
            onScanned = {
                scanning = false
                onScanned(it)
            },
            onUnavailable = {
                scanning = false
                onNotice("无法使用相机扫码")
            },
            onCancel = { scanning = false },
        )
    }
}

/** 组字，没有时不占位置。 */
@Composable
private fun ComposingText(keyboard: KeyboardInput, modifier: Modifier) {
    val composing by keyboard.composing.collectAsStateWithLifecycle()
    if (composing.isEmpty()) return

    Text(
        composing,
        color = EchoPadColors.Text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        maxLines = 3,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
    )
}

/** 连接状态：圆点 + 电脑名称；版本不兼容时写该更新哪一端，圆点为灰色。 */
@Composable
private fun ConnectionLabel(state: ConnectionState, activeName: String?, modifier: Modifier = Modifier) {
    val (label, dot) = when (state) {
        is ConnectionState.Connected -> state.desktopName.ifEmpty { "已连接" } to EchoPadColors.Connected
        is ConnectionState.VersionMismatch -> when (state.outdated) {
            OutdatedApp.Mobile -> "手机端需要更新"
            OutdatedApp.Desktop -> "电脑端需要更新"
        } to EchoPadColors.TextMuted
        else -> (activeName?.let { "正在连接 $it" } ?: "正在连接…") to EchoPadColors.Connecting
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = EchoPadColors.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 配过的电脑列表：点一行切换到这台；取消配对前先确认；最下方添加电脑。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopListSheet(
    desktops: List<DesktopEntry>,
    activeId: String?,
    onSwitch: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var unpairing by remember { mutableStateOf<DesktopEntry?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EchoPadColors.Surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            for (desktop in desktops) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSwitch(desktop.id)
                            onDismiss()
                        }
                        .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (desktop.online) EchoPadColors.Connected else EchoPadColors.TextMuted),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        desktop.name,
                        color = EchoPadColors.Text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (desktop.id == activeId) {
                        Text("当前", color = EchoPadColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { unpairing = desktop }) {
                        Text("取消配对", color = EchoPadColors.TextMuted)
                    }
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Text("添加电脑", color = EchoPadColors.Text)
            }
        }
    }
    unpairing?.let { desktop ->
        AlertDialog(
            onDismissRequest = { unpairing = null },
            title = { Text("取消和 ${desktop.name} 的配对？") },
            text = { Text("之后要重新扫码才能再用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        unpairing = null
                        onUnpair(desktop.id)
                    },
                ) {
                    Text("取消配对", color = EchoPadColors.Text)
                }
            },
            dismissButton = {
                TextButton(onClick = { unpairing = null }) {
                    Text("保留", color = EchoPadColors.TextMuted)
                }
            },
            containerColor = EchoPadColors.Surface,
            titleContentColor = EchoPadColors.Text,
            textContentColor = EchoPadColors.TextMuted,
        )
    }
}

/** 圆形的图标开关，撑宽时是长条；打开时反色，再点一下关上。没有图标时显示 [label]。 */
@Composable
private fun RoundToggle(label: String, icon: DrawableResource?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) EchoPadColors.Text else EchoPadColors.SurfaceRaised)
            .toggleable(value = selected, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (selected) EchoPadColors.Background else EchoPadColors.Text
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        } else {
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 触控区和最右侧的滚动条。[twoFingerScroll] 和 [stripScroll] 是用户为双指滚动和滚动条选的滚动方向，只在设置里改，
 * 这时没有手指在触控区上，改了以后重新开始识别不会打断手势。[stripReach] 是滚动条的触摸范围在看得见的部分以外
 * 向右延伸的宽度，竖屏时延伸到屏幕边缘，外观不变。
 */
@Composable
private fun TouchSurface(
    output: TouchpadOutput,
    twoFingerScroll: ScrollDirection,
    stripScroll: ScrollDirection,
    modifier: Modifier,
    stripReach: Dp = 0.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var inertia by remember { mutableStateOf<Job?>(null) }

    // 有累积的指针移动和滚动时，在下一帧屏幕刷新时合并发送一次；手指不动时不占用屏幕刷新。
    LaunchedEffect(output) {
        while (true) {
            output.awaitPending()
            withFrameMillis { output.flush() }
        }
    }

    fun handle(actions: List<TouchpadAction>) {
        for (action in actions) {
            when (action) {
                is TouchpadAction.Fling -> inertia = scope.launch {
                    runInertia(ScrollInertia(action.vx, action.vy), output)
                }
                TouchpadAction.LeftClick, TouchpadAction.RightClick ->
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                TouchpadAction.DragStart -> haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                else -> Unit
            }
            output.handle(action)
        }
    }

    Box(modifier) {
        Row(Modifier.fillMaxSize().padding(end = stripReach).clip(RoundedCornerShape(24.dp)).background(EchoPadColors.Surface)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(output, twoFingerScroll) {
                    val recognizer = GestureRecognizer(scrollDirection = twoFingerScroll)
                    coroutineScope {
                        var timer: Job? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                fun PointerInputChange.point() = TouchPoint(position.x.toDp().value, position.y.toDp().value)
                                // 同一次事件里移动的手指一起交给识别器，按下和抬起再逐个处理。
                                val moved = event.changes.filter { it.pressed && it.previousPressed && it.positionChanged() }
                                handle(recognizer.move(moved.associate { it.id.value to it.point() }, event.changes.first().uptimeMillis))
                                for (change in event.changes) {
                                    val id = change.id.value
                                    val (x, y) = change.point()
                                    val time = change.uptimeMillis
                                    val actions = when {
                                        change.changedToDownIgnoreConsumed() -> {
                                            inertia?.cancel()
                                            recognizer.down(id, x, y, time)
                                        }
                                        change.changedToUpIgnoreConsumed() -> recognizer.up(id, x, y, time)
                                        else -> emptyList()
                                    }
                                    change.consume()
                                    handle(actions)
                                }
                                timer?.cancel()
                                timer = recognizer.deadline?.let { deadline ->
                                    val now = event.changes.first().uptimeMillis
                                    launch {
                                        delay(deadline - now)
                                        handle(recognizer.timeout(deadline))
                                    }
                                }
                            }
                        }
                    }
                },
            )
            // 看得见的滚动条。
            Box(Modifier.width(SCROLL_STRIP_WIDTH).fillMaxHeight().background(EchoPadColors.SurfaceRaised))
        }
        // 滚动条的触摸范围：盖住看得见的滚动条，再向右延伸 [stripReach]。单手时大拇指上下滑动就能滚动；
        // 一开始就横着滑是移动指针（docs/prd/touchpad.md）。
        Box(
            Modifier.align(Alignment.CenterEnd).width(SCROLL_STRIP_WIDTH + stripReach).fillMaxHeight().pointerInput(output, stripScroll) {
                val recognizer = ScrollStripRecognizer(direction = stripScroll)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    inertia?.cancel()
                    recognizer.down(down.position.x.toDp().value, down.position.y.toDp().value, down.uptimeMillis)
                    // 手指滑出滚动条后仍然会收到它的事件，判定出的手势一直持续到抬起；其他手指不参与。
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        val x = change.position.x.toDp().value
                        val y = change.position.y.toDp().value
                        change.consume()
                        if (!change.pressed) {
                            handle(recognizer.up(x, y, change.uptimeMillis))
                            break
                        }
                        handle(recognizer.move(x, y, change.uptimeMillis))
                    }
                }
            },
        )
    }
}

private suspend fun runInertia(inertia: ScrollInertia, output: TouchpadOutput) {
    var last = withFrameMillis { it }
    while (true) {
        val now = withFrameMillis { it }
        val (dx, dy) = inertia.step(now - last) ?: return
        output.scroll(dx, dy)
        last = now
    }
}

@Composable
private fun TalkButton(
    dictation: DictationController,
    enabled: Boolean,
    onNotice: (String) -> Unit,
    modifier: Modifier,
) {
    val audio = rememberAudioCapture()
    val haptics = LocalHapticFeedback.current
    val speaking by dictation.speaking.collectAsStateWithLifecycle()
    // 按住时手指滑到按钮上边缘以外：这时松手就取消（DESIGN.md）。
    var cancelling by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(
                when {
                    speaking && cancelling -> EchoPadColors.Cancel
                    speaking -> EchoPadColors.Text
                    else -> EchoPadColors.SurfaceRaised
                },
            )
            .pointerInput(enabled, audio) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!enabled) return@awaitEachGesture
                    if (!audio.hasPermission) {
                        audio.requestPermission { granted ->
                            if (!granted) onNotice("需要麦克风权限才能语音输入")
                        }
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dictation.start(audio.record())
                    try {
                        // 手指移出按钮后仍然会收到它的事件，位置相对按钮，上边缘以上为负。
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val above = change.position.y < 0f
                            if (above != cancelling) {
                                cancelling = above
                                if (above) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            }
                        }
                    } finally {
                        if (cancelling) dictation.cancel() else dictation.stop()
                        cancelling = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                speaking && cancelling -> "松开取消"
                speaking -> "松开结束"
                else -> "按住说话"
            },
            color = when {
                speaking && cancelling -> EchoPadColors.Text
                speaking -> EchoPadColors.Background
                enabled -> EchoPadColors.Text
                else -> EchoPadColors.TextMuted
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 设置：指针速度和滚动速度（松手时保存）、双指滚动和单指滚动的方向（选择时保存），最下面是版本号与检查更新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: TouchpadSettings,
    onSettingsChange: (TouchpadSettings) -> Unit,
    update: UpdateState?,
    onCheckForUpdates: (force: Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pointer by remember { mutableFloatStateOf(settings.speed.pointer) }
    var scroll by remember { mutableFloatStateOf(settings.speed.scroll) }
    var twoFingerScroll by remember { mutableStateOf(settings.twoFingerScroll) }
    var stripScroll by remember { mutableStateOf(settings.stripScroll) }
    val save = {
        onSettingsChange(
            TouchpadSettings(TouchpadSpeed(pointer = pointer, scroll = scroll), twoFingerScroll = twoFingerScroll, stripScroll = stripScroll),
        )
    }
    // 打开设置时检查更新，距上次检查不足 5 分钟就跳过。
    LaunchedEffect(Unit) { onCheckForUpdates(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EchoPadColors.Surface) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            SpeedSlider("指针速度", pointer, onValueChange = { pointer = it }, onValueChangeFinished = save)
            SpeedSlider("滚动速度", scroll, onValueChange = { scroll = it }, onValueChangeFinished = save)
            // 单指滚动就是滚动条上的滚动（DESIGN.md）。
            ScrollDirectionChoice("双指滚动", twoFingerScroll) {
                twoFingerScroll = it
                save()
            }
            ScrollDirectionChoice("单指滚动", stripScroll) {
                stripScroll = it
                save()
            }
            if (update != null) AboutRow(update, onCheck = { onCheckForUpdates(true) }, onUpdate = onUpdate)
        }
    }
}

/** 一行滚动方向：左边是名称，右边在"自然"和"传统"里选一个，选中的反色。 */
@Composable
private fun ScrollDirectionChoice(label: String, value: ScrollDirection, onValueChange: (ScrollDirection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = EchoPadColors.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            ScrollDirection.entries.forEachIndexed { index, direction ->
                SegmentedButton(
                    selected = value == direction,
                    onClick = { onValueChange(direction) },
                    shape = SegmentedButtonDefaults.itemShape(index, ScrollDirection.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = EchoPadColors.Text,
                        activeContentColor = EchoPadColors.Background,
                        activeBorderColor = EchoPadColors.Text,
                        inactiveContainerColor = EchoPadColors.SurfaceRaised,
                        inactiveContentColor = EchoPadColors.Text,
                        inactiveBorderColor = EchoPadColors.SurfaceRaised,
                    ),
                    // 选中靠反色表示，不加对勾。
                    icon = {},
                    label = { Text(direction.label) },
                )
            }
        }
    }
}

private val ScrollDirection.label: String
    get() = when (this) {
        ScrollDirection.Natural -> "自然"
        ScrollDirection.Traditional -> "传统"
    }

@Composable
private fun SpeedSlider(label: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = EchoPadColors.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val tenths = (value * 10).roundToInt()
        Text("${tenths / 10}.${tenths % 10}×", color = EchoPadColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = TouchpadSpeed.RANGE,
        // 每 0.1 倍一档。
        steps = 14,
        colors = SliderDefaults.colors(
            thumbColor = EchoPadColors.Text,
            activeTrackColor = EchoPadColors.Text,
            inactiveTrackColor = EchoPadColors.SurfaceRaised,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}
