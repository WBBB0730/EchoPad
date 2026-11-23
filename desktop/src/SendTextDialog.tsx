// "发送文本"的输入框：打好字发给一部手机，进它的剪贴板，不动电脑自己的剪贴板（docs/prd/clipboard-sync.md）。
// 主窗口里手机卡片上的按钮和菜单栏菜单共用；⌘↩ 也能发送。
import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";

export function SendTextDialog({ phone, onClose }: { phone: { id: string; name: string } | null; onClose: () => void }) {
  const [text, setText] = useState("");

  function send() {
    if (!phone || !text) return;
    invoke<string>("send_text", { phoneId: phone.id, text }).then((message) => {
      toast(message);
      setText("");
      onClose();
    });
  }

  return (
    <Dialog open={phone !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>发送文本到 {phone?.name}</DialogTitle>
        </DialogHeader>
        <Textarea
          autoFocus
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && event.metaKey) {
              event.preventDefault();
              send();
            }
          }}
          className="max-h-60"
        />
        <DialogFooter className="flex-row justify-end">
          <DialogClose render={<Button variant="outline" />}>取消</DialogClose>
          <Button disabled={!text} onClick={send}>
            发送
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
