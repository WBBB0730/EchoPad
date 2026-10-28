import { type ReactNode, useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { SettingsIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { SettingsPage } from "./SettingsPage";

type Status = {
  pairingLink: string;
  pairingQrSvg: string;
  connectedDevices: string[];
  inputPermission: boolean | null;
  dictationReady: boolean;
};

function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [page, setPage] = useState<"status" | "settings">("status");

  useEffect(() => {
    const unlisten = listen<Status>("status-changed", (event) => setStatus(event.payload));
    invoke<Status>("get_status").then(setStatus);
    return () => {
      unlisten.then((stop) => stop());
    };
  }, []);

  if (!status) {
    return null;
  }

  if (page === "settings") {
    return <SettingsPage onClose={() => setPage("status")} />;
  }

  const connected = status.connectedDevices.length > 0;

  return (
    <main className="flex min-h-screen flex-col items-center px-6 pt-10 pb-6">
      <div
        className="size-56 rounded-2xl bg-white p-3 ring-1 ring-border [&_svg]:size-full"
        dangerouslySetInnerHTML={{ __html: status.pairingQrSvg }}
      />
      <p className="mt-4 text-sm text-muted-foreground">用 EchoPad 手机端扫码配对</p>

      <ul className="mt-8 w-full divide-y rounded-xl border bg-card text-sm">
        <StatusRow ok={connected}>
          {connected ? `已连接 ${status.connectedDevices.join("、")}` : "等待手机端连接"}
        </StatusRow>
        <StatusRow ok={status.inputPermission === true}>
          {status.inputPermission === false ? "需要在系统设置中允许辅助功能" : "可以控制指针"}
        </StatusRow>
        <StatusRow ok={status.dictationReady}>{status.dictationReady ? "语音输入可用" : "语音输入未配置"}</StatusRow>
      </ul>

      <Button variant="ghost" size="sm" className="mt-auto text-muted-foreground" onClick={() => setPage("settings")}>
        <SettingsIcon />
        设置
      </Button>
    </main>
  );
}

function StatusRow({ ok, children }: { ok: boolean; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <span className={cn("size-2 shrink-0 rounded-full", ok ? "bg-green-600" : "bg-muted-foreground/40")} />
      <span className={ok ? "text-foreground" : "text-muted-foreground"}>{children}</span>
    </li>
  );
}

export default App;
