//! 识别事件 → 整理 → 上屏。定稿句按顺序整理，整理完立即上屏（ADR-0006）。

use std::sync::Arc;

use tokio::sync::mpsc;

use super::{Cleaner, Outbound, RecognitionEvent};
use crate::{
    input::{InputCommand, InputSink},
    protocol::{
        CommittedText, DictationFailed, DictationFinished, InterimText, ServerMessage, server_message::Body,
    },
};

/// 整理时作为参考的前文最多保留这么多字。
const PRECEDING_CONTEXT_CHARS: usize = 200;

pub async fn run<C: Cleaner>(
    session_id: u32,
    mut events: mpsc::UnboundedReceiver<RecognitionEvent>,
    cleaner: Arc<C>,
    input: Arc<dyn InputSink>,
    outbound: Outbound,
) {
    let (sentences, queue) = mpsc::unbounded_channel();
    let committer = tokio::spawn(commit_sentences(session_id, queue, cleaner, input, outbound.clone()));

    let mut failure = None;
    let mut last_interim = String::new();
    while let Some(event) = events.recv().await {
        match event {
            RecognitionEvent::Interim(text) => {
                if text != last_interim {
                    send(&outbound, Body::InterimText(InterimText { session_id, text: text.clone() }));
                    last_interim = text;
                }
            }
            RecognitionEvent::Final(sentence) => {
                let _ = sentences.send(sentence);
            }
            RecognitionEvent::Finished => break,
            RecognitionEvent::Failed(message) => {
                failure = Some(message);
                break;
            }
        }
    }

    // 已经定稿的句子照常整理，然后再告诉手机端结束。
    drop(sentences);
    let _ = committer.await;
    send(
        &outbound,
        match failure {
            Some(message) => Body::DictationFailed(DictationFailed { session_id, message }),
            None => Body::DictationFinished(DictationFinished { session_id }),
        },
    );
}

async fn commit_sentences<C: Cleaner>(
    session_id: u32,
    mut queue: mpsc::UnboundedReceiver<String>,
    cleaner: Arc<C>,
    input: Arc<dyn InputSink>,
    outbound: Outbound,
) {
    let mut committed = String::new();
    while let Some(sentence) = queue.recv().await {
        let cleaned = match cleaner.clean(&sentence, tail(&committed, PRECEDING_CONTEXT_CHARS)).await {
            Ok(cleaned) => cleaned,
            Err(err) => {
                // 整理失败时使用识别原文，不丢掉用户说的话。
                tracing::warn!("cleanup failed, using the recognized sentence: {err:#}");
                sentence
            }
        };
        let cleaned = cleaned.trim();
        // 整句都是口头禅时，整理结果为空，什么都不做。
        if cleaned.is_empty() {
            continue;
        }
        let text = format!("{}{cleaned}", separator(&committed, cleaned));
        input.send(InputCommand::Text(text.clone()));
        send(&outbound, Body::CommittedText(CommittedText { session_id, text: cleaned.to_owned() }));
        committed.push_str(&text);
    }
}

/// 英文句子之间补一个空格，中文不需要。
fn separator(committed: &str, next: &str) -> &'static str {
    match (committed.chars().last(), next.chars().next()) {
        (Some(last), Some(first))
            if (last.is_ascii_alphanumeric() || ".,!?;:".contains(last)) && first.is_ascii_alphanumeric() =>
        {
            " "
        }
        _ => "",
    }
}

fn tail(text: &str, max_chars: usize) -> &str {
    let skip = text.chars().count().saturating_sub(max_chars);
    text.char_indices().nth(skip).map_or("", |(index, _)| &text[index..])
}

fn send(outbound: &Outbound, body: Body) {
    let _ = outbound.send(ServerMessage { body: Some(body) });
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    /// 把每句话包上方括号，便于断言整理确实发生了。
    struct BracketCleaner;

    impl Cleaner for BracketCleaner {
        async fn clean(&self, sentence: &str, _preceding: &str) -> anyhow::Result<String> {
            Ok(match sentence {
                "嗯嗯" => String::new(),
                "fail" => anyhow::bail!("cleanup service unavailable"),
                _ => format!("[{sentence}]"),
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

    #[tokio::test]
    async fn only_cleaned_final_sentences_are_committed_in_order() {
        let (input, bodies) = run_events(vec![
            RecognitionEvent::Interim("今天".into()),
            RecognitionEvent::Final("今天天气不错".into()),
            RecognitionEvent::Interim("我们".into()),
            RecognitionEvent::Final("我们出去走走".into()),
            RecognitionEvent::Finished,
        ])
        .await;

        assert_eq!(input.texts(), ["[今天天气不错]", "[我们出去走走]"]);
        let interims: Vec<_> = bodies
            .iter()
            .filter_map(|body| match body {
                Body::InterimText(interim) => Some(interim.text.as_str()),
                _ => None,
            })
            .collect();
        assert_eq!(interims, ["今天", "我们"]);
        assert!(matches!(bodies.last(), Some(Body::DictationFinished(DictationFinished { session_id: 7 }))));
    }

    #[tokio::test]
    async fn filler_only_sentences_are_skipped_and_failed_cleanup_keeps_the_original() {
        let (input, _) = run_events(vec![
            RecognitionEvent::Final("嗯嗯".into()),
            RecognitionEvent::Final("fail".into()),
            RecognitionEvent::Finished,
        ])
        .await;

        assert_eq!(input.texts(), ["fail"]);
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

    #[test]
    fn english_sentences_are_separated_by_a_space() {
        assert_eq!(separator("", "Hello"), "");
        assert_eq!(separator("Hello world.", "Next"), " ");
        assert_eq!(separator("你好。", "下一句"), "");
        assert_eq!(separator("你好。", "Next"), "");
    }

    #[test]
    fn tail_keeps_the_last_characters() {
        assert_eq!(tail("一二三四五", 3), "三四五");
        assert_eq!(tail("短", 3), "短");
    }
}
