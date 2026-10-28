import { type ReactNode, useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { ChevronLeftIcon, EyeIcon, EyeOffIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";

type Credential =
  "volcengineSpeech" | "volcengineArk" | "dashScope" | "deepSeek" | "openAi" | "openRouter" | "customCleanup";

type Settings = {
  recognition: { service: string; doubaoResource: string };
  cleanup: { enabled: boolean; preset: string; baseUrl: string; model: string };
  keys: Partial<Record<Credential, string>>;
};

type Choice = { id: string; name: string; credential: Credential | null };

type Preset = {
  id: string;
  name: string;
  baseUrl: string | null;
  defaultModel: string;
  credential: Credential | null;
};

type SettingsView = {
  settings: Settings;
  recognitionServices: Choice[];
  doubaoResources: Choice[];
  cleanupPresets: Preset[];
};

export function SettingsPage({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<SettingsView | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    invoke<SettingsView>("get_settings").then((next) => {
      setView(next);
      setSettings(next.settings);
    });
  }, []);

  if (!view || !settings) {
    return null;
  }

  const recognition = view.recognitionServices.find((service) => service.id === settings.recognition.service);
  const preset = view.cleanupPresets.find((item) => item.id === settings.cleanup.preset);

  function update(change: (draft: Settings) => void) {
    setSettings((current) => {
      if (!current) return current;
      const draft = structuredClone(current);
      change(draft);
      return draft;
    });
  }

  function choosePreset(id: string) {
    const next = view?.cleanupPresets.find((item) => item.id === id);
    update((draft) => {
      draft.cleanup.preset = id;
      draft.cleanup.model = next?.defaultModel ?? "";
    });
  }

  async function save() {
    setSaving(true);
    setError("");
    try {
      await invoke<SettingsView>("save_settings", { settings });
      onClose();
    } catch (err) {
      setError(String(err));
    } finally {
      setSaving(false);
    }
  }

  function keyField(credential: Credential | null) {
    if (!credential) return null;
    return (
      <KeyInput
        value={settings?.keys[credential] ?? ""}
        onChange={(key) => update((draft) => (draft.keys[credential] = key))}
      />
    );
  }

  return (
    <main className="flex h-screen flex-col">
      <header className="flex items-center gap-1 px-4 pt-4 pb-2">
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="返回">
          <ChevronLeftIcon />
        </Button>
        <h1 className="text-sm font-medium">设置</h1>
      </header>

      <div className="flex-1 space-y-4 overflow-y-auto px-4 pb-4">
        <Card>
          <CardHeader>
            <CardTitle>语音识别</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Field label="服务">
              <ChoiceSelect
                items={view.recognitionServices}
                value={settings.recognition.service}
                onChange={(service) => update((draft) => (draft.recognition.service = service))}
              />
            </Field>
            {settings.recognition.service === "doubao" && (
              <Field label="开通的资源">
                <ChoiceSelect
                  items={view.doubaoResources}
                  value={settings.recognition.doubaoResource}
                  onChange={(resource) => update((draft) => (draft.recognition.doubaoResource = resource))}
                />
              </Field>
            )}
            {keyField(recognition?.credential ?? null)}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>文字整理</CardTitle>
            <CardAction>
              <Switch
                checked={settings.cleanup.enabled}
                onCheckedChange={(enabled) => update((draft) => (draft.cleanup.enabled = enabled))}
                aria-label="文字整理"
              />
            </CardAction>
          </CardHeader>
          {settings.cleanup.enabled && (
            <CardContent className="flex flex-col gap-4">
              <Field label="服务">
                <ChoiceSelect items={view.cleanupPresets} value={settings.cleanup.preset} onChange={choosePreset} />
              </Field>
              {preset?.baseUrl === null && (
                <Field label="服务地址">
                  <Input
                    className="text-sm"
                    placeholder="https://…/v1"
                    value={settings.cleanup.baseUrl}
                    onChange={(event) => update((draft) => (draft.cleanup.baseUrl = event.target.value))}
                  />
                </Field>
              )}
              <Field label="模型">
                <Input
                  className="text-sm"
                  placeholder={preset?.defaultModel || "模型名称"}
                  value={settings.cleanup.model}
                  onChange={(event) => update((draft) => (draft.cleanup.model = event.target.value))}
                />
              </Field>
              {keyField(preset?.credential ?? null)}
            </CardContent>
          )}
        </Card>
      </div>

      <footer className="flex flex-col gap-2 border-t p-4">
        {error && <p className="text-sm text-destructive select-text">{error}</p>}
        <Button size="lg" onClick={save} disabled={saving}>
          保存
        </Button>
      </footer>
    </main>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <Label>{label}</Label>
      {children}
    </div>
  );
}

function ChoiceSelect({
  items,
  value,
  onChange,
}: {
  items: { id: string; name: string }[];
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Select
      items={items.map((item) => ({ value: item.id, label: item.name }))}
      value={value}
      onValueChange={(next) => next !== null && onChange(next)}
    >
      <SelectTrigger className="w-full">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {items.map((item) => (
          <SelectItem key={item.id} value={item.id}>
            {item.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function KeyInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [visible, setVisible] = useState(false);
  return (
    <Field label="API Key">
      <div className="relative">
        <Input
          type={visible ? "text" : "password"}
          autoComplete="off"
          spellCheck={false}
          className="pr-9 font-mono text-sm"
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
        <Button
          variant="ghost"
          size="icon-xs"
          className="absolute top-1/2 right-1.5 -translate-y-1/2 text-muted-foreground"
          onClick={() => setVisible(!visible)}
          aria-label={visible ? "隐藏" : "显示"}
        >
          {visible ? <EyeOffIcon /> : <EyeIcon />}
        </Button>
      </div>
    </Field>
  );
}
