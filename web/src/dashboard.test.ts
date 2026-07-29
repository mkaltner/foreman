import { describe, expect, it } from "vitest";
import {
  dashboardCounts,
  formatDuration,
  formatElapsed,
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
      { ...base, id: "a", repository: "/work/foreman", status: "working", lastActivity: 10 },
      { ...base, id: "b", repository: "/archive/foreman", status: "waiting", lastActivity: 20 },
      { ...base, id: "c", repository: "/work/foreman", status: "failed", terminalAt: now / 1000 },
    ], now);
    expect(groups).toHaveLength(2);
    expect(groups.map((group) => group.id)).toContain("/work/foreman");
    expect(groups.find((group) => group.id === "/work/foreman"))
      .toEqual(expect.objectContaining({ active: 1, failed: 1 }));
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
});
