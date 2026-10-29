/** 发版流程用到的纯函数：校验标签与版本号、生成更新清单。见 docs/prd/release.md。 */

import { parse } from "semver";
import { type ChangelogEntry } from "./changelog";

/** 标签必须是 `v` + 代码里的版本号，版本号只接受 X.Y.Z（ADR-0008）。 */
export function checkReleaseTag(tag: string | undefined, version: string): void {
  if (tag !== `v${version}`) {
    throw new Error(`Tag ${tag ?? "(missing)"} does not match version ${version} in desktop/package.json`);
  }
  const parsed = parse(version);
  if (parsed === null || parsed.prerelease.length > 0 || parsed.build.length > 0) {
    throw new Error(`Version ${version} must be X.Y.Z without a prerelease or build suffix`);
  }
}

/**
 * Tauri 官方格式的静态更新清单 `latest.json`。`notes` 放整份切好段的更新日志（JSON 字符串），
 * 两端按版本号挑段落（ADR-0011）；手机端也读这一份（docs/prd/in-app-update.md）。
 */
export function updateManifest(input: {
  version: string;
  changelog: ChangelogEntry[];
  pubDate: Date;
  /** Mac 更新包（.app.tar.gz）的下载地址 */
  url: string;
  /** Mac 更新包的签名（.sig 文件的内容） */
  signature: string;
}): string {
  const manifest = {
    version: input.version,
    notes: JSON.stringify(input.changelog),
    pub_date: input.pubDate.toISOString(),
    platforms: {
      "darwin-aarch64": { signature: input.signature.trim(), url: input.url },
    },
  };
  return `${JSON.stringify(manifest, null, 2)}\n`;
}
