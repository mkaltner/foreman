import { describe, expect, it } from "vitest";
import {
  forgetServerUpdateOperationId,
  loadServerUpdateOperationId,
  normalizeServerUpdateCheck,
  normalizeServerUpdateOperation,
  saveServerUpdateOperationId,
  serverUpdatePhaseLabel,
} from "./server-update";

const operation = {
  id: "fmu_1234567890abcdef",
  phase: "healthChecking",
  currentVersion: "1.0.2",
  targetVersion: "1.0.3",
  source: "Official Foreman GitHub releases",
  sourceUrl: "https://github.com/mkaltner/foreman/releases",
  releaseNotesUrl: "https://github.com/mkaltner/foreman/releases/tag/v1.0.3",
  progress: 92,
  createdAt: "2026-08-31T00:00:00Z",
  updatedAt: "2026-08-31T00:00:01Z",
  message: "Checking the restarted service.",
};

describe("server update protocol projection", () => {
  it("accepts bounded durable progress and rejects paths, sources, and invalid phases", () => {
    expect(normalizeServerUpdateOperation(operation)?.phase).toBe("healthChecking");
    expect(normalizeServerUpdateOperation({ ...operation, phase: "runShell" })).toBeNull();
    expect(normalizeServerUpdateOperation({ ...operation, sourceUrl: "https://evil.invalid" })).toBeNull();
    expect(normalizeServerUpdateOperation({ ...operation, recoveryCommand: "rm -rf /" })).toBeNull();
    expect(normalizeServerUpdateOperation({ ...operation, id: "fmu_too-short" })).toBeNull();
    expect(serverUpdatePhaseLabel("rollingBack")).toBe("Rolling back");
  });

  it("validates safe blocker categories without accepting transcript-shaped data", () => {
    const check = normalizeServerUpdateCheck({
      currentVersion: "1.0.2",
      releaseBuild: true,
      source: "Official Foreman GitHub releases",
      sourceUrl: "https://github.com/mkaltner/foreman/releases",
      updateAvailable: true,
      target: {
        version: "1.0.3",
        tag: "v1.0.3",
        title: "Foreman 1.0.3",
        publishedAt: "2026-08-31T00:00:00Z",
        releaseNotesUrl: "https://github.com/mkaltner/foreman/releases/tag/v1.0.3",
        artifactAvailable: true,
      },
      blockers: [{ category: "pendingInput", count: 1 }],
      operation,
    });
    expect(check?.blockers).toEqual([{ category: "pendingInput", count: 1 }]);
    expect(normalizeServerUpdateCheck({ ...check, blockers: [{ category: "prompt", count: 1, transcript: "secret" }] })).toBeNull();
    expect(normalizeServerUpdateCheck({ ...check, updateAvailable: true, target: null })).toBeNull();
  });

  it("keeps operation restoration isolated by host", () => {
    localStorage.clear();
    saveServerUpdateOperationId("host-a", operation.id);
    saveServerUpdateOperationId("host-b", "fmu_bbbbbbbbbbbbbbbb");
    expect(loadServerUpdateOperationId("host-a")).toBe(operation.id);
    expect(loadServerUpdateOperationId("host-b")).toBe("fmu_bbbbbbbbbbbbbbbb");
    forgetServerUpdateOperationId("host-a");
    expect(loadServerUpdateOperationId("host-a")).toBeNull();
    expect(loadServerUpdateOperationId("host-b")).toBe("fmu_bbbbbbbbbbbbbbbb");
  });
});
