// 发布 job 用：按 Tauri 官方的静态清单格式写出 latest.json（ADR-0011）。
// 用法：tsx scripts/update-manifest.ts <Mac 更新包下载地址> <Mac .sig 文件> <Windows 安装包下载地址> <Windows .sig 文件> <输出路径>

import { readFileSync, writeFileSync } from "node:fs";
import { parseChangelog } from "./changelog";
import { updateManifest } from "./release";

const [macUrl, macSignature, windowsUrl, windowsSignature, out] = process.argv.slice(2);
if (
  macUrl === undefined ||
  macSignature === undefined ||
  windowsUrl === undefined ||
  windowsSignature === undefined ||
  out === undefined
) {
  throw new Error("Usage: update-manifest.ts <mac-url> <mac-signature-file> <windows-url> <windows-signature-file> <out>");
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
    mac: { url: macUrl, signature: readFileSync(macSignature, "utf8") },
    windows: { url: windowsUrl, signature: readFileSync(windowsSignature, "utf8") },
  }),
);
