// Mac 打包 job 用：按 Tauri 官方的静态清单格式写出 latest.json（ADR-0011）。
// 用法：tsx scripts/update-manifest.ts <Mac 更新包下载地址> <.sig 文件> <输出路径>

import { readFileSync, writeFileSync } from "node:fs";
import { parseChangelog } from "./changelog";
import { updateManifest } from "./release";

const [url, signatureFile, out] = process.argv.slice(2);
if (url === undefined || signatureFile === undefined || out === undefined) {
  throw new Error("Usage: update-manifest.ts <url> <signature-file> <out>");
}
const { version } = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as {
  version: string;
};

writeFileSync(
  out,
  updateManifest({
    version,
    changelog: parseChangelog(readFileSync(new URL("../../CHANGELOG.md", import.meta.url), "utf8")),
    pubDate: new Date(),
    url,
    signature: readFileSync(signatureFile, "utf8"),
  }),
);
