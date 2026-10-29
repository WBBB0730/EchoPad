//! 语音输入的服务设置（ADR-0007、docs/prd/dictation.md）。
//! 服务选择和密钥一起保存在本机的设置文件里，由用户自己管理；文件只有当前用户可以读写。

use std::{collections::BTreeMap, fs, io, path::PathBuf, sync::Mutex};

use anyhow::{Context, Result};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{Value, json};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub recognition: RecognitionSettings,
    pub cleanup: CleanupSettings,
    /// 用户填写的密钥。同一家服务商只存一份。
    #[serde(default)]
    pub keys: BTreeMap<Credential, String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecognitionSettings {
    pub service: RecognitionService,
    /// 豆包的资源：模型版本和计费方式，需要和用户在控制台开通的一致。
    pub doubao_resource: DoubaoResource,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CleanupSettings {
    pub enabled: bool,
    #[serde(deserialize_with = "known_preset_or_default")]
    pub preset: CleanupPreset,
    /// 只有自定义预设使用，其他预设的地址是固定的。
    pub base_url: String,
    pub model: String,
}

/// 已经去掉的预设（例如本机 Ollama）读出来时换成默认预设，旧的设置文件照样能读。
fn known_preset_or_default<'de, D: Deserializer<'de>>(deserializer: D) -> Result<CleanupPreset, D::Error> {
    let value = Value::deserialize(deserializer)?;
    Ok(CleanupPreset::deserialize(value).unwrap_or_default())
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            recognition: RecognitionSettings {
                service: RecognitionService::Doubao,
                doubao_resource: DoubaoResource::Seed2Duration,
            },
            cleanup: CleanupSettings {
                enabled: true,
                preset: CleanupPreset::default(),
                base_url: String::new(),
                model: CleanupPreset::default().default_model().to_owned(),
            },
            keys: BTreeMap::new(),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RecognitionService {
    Doubao,
    Bailian,
}

impl RecognitionService {
    pub const ALL: [Self; 2] = [Self::Doubao, Self::Bailian];

    pub fn name(self) -> &'static str {
        match self {
            Self::Doubao => "豆包语音",
            Self::Bailian => "阿里百炼",
        }
    }

    pub fn credential(self) -> Credential {
        match self {
            Self::Doubao => Credential::VolcengineSpeech,
            Self::Bailian => Credential::DashScope,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum DoubaoResource {
    Seed2Duration,
    Seed2Concurrent,
    Big1Duration,
    Big1Concurrent,
}

impl DoubaoResource {
    pub const ALL: [Self; 4] = [Self::Seed2Duration, Self::Seed2Concurrent, Self::Big1Duration, Self::Big1Concurrent];

    pub fn name(self) -> &'static str {
        match self {
            Self::Seed2Duration => "2.0 小时版",
            Self::Seed2Concurrent => "2.0 并发版",
            Self::Big1Duration => "1.0 小时版",
            Self::Big1Concurrent => "1.0 并发版",
        }
    }

    pub fn resource_id(self) -> &'static str {
        match self {
            Self::Seed2Duration => "volc.seedasr.sauc.duration",
            Self::Seed2Concurrent => "volc.seedasr.sauc.concurrent",
            Self::Big1Duration => "volc.bigasr.sauc.duration",
            Self::Big1Concurrent => "volc.bigasr.sauc.concurrent",
        }
    }
}

/// 整理服务的预设，都兼容 OpenAI Chat Completions。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum CleanupPreset {
    #[default]
    Ark,
    Bailian,
    DeepSeek,
    OpenAi,
    OpenRouter,
    Custom,
}

/// 预设服务里可选的一个整理模型。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct CleanupModel {
    /// 请求里用的模型名。
    pub id: &'static str,
    /// 设置界面里显示的名称。
    pub name: &'static str,
}

impl CleanupPreset {
    pub const ALL: [Self; 6] = [Self::Ark, Self::Bailian, Self::DeepSeek, Self::OpenAi, Self::OpenRouter, Self::Custom];

    pub fn name(self) -> &'static str {
        match self {
            Self::Ark => "火山方舟",
            Self::Bailian => "阿里百炼",
            Self::DeepSeek => "DeepSeek",
            Self::OpenAi => "OpenAI",
            Self::OpenRouter => "OpenRouter",
            Self::Custom => "自定义",
        }
    }

    /// 预设的服务地址；自定义预设由用户填写。
    pub fn base_url(self) -> Option<&'static str> {
        match self {
            Self::Ark => Some("https://ark.cn-beijing.volces.com/api/v3"),
            Self::Bailian => Some("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            Self::DeepSeek => Some("https://api.deepseek.com"),
            Self::OpenAi => Some("https://api.openai.com/v1"),
            Self::OpenRouter => Some("https://openrouter.ai/api/v1"),
            Self::Custom => None,
        }
    }

    /// 可选的模型，第一个是默认。都是响应快、能关闭深度思考的型号，用户只能从中选择（ADR-0007）；
    /// 自定义地址由用户填写模型，列表为空。
    pub fn models(self) -> &'static [CleanupModel] {
        match self {
            Self::Ark => &[
                CleanupModel { id: "doubao-seed-2-0-mini-260428", name: "豆包 Seed 2.0 mini" },
                CleanupModel { id: "deepseek-v4-1-flash-261017", name: "DeepSeek V4.1 Flash" },
            ],
            Self::Bailian => &[
                CleanupModel { id: "qwen3.8-flash", name: "Qwen3.8 Flash" },
                CleanupModel { id: "qwen3.7-flash", name: "Qwen3.7 Flash" },
            ],
            Self::DeepSeek => &[CleanupModel { id: "deepseek-flash", name: "DeepSeek V4.1 Flash" }],
            Self::OpenAi => &[
                CleanupModel { id: "gpt-6-luna", name: "GPT-6 Luna" },
                CleanupModel { id: "gpt-5.4-mini", name: "GPT-5.4 mini" },
            ],
            Self::OpenRouter => &[
                CleanupModel { id: "openai/gpt-6-luna", name: "GPT-6 Luna" },
                CleanupModel { id: "google/gemini-3.1-flash-lite", name: "Gemini 3.1 Flash Lite" },
                CleanupModel { id: "qwen/qwen3.8-flash", name: "Qwen3.8 Flash" },
            ],
            Self::Custom => &[],
        }
    }

    /// 默认模型；自定义地址为空，由用户填写。
    pub fn default_model(self) -> &'static str {
        self.models().first().map_or("", |model| model.id)
    }

    /// 关闭深度思考的请求参数，各家写法不同，列表里的模型都支持。自定义地址的模型未知，不附加。
    pub fn extra_body(self) -> Value {
        match self {
            Self::Ark | Self::DeepSeek => json!({ "thinking": { "type": "disabled" } }),
            Self::Bailian => json!({ "enable_thinking": false }),
            Self::OpenAi => json!({ "reasoning_effort": "none" }),
            Self::OpenRouter => json!({ "reasoning": { "enabled": false } }),
            Self::Custom => json!({}),
        }
    }

    pub fn credential(self) -> Credential {
        match self {
            Self::Ark => Credential::VolcengineArk,
            Self::Bailian => Credential::DashScope,
            Self::DeepSeek => Credential::DeepSeek,
            Self::OpenAi => Credential::OpenAi,
            Self::OpenRouter => Credential::OpenRouter,
            Self::Custom => Credential::CustomCleanup,
        }
    }
}

/// 一项密钥。同一家服务商只存一份，例如识别和整理都用阿里百炼时共用一个密钥。
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Credential {
    VolcengineSpeech,
    VolcengineArk,
    DashScope,
    DeepSeek,
    OpenAi,
    OpenRouter,
    CustomCleanup,
}

/// 本机的设置文件。
pub struct SettingsStore {
    path: PathBuf,
    settings: Mutex<Settings>,
}

impl SettingsStore {
    pub fn load(path: PathBuf) -> Result<Self> {
        let settings = match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice::<Settings>(&bytes)
                .with_context(|| format!("failed to parse {}", path.display()))?
                .with_listed_model(),
            Err(err) if err.kind() == io::ErrorKind::NotFound => Settings::default(),
            Err(err) => return Err(err).with_context(|| format!("failed to read {}", path.display())),
        };
        Ok(Self {
            path,
            settings: Mutex::new(settings),
        })
    }

    pub fn get(&self) -> Settings {
        self.settings.lock().unwrap().clone()
    }

    /// 保存前去掉密钥两端的空白，并丢弃空的密钥。
    pub fn save(&self, settings: Settings) -> Result<()> {
        let mut settings = settings.with_listed_model();
        settings.keys = settings
            .keys
            .into_iter()
            .map(|(credential, key)| (credential, key.trim().to_owned()))
            .filter(|(_, key)| !key.is_empty())
            .collect();
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        write_private(&self.path, &serde_json::to_vec_pretty(&settings)?)
            .with_context(|| format!("failed to write {}", self.path.display()))?;
        *self.settings.lock().unwrap() = settings;
        Ok(())
    }
}

/// 文件里有密钥，只允许当前用户读写。
fn write_private(path: &std::path::Path, contents: &[u8]) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt, os::unix::fs::PermissionsExt};
        let mut file = fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
        // 文件已存在时 mode 不生效，单独收紧一次权限。
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
        file.write_all(contents)
    }
    #[cfg(not(unix))]
    {
        fs::write(path, contents)
    }
}

/// 语音输入实际使用的配置，由设置合成。
#[derive(Clone)]
pub struct DictationConfig {
    pub recognition: RecognitionConfig,
    /// 整理关闭时为 `None`，定稿句原样使用。
    pub cleanup: Option<CleanupConfig>,
}

#[derive(Clone)]
pub enum RecognitionConfig {
    Doubao { api_key: String, resource_id: String },
    Bailian { api_key: String },
}

#[derive(Clone)]
pub struct CleanupConfig {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub extra_body: Value,
}

impl Settings {
    fn key(&self, credential: Credential) -> Option<String> {
        self.keys.get(&credential).map(|key| key.trim().to_owned()).filter(|key| !key.is_empty())
    }

    /// 预设的模型只能从列表里选：保存的模型不在列表里时（例如旧版本的默认模型），改用默认模型。
    fn with_listed_model(mut self) -> Self {
        let models = self.cleanup.preset.models();
        if !models.is_empty() && !models.iter().any(|model| model.id == self.cleanup.model) {
            self.cleanup.model = self.cleanup.preset.default_model().to_owned();
        }
        self
    }

    /// 缺少必需的密钥、地址或模型时返回 `None`，此时语音输入不可用。
    pub fn dictation_config(&self) -> Option<DictationConfig> {
        let service = self.recognition.service;
        let api_key = self.key(service.credential())?;
        let recognition = match service {
            RecognitionService::Doubao => RecognitionConfig::Doubao {
                api_key,
                resource_id: self.recognition.doubao_resource.resource_id().to_owned(),
            },
            RecognitionService::Bailian => RecognitionConfig::Bailian { api_key },
        };

        let cleanup = if self.cleanup.enabled {
            let preset = self.cleanup.preset;
            let base_url = preset.base_url().unwrap_or(&self.cleanup.base_url).trim().to_owned();
            let model = self.cleanup.model.trim().to_owned();
            let api_key = self.key(preset.credential())?;
            if base_url.is_empty() || model.is_empty() {
                return None;
            }
            Some(CleanupConfig {
                extra_body: preset.extra_body(),
                base_url,
                api_key,
                model,
            })
        } else {
            None
        };

        Some(DictationConfig { recognition, cleanup })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path() -> PathBuf {
        std::env::temp_dir()
            .join(format!("echopad-settings-{}", uuid::Uuid::new_v4()))
            .join("settings.json")
    }

    fn with_keys(keys: &[(Credential, &str)]) -> Settings {
        Settings {
            keys: keys.iter().map(|(credential, key)| (*credential, (*key).to_owned())).collect(),
            ..Settings::default()
        }
    }

    #[test]
    fn settings_and_keys_round_trip_through_a_private_file() {
        let path = temp_path();
        let store = SettingsStore::load(path.clone()).unwrap();
        assert_eq!(store.get(), Settings::default());

        let mut settings = with_keys(&[(Credential::DashScope, " dashscope-key "), (Credential::OpenAi, "  ")]);
        settings.recognition.service = RecognitionService::Bailian;
        store.save(settings).unwrap();

        let saved = SettingsStore::load(path.clone()).unwrap().get();
        assert_eq!(saved.recognition.service, RecognitionService::Bailian);
        assert_eq!(saved.keys, BTreeMap::from([(Credential::DashScope, "dashscope-key".to_owned())]));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        }
    }

    #[test]
    fn dictation_needs_the_recognition_key() {
        assert!(Settings::default().dictation_config().is_none());
        assert!(
            with_keys(&[(Credential::VolcengineSpeech, "speech-key")]).dictation_config().is_none(),
            "cleanup is enabled but has no key"
        );

        let config = with_keys(&[(Credential::VolcengineSpeech, "speech-key"), (Credential::VolcengineArk, "ark-key")])
            .dictation_config()
            .unwrap();
        assert!(matches!(
            config.recognition,
            RecognitionConfig::Doubao { ref api_key, ref resource_id }
                if api_key == "speech-key" && resource_id == "volc.seedasr.sauc.duration"
        ));
        let cleanup = config.cleanup.unwrap();
        assert_eq!(cleanup.api_key, "ark-key");
        assert_eq!(cleanup.model, "doubao-seed-2-0-mini-260428");
        assert_eq!(cleanup.extra_body, json!({ "thinking": { "type": "disabled" } }));
    }

    #[test]
    fn cleanup_can_be_turned_off() {
        let mut settings = with_keys(&[(Credential::VolcengineSpeech, "speech-key")]);
        settings.cleanup.enabled = false;
        assert!(settings.dictation_config().unwrap().cleanup.is_none());
    }

    #[test]
    fn one_key_serves_both_services_of_the_same_provider() {
        let mut settings = with_keys(&[(Credential::DashScope, "dashscope-key")]);
        settings.recognition.service = RecognitionService::Bailian;
        settings.cleanup.preset = CleanupPreset::Bailian;
        settings.cleanup.model = CleanupPreset::Bailian.default_model().into();

        let config = settings.dictation_config().unwrap();
        assert!(matches!(config.recognition, RecognitionConfig::Bailian { ref api_key } if api_key == "dashscope-key"));
        assert_eq!(config.cleanup.unwrap().api_key, "dashscope-key");
    }

    #[test]
    fn every_preset_offers_listed_models_except_the_custom_address() {
        for preset in CleanupPreset::ALL {
            let models = preset.models();
            assert_eq!(models.is_empty(), preset == CleanupPreset::Custom, "{preset:?}");
            assert_eq!(preset.default_model(), models.first().map_or("", |model| model.id));
        }
    }

    #[test]
    fn custom_address_uses_the_typed_model_without_vendor_parameters() {
        let mut settings = with_keys(&[(Credential::VolcengineSpeech, "speech-key"), (Credential::CustomCleanup, "key")]);
        settings.cleanup.preset = CleanupPreset::Custom;
        settings.cleanup.base_url = "https://llm.example.com/v1".into();
        settings.cleanup.model = "my-model".into();

        let cleanup = settings.dictation_config().unwrap().cleanup.unwrap();
        assert_eq!(cleanup.base_url, "https://llm.example.com/v1");
        assert_eq!(cleanup.model, "my-model");
        assert_eq!(cleanup.extra_body, json!({}));
    }

    #[test]
    fn settings_from_older_versions_switch_to_a_listed_model() {
        let load = |cleanup: Value| {
            let path = temp_path();
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            let file = json!({
                "recognition": { "service": "doubao", "doubaoResource": "seed2Duration" },
                "cleanup": cleanup,
            });
            fs::write(&path, file.to_string()).unwrap();
            SettingsStore::load(path).unwrap().get().cleanup
        };

        // 旧版本的默认模型已经不在列表里。
        let cleanup = load(json!({ "enabled": true, "preset": "ark", "baseUrl": "", "model": "doubao-seed-2-1-lite-260915" }));
        assert_eq!(cleanup.model, "doubao-seed-2-0-mini-260428");

        // 本机 Ollama 已经去掉。
        let cleanup = load(json!({ "enabled": true, "preset": "ollama", "baseUrl": "", "model": "qwen3" }));
        assert_eq!((cleanup.preset, cleanup.model.as_str()), (CleanupPreset::Ark, "doubao-seed-2-0-mini-260428"));

        // 自定义地址的模型由用户填写，保持不变。
        let cleanup = load(json!({ "enabled": true, "preset": "custom", "baseUrl": "https://llm.example.com/v1", "model": "my-model" }));
        assert_eq!(cleanup.model, "my-model");
    }
}
