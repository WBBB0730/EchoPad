import { type ReactNode, useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { SettingsIcon, SmartphoneIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { SettingsPage } from "./SettingsPage";

type ConnectedDevice = { id: number; name: string };

type Status = {
  pairingLink: string;
  pairingQrSvg: string;
  connectedDevices: ConnectedDevice[];
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
      {connected ? (
        <ul className="w-full divide-y rounded-xl border bg-card text-sm">
          {status.connectedDevices.map((device) => (
            <li key={device.id} className="flex items-center gap-3 px-4 py-3">
              <SmartphoneIcon className="size-4 shrink-0 text-muted-foreground" />
              <span className="flex-1 truncate">{device.name}</span>
              <Button variant="outline" size="xs" onClick={() => invoke("disconnect_device", { id: device.id })}>
                断开连接
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <>
          <div
            className="size-56 rounded-2xl bg-white p-3 ring-1 ring-border [&_svg]:size-full"
            dangerouslySetInnerHTML={{ __html: status.pairingQrSvg }}
          />
          <p className="mt-4 text-sm text-muted-foreground">用 EchoPad 手机端扫码配对</p>
        </>
      )}

      <ul className="mt-8 w-full divide-y rounded-xl border bg-card text-sm">
        <StatusRow ok={connected}>{connected ? "手机端已连接" : "等待手机端连接"}</StatusRow>
        <StatusRow
          ok={status.inputPermission === true}
          action={
            status.inputPermission === false && (
              <Button variant="outline" size="xs" onClick={() => invoke("open_accessibility_settings")}>
                打开系统设置
              </Button>
            )
          }
        >
          {status.inputPermission === false ? "需要允许辅助功能" : "可以控制指针"}
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

function StatusRow({ ok, action, children }: { ok: boolean; action?: ReactNode; children: ReactNode }) {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <span className={cn("size-2 shrink-0 rounded-full", ok ? "bg-green-600" : "bg-muted-foreground/40")} />
      <span className={cn("flex-1", ok ? "text-foreground" : "text-muted-foreground")}>{children}</span>
      {action}
    </li>
  );
}

export default App;
