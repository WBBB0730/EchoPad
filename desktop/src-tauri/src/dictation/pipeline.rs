//! 识别事件 → 整理 → 上屏。松开后把全部定稿句拼成一段整理一次，整理好一次上屏（ADR-0013）。

use std::sync::Arc;

use tokio::sync::mpsc;

use super::{Cleaner, Outbound, RecognitionEvent};
use crate::{
    input::{InputCommand, InputSink},
    protocol::{DictationFailed, DictationFinished, ServerMessage, server_message::Body},
};

pub async fn run<C: Cleaner>(
    session_id: u32,
    mut events: mpsc::UnboundedReceiver<RecognitionEvent>,
    cleaner: Arc<C>,
    input: Arc<dyn InputSink>,
    outbound: Outbound,
) {
    let mut recognized = String::new();
    let mut failure = None;
    while let Some(event) = events.recv().await {
        match event {
            RecognitionEvent::Final(sentence) => {
                recognized.push_str(separator(&recognized, &sentence));
                recognized.push_str(&sentence);
            }
            RecognitionEvent::Finished => break,
            RecognitionEvent::Failed(message) => {
                failure = Some(message);
                break;
            }
        }
    }

    // 识别出错时，已经定稿的句子照常整理上屏，然后再告诉手机端出错。
    let cleanup_failure = commit(&recognized, cleaner.as_ref(), input.as_ref()).await;
    let body = match failure {
        Some(message) => Body::DictationFailed(DictationFailed { session_id, message }),
        None => Body::DictationFinished(DictationFinished {
            session_id,
            cleanup_failure: cleanup_failure.unwrap_or_default(),
        }),
    };
    let _ = outbound.send(ServerMessage { body: Some(body) });
}

/// 整理并上屏，返回整理失败的原因。
async fn commit<C: Cleaner>(recognized: &str, cleaner: &C, input: &dyn InputSink) -> Option<String> {
    if recognized.is_empty() {
        return None;
    }
    let (text, failure) = match cleaner.clean(recognized).await {
        Ok(cleaned) => (cleaned, None),
        Err(err) => {
            // 整理失败时使用识别原文，不丢掉用户说的话。
            tracing::warn!("cleanup failed, using the recognized text: {err:#}");
            (recognized.to_owned(), Some(format!("{err:#}")))
        }
    };
    // 整段都是口头禅时，整理结果为空，什么都不上屏。
    let text = text.trim();
    if !text.is_empty() {
        input.send(InputCommand::Text(text.to_owned()));
    }
    failure
}

/// 英文句子之间补一个空格，中文不需要。
fn separator(recognized: &str, next: &str) -> &'static str {
    match (recognized.chars().last(), next.chars().next()) {
        (Some(last), Some(first))
            if (last.is_ascii_alphanumeric() || ".,!?;:".contains(last)) && first.is_ascii_alphanumeric() =>
        {
            " "
        }
        _ => "",
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    /// 把整段包上方括号，便于断言整理确实发生了、而且只发生了一次。
    struct BracketCleaner;

    impl Cleaner for BracketCleaner {
        async fn clean(&self, text: &str) -> anyhow::Result<String> {
            Ok(match text {
                "嗯嗯" => String::new(),
                "fail" => anyhow::bail!("cleanup service unavailable"),
                _ => format!("[{text}]"),
            })
        }
    }

    #[derive(Default)]
    struct RecordingInput(Mutex<Vec<InputCommand>>);

    impl InputSink for RecordingInput {
        fn send(&self, command: InputCommand) {
            self.0.lock().unwrap().push(command);
        }
    }

    impl RecordingInput {
        fn texts(&self) -> Vec<String> {
            self.0
                .lock()
                .unwrap()
                .iter()
                .filter_map(|command| match command {
                    InputCommand::Text(text) => Some(text.clone()),
                    _ => None,
                })
                .collect()
        }
    }

    async fn run_events(events: Vec<RecognitionEvent>) -> (Arc<RecordingInput>, Vec<Body>) {
        let (event_tx, event_rx) = mpsc::unbounded_channel();
        for event in events {
            event_tx.send(event).unwrap();
        }
        drop(event_tx);
        let input = Arc::new(RecordingInput::default());
        let (outbound, mut messages) = mpsc::unbounded_channel();
        run(7, event_rx, Arc::new(BracketCleaner), input.clone(), outbound).await;
        let mut bodies = Vec::new();
        while let Ok(message) = messages.try_recv() {
            bodies.extend(message.body);
        }
        (input, bodies)
    }

    fn finished(cleanup_failure: &str) -> Body {
        Body::DictationFinished(DictationFinished { session_id: 7, cleanup_failure: cleanup_failure.into() })
    }

    #[tokio::test]
    async fn all_final_sentences_are_cleaned_once_and_committed_together() {
        let (input, bodies) = run_events(vec![
            RecognitionEvent::Final("明天上午9点开会。".into()),
            RecognitionEvent::Final("不对，是下午3点。".into()),
            RecognitionEvent::Finished,
        ])
        .await;

        assert_eq!(input.texts(), ["[明天上午9点开会。不对，是下午3点。]"]);
        assert_eq!(bodies, [finished("")]);
    }

    #[tokio::test]
    async fn failed_cleanup_commits_the_recognized_text_and_reports_why() {
        let (input, bodies) = run_events(vec![RecognitionEvent::Final("fail".into()), RecognitionEvent::Finished]).await;

        assert_eq!(input.texts(), ["fail"]);
        assert_eq!(bodies, [finished("cleanup service unavailable")]);
    }

    #[tokio::test]
    async fn filler_only_or_silent_dictation_commits_nothing() {
        let (input, bodies) = run_events(vec![RecognitionEvent::Final("嗯嗯".into()), RecognitionEvent::Finished]).await;
        assert!(input.texts().is_empty());
        assert_eq!(bodies, [finished("")]);

        let (input, bodies) = run_events(vec![RecognitionEvent::Finished]).await;
        assert!(input.texts().is_empty(), "nothing recognized, nothing to clean");
        assert_eq!(bodies, [finished("")]);
    }

    #[tokio::test]
    async fn recognition_failure_still_commits_finished_sentences() {
        let (input, bodies) = run_events(vec![
            RecognitionEvent::Final("第一句".into()),
            RecognitionEvent::Failed("识别失败".into()),
        ])
        .await;

        assert_eq!(input.texts(), ["[第一句]"]);
        assert!(matches!(bodies.last(), Some(Body::DictationFailed(failed)) if failed.message == "识别失败"));
    }

    #[tokio::test]
    async fn english_sentences_are_joined_with_a_space() {
        let (input, _) = run_events(vec![
            RecognitionEvent::Final("Hello world.".into()),
            RecognitionEvent::Final("Next one".into()),
            RecognitionEvent::Finished,
        ])
        .await;

        assert_eq!(input.texts(), ["[Hello world. Next one]"]);
    }

    #[test]
    fn only_english_sentences_need_a_separator() {
        assert_eq!(separator("", "Hello"), "");
        assert_eq!(separator("Hello world.", "Next"), " ");
        assert_eq!(separator("你好。", "下一句"), "");
        assert_eq!(separator("你好。", "Next"), "");
    }
}
