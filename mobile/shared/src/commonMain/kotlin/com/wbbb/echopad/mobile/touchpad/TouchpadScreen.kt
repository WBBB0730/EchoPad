package com.wbbb.echopad.mobile.touchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.dictation.rememberAudioCapture
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
    onNotice: (String) -> Unit,
) {
    val connected = state is ConnectionState.Connected
    // 转屏后展开的面板保持不变。
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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
        ConnectionLabel(
            label = (state as? ConnectionState.Connected)?.desktopName?.ifEmpty { "已连接" } ?: "正在连接…",
            connected = connected,
            modifier = modifier,
        )
    }
    val settingsButton = @Composable {
        TextButton(onClick = { settingsOpen = true }) {
            Text("设置", color = EchoPadColors.TextMuted)
        }
    }
    val disconnectButton = @Composable { DisconnectButton(onDisconnect = connection::unpair) }
    val mediaToggle = @Composable { PanelToggle("媒体", open = panel == Panel.Media) { togglePanel(Panel.Media) } }
    val keyboardToggle = @Composable { PanelToggle("键盘", open = panel == Panel.Keyboard) { togglePanel(Panel.Keyboard) } }
    val talkButton = @Composable { modifier: Modifier -> TalkButton(dictation, connected, onNotice, modifier) }
    val bottomArea = @Composable { modifier: Modifier -> BottomArea(dictation, keyboard, panel, connected, modifier) }

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
                        connectionLabel(Modifier.padding(start = 12.dp, top = 12.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            settingsButton()
                            disconnectButton()
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
                    disconnectButton()
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
}

/** 临时文字或组字，以及展开的面板。都没有时不占位置。 */
@Composable
private fun BottomArea(
    dictation: DictationController,
    keyboard: KeyboardInput,
    panel: Panel?,
    connected: Boolean,
    modifier: Modifier,
) {
    val interimText by dictation.interimText.collectAsStateWithLifecycle()
    val composing by keyboard.composing.collectAsStateWithLifecycle()
    val liveText = interimText.ifEmpty { composing }
    if (liveText.isEmpty() && panel == null) return

    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (liveText.isNotEmpty()) {
            Text(
                liveText,
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

/** 连接状态：圆点 + 电脑名称。 */
@Composable
private fun ConnectionLabel(label: String, connected: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (connected) EchoPadColors.Connected else EchoPadColors.Connecting),
        )
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

/** 断开连接前先确认：断开后要重新扫码配对。 */
@Composable
private fun DisconnectButton(onDisconnect: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }) {
        Text("断开连接", color = EchoPadColors.TextMuted)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("断开连接？") },
            text = { Text("断开后需要重新扫码配对。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDisconnect()
                    },
                ) {
                    Text("断开", color = EchoPadColors.Text)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("取消", color = EchoPadColors.TextMuted)
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

    // 每帧屏幕刷新时把累积的指针移动和滚动合并发送一次。
    LaunchedEffect(output) {
        while (true) withFrameMillis { output.flush() }
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
                            for (change in event.changes) {
                                val id = change.id.value
                                val x = change.position.x.toDp().value
                                val y = change.position.y.toDp().value
                                val time = change.uptimeMillis
                                val actions = when {
                                    change.changedToDownIgnoreConsumed() -> {
                                        inertia?.cancel()
                                        recognizer.down(id, x, y, time)
                                    }
                                    change.changedToUpIgnoreConsumed() -> recognizer.up(id, x, y, time)
                                    change.positionChanged() -> recognizer.move(id, x, y, time)
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (speaking) EchoPadColors.Text else EchoPadColors.SurfaceRaised)
            .pointerInput(enabled, audio) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        if (!audio.hasPermission) {
                            audio.requestPermission { granted ->
                                if (!granted) onNotice("需要麦克风权限才能语音输入")
                            }
                            return@detectTapGestures
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        dictation.start(audio.record())
                        tryAwaitRelease()
                        dictation.stop()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (speaking) "松开结束" else "按住说话",
            color = when {
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
