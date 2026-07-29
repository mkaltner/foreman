import { describe, expect, it } from "vitest";
import { notificationStateDescription, TurnNotificationMonitor } from "./notifications";

describe("web turn notifications", () => {
  it("notifies only after a session has been observed working", () => {
    const monitor = new TurnNotificationMonitor();
    expect(monitor.observe("one", "Build client", "completed")).toBeNull();
    expect(monitor.observe("one", "Build client", "working")).toBeNull();
    expect(monitor.observe("one", "Build client", "completed")).toEqual({
      sessionId: "one",
      title: "Foreman turn completed",
      body: "Build client — This turn finished successfully.",
    });
    expect(monitor.observe("one", "Build client", "completed")).toBeNull();
  });

  it("covers attention and failure outcomes independently", () => {
    const monitor = new TurnNotificationMonitor();
    monitor.seed([
      { id: "attention", status: "working" },
      { id: "failed", status: "working" },
      { id: "recent", status: "completed" },
    ]);
    expect(monitor.observe("attention", "Review change", "waiting")?.title)
      .toBe("Foreman needs your attention");
    expect(monitor.observe("failed", "Run tests", "failed")?.title)
      .toBe("Foreman turn failed");
    expect(monitor.observe("recent", "Old task", "completed")).toBeNull();
  });

  it("explains the secure-context requirement", () => {
    expect(notificationStateDescription("insecure", false)).toContain("HTTPS or localhost");
    expect(notificationStateDescription("granted", true)).toContain("background");
  });
});
