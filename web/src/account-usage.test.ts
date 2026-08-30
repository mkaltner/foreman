import { describe, expect, it } from "vitest";
import {
  accountUsageWindows,
  normalizeAccountUsage,
  rateLimitLabel,
} from "./account-usage";
import { forgetStoredHost, loadAccountUsage, saveAccountUsage, type StoredHost } from "./storage";

describe("account usage protocol compatibility and retention", () => {
  it("reads legacy primary/secondary payloads without fabricating absent windows", () => {
    const multi = normalizeAccountUsage({ providers: { codex: { available: true, rateLimits: {
      primary: { usedPercent: 12, windowDurationMins: 300 },
      secondary: { usedPercent: 34, windowDurationMins: 10_080 },
    } } } });
    const single = normalizeAccountUsage({ providers: { codex: { available: true, rateLimits: {
      primary: { usedPercent: 12 },
    } } } });

    expect(accountUsageWindows(multi?.providers.codex)).toHaveLength(2);
    expect(accountUsageWindows(single?.providers.codex)).toHaveLength(1);
    expect(rateLimitLabel(accountUsageWindows(single?.providers.codex)[0], 0, 1)).toBe("Usage limit");
  });

  it("bounds extensible windows, values, labels, and timestamps", () => {
    const normalized = normalizeAccountUsage({ providers: {
      codex: { available: true, observedAt: Number.MAX_SAFE_INTEGER, rateLimits: { windows: Array.from({ length: 20 }, (_, index) => ({
        id: `window-${index}`,
        label: "x".repeat(150),
        usedPercent: index === 0 ? 140 : -4,
        windowDurationMins: Number.MAX_SAFE_INTEGER,
        resetsAt: Number.MAX_SAFE_INTEGER,
      })) } },
      secret: { available: true, rateLimits: { windows: [{ usedPercent: 1 }] } },
    } });
    const windows = accountUsageWindows(normalized?.providers.codex);

    expect(windows).toHaveLength(16);
    expect(windows[0]).toMatchObject({ usedPercent: 100, windowDurationMins: 525_600, resetsAt: 253_402_300_799 });
    expect(windows[0].label).toHaveLength(100);
    expect(normalized?.providers).not.toHaveProperty("secret");
  });

  it("keeps windows with duplicate or missing provider identities distinct", () => {
    const normalized = normalizeAccountUsage({ providers: { codex: { available: true, rateLimits: { windows: [
      { id: "same", usedPercent: 10 },
      { id: "same", usedPercent: 20 },
      { usedPercent: 30 },
      { usedPercent: 40 },
    ] } } } });

    expect(accountUsageWindows(normalized?.providers.codex).map((window) => window.id)).toEqual([
      "same", "same-2", "window-3", "window-4",
    ]);
  });

  it("retains host-isolated snapshots across reload and removes them when forgotten", () => {
    const storage = localStorage;
    storage.clear();
    const usageA = normalizeAccountUsage({ providers: { codex: { available: true, rateLimits: { windows: [{ id: "five", usedPercent: 10 }] } } } })!;
    const usageB = normalizeAccountUsage({ providers: { codex: { available: true, rateLimits: { windows: [{ id: "week", usedPercent: 70 }] } } } })!;
    saveAccountUsage(usageA, "host-a", storage);
    saveAccountUsage(usageB, "host-b", storage);

    expect(accountUsageWindows(loadAccountUsage("host-a", storage)?.providers.codex)[0].usedPercent).toBe(10);
    expect(accountUsageWindows(loadAccountUsage("host-b", storage)?.providers.codex)[0].usedPercent).toBe(70);
    const hostA: StoredHost = { id: "host-a", displayName: "A", host: "a", tcpPort: 8765, webPort: 8766, deviceToken: "token", pairedAt: 1, lastConnectedAt: null, lastKnownStatus: "disconnected", runtimeMode: null, isDefault: true };
    forgetStoredHost({ hosts: [hostA], activeHostId: hostA.id }, hostA.id, storage);
    expect(loadAccountUsage("host-a", storage)).toBeNull();
    expect(loadAccountUsage("host-b", storage)).not.toBeNull();
  });
});
