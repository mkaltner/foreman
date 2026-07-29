import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  forgetHost,
  loadAppearance,
  loadHost,
  loadNotificationsEnabled,
  saveAppearance,
  saveHost,
  saveNotificationsEnabled,
} from "./storage";
import {
  confirmSessionAction,
  createSubmissionGuard,
  isNearBottom,
  parseAssistantContent,
  reasoningDescription,
  reasoningLabel,
} from "./ui";

describe("storage, appearance, and interaction helpers", () => {
  beforeEach(() => localStorage.clear());

  it("stores a persistent token but forgets the entire host without retaining pairing material", () => {
    saveHost({ host: "codex.local", port: 8766, deviceName: "Browser", deviceToken: "fmt_secret" });
    expect(loadHost()).toEqual({
      host: "codex.local",
      port: 8766,
      deviceName: "Browser",
      deviceToken: "fmt_secret",
    });
    expect(localStorage.getItem("foreman.host.v1")).not.toContain("pairingKey");
    forgetHost();
    expect(loadHost()).toBeNull();
  });

  it("persists theme and accent with safe defaults", () => {
    expect(loadAppearance()).toEqual({ theme: "system", accent: "purple" });
    saveAppearance({ theme: "dark", accent: "teal" });
    expect(loadAppearance()).toEqual({ theme: "dark", accent: "teal" });
    localStorage.setItem("foreman.appearance.v1", '{"theme":"broken","accent":"chartreuse"}');
    expect(loadAppearance()).toEqual({ theme: "system", accent: "purple" });
  });

  it("persists the browser notification preference disabled by default", () => {
    expect(loadNotificationsEnabled()).toBe(false);
    saveNotificationsEnabled(true);
    expect(loadNotificationsEnabled()).toBe(true);
    saveNotificationsEnabled(false);
    expect(loadNotificationsEnabled()).toBe(false);
  });

  it("prevents duplicate submissions until the accepted request finishes", () => {
    const guard = createSubmissionGuard();
    expect(guard.enter()).toBe(true);
    expect(guard.enter()).toBe(false);
    guard.leave();
    expect(guard.enter()).toBe(true);
  });

  it("tracks bottom proximity and requires confirmation for archive and permanent delete", () => {
    expect(isNearBottom(900, 100, 1050)).toBe(true);
    expect(isNearBottom(500, 100, 1050)).toBe(false);
    const confirm = vi.fn((_message: string) => true);
    expect(confirmSessionAction("archive", "Alpha", confirm)).toBe(true);
    expect(confirmSessionAction("delete", "Alpha", confirm)).toBe(true);
    expect(confirm.mock.calls[1][0]).toContain("cannot be undone");
  });

  it("uses Android-style reasoning labels and warns about Ultra usage", () => {
    expect(reasoningLabel("low")).toBe("Light");
    expect(reasoningLabel("xhigh")).toBe("Extra High");
    expect(reasoningDescription("high")).toBeUndefined();
    expect(reasoningDescription("ultra")).toBe("Consumes usage limits faster");
  });

  it("separates app directives from assistant Markdown", () => {
    const content = parseAssistantContent([
      "Validation passed.",
      "",
      '::git-commit{cwd="/home/mkaltner/projects/foreman"}',
      '::git-push{cwd="/home/mkaltner/projects/foreman" branch="codex/web"}',
    ].join("\n"));

    expect(content).toEqual([
      { kind: "markdown", text: "Validation passed.\n" },
      { kind: "directive", directive: { name: "git-commit", attributes: { cwd: "/home/mkaltner/projects/foreman" } } },
      { kind: "directive", directive: { name: "git-push", attributes: { cwd: "/home/mkaltner/projects/foreman", branch: "codex/web" } } },
    ]);
  });

  it("preserves unsupported directives as ordinary Markdown", () => {
    expect(parseAssistantContent('::unknown{value="safe"}')).toEqual([
      { kind: "markdown", text: '::unknown{value="safe"}' },
    ]);
  });
});
