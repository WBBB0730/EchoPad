import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

/** 更新日志的一段（ADR-0011）。 */
export type ChangelogEntry = { version: string; date: string | null; body: string };

/** 电脑端的更新状态，由 Rust 侧的更新流程推送（docs/prd/in-app-update.md）。 */
export type UpdateStatus = {
  phase: "upToDate" | "checking" | "available" | "downloading" | "ready" | "failed";
  currentVersion: string;
  availableVersion: string | null;
  /** 当前版本之后、到新版本为止的更新日志（新→旧） */
  changelog: ChangelogEntry[];
  /** 是否显示"新版本可用"，提示不能关闭 */
  prompt: boolean;
  /** 确认后安装并重启；开发版是打开 Release 页面 */
  installable: boolean;
};

export function useUpdateStatus(): UpdateStatus | null {
  const [status, setStatus] = useState<UpdateStatus | null>(null);

  useEffect(() => {
    const unlisten = listen<UpdateStatus>("update-status-changed", (event) => setStatus(event.payload));
    invoke<UpdateStatus>("get_update_status").then(setStatus);
    return () => {
      unlisten.then((stop) => stop());
    };
  }, []);

  return status;
}
