import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  forgetHost,
  loadAppearance,
  loadHost,
  saveAppearance,
  saveHost,
} from "./storage";
import {
  confirmSessionAction,
  createSubmissionGuard,
  isNearBottom,
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
});
