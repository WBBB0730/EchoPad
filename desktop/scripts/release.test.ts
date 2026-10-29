import { describe, expect, it } from "vitest";
import { checkReleaseTag, updateManifest } from "./release";

describe("checkReleaseTag", () => {
  it("接受与版本号一致的标签", () => {
    expect(() => checkReleaseTag("v1.2.3", "1.2.3")).not.toThrow();
  });

  it("标签缺失或与版本号不一致时失败", () => {
    expect(() => checkReleaseTag(undefined, "1.2.3")).toThrow("(missing)");
    expect(() => checkReleaseTag("v1.2.4", "1.2.3")).toThrow("does not match");
    expect(() => checkReleaseTag("1.2.3", "1.2.3")).toThrow("does not match");
  });

  it("带预发布或构建后缀的版本号不能发版", () => {
    expect(() => checkReleaseTag("v1.2.3-beta.1", "1.2.3-beta.1")).toThrow("X.Y.Z");
    expect(() => checkReleaseTag("v1.2.3+build", "1.2.3+build")).toThrow("X.Y.Z");
  });
});

describe("updateManifest", () => {
  it("整份更新日志以 JSON 写进 notes，签名去掉首尾空白", () => {
    const changelog = [
      { version: "1.1.0", date: null, body: "- 新增缩放" },
      { version: "1.0.0", date: "2026-10-01", body: "- 新增手机键盘" },
    ];
    const manifest = JSON.parse(
      updateManifest({
        version: "1.1.0",
        changelog,
        pubDate: new Date("2026-10-29T00:00:00Z"),
        url: "https://example.com/EchoPad.app.tar.gz",
        signature: "c2lnbmF0dXJl\n",
      }),
    );
    expect(manifest).toEqual({
      version: "1.1.0",
      notes: JSON.stringify(changelog),
      pub_date: "2026-10-29T00:00:00.000Z",
      platforms: {
        "darwin-aarch64": { signature: "c2lnbmF0dXJl", url: "https://example.com/EchoPad.app.tar.gz" },
      },
    });
  });
});
