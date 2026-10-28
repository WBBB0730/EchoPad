//! 语音输入：把手机端的录音交给识别服务，再把每个定稿句整理后上屏或发回输入框（docs/prd/dictation.md）。

mod bailian;
mod cleanup;
mod doubao;
mod pipeline;

use std::{future::Future, sync::Arc};

use tokio::{sync::mpsc, task::JoinHandle};

use crate::{
    input::InputSink,
    protocol::ServerMessage,
    screen_context,
    settings::{DictationConfig, RecognitionConfig},
};

pub use cleanup::ChatCleaner;
pub use pipeline::run as run_pipeline;

/// 发往手机端的消息。
pub type Outbound = mpsc::UnboundedSender<ServerMessage>;

pub enum AudioFrame {
    /// 16kHz、16 位、单声道 PCM。
    Pcm(Vec<u8>),
    /// 用户松开了说话按钮。
    End,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecognitionEvent {
    /// 当前这句话的临时文字，可能还会被改写。
    Interim(String),
    /// 识别服务确认不再改写的定稿句。
    Final(String),
    /// 最后一个定稿句之后，识别结束。
    Finished,
    Failed(String),
}

/// 对一个定稿句做整理（ADR-0006）。
pub trait Cleaner: Send + Sync + 'static {
    /// `preceding` 是本次语音输入里已经整理好的文字，只作参考。
    fn clean(&self, sentence: &str, preceding: &str) -> impl Future<Output = anyhow::Result<String>> + Send;
}

/// 整理关闭时使用：定稿句原样使用。
pub struct Verbatim;

impl Cleaner for Verbatim {
    async fn clean(&self, sentence: &str, _preceding: &str) -> anyhow::Result<String> {
        Ok(sentence.to_owned())
    }
}

pub trait DictationService: Send + Sync + 'static {
    fn start(&self, session_id: u32, input: Arc<dyn InputSink>, outbound: Outbound) -> DictationHandle;
}

/// 一次进行中的语音输入。被丢弃时中止所有尚未处理完的内容。
pub struct DictationHandle {
    audio: mpsc::UnboundedSender<AudioFrame>,
    tasks: Vec<JoinHandle<()>>,
}

impl DictationHandle {
    pub fn new(audio: mpsc::UnboundedSender<AudioFrame>, tasks: Vec<JoinHandle<()>>) -> Self {
        Self { audio, tasks }
    }

    pub fn push_audio(&self, pcm: Vec<u8>) {
        let _ = self.audio.send(AudioFrame::Pcm(pcm));
    }

    pub fn finish(&self) {
        let _ = self.audio.send(AudioFrame::End);
    }

    pub fn is_finished(&self) -> bool {
        self.tasks.iter().all(JoinHandle::is_finished)
    }
}

impl Drop for DictationHandle {
    fn drop(&mut self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

/// 用用户选择的云端识别服务和整理服务完成语音输入（ADR-0005、ADR-0006、ADR-0007）。
pub struct CloudDictation {
    recognition: RecognitionConfig,
    cleaner: Option<Arc<ChatCleaner>>,
}

impl CloudDictation {
    pub fn new(config: DictationConfig) -> Self {
        Self {
            recognition: config.recognition,
            cleaner: config.cleanup.map(|cleanup| Arc::new(ChatCleaner::new(cleanup))),
        }
    }
}

impl DictationService for CloudDictation {
    fn start(&self, session_id: u32, input: Arc<dyn InputSink>, outbound: Outbound) -> DictationHandle {
        let (audio, events, recognition) = match &self.recognition {
            RecognitionConfig::Doubao { api_key, resource_id } => {
                doubao::start(api_key.clone(), resource_id.clone(), screen_context::current())
            }
            RecognitionConfig::Bailian { api_key } => bailian::start(api_key.clone()),
        };
        let pipeline = match &self.cleaner {
            Some(cleaner) => tokio::spawn(run_pipeline(session_id, events, cleaner.clone(), input, outbound)),
            None => tokio::spawn(run_pipeline(session_id, events, Arc::new(Verbatim), input, outbound)),
        };
        DictationHandle::new(audio, vec![recognition, pipeline])
    }
}
