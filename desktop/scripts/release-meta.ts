// 发版检查 job 用：校验标签与版本号，输出 Release 正文（CHANGELOG.md 里这一版那段，没写为空）。
// 用法：tsx scripts/release-meta.ts <tag> >> "$GITHUB_OUTPUT"

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { findChangelogEntry, parseChangelog } from "./changelog";
import { checkReleaseTag } from "./release";

const tag = process.argv[2];
const { version } = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as {
  version: string;
};
checkReleaseTag(tag, version);

const changelog = parseChangelog(readFileSync(new URL("../../CHANGELOG.md", import.meta.url), "utf8"));
const notes = findChangelogEntry(changelog, version)?.body ?? "";
// 多行输出按 GitHub 文档写成 heredoc，分隔符随机，避免与正文撞车
const delimiter = `NOTES_${randomUUID()}`;

process.stdout.write(`version=${version}\n`);
process.stdout.write(`notes<<${delimiter}\n${notes}\n${delimiter}\n`);
