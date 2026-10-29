// 更新弹窗：首页的"更新"和设置里的"立即更新"共用。列出当前版本之后、到新版本为止的更新日志，
// 确认后才安装（docs/prd/in-app-update.md）。日志是 Markdown：原始 HTML 不渲染（库默认），链接用系统浏览器打开。
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { invoke } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { UpdateStatus } from "./update";

// 日志只是几行短句：排成紧凑的列表，不套文章排版
const MARKDOWN_COMPONENTS: Components = {
  ul: ({ children }) => <ul className="list-disc space-y-0.5 pl-4 marker:text-muted-foreground">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal space-y-0.5 pl-5 marker:text-muted-foreground">{children}</ol>,
  p: ({ children }) => <p className="mt-1 first:mt-0">{children}</p>,
  strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
  a: ({ href, children }) => (
    <a
      href={href}
      className="underline underline-offset-3"
      onClick={(event) => {
        event.preventDefault();
        if (href) openUrl(href);
      }}
    >
      {children}
    </a>
  ),
};

export function UpdateDialog({
  update,
  open,
  onOpenChange,
}: {
  update: UpdateStatus;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  async function confirm() {
    onOpenChange(false);
    // 安装失败时更新状态会变成失败，在设置里提示
    await invoke("apply_update").catch(() => {});
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>EchoPad {update.availableVersion} 可用</DialogTitle>
          <DialogDescription>当前版本 {update.currentVersion}</DialogDescription>
        </DialogHeader>
        <div className="max-h-72 overflow-y-auto leading-relaxed select-text">
          {update.changelog.length === 0 ? (
            <p>无更新日志</p>
          ) : (
            <div className="space-y-3">
              {update.changelog.map((entry) => (
                <section key={entry.version}>
                  <div className="mb-1 flex items-baseline justify-between gap-3">
                    <span className="font-medium">{entry.version}</span>
                    {entry.date && <span className="text-xs text-muted-foreground">{entry.date}</span>}
                  </div>
                  <Markdown remarkPlugins={[remarkGfm]} components={MARKDOWN_COMPONENTS}>
                    {entry.body}
                  </Markdown>
                </section>
              ))}
            </div>
          )}
        </div>
        <DialogFooter className="flex-row justify-end">
          <DialogClose render={<Button variant="outline" />}>取消</DialogClose>
          <Button onClick={confirm}>{update.installable ? "重启并更新" : "前往下载"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
