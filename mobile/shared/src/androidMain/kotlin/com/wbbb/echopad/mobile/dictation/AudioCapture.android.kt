package com.wbbb.echopad.mobile.dictation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

private const val SAMPLE_RATE = 16_000
/** 100ms 的 16 位单声道 PCM。 */
private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2

@Composable
actual fun rememberAudioCapture(): AudioCapture {
    val context = LocalContext.current
    val pending = remember { PermissionCallback() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending.deliver(granted)
    }
    return remember(context, launcher) {
        AndroidAudioCapture(context) { onResult ->
            pending.callback = onResult
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private class PermissionCallback {
    var callback: ((Boolean) -> Unit)? = null

    fun deliver(granted: Boolean) {
        callback?.invoke(granted)
        callback = null
    }
}

private class AndroidAudioCapture(
    private val context: Context,
    private val request: ((Boolean) -> Unit) -> Unit,
) : AudioCapture {
    override val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun requestPermission(onResult: (granted: Boolean) -> Unit) = request(onResult)

    // 调用方先检查 hasPermission 再录音。
    @SuppressLint("MissingPermission")
    override fun record(): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 2),
        )
        try {
            recorder.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
