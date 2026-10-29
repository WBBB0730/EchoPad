/**
 * 更新日志（仓库根 CHANGELOG.md，手写，两端共用）：一个版本一段。
 * 发版时在这里切段：整份写进更新清单（ADR-0011），这一版那段作 Release 正文。见 docs/prd/release.md。
 */

import { fromMarkdown } from "mdast-util-from-markdown";
import { toString } from "mdast-util-to-string";
import { valid } from "semver";

export type ChangelogEntry = {
  /** 版本号（X.Y.Z） */
  version: string;
  /** 标题里的发布日期（YYYY-MM-DD）；没写为 null */
  date: string | null;
  /** 该段正文的 Markdown 原文（不含标题） */
  body: string;
};

/** Keep a Changelog 式标题 `[1.2.3] - 2026-01-01`（方括号与日期可省）；只认 X.Y.Z。 */
const VERSION_HEADING = /^\[?(\d+\.\d+\.\d+)\]?(?:\s+-\s+(\d{4}-\d{2}-\d{2}))?$/;

/**
 * 按二级标题切段：标题是版本号的才算版本段，其余（非版本号、带预发布后缀的标题等）忽略；
 * 正文取到下一个一 / 二级标题为止。同一版本写了多段只取第一段。
 */
export function parseChangelog(markdown: string): ChangelogEntry[] {
  const nodes = fromMarkdown(markdown).children;
  const entries: ChangelogEntry[] = [];
  nodes.forEach((node, i) => {
    if (node.type !== "heading" || node.depth !== 2) return;
    const match = VERSION_HEADING.exec(toString(node).trim());
    if (match === null) return;
    const version = match[1]!;
    if (valid(version) === null || entries.some((e) => e.version === version)) return;

    const next = nodes.slice(i + 1).find((n) => n.type === "heading" && n.depth <= 2);
    const start = node.position?.end.offset ?? 0;
    const end = next?.position?.start.offset ?? markdown.length;
    entries.push({ version, date: match[2] ?? null, body: markdown.slice(start, end).trim() });
  });
  return entries;
}

/** 某次发版对应的那一段；没写为 null。 */
export function findChangelogEntry(entries: ChangelogEntry[], version: string): ChangelogEntry | null {
  return entries.find((e) => e.version === version) ?? null;
}
