import { describe, expect, it } from "vitest";
import {
  attentionState,
  dashboardCounts,
  MAX_RECENT_ACTIVITY,
  formatDuration,
  formatElapsed,
  isStaleActive,
  oldestActiveSession,
  recordRecentActivity,
  repositoryGroups,
  sessionMatchesFilter,
  sortDashboardSessions,
} from "./dashboard";
import {
  applySessionSummaryEvent,
  applySessionSummaryEventBatch,
  reconcileSessionSummaries,
  type SessionSummary,
} from "./protocol";

const now = 1_720_000_000_000;
const base: SessionSummary = {
  id: "one",
  repository: "/projects/foreman",
  title: "Build dashboard",
  status: "idle",
};

describe("dashboard projections", () => {
  it("counts active, waiting, failed, and recent terminal sessions", () => {
    const sessions: SessionSummary[] = [
      { ...base, id: "active", status: "working" },
      { ...base, id: "waiting", status: "waiting", waitType: "approval" },
      { ...base, id: "failed", status: "failed", terminalAt: now / 1000 - 10 },
      { ...base, id: "done", status: "completed", terminalAt: now / 1000 - 20 },
      { ...base, id: "old", status: "completed", terminalAt: now / 1000 - 7200 },
    ];
    expect(dashboardCounts(sessions, now)).toEqual({ active: 1, waiting: 1, failed: 1, recent: 2 });
    expect(sessionMatchesFilter(sessions[1], "waiting", now)).toBe(true);
    expect(sessionMatchesFilter(sessions[4], "recent", now)).toBe(false);
  });

  it("groups repositories by canonical path without merging equal display names", () => {
    const groups = repositoryGroups([
      { ...base, id: "a", repository: "/work/foreman", status: "working", lastActivity: 10, activityLabel: "Running tests" },
      { ...base, id: "b", repository: "/archive/foreman", status: "waiting", lastActivity: 20 },
      { ...base, id: "c", repository: "/work/foreman", status: "failed", terminalAt: now / 1000 },
    ], now);
    expect(groups).toHaveLength(2);
    expect(groups.map((group) => group.id)).toContain("/work/foreman");
    expect(groups.find((group) => group.id === "/work/foreman"))
      .toEqual(expect.objectContaining({ active: 1, failed: 1, currentActivity: "Running tests" }));
  });

  it("sorts attention before active work and then by latest activity", () => {
    const sorted = sortDashboardSessions([
      { ...base, id: "older-active", status: "working", lastActivity: 20 },
      { ...base, id: "newer-active", status: "working", lastActivity: 30 },
      { ...base, id: "wait", status: "waiting", lastActivity: 10 },
    ]);
    expect(sorted.map((session) => session.id)).toEqual(["wait", "newer-active", "older-active"]);
  });

  it("formats authoritative elapsed and completed durations", () => {
    expect(formatElapsed(now / 1000 - 12, now)).toBe("12s");
    expect(formatElapsed(now / 1000 - 134, now)).toBe("2m 14s");
    expect(formatElapsed(now / 1000 - 3780, now)).toBe("1h 03m");
    expect(formatDuration(12_345)).toBe("12s");
    expect(formatElapsed(null, now)).toBe("Start time unavailable");
  });

  it("updates only monitoring fields and never stores transcript deltas", () => {
    const active = applySessionSummaryEvent(base, {
      kind: "status",
      status: "working",
      turnId: "turn-1",
      startedAt: now / 1000,
      observedAt: now / 1000,
    });
    const responding = applySessionSummaryEvent(active, {
      kind: "assistant.delta",
      turnId: "turn-1",
      itemId: "assistant-1",
      text: "private transcript content",
      observedAt: now / 1000 + 1,
    });
    expect(responding.activityLabel).toBe("Responding");
    expect(responding.messages).toBeUndefined();
    const waiting = applySessionSummaryEvent(responding, {
      kind: "status",
      status: "waiting",
      turnId: "turn-1",
      waitType: "approval",
      waitDescription: "Approval required elsewhere",
    });
    expect(waiting).toEqual(expect.objectContaining({ status: "waiting", waitType: "approval" }));
    const completed = applySessionSummaryEvent(waiting, {
      kind: "status",
      status: "completed",
      turnId: "turn-1",
      completedAt: now / 1000 + 5,
      durationMs: 5000,
    });
    expect(completed).toEqual(expect.objectContaining({
      status: "completed",
      activeTurnId: null,
      terminalAt: now / 1000 + 5,
      turnDurationMs: 5000,
    }));
  });

  it("replaces live tool activity when a tool completes", () => {
    const running = applySessionSummaryEvent(base, {
      kind: "item",
      phase: "started",
      turnId: "turn-1",
      item: { id: "tool-1", kind: "tool", description: "Web search", status: "inProgress" },
    });
    expect(running.activityLabel).toBe("Searching the web");
    const completed = applySessionSummaryEvent(running, {
      kind: "item",
      phase: "completed",
      turnId: "turn-1",
      item: { id: "tool-1", kind: "tool", description: "Web search", status: "completed" },
    });
    expect(completed.activityLabel).toBe("Thinking");
  });

  it("preserves browser-observed terminal state across a summary resync", () => {
    const previous = [{ ...base, status: "completed", terminalAt: now / 1000, turnDurationMs: 5000 }];
    const reconciled = reconcileSessionSummaries(previous, [{ ...base, status: "idle" }]);
    expect(reconciled[0]).toEqual(expect.objectContaining({ status: "completed", terminalAt: now / 1000 }));
  });

  it("coalesces frequent activity for five active sessions without replacing unrelated cards", () => {
    const sessions = Array.from({ length: 20 }, (_, index): SessionSummary => ({
      ...base,
      id: `session-${index}`,
      status: index < 5 ? "working" : "idle",
    }));
    const buffered = new Map(
      sessions.slice(0, 5).map((session) => [
        session.id,
        Array.from({ length: 20 }, (_, index) => ({
          kind: "activity",
          label: "Thinking",
          text: `Update ${index}`,
          append: false,
          observedAt: now / 1000 + index,
        })),
      ]),
    );
    const updated = applySessionSummaryEventBatch(sessions, buffered);
    expect(updated.slice(0, 5).every((session) => session.activityText === "Update 19")).toBe(true);
    expect(updated.slice(5).every((session, index) => session === sessions[index + 5])).toBe(true);
    expect(updated.every((session) => session.messages === undefined)).toBe(true);
  });

  it("does not let a buffered assistant delta revive a terminal session", () => {
    const completed: SessionSummary = {
      ...base,
      status: "completed",
      terminalAt: now / 1000,
      turnDurationMs: 5000,
    };
    const updated = applySessionSummaryEventBatch([completed], new Map([
      [completed.id, [{
        kind: "assistant.delta",
        turnId: "completed-turn",
        itemId: "assistant-1",
        text: "late buffered text",
        observedAt: now / 1000 - 1,
      }]],
    ]));

    expect(updated[0]).toBe(completed);
    expect(updated[0]).toEqual(expect.objectContaining({
      status: "completed",
      terminalAt: now / 1000,
      turnDurationMs: 5000,
    }));
  });

  it("does not let buffered events from a completed turn overwrite a new turn", () => {
    const reactivated: SessionSummary = {
      ...base,
      status: "working",
      activeTurnId: "new-turn",
      activityLabel: "Starting new turn",
      lastActivity: now / 1000 + 2,
    };
    const updated = applySessionSummaryEventBatch([reactivated], new Map([
      [reactivated.id, [
        {
          kind: "assistant.delta",
          turnId: "completed-turn",
          itemId: "assistant-1",
          text: "late buffered text",
          observedAt: now / 1000,
        },
        {
          kind: "activity",
          turnId: "completed-turn",
          label: "Finishing old turn",
          observedAt: now / 1000 + 1,
        },
      ]],
    ]));

    expect(updated[0]).toBe(reactivated);
    expect(updated[0]).toEqual(expect.objectContaining({
      status: "working",
      activeTurnId: "new-turn",
      activityLabel: "Starting new turn",
      lastActivity: now / 1000 + 2,
    }));
  });

  it("classifies waiting, failure, disconnect, and conservative stale attention", () => {
    const service = {
      foremanVersion: "test", connected: true, uptimeSeconds: 1,
      codex: { connected: true, mode: "shared" as const, runtimeStatus: "available" },
      listeners: { tcpPort: 1 }, repositoryRoot: "/work",
    };
    expect(attentionState({ ...base, status: "waiting", waitType: "input" }, service, now)?.label).toBe("Waiting for input");
    expect(attentionState({ ...base, status: "failed", terminalAt: now / 1000 }, service, now)?.type).toBe("failed");
    expect(attentionState({ ...base, status: "working" }, { ...service, codex: { ...service.codex, connected: false } }, now)?.type).toBe("disconnected");
    const active = { ...base, status: "working", lastActivity: now / 1000 - 601 };
    expect(isStaleActive(active, service, now)).toBe(true);
    const refreshed = applySessionSummaryEvent(active, { kind: "activity", label: "Running tests", observedAt: now / 1000 });
    expect(isStaleActive(refreshed, service, now)).toBe(false);
  });

  it("selects the oldest authoritative active turn", () => {
    const selected = oldestActiveSession([
      { ...base, id: "new", status: "working", activeTurnStartedAt: now / 1000 - 30 },
      { ...base, id: "old", status: "waiting", activeTurnStartedAt: now / 1000 - 90 },
      { ...base, id: "guess", status: "working" },
    ]);
    expect(selected?.id).toBe("old");
  });

  it("separates discovered repositories from unscoped workspaces", () => {
    const groups = repositoryGroups([
      { ...base, id: "repo", repository: "/work/foreman/packages/web" },
      { ...base, id: "home", repository: "/home/operator" },
    ], now, [{ id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false }], "/work");
    expect(groups.find((group) => group.id === "/work/foreman")?.kind).toBe("repository");
    expect(groups.find((group) => group.id === "/home/operator")?.kind).toBe("workspace");
  });

  it("coalesces noisy activity and bounds the browser-only feed at twenty", () => {
    let entries = recordRecentActivity([], base, { kind: "activity", label: "Thinking", observedAt: now / 1000 }, now);
    entries = recordRecentActivity(entries, base, { kind: "activity", label: "Running tests", text: "private prompt", observedAt: now / 1000 + 1 }, now + 1000);
    expect(entries).toHaveLength(1);
    expect(entries[0].description).toBe("Running tests");
    expect(JSON.stringify(entries)).not.toContain("private prompt");
    entries = recordRecentActivity(entries, base, {
      kind: "item",
      phase: "started",
      item: { id: "command", kind: "command", description: "private command" },
      observedAt: now / 1000 + 2,
    }, now + 2000);
    expect(entries).toHaveLength(1);
    expect(JSON.stringify(entries)).not.toContain("private command");
    entries = recordRecentActivity(entries, base, { kind: "activity", label: "Editing files", observedAt: now / 1000 + 600 }, now + 600_000);
    expect(entries).toHaveLength(1);
    expect(entries[0].description).toBe("Editing files");
    entries = recordRecentActivity(entries, { ...base, status: "working" }, { kind: "status", status: "working", observedAt: now / 1000 + 601 }, now + 601_000);
    expect(entries).toHaveLength(1);
    for (let index = 0; index < 25; index += 1) {
      entries = recordRecentActivity(entries, { ...base, id: `session-${index}` }, { kind: "status", status: "completed", observedAt: now / 1000 + index + 10 }, now + index * 1000);
    }
    expect(entries).toHaveLength(MAX_RECENT_ACTIVITY);
  });
});
