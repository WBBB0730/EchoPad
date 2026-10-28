package com.wbbb.echopad.mobile.dictation

import com.wbbb.echopad.mobile.connection.ConnectionState
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.AudioChunk
import com.wbbb.echopad.mobile.protocol.ClientMessage
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
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString

/**
 * 按住说话：按下时开始一次语音输入，录音实时发给电脑端，松开时结束（docs/prd/dictation.md）。
 * 识别过程中的临时文字只显示在手机端；整理好的句子由电脑端直接上屏。
 */
class DictationController(
    private val connection: DesktopConnection,
    private val scope: CoroutineScope,
) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _interimText = MutableStateFlow("")
    val interimText: StateFlow<String> = _interimText.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    private var sessionId = 0
    private var recording: Job? = null

    init {
        scope.launch { connection.messages.collect(::handle) }
        scope.launch {
            // 断线时电脑端会丢弃未完成的语音输入，手机端也随之结束。
            connection.state.collect { state -> if (state !is ConnectionState.Connected) reset() }
        }
    }

    fun start(audio: Flow<ByteArray>) {
        if (_speaking.value) return
        val id = ++sessionId
        if (!connection.send(ClientMessage(dictation_start = DictationStart(session_id = id)))) return
        _speaking.value = true
        _interimText.value = ""
        recording = scope.launch {
            audio.collect { pcm ->
                connection.send(ClientMessage(audio_chunk = AudioChunk(session_id = id, pcm = pcm.toByteString())))
            }
        }
    }

    fun stop() {
        if (!_speaking.value) return
        _speaking.value = false
        val id = sessionId
        val job = recording
        recording = null
        scope.launch {
            // 等最后一段录音发出后再告诉电脑端结束。
            job?.cancelAndJoin()
            connection.send(ClientMessage(dictation_stop = DictationStop(session_id = id)))
        }
    }

    private fun handle(message: ServerMessage) {
        message.interim_text?.takeIf { it.session_id == sessionId }?.let { _interimText.value = it.text }
        message.dictation_finished?.takeIf { it.session_id == sessionId }?.let { _interimText.value = "" }
        message.dictation_failed?.takeIf { it.session_id == sessionId }?.let {
            reset()
            _failures.tryEmit(it.message)
        }
    }

    private fun reset() {
        recording?.cancel()
        recording = null
        _speaking.value = false
        _interimText.value = ""
    }
}
