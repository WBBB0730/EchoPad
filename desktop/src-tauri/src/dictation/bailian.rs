//! 阿里百炼 Fun-ASR 实时语音识别客户端（ADR-0005）。
//!
//! 协议见阿里云百炼文档「Fun-ASR 实时语音识别 WebSocket API」：先发 run-task 指令，
//! 收到 task-started 后发送二进制音频，服务端用 result-generated 逐句返回结果，
//! `sentence_end` 为真的那条就是定稿句；最后发 finish-task，收到 task-finished 结束。

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::{sync::mpsc, task::JoinHandle};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Message, client::IntoClientRequest, http::HeaderValue},
};

use super::{AudioFrame, RecognitionEvent};

const ENDPOINT: &str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
const MODEL: &str = "fun-asr-realtime";
/// 与豆包一致：停顿 0.8 秒判定一句话说完，尽快得到定稿句。
const SENTENCE_SILENCE_MS: u32 = 800;

/// 开始一次识别。返回录音的入口、识别事件的出口和识别任务。
pub fn start(
    api_key: String,
) -> (
    mpsc::UnboundedSender<AudioFrame>,
    mpsc::UnboundedReceiver<RecognitionEvent>,
    JoinHandle<()>,
) {
    let (audio, audio_rx) = mpsc::unbounded_channel();
    let (events_tx, events) = mpsc::unbounded_channel();
    let task = tokio::spawn(async move {
        if let Err(err) = run(&api_key, audio_rx, &events_tx).await {
            tracing::warn!("recognition failed: {err:#}");
            let _ = events_tx.send(RecognitionEvent::Failed(format!("识别失败：{err}")));
        }
    });
    (audio, events, task)
}

async fn run(
    api_key: &str,
    mut audio: mpsc::UnboundedReceiver<AudioFrame>,
    events: &mpsc::UnboundedSender<RecognitionEvent>,
) -> Result<()> {
    let mut request = ENDPOINT.into_client_request()?;
    request
        .headers_mut()
        .insert("Authorization", HeaderValue::from_str(&format!("Bearer {api_key}"))?);
    let (socket, _) = connect_async(request).await.context("无法连接识别服务")?;
    let (mut writer, mut reader) = socket.split();

    let task_id = uuid::Uuid::new_v4().simple().to_string();
    writer.send(Message::Text(run_task(&task_id).to_string().into())).await?;

    // 收到 task-started 之后才能发送音频，在此之前录音留在通道里排队。
    loop {
        match next_event(&mut reader).await? {
            ServerEvent::Started => break,
            ServerEvent::Failed(message) => bail!("{message}"),
            ServerEvent::Result { .. } | ServerEvent::Finished | ServerEvent::Other => {}
        }
    }

    let mut audio_open = true;
    loop {
        tokio::select! {
            frame = audio.recv(), if audio_open => match frame {
                Some(AudioFrame::Pcm(pcm)) => writer.send(Message::Binary(pcm.into())).await?,
                Some(AudioFrame::End) | None => {
                    writer.send(Message::Text(finish_task(&task_id).to_string().into())).await?;
                    audio_open = false;
                }
            },
            event = next_event(&mut reader) => match event? {
                ServerEvent::Result { text, sentence_end } => {
                    if sentence_end {
                        if !text.trim().is_empty() {
                            let _ = events.send(RecognitionEvent::Final(text));
                        }
                        let _ = events.send(RecognitionEvent::Interim(String::new()));
                    } else {
                        let _ = events.send(RecognitionEvent::Interim(text));
                    }
                }
                ServerEvent::Finished => {
                    let _ = events.send(RecognitionEvent::Finished);
                    return Ok(());
                }
                ServerEvent::Failed(message) => bail!("{message}"),
                ServerEvent::Started | ServerEvent::Other => {}
            },
        }
    }
}

fn run_task(task_id: &str) -> Value {
    json!({
        "header": { "action": "run-task", "task_id": task_id, "streaming": "duplex" },
        "payload": {
            "task_group": "audio",
            "task": "asr",
            "function": "recognition",
            "model": MODEL,
            "parameters": {
                "format": "pcm",
                "sample_rate": 16000,
                "max_sentence_silence": SENTENCE_SILENCE_MS,
            },
            "input": {},
        },
    })
}

fn finish_task(task_id: &str) -> Value {
    json!({
        "header": { "action": "finish-task", "task_id": task_id, "streaming": "duplex" },
        "payload": { "input": {} },
    })
}

#[derive(Debug, PartialEq, Eq)]
enum ServerEvent {
    Started,
    Result { text: String, sentence_end: bool },
    Finished,
    Failed(String),
    Other,
}

async fn next_event<S>(reader: &mut S) -> Result<ServerEvent>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    loop {
        let Some(message) = reader.next().await else {
            bail!("识别服务提前断开了连接");
        };
        match message? {
            Message::Text(text) => return parse_event(&text),
            Message::Close(close) => bail!("识别服务关闭了连接：{close:?}"),
            _ => {}
        }
    }
}

fn parse_event(text: &str) -> Result<ServerEvent> {
    let event: Value = serde_json::from_str(text)?;
    let header = &event["header"];
    Ok(match header["event"].as_str() {
        Some("task-started") => ServerEvent::Started,
        Some("task-finished") => ServerEvent::Finished,
        Some("task-failed") => ServerEvent::Failed(format!(
            "错误码 {}，{}",
            header["error_code"].as_str().unwrap_or_default(),
            header["error_message"].as_str().unwrap_or_default(),
        )),
        Some("result-generated") => {
            let sentence = &event["payload"]["output"]["sentence"];
            // 开启静音保活时会收到心跳结果，不含识别内容。
            if sentence["heartbeat"].as_bool() == Some(true) {
                ServerEvent::Other
            } else {
                ServerEvent::Result {
                    text: sentence["text"].as_str().unwrap_or_default().to_owned(),
                    sentence_end: sentence["sentence_end"].as_bool() == Some(true),
                }
            }
        }
        _ => ServerEvent::Other,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_task_requests_16k_pcm_from_fun_asr() {
        let task = run_task("abc");
        assert_eq!(task["header"]["action"], "run-task");
        assert_eq!(task["header"]["streaming"], "duplex");
        assert_eq!(task["payload"]["model"], "fun-asr-realtime");
        assert_eq!(task["payload"]["parameters"]["format"], "pcm");
        assert_eq!(task["payload"]["parameters"]["sample_rate"], 16000);
        assert_eq!(finish_task("abc")["header"]["task_id"], "abc");
    }

    #[test]
    fn parses_server_events() {
        let result = |end: bool| {
            json!({
                "header": { "event": "result-generated" },
                "payload": { "output": { "sentence": { "text": "你好", "sentence_end": end } } },
            })
            .to_string()
        };
        assert_eq!(parse_event(&result(false)).unwrap(), ServerEvent::Result { text: "你好".into(), sentence_end: false });
        assert_eq!(parse_event(&result(true)).unwrap(), ServerEvent::Result { text: "你好".into(), sentence_end: true });

        let started = json!({ "header": { "event": "task-started" }, "payload": {} }).to_string();
        assert_eq!(parse_event(&started).unwrap(), ServerEvent::Started);

        let failed = json!({
            "header": { "event": "task-failed", "error_code": "InvalidApiKey", "error_message": "bad key" },
            "payload": {},
        })
        .to_string();
        assert_eq!(parse_event(&failed).unwrap(), ServerEvent::Failed("错误码 InvalidApiKey，bad key".into()));

        let heartbeat = json!({
            "header": { "event": "result-generated" },
            "payload": { "output": { "sentence": { "text": "", "heartbeat": true } } },
        })
        .to_string();
        assert_eq!(parse_event(&heartbeat).unwrap(), ServerEvent::Other);
    }
}
