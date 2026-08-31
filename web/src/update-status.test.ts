import { describe, expect, it } from "vitest";
import type { ForemanRelease, ReleaseUpdateSnapshot } from "./protocol";
import { compareSemVer, componentUpdateStatus, normalizeReleaseUpdates, parseSemVer } from "./update-status";

function release(version: string, artifactAvailable = true): ForemanRelease {
  return {
    version,
    tag: `v${version}`,
    title: `Foreman ${version}`,
    publishedAt: "2026-08-29T04:47:19Z",
    releaseNotesUrl: `https://github.com/mkaltner/foreman/releases/tag/v${version}`,
    artifactAvailable,
  };
}

function snapshot(supported: ForemanRelease | null, newest = supported): ReleaseUpdateSnapshot {
  return {
    observedAt: "2026-08-30T00:00:00Z",
    stale: false,
    refreshStatus: "idle",
    components: {
      server: { supportedRelease: supported, newestRelease: newest },
      android: { supportedRelease: supported, newestRelease: newest },
    },
  };
}

describe("SemVer update status", () => {
  it("orders stable and prerelease identifiers by SemVer", () => {
    expect(compareSemVer(parseSemVer("1.10.0")!, parseSemVer("1.9.0")!)).toBeGreaterThan(0);
    expect(compareSemVer(parseSemVer("1.0.0-beta.11")!, parseSemVer("1.0.0-beta.2")!)).toBeGreaterThan(0);
    expect(compareSemVer(parseSemVer("1.0.0")!, parseSemVer("1.0.0-rc.1")!)).toBeGreaterThan(0);
    expect(compareSemVer(parseSemVer("999999999999999999.0.0")!, parseSemVer("2.0.0")!)).toBeGreaterThan(0);
  });

  it.each([
    ["1.0.0", "1.0.0", "up-to-date"],
    ["0.9.0", "1.0.0", "update-available"],
    ["1.1.0", "1.0.0", "newer-than-latest"],
  ])("classifies installed %s against %s", (installed, latest, kind) => {
    const latestRelease = release(latest);
    expect(componentUpdateStatus(installed, true, snapshot(latestRelease), snapshot(latestRelease).components.server, "server").kind).toBe(kind);
  });

  it("labels development, prerelease, and malformed installed builds honestly", () => {
    const current = snapshot(release("1.0.0"));
    expect(componentUpdateStatus("1.0.0", false, current, current.components.server, "server").kind).toBe("development");
    expect(componentUpdateStatus("1.1.0-beta.1", true, current, current.components.server, "server").kind).toBe("prerelease");
    expect(componentUpdateStatus("source", true, current, current.components.server, "server").kind).toBe("unavailable");
  });

  it("reports a newer incomplete release without offering it", () => {
    const supported = release("1.0.0");
    const newest = release("1.0.2", false);
    const current = snapshot(supported, newest);
    const status = componentUpdateStatus("1.0.2", true, current, current.components.server, "server");
    expect(status.kind).toBe("artifact-unavailable");
    expect(status.release?.releaseNotesUrl).toContain("/v1.0.2");
  });

  it("offers the newest complete component release even when a later release is incomplete", () => {
    const supported = release("1.1.0");
    const current = snapshot(supported, release("1.2.0", false));
    const status = componentUpdateStatus("1.0.0", true, current, current.components.server, "server");
    expect(status.kind).toBe("update-available");
    expect(status.release?.version).toBe("1.1.0");
  });
});

describe("release projection validation", () => {
  it("accepts only fixed official release-note links", () => {
    const valid = snapshot(release("1.0.0"));
    expect(normalizeReleaseUpdates(valid)).toEqual(valid);
    const malicious = structuredClone(valid);
    malicious.components.server.newestRelease!.releaseNotesUrl = "https://example.com/download";
    expect(normalizeReleaseUpdates(malicious)).toBeNull();
  });

  it("rejects malformed remote versions and unbounded fields", () => {
    const malformed = snapshot(release("1.0.0"));
    malformed.components.server.newestRelease!.version = "latest";
    expect(normalizeReleaseUpdates(malformed)).toBeNull();
    const oversized = snapshot(release("1.0.0"));
    oversized.components.android.newestRelease!.title = "x".repeat(161);
    expect(normalizeReleaseUpdates(oversized)).toBeNull();
  });
});
