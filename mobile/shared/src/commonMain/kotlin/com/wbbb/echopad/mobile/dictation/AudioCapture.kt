package com.wbbb.echopad.mobile.dictation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/** 按住说话时的录音。 */
interface AudioCapture {
    val hasPermission: Boolean

    fun requestPermission(onResult: (granted: Boolean) -> Unit)

    /** 开始收集时开始录音，停止收集时结束；每段是 100ms 的 16kHz、16 位、单声道 PCM。 */
    fun record(): Flow<ByteArray>
}

@Composable
expect fun rememberAudioCapture(): AudioCapture
