package com.wbbb.echopad.mobile.touchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.dictation.DictationMode
import com.wbbb.echopad.mobile.dictation.rememberAudioCapture
import com.wbbb.echopad.mobile.typing.Composer
import com.wbbb.echopad.mobile.typing.KeyboardPanel
import com.wbbb.echopad.mobile.ui.EchoPadColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TouchpadScreen(
    state: ConnectionState,
    connection: DesktopConnection,
    dictation: DictationController,
    composer: Composer,
    dictationMode: DictationMode,
    onDictationModeChange: (DictationMode) -> Unit,
    onNotice: (String) -> Unit,
) {
    val connected = state is ConnectionState.Connected
    var keyboardOpen by remember { mutableStateOf(false) }
    // 先编辑模式下收到语音文字时，打开键盘面板显示输入框。
    LaunchedEffect(composer) {
        composer.voiceTextArrived.collect { keyboardOpen = true }
    }

    // 弹出手机输入法时，下方的面板和按钮跟着上移。
    Column(Modifier.fillMaxSize().imePadding()) {
        StatusBar(
            label = (state as? ConnectionState.Connected)?.desktopName?.ifEmpty { "已连接" } ?: "正在连接…",
            connected = connected,
            onRepair = connection::unpair,
        )
        TouchSurface(
            output = remember(connection) { TouchpadOutput { connection.send(it) } },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 避开系统的边缘手势区域，免得滑动时触发返回。
                .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp),
        )
        if (keyboardOpen) {
            KeyboardPanel(composer = composer, connected = connected, modifier = Modifier.padding(top = 12.dp))
        }
        DictationBar(
            dictation = dictation,
            enabled = connected,
            mode = dictationMode,
            onModeChange = onDictationModeChange,
            keyboardOpen = keyboardOpen,
            onKeyboardToggle = { keyboardOpen = !keyboardOpen },
            onNotice = onNotice,
        )
    }
}

@Composable
private fun StatusBar(label: String, connected: Boolean, onRepair: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (connected) EchoPadColors.Connected else EchoPadColors.Connecting),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = EchoPadColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text("···", color = EchoPadColors.TextMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重新配对") },
                    onClick = {
                        menuOpen = false
                        onRepair()
                    },
                )
            }
        }
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
                            TouchpadAction.LeftClick, TouchpadAction.DoubleClick, TouchpadAction.RightClick ->
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
private fun DictationBar(
    dictation: DictationController,
    enabled: Boolean,
    mode: DictationMode,
    onModeChange: (DictationMode) -> Unit,
    keyboardOpen: Boolean,
    onKeyboardToggle: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val audio = rememberAudioCapture()
    val haptics = LocalHapticFeedback.current
    val speaking by dictation.speaking.collectAsStateWithLifecycle()
    val interimText by dictation.interimText.collectAsStateWithLifecycle()
    // 按下说话按钮时使用当时选中的上屏方式。
    val currentMode by rememberUpdatedState(mode)

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        if (interimText.isNotEmpty()) {
            Text(
                interimText,
                color = EchoPadColors.Text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    onModeChange(if (mode == DictationMode.Direct) DictationMode.Compose else DictationMode.Direct)
                },
            ) {
                Text(if (mode == DictationMode.Direct) "直接上屏" else "先编辑", color = EchoPadColors.TextMuted)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
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
                                dictation.start(audio.record(), currentMode)
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
            TextButton(onClick = onKeyboardToggle) {
                Text(if (keyboardOpen) "收起" else "键盘", color = EchoPadColors.TextMuted)
            }
        }
    }
}
