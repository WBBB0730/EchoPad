// 和系统标题栏融为一体的顶栏：左侧留给系统的窗口按钮，空白处可以拖动窗口，右侧是置顶按钮（DESIGN.md）。
import { useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { PinIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function TitleBar() {
  /** 窗口是否置顶：本次运行内有效，重启后默认不置顶。 */
  const [pinned, setPinned] = useState(false);
  const label = pinned ? "取消置顶" : "窗口置顶";

  function togglePin() {
    getCurrentWindow()
      .setAlwaysOnTop(!pinned)
      .then(() => setPinned(!pinned));
  }

  return (
    <div data-tauri-drag-region className="flex h-9 shrink-0 items-center justify-end pr-2.5 select-none">
      <Button
        variant={pinned ? "secondary" : "ghost"}
        size="icon-sm"
        className={pinned ? "text-foreground" : "text-muted-foreground"}
        onClick={togglePin}
        aria-pressed={pinned}
        aria-label={label}
        title={label}
      >
        <PinIcon className={cn("size-3.5", pinned && "fill-current")} />
      </Button>
    </div>
  );
}
