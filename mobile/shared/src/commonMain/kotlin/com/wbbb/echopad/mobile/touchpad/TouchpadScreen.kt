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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.DesktopEntry
import com.wbbb.echopad.mobile.connection.OutdatedApp
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.dictation.rememberAudioCapture
import com.wbbb.echopad.mobile.pairing.ScanOverlay
import com.wbbb.echopad.mobile.typing.KeyboardInput
import com.wbbb.echopad.mobile.typing.KeyboardPanel
import com.wbbb.echopad.mobile.typing.MediaPanel
import com.wbbb.echopad.mobile.typing.phoneKeyboard
import com.wbbb.echopad.mobile.ui.EchoPadColors
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 底部展开的面板，同一时间最多一个。 */
private enum class Panel { Keyboard, Media }

@Composable
fun TouchpadScreen(
    state: ConnectionState,
    connection: DesktopConnection,
    dictation: DictationController,
    keyboard: KeyboardInput,
    speed: TouchpadSpeed,
    onSpeedChange: (TouchpadSpeed) -> Unit,
    onScanned: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val connected = state is ConnectionState.Connected
    // 转屏后展开的面板保持不变。
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var desktopsOpen by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val desktops by connection.desktops.collectAsStateWithLifecycle()
    val activeId by connection.activeId.collectAsStateWithLifecycle()
    val activeName = desktops.find { it.id == activeId }?.name
    val output = remember(connection) { TouchpadOutput { connection.send(it) } }
    SideEffect { output.speed = speed }

    // 用系统返回手势收起手机键盘时，键盘面板也一起收起。
    val imeVisible by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    LaunchedEffect(panel == Panel.Keyboard) {
        if (panel != Panel.Keyboard) return@LaunchedEffect
        snapshotFlow { imeVisible }.dropWhile { !it }.first { !it }
        panel = null
    }

    val togglePanel = { target: Panel -> panel = if (panel == target) null else target }
    val connectionLabel = @Composable { modifier: Modifier ->
        // 点电脑名打开电脑列表，在那里切换、取消配对或添加电脑。
        ConnectionLabel(state, activeName, modifier.clickable { desktopsOpen = true })
    }
    val settingsButton = @Composable {
        TextButton(onClick = { settingsOpen = true }) {
            Text("设置", color = EchoPadColors.TextMuted)
        }
    }
    val mediaToggle = @Composable { PanelToggle("媒体", open = panel == Panel.Media) { togglePanel(Panel.Media) } }
    val keyboardToggle = @Composable { PanelToggle("键盘", open = panel == Panel.Keyboard) { togglePanel(Panel.Keyboard) } }
    val talkButton = @Composable { modifier: Modifier -> TalkButton(dictation, connected, onNotice, modifier) }
    val bottomArea = @Composable { modifier: Modifier -> BottomArea(keyboard, panel, connected, modifier) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 按整个页面的宽高判断横竖屏；弹出手机键盘只缩小下面这一层。
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().imePadding().phoneKeyboard(keyboard, open = panel == Panel.Keyboard)) {
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    TouchSurface(
                        output = output,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Start))
                            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    )
                    Column(
                        modifier = Modifier.width(200.dp).fillMaxHeight().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            connectionLabel(Modifier.weight(1f).padding(start = 12.dp))
                            settingsButton()
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            mediaToggle()
                            keyboardToggle()
                        }
                        talkButton(Modifier.fillMaxWidth().weight(1f))
                    }
                }
                bottomArea(Modifier.padding(bottom = 12.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    connectionLabel(Modifier.weight(1f))
                    settingsButton()
                }
                TouchSurface(
                    output = output,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 避开系统的边缘手势区域，免得滑动时触发返回。
                        .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp),
                )
                bottomArea(Modifier)
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    mediaToggle()
                    talkButton(Modifier.weight(1f).height(56.dp))
                    keyboardToggle()
                }
            }
        }
    }

    if (settingsOpen) {
        SpeedSheet(speed = speed, onSpeedChange = onSpeedChange, onDismiss = { settingsOpen = false })
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

/** 组字以及展开的面板。都没有时不占位置。 */
@Composable
private fun BottomArea(
    keyboard: KeyboardInput,
    panel: Panel?,
    connected: Boolean,
    modifier: Modifier,
) {
    val composing by keyboard.composing.collectAsStateWithLifecycle()
    if (composing.isEmpty() && panel == null) return

    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (composing.isNotEmpty()) {
            Text(
                composing,
                color = EchoPadColors.Text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        when (panel) {
            Panel.Keyboard -> KeyboardPanel(keyboard, connected)
            Panel.Media -> MediaPanel(keyboard, connected)
            null -> Unit
        }
    }
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

@Composable
private fun PanelToggle(label: String, open: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(if (open) "收起" else label, color = EchoPadColors.TextMuted)
    }
}

@Composable
private fun TouchSurface(output: TouchpadOutput, modifier: Modifier) {
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

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(EchoPadColors.Surface)
            .pointerInput(output) {
                val recognizer = GestureRecognizer()
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

/** 指针速度和滚动速度，松手时保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(speed: TouchpadSpeed, onSpeedChange: (TouchpadSpeed) -> Unit, onDismiss: () -> Unit) {
    var pointer by remember { mutableFloatStateOf(speed.pointer) }
    var scroll by remember { mutableFloatStateOf(speed.scroll) }
    val save = { onSpeedChange(TouchpadSpeed(pointer = pointer, scroll = scroll)) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EchoPadColors.Surface) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            SpeedSlider("指针速度", pointer, onValueChange = { pointer = it }, onValueChangeFinished = save)
            SpeedSlider("滚动速度", scroll, onValueChange = { scroll = it }, onValueChangeFinished = save)
        }
    }
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
