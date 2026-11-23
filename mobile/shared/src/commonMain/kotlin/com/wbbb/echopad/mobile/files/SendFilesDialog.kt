package com.wbbb.echopad.mobile.files

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.wbbb.echopad.mobile.ui.EchoPadColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 发剪贴板里的文件之前问用户（docs/prd/send-files.md）：[ask] 等到用户回答，界面在 [asking] 不为 null 时显示 [SendFilesDialog]。 */
class SendFilesPrompt {
    private val _asking = MutableStateFlow<Question?>(null)
    val asking: StateFlow<Question?> = _asking.asStateFlow()

    /** 返回用户是否要发。 */
    suspend fun ask(pending: PendingFiles): Boolean {
        val question = Question(pending.question)
        _asking.value = question
        return try {
            question.reply.await()
        } finally {
            _asking.value = null
        }
    }

    class Question(val text: String) {
        internal val reply = CompletableDeferred<Boolean>()

        fun answer(send: Boolean) {
            reply.complete(send)
        }
    }
}

/** 发送剪贴板里的文件前的确认框（DESIGN.md）。 */
@Composable
fun SendFilesDialog(question: SendFilesPrompt.Question) {
    AlertDialog(
        onDismissRequest = { question.answer(false) },
        text = { Text(question.text) },
        confirmButton = {
            TextButton(onClick = { question.answer(true) }) {
                Text("发送", color = EchoPadColors.Text)
            }
        },
        dismissButton = {
            TextButton(onClick = { question.answer(false) }) {
                Text("取消", color = EchoPadColors.TextMuted)
            }
        },
        containerColor = EchoPadColors.Surface,
        textContentColor = EchoPadColors.Text,
    )
}
