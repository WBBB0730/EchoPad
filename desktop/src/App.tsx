import { type ReactNode, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { ChevronLeftIcon, PlusIcon, SettingsIcon, SmartphoneIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { SettingsPage } from "./SettingsPage";

type Phone = { id: string; name: string; online: boolean };

type Status = {
  pairingLink: string;
  pairingQrSvg: string;
  phones: Phone[];
  inputPermission: boolean | null;
  dictationReady: boolean;
};

function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [page, setPage] = useState<"status" | "settings" | "pairing">("status");
  const pairedCount = useRef(0);

  useEffect(() => {
    const unlisten = listen<Status>("status-changed", (event) => setStatus(event.payload));
    invoke<Status>("get_status").then(setStatus);
    return () => {
      unlisten.then((stop) => stop());
    };
  }, []);

  // 配上新手机后自动回到手机列表。
  useEffect(() => {
    const count = status?.phones.length ?? 0;
    if (count > pairedCount.current && page === "pairing") {
      setPage("status");
    }
    pairedCount.current = count;
  }, [status?.phones.length, page]);

  if (!status) {
    return null;
  }

  if (page === "settings") {
    return <SettingsPage onClose={() => setPage("status")} />;
  }

  if (page === "pairing") {
    return (
      <main className="flex h-screen flex-col">
        <header className="flex items-center gap-1 px-4 pt-4 pb-2">
          <Button variant="ghost" size="icon-sm" onClick={() => setPage("status")} aria-label="返回">
            <ChevronLeftIcon />
          </Button>
          <h1 className="text-sm font-medium">配对新手机</h1>
        </header>
        <div className="flex flex-col items-center pt-6">
          <PairingCode status={status} />
        </div>
      </main>
    );
  }

  const online = status.phones.filter((phone) => phone.online).length;

  return (
    <main className="flex min-h-screen flex-col items-center px-6 pt-10 pb-6">
      {status.phones.length === 0 ? (
        <PairingCode status={status} />
      ) : (
        <>
          <ul className="w-full divide-y rounded-xl border bg-card text-sm">
            {status.phones.map((phone) => (
              <li key={phone.id} className="flex items-center gap-3 px-4 py-3">
                <SmartphoneIcon className="size-4 shrink-0 text-muted-foreground" />
                <span className={cn("flex-1 truncate", !phone.online && "text-muted-foreground")}>{phone.name}</span>
                <span
                  className={cn("size-2 shrink-0 rounded-full", phone.online ? "bg-green-600" : "bg-muted-foreground/40")}
                />
                <Button variant="outline" size="xs" onClick={() => invoke("unpair_phone", { id: phone.id })}>
                  取消配对
                </Button>
              </li>
            ))}
          </ul>
          <Button variant="ghost" size="sm" className="mt-2 self-start text-muted-foreground" onClick={() => setPage("pairing")}>
            <PlusIcon />
            配对新手机
          </Button>
        </>
      )}

      <ul className="mt-8 w-full divide-y rounded-xl border bg-card text-sm">
        <StatusRow ok={online > 0}>{online > 0 ? `${online} 部手机在线` : "没有手机在线"}</StatusRow>
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

function PairingCode({ status }: { status: Status }) {
  return (
    <>
      <div
        className="size-56 rounded-2xl bg-white p-3 ring-1 ring-border [&_svg]:size-full"
        dangerouslySetInnerHTML={{ __html: status.pairingQrSvg }}
      />
      <p className="mt-4 text-sm text-muted-foreground">用 EchoPad 手机端扫码配对</p>
    </>
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
