import { type ReactNode, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { toast } from "sonner";
import { ChevronLeftIcon, ClipboardIcon, FileIcon, PlusIcon, SettingsIcon, SmartphoneIcon, TypeIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { SendTextDialog } from "./SendTextDialog";
import { SettingsPage } from "./SettingsPage";
import { UpdateDialog } from "./UpdateDialog";
import { useUpdateStatus } from "./update";

type Phone = { id: string; name: string; online: boolean };

type Status = {
  pairingLink: string;
  pairingQrSvg: string;
  phones: Phone[];
  /** 版本不兼容时需要更新的一端（ADR-0010）。 */
  outdated: "mobile" | "desktop" | null;
  inputPermission: boolean | null;
  dictationReady: boolean;
  /** 开着剪贴板同步，而系统一律拒绝读取剪贴板（ADR-0017）。 */
  clipboardAccessDenied: boolean;
};

const OUTDATED_LABELS = { mobile: "手机端需要更新", desktop: "电脑端需要更新" } as const;

function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [page, setPage] = useState<"status" | "settings" | "pairing">("status");
  const pairedCount = useRef(0);
  const update = useUpdateStatus();
  const [updateOpen, setUpdateOpen] = useState(false);
  /** 正在给哪部手机打要发送的字。 */
  const [sendingTextTo, setSendingTextTo] = useState<Phone | null>(null);

  // 菜单栏菜单里的"发送文本到 某某…"打开主窗口，在这里弹输入框。
  useEffect(() => {
    const unlisten = listen<string>("send-text", (event) => {
      setPage("status");
      invoke<Status>("get_status").then((status) => setSendingTextTo(status.phones.find((phone) => phone.id === event.payload) ?? null));
    });
    return () => {
      unlisten.then((stop) => stop());
    };
  }, []);

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

  if (!status || !update) {
    return null;
  }

  const updateDialog = <UpdateDialog update={update} open={updateOpen} onOpenChange={setUpdateOpen} />;

  if (page === "settings") {
    return (
      <>
        <SettingsPage updateStatus={update} onUpdate={() => setUpdateOpen(true)} onClose={() => setPage("status")} />
        {updateDialog}
      </>
    );
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
  // 状态列表只列出要处理的事，全都正常时不显示（DESIGN.md）；手机在不在线看列表里的圆点。
  const issues = [
    online === 0 && status.outdated && (
      <StatusRow key="outdated" ok={false}>
        {OUTDATED_LABELS[status.outdated]}
      </StatusRow>
    ),
    status.inputPermission === false && (
      <StatusRow
        key="input"
        ok={false}
        action={
          <Button variant="outline" size="xs" onClick={() => invoke("open_privacy_settings", { pane: "accessibility" })}>
            打开系统设置
          </Button>
        }
      >
        需要允许辅助功能
      </StatusRow>
    ),
    !status.dictationReady && (
      <StatusRow key="dictation" ok={false}>
        语音输入未配置
      </StatusRow>
    ),
    status.clipboardAccessDenied && (
      <StatusRow
        key="clipboard"
        ok={false}
        action={
          <Button variant="outline" size="xs" onClick={() => invoke("open_privacy_settings", { pane: "pasteboard" })}>
            打开系统设置
          </Button>
        }
      >
        需要允许读取剪贴板
      </StatusRow>
    ),
    update.prompt && (
      <StatusRow
        key="update"
        ok
        action={
          <Button variant="outline" size="xs" onClick={() => setUpdateOpen(true)}>
            更新
          </Button>
        }
      >
        新版本 {update.availableVersion} 可用
      </StatusRow>
    ),
  ].filter(Boolean);

  return (
    <>
      <main className="flex min-h-screen flex-col items-center px-6 pt-10 pb-6">
        {status.phones.length === 0 ? (
          <PairingCode status={status} />
        ) : (
          <>
            <ul className="flex w-full flex-col gap-2 text-sm">
              {status.phones.map((phone) => (
                <PhoneCard key={phone.id} phone={phone} onSendText={() => setSendingTextTo(phone)} />
              ))}
            </ul>
            <Button variant="ghost" size="sm" className="mt-2 self-start text-muted-foreground" onClick={() => setPage("pairing")}>
              <PlusIcon />
              配对新手机
            </Button>
          </>
        )}

        {issues.length > 0 && <ul className="mt-8 w-full divide-y rounded-xl border bg-card text-sm">{issues}</ul>}

        <Button variant="ghost" size="sm" className="mt-auto text-muted-foreground" onClick={() => setPage("settings")}>
          <SettingsIcon />
          设置
        </Button>
      </main>
      {updateDialog}
      <SendTextDialog phone={sendingTextTo} onClose={() => setSendingTextTo(null)} />
    </>
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

/** 手机卡片上的按钮：图标在上、文字在下，用小字和次要色，不抢名称的风头；图标只表示内容，都是发给这部手机，不再画箭头。 */
const PHONE_ACTION = "h-auto flex-col gap-1 py-2.5 text-xs font-normal text-muted-foreground hover:text-foreground";

/**
 * 一部配过的手机：名称和在线状态；在线时下面是只发给它的"发送剪贴板""发送文件"和"发送文本"
 * （DESIGN.md、docs/prd/clipboard-sync.md、docs/prd/send-files.md）。
 */
function PhoneCard({ phone, onSendText }: { phone: Phone; onSendText: () => void }) {
  const run = (command: "sync_clipboard" | "send_files") =>
    invoke<string | null>(command, { phoneId: phone.id }).then((message) => message && toast(message));
  return (
    <li className="rounded-xl border bg-card p-3">
      <div className="flex items-center gap-3">
        <SmartphoneIcon className="size-5 shrink-0 text-muted-foreground" />
        <div className="min-w-0 flex-1">
          <p className={cn("truncate", !phone.online && "text-muted-foreground")}>{phone.name}</p>
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className={cn("size-1.5 rounded-full", phone.online ? "bg-green-600" : "bg-muted-foreground/40")} />
            {phone.online ? "在线" : "离线"}
          </p>
        </div>
        <Button variant="ghost" size="xs" className="text-muted-foreground" onClick={() => invoke("unpair_phone", { id: phone.id })}>
          取消配对
        </Button>
      </div>
      {phone.online && (
        <div className="mt-3 grid grid-cols-3 gap-2">
          <Button variant="secondary" className={PHONE_ACTION} onClick={() => run("sync_clipboard")}>
            <ClipboardIcon />
            发送剪贴板
          </Button>
          <Button variant="secondary" className={PHONE_ACTION} onClick={() => run("send_files")}>
            <FileIcon />
            发送文件
          </Button>
          <Button variant="secondary" className={PHONE_ACTION} onClick={onSendText}>
            <TypeIcon />
            发送文本
          </Button>
        </div>
      )}
    </li>
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
