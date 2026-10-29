import { describe, expect, it } from "vitest";
import { findChangelogEntry, parseChangelog, type ChangelogEntry } from "./changelog";

const CHANGELOG = `# 更新日志

## 说明

- 不是版本号的标题不算版本段

## [1.10.0] - 2026-09-25

- 新增缩放
- 修复拖拽中途松开的问题

## 1.9.0

- 新增媒体键

## [1.8.0-beta.1]

- 带预发布后缀的标题不算版本段

## [1.8.0] - 2026-09-22

- 新增横屏

\`\`\`
## 9.9.9
\`\`\`

## [01.7.0]

- 不合法的版本号不算版本段

## [1.6.0] - 2026-09-20

- 新增手机键盘

## [1.6.0]

- 同一版本的第二段被忽略
`;

function versions(entries: ChangelogEntry[]): string[] {
  return entries.map((e) => e.version);
}

describe("parseChangelog", () => {
  it("按版本号二级标题切段，日期与方括号可省", () => {
    const entries = parseChangelog(CHANGELOG);
    expect(versions(entries)).toEqual(["1.10.0", "1.9.0", "1.8.0", "1.6.0"]);
    expect(entries[0]).toEqual({
      version: "1.10.0",
      date: "2026-09-25",
      body: "- 新增缩放\n- 修复拖拽中途松开的问题",
    });
    expect(entries[1]).toEqual({ version: "1.9.0", date: null, body: "- 新增媒体键" });
  });

  it("代码块里的井号不算标题，正文取到下一个版本段为止", () => {
    const entry = parseChangelog(CHANGELOG).find((e) => e.version === "1.8.0");
    expect(entry?.body).toBe("- 新增横屏\n\n```\n## 9.9.9\n```");
  });

  it("同一版本写了多段只取第一段", () => {
    const entry = parseChangelog(CHANGELOG).find((e) => e.version === "1.6.0");
    expect(entry?.body).toBe("- 新增手机键盘");
  });

  it("空文件没有版本段", () => {
    expect(parseChangelog("")).toEqual([]);
    expect(parseChangelog("# 更新日志\n")).toEqual([]);
  });
});

describe("findChangelogEntry", () => {
  it("取这一版那段，没写为 null", () => {
    const entries = parseChangelog(CHANGELOG);
    expect(findChangelogEntry(entries, "1.9.0")?.body).toBe("- 新增媒体键");
    expect(findChangelogEntry(entries, "1.7.0")).toBeNull();
  });
});
