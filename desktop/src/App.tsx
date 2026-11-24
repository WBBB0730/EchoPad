import { type ReactNode, useEffect, useEffectEvent, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { type DragDropEvent, getCurrentWebview } from "@tauri-apps/api/webview";
import { toast } from "sonner";
import { ChevronLeftIcon, ClipboardIcon, FileIcon, PlusIcon, SettingsIcon, SmartphoneIcon, TypeIcon, UploadIcon } from "lucide-react";
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
  /** 是否正拖着本机文件悬停在窗口上。 */
  const [dragging, setDragging] = useState(false);
  /** 拖着文件时指针下的手机卡片。 */
  const [dragOverPhone, setDragOverPhone] = useState<string | null>(null);

  const onlinePhones = status?.phones.filter((phone) => phone.online) ?? [];
  // 只在首页、有手机在线、没有弹窗时接收拖进来的文件（DESIGN.md）。
  const dropEnabled = page === "status" && onlinePhones.length > 0 && !updateOpen && sendingTextTo === null;

  // 拖进窗口的本机文件：只有 1 部手机在线时发给它，多部在线时发给指针下的卡片；松手后先确认再发（docs/prd/send-files.md）。
  const onDragDrop = useEffectEvent((event: DragDropEvent) => {
    if (event.type === "leave") {
      setDragging(false);
      return;
    }
    const phoneId = onlinePhones.length === 1 ? onlinePhones[0].id : phoneCardAt(event.position);
    setDragOverPhone(phoneId);
    if (event.type === "enter") {
      // 拖的是文字之类不是文件的内容时没有路径，不接收。
      setDragging(event.paths.length > 0);
    } else if (event.type === "drop") {
      if (dragging && dropEnabled && phoneId) {
        invoke<string | null>("send_dropped_files", { phoneId, paths: event.paths }).then((message) => message && toast(message));
      }
      setDragging(false);
    }
  });

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

  useEffect(() => {
    const unlisten = getCurrentWebview().onDragDropEvent((event) => onDragDrop(event.payload));
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
      <main className="flex min-h-0 flex-1 flex-col">
        <header className="flex items-center gap-1 px-4 pt-1 pb-2">
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

  // 状态列表只列出要处理的事，全都正常时不显示（DESIGN.md）；手机在不在线看列表里的圆点。
  const issues = [
    onlinePhones.length === 0 && status.outdated && (
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

  // 拖着文件时，只有 1 部手机在线就整个首页都是投放区，多部在线就每张在线卡片各是一个（DESIGN.md）。
  const dropping = dropEnabled && dragging;
  const dropOnCards = dropping && onlinePhones.length > 1;

  return (
    <>
      <main className="relative flex min-h-0 flex-1 flex-col">
        <div className="flex min-h-0 flex-1 flex-col items-center overflow-y-auto px-6 pt-4 pb-6">
          {status.phones.length === 0 ? (
            <PairingCode status={status} />
          ) : (
            <>
              <ul className="flex w-full flex-col gap-2 text-sm">
                {status.phones.map((phone) => (
                  <PhoneCard
                    key={phone.id}
                    phone={phone}
                    drop={dropOnCards ? (dragOverPhone === phone.id ? "over" : "ready") : undefined}
                    onSendText={() => setSendingTextTo(phone)}
                  />
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
        </div>
        {dropping && !dropOnCards && <DropOverlay phoneName={onlinePhones[0].name} variant="window" />}
      </main>
      {updateDialog}
      <SendTextDialog phone={sendingTextTo} onClose={() => setSendingTextTo(null)} />
    </>
  );
}

/**
 * 拖放位置下的在线手机卡片。Tauri 2.12（wry 0.57）在 macOS 上给的拖放位置虽然标着物理像素，实际已经是页面坐标，
 * 所以不按屏幕倍数换算；Tauri 改成真正的物理像素后，这里要先 `position.toLogical(window.devicePixelRatio)`。
 */
function phoneCardAt({ x, y }: { x: number; y: number }) {
  return document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-drop-phone-id]")?.dataset.dropPhoneId ?? null;
}

/** 拖着文件悬停时的提示：盖住整个首页，或者指针下的那张卡片（DESIGN.md）。 */
function DropOverlay({ phoneName, variant }: { phoneName: string; variant: "window" | "card" }) {
  return (
    <div
      className={cn(
        "pointer-events-none absolute flex items-center justify-center gap-2 bg-background/90 text-sm font-medium text-foreground backdrop-blur-xs",
        variant === "window" ? "inset-3 flex-col rounded-2xl border-2 border-dashed border-foreground/30" : "inset-0 rounded-xl",
      )}
    >
      <UploadIcon className={variant === "window" ? "size-6" : "size-4"} />
      <span>松手发送到 {phoneName}</span>
    </div>
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
 * 多部手机在线时拖着文件，`drop` 是卡片作为投放区的样子：`ready` 等着放，`over` 指针就在上面。
 */
function PhoneCard({ phone, drop, onSendText }: { phone: Phone; drop?: "ready" | "over"; onSendText: () => void }) {
  const run = (command: "sync_clipboard" | "send_files") =>
    invoke<string | null>(command, { phoneId: phone.id }).then((message) => message && toast(message));
  return (
    <li
      data-drop-phone-id={phone.online ? phone.id : undefined}
      className={cn(
        "relative rounded-xl border bg-card p-3 transition-colors",
        drop === "ready" && (phone.online ? "border-dashed border-foreground/40" : "opacity-50"),
        drop === "over" && "border-foreground ring-2 ring-foreground/20",
      )}
    >
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
      {drop === "over" && <DropOverlay phoneName={phone.name} variant="card" />}
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
