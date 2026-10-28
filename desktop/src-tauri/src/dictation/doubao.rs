//! 豆包流式语音识别客户端（ADR-0005）。
//!
//! 协议见火山引擎文档「大模型流式语音识别 API」：WebSocket 二进制帧，每帧是 4 字节头、
//! 4 字节负载长度和 gzip 压缩的负载。使用「双向流式（优化版）」并开启二遍识别，
//! 只有二遍识别的结果才会标记为定稿句（`definite`）。

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::{sync::mpsc, task::JoinHandle};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Message, client::IntoClientRequest, http::HeaderValue},
};

use super::{AudioFrame, RecognitionEvent};

const ENDPOINT: &str = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";

/// 开始一次识别。返回录音的入口、识别事件的出口和识别任务。
pub fn start(
    api_key: String,
    resource_id: String,
    context: Option<String>,
) -> (
    mpsc::UnboundedSender<AudioFrame>,
    mpsc::UnboundedReceiver<RecognitionEvent>,
    JoinHandle<()>,
) {
    let (audio, audio_rx) = mpsc::unbounded_channel();
    let (events_tx, events) = mpsc::unbounded_channel();
    let task = tokio::spawn(async move {
        if let Err(err) = run(&api_key, &resource_id, context.as_deref(), audio_rx, &events_tx).await {
            tracing::warn!("recognition failed: {err:#}");
            let _ = events_tx.send(RecognitionEvent::Failed(format!("识别失败：{err}")));
        }
    });
    (audio, events, task)
}

async fn run(
    api_key: &str,
    resource_id: &str,
    context: Option<&str>,
    mut audio: mpsc::UnboundedReceiver<AudioFrame>,
    events: &mpsc::UnboundedSender<RecognitionEvent>,
) -> Result<()> {
    let mut request = ENDPOINT.into_client_request()?;
    let headers = request.headers_mut();
    headers.insert("X-Api-Key", HeaderValue::from_str(api_key)?);
    headers.insert("X-Api-Resource-Id", HeaderValue::from_str(resource_id)?);
    headers.insert("X-Api-Connect-Id", HeaderValue::from_str(&uuid::Uuid::new_v4().to_string())?);

    let (socket, response) = connect_async(request)
        .await
        .context("无法连接识别服务")?;
    if let Some(logid) = response.headers().get("X-Tt-Logid") {
        tracing::info!(?logid, "recognition session started");
    }
    let (mut writer, mut reader) = socket.split();
    writer
        .send(Message::Binary(frame::full_client_request(&request_payload(context))?.into()))
        .await?;

    let mut tracker = UtteranceTracker::default();
    let mut audio_open = true;
    loop {
        tokio::select! {
            frame = audio.recv(), if audio_open => {
                let (pcm, last) = match frame {
                    Some(AudioFrame::Pcm(pcm)) => (pcm, false),
                    Some(AudioFrame::End) | None => (Vec::new(), true),
                };
                writer.send(Message::Binary(frame::audio_request(&pcm, last)?.into())).await?;
                audio_open = !last;
            }
            message = reader.next() => {
                let Some(message) = message else {
                    bail!("识别服务提前断开了连接");
                };
                match message? {
                    Message::Binary(data) => match frame::parse_server_response(&data)? {
                        frame::ServerResponse::Result { payload, is_last } => {
                            if let Some(payload) = payload {
                                for event in tracker.update(&payload, is_last) {
                                    let _ = events.send(event);
                                }
                            }
                            if is_last {
                                let _ = events.send(RecognitionEvent::Finished);
                                return Ok(());
                            }
                        }
                        frame::ServerResponse::Error { code, message } => {
                            bail!("错误码 {code}，{message}");
                        }
                    },
                    Message::Close(close) => bail!("识别服务关闭了连接：{close:?}"),
                    _ => {}
                }
            }
        }
    }
}

fn request_payload(context: Option<&str>) -> Value {
    let mut request = json!({
        "model_name": "bigmodel",
        // 二遍识别：用更准的非流式模型重新识别每个分句，只有它的结果才是定稿句。
        "enable_nonstream": true,
        "enable_itn": true,
        "enable_punc": true,
        // 语义顺滑：识别时就去掉语气词和重复，减轻整理的负担。
        "enable_ddc": true,
        "show_utterances": true,
    });
    if let Some(context) = context {
        let dialog_context = json!({
            "context_type": "dialog_ctx",
            "context_data": [{ "text": context }],
        });
        request["corpus"] = json!({ "context": dialog_context.to_string() });
    }
    json!({
        "user": { "uid": "echopad" },
        "audio": { "format": "pcm", "codec": "raw", "rate": 16000, "bits": 16, "channel": 1 },
        "request": request,
    })
}

/// 从全量返回的分句列表里找出新出现的定稿句，其余的拼成临时文字。
#[derive(Default)]
struct UtteranceTracker {
    finalized: usize,
    interim: String,
}

impl UtteranceTracker {
    fn update(&mut self, payload: &Value, is_last: bool) -> Vec<RecognitionEvent> {
        let Some(utterances) = payload.pointer("/result/utterances").and_then(Value::as_array) else {
            return Vec::new();
        };
        let text = |utterance: &Value| utterance["text"].as_str().unwrap_or_default().to_owned();

        let mut events = Vec::new();
        for utterance in &utterances[self.finalized.min(utterances.len())..] {
            // 最后一包之后不会再有二遍结果，剩下的分句也一并定稿。
            if utterance["definite"].as_bool() != Some(true) && !is_last {
                break;
            }
            self.finalized += 1;
            let sentence = text(utterance);
            if !sentence.trim().is_empty() {
                events.push(RecognitionEvent::Final(sentence));
            }
        }

        let interim: String = utterances.iter().skip(self.finalized).map(text).collect();
        if interim != self.interim {
            self.interim.clone_from(&interim);
            events.push(RecognitionEvent::Interim(interim));
        }
        events
    }
}

mod frame {
    use std::io::{Read, Write};

    use anyhow::{Context, Result, bail, ensure};
    use flate2::{Compression, read::GzDecoder, write::GzEncoder};
    use serde_json::Value;

    const VERSION_AND_HEADER_SIZE: u8 = 0b0001_0001;

    const FULL_CLIENT_REQUEST: u8 = 0b0001;
    const AUDIO_ONLY_REQUEST: u8 = 0b0010;
    const FULL_SERVER_RESPONSE: u8 = 0b1001;
    const SERVER_ERROR_RESPONSE: u8 = 0b1111;

    const FLAG_HAS_SEQUENCE: u8 = 0b0001;
    const FLAG_LAST_PACKET: u8 = 0b0010;
    const FLAG_HAS_EVENT: u8 = 0b0100;

    const SERIALIZATION_NONE: u8 = 0b0000;
    const SERIALIZATION_JSON: u8 = 0b0001;
    const COMPRESSION_GZIP: u8 = 0b0001;

    pub enum ServerResponse {
        Result { payload: Option<Value>, is_last: bool },
        Error { code: u32, message: String },
    }

    pub fn full_client_request(payload: &Value) -> Result<Vec<u8>> {
        encode(FULL_CLIENT_REQUEST, 0, SERIALIZATION_JSON, &serde_json::to_vec(payload)?)
    }

    /// 最后一包（负包）可以不带音频。
    pub fn audio_request(pcm: &[u8], last: bool) -> Result<Vec<u8>> {
        let flags = if last { FLAG_LAST_PACKET } else { 0 };
        encode(AUDIO_ONLY_REQUEST, flags, SERIALIZATION_NONE, pcm)
    }

    fn encode(message_type: u8, flags: u8, serialization: u8, payload: &[u8]) -> Result<Vec<u8>> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(payload)?;
        let compressed = encoder.finish()?;

        let mut frame = Vec::with_capacity(8 + compressed.len());
        frame.extend([
            VERSION_AND_HEADER_SIZE,
            (message_type << 4) | flags,
            (serialization << 4) | COMPRESSION_GZIP,
            0,
        ]);
        frame.extend(u32::try_from(compressed.len())?.to_be_bytes());
        frame.extend(compressed);
        Ok(frame)
    }

    pub fn parse_server_response(data: &[u8]) -> Result<ServerResponse> {
        ensure!(data.len() >= 4, "response frame too short");
        let header_size = usize::from(data[0] & 0x0f) * 4;
        let message_type = data[1] >> 4;
        let flags = data[1] & 0x0f;
        let gzip = data[2] & 0x0f == COMPRESSION_GZIP;

        let mut rest = data.get(header_size..).context("response header truncated")?;
        if flags & FLAG_HAS_SEQUENCE != 0 {
            take(&mut rest, 4)?;
        }
        if flags & FLAG_HAS_EVENT != 0 {
            take(&mut rest, 4)?;
        }

        match message_type {
            FULL_SERVER_RESPONSE => {
                let size = read_u32(&mut rest)?;
                let payload = decompress(take(&mut rest, size)?, gzip)?;
                Ok(ServerResponse::Result {
                    payload: if payload.is_empty() { None } else { Some(serde_json::from_slice(&payload)?) },
                    is_last: flags & FLAG_LAST_PACKET != 0,
                })
            }
            SERVER_ERROR_RESPONSE => {
                let code = read_u32(&mut rest)?;
                let size = read_u32(&mut rest)?;
                let message = decompress(take(&mut rest, size)?, gzip)?;
                Ok(ServerResponse::Error {
                    code: code as u32,
                    message: String::from_utf8_lossy(&message).into_owned(),
                })
            }
            other => bail!("unexpected response message type {other:#06b}"),
        }
    }

    fn read_u32(rest: &mut &[u8]) -> Result<usize> {
        let bytes: [u8; 4] = take(rest, 4)?.try_into()?;
        Ok(u32::from_be_bytes(bytes) as usize)
    }

    fn take<'a>(rest: &mut &'a [u8], len: usize) -> Result<&'a [u8]> {
        ensure!(rest.len() >= len, "response frame truncated");
        let (head, tail) = rest.split_at(len);
        *rest = tail;
        Ok(head)
    }

    fn decompress(payload: &[u8], gzip: bool) -> Result<Vec<u8>> {
        if !gzip || payload.is_empty() {
            return Ok(payload.to_vec());
        }
        let mut decompressed = Vec::new();
        GzDecoder::new(payload).read_to_end(&mut decompressed)?;
        Ok(decompressed)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        fn server_frame(flags: u8, sequence: i32, json: &Value) -> Vec<u8> {
            let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
            encoder.write_all(&serde_json::to_vec(json).unwrap()).unwrap();
            let payload = encoder.finish().unwrap();
            let mut frame = vec![VERSION_AND_HEADER_SIZE, (FULL_SERVER_RESPONSE << 4) | flags, 0b0001_0001, 0];
            frame.extend(sequence.to_be_bytes());
            frame.extend((payload.len() as u32).to_be_bytes());
            frame.extend(payload);
            frame
        }

        #[test]
        fn client_requests_follow_the_documented_header() {
            let request = full_client_request(&serde_json::json!({ "a": 1 })).unwrap();
            assert_eq!(request[..4], [0x11, 0x10, 0x11, 0x00]);

            let audio = audio_request(&[1, 2, 3], false).unwrap();
            assert_eq!(audio[..4], [0x11, 0x20, 0x01, 0x00]);

            let last = audio_request(&[], true).unwrap();
            assert_eq!(last[..4], [0x11, 0x22, 0x01, 0x00]);
        }

        #[test]
        fn parses_results_with_sequence_and_last_flag() {
            let json = serde_json::json!({ "result": { "text": "你好" } });
            let ServerResponse::Result { payload, is_last } =
                parse_server_response(&server_frame(0b0011, -3, &json)).unwrap()
            else {
                panic!("expected a result");
            };
            assert!(is_last);
            assert_eq!(payload, Some(json));
        }

        #[test]
        fn parses_error_responses() {
            let message = b"{\"error\":\"invalid\"}";
            let mut frame = vec![VERSION_AND_HEADER_SIZE, SERVER_ERROR_RESPONSE << 4, 0b0001_0000, 0];
            frame.extend(45_000_001u32.to_be_bytes());
            frame.extend((message.len() as u32).to_be_bytes());
            frame.extend(message);

            let ServerResponse::Error { code, message } = parse_server_response(&frame).unwrap() else {
                panic!("expected an error");
            };
            assert_eq!(code, 45_000_001);
            assert!(message.contains("invalid"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload(utterances: Value) -> Value {
        json!({ "result": { "text": "", "utterances": utterances } })
    }

    #[test]
    fn definite_utterances_become_final_sentences_once() {
        let mut tracker = UtteranceTracker::default();

        let events = tracker.update(&payload(json!([{ "text": "今天天", "definite": false }])), false);
        assert_eq!(events, [RecognitionEvent::Interim("今天天".into())]);

        let events = tracker.update(
            &payload(json!([
                { "text": "今天天气不错。", "definite": true },
                { "text": "我们", "definite": false },
            ])),
            false,
        );
        assert_eq!(
            events,
            [RecognitionEvent::Final("今天天气不错。".into()), RecognitionEvent::Interim("我们".into())]
        );

        let events = tracker.update(
            &payload(json!([
                { "text": "今天天气不错。", "definite": true },
                { "text": "我们出去走走。", "definite": false },
            ])),
            true,
        );
        assert_eq!(
            events,
            [RecognitionEvent::Final("我们出去走走。".into()), RecognitionEvent::Interim(String::new())]
        );
    }

    #[test]
    fn context_is_sent_as_dialog_context() {
        let request = request_payload(Some("用户正在「备忘录」中输入文字"));
        let context: Value =
            serde_json::from_str(request["request"]["corpus"]["context"].as_str().unwrap()).unwrap();
        assert_eq!(context["context_type"], "dialog_ctx");
        assert_eq!(context["context_data"][0]["text"], "用户正在「备忘录」中输入文字");
        assert!(request_payload(None)["request"].get("corpus").is_none());
    }
}
