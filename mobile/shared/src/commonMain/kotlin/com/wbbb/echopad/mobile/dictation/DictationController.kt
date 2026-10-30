package com.wbbb.echopad.mobile.dictation

import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.AudioChunk
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.DictationCancel
import com.wbbb.echopad.mobile.protocol.DictationStart
import com.wbbb.echopad.mobile.protocol.DictationStop
import com.wbbb.echopad.mobile.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString

/**
 * 按住说话：按下时开始一次语音输入，录音实时发给电脑端；松开时结束，上滑后松开则取消（docs/prd/dictation.md）。
 * 说话时手机端不显示识别中的文字；松开后由电脑端整理好直接上屏（ADR-0013）。
 */
class DictationController(
    private val connection: DesktopConnection,
    private val scope: CoroutineScope,
) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    private var sessionId = 0

    /** 还在等电脑端结果的那次语音输入；取消后为空，它的结果不再提示。 */
    private var pendingSession: Int? = null
    private var recording: Job? = null

    init {
        scope.launch { connection.messages.collect(::handle) }
        scope.launch {
            // 断线时电脑端会丢弃未完成的语音输入，手机端也随之结束。
            connection.state.collect { state -> if (state !is ConnectionState.Connected) reset() }
        }
        scope.launch {
            // 切换到另一台电脑时，原来那台会丢弃未完成的语音输入（ADR-0015），手机端也随之结束。
            connection.activeId.drop(1).collect { reset() }
        }
    }

    fun start(audio: Flow<ByteArray>) {
        if (_speaking.value) return
        val id = ++sessionId
        if (!connection.send(ClientMessage(dictation_start = DictationStart(session_id = id)))) return
        _speaking.value = true
        pendingSession = id
        recording = scope.launch {
            audio.collect { pcm ->
                connection.send(ClientMessage(audio_chunk = AudioChunk(session_id = id, pcm = pcm.toByteString())))
            }
        }
    }

    fun stop() {
        endRecording { id -> ClientMessage(dictation_stop = DictationStop(session_id = id)) }
    }

    /** 丢弃这次语音输入的全部内容，电脑上什么都不输入。 */
    fun cancel() {
        if (!_speaking.value) return
        pendingSession = null
        endRecording { id -> ClientMessage(dictation_cancel = DictationCancel(session_id = id)) }
    }

    private fun endRecording(message: (Int) -> ClientMessage) {
        if (!_speaking.value) return
        _speaking.value = false
        val id = sessionId
        val job = recording
        recording = null
        scope.launch {
            // 等最后一段录音发出后再告诉电脑端。
            job?.cancelAndJoin()
            connection.send(message(id))
        }
    }

    private fun handle(message: ServerMessage) {
        message.dictation_finished?.takeIf { it.session_id == pendingSession }?.let {
            pendingSession = null
            if (it.cleanup_failure.isNotEmpty()) _failures.tryEmit("整理失败，已输入识别原文：${it.cleanup_failure}")
        }
        message.dictation_failed?.takeIf { it.session_id == pendingSession }?.let {
            reset()
            _failures.tryEmit(it.message)
        }
    }

    private fun reset() {
        recording?.cancel()
        recording = null
        pendingSession = null
        _speaking.value = false
    }
}
