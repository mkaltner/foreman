import { describe, expect, it } from "vitest";
import {
  applySessionEvent,
  applySessionSummaryEventBatch,
  groupSessions,
  liveActivityLabel,
  liveActivityMessage,
  reconcileSessionSettings,
  routeForSession,
  type AccessLevelInfo,
  type ModelInfo,
  type SessionSummary,
} from "./protocol";

const session: SessionSummary = {
  id: "thread-1",
  title: "Test",
  repository: "/projects/test",
  status: "idle",
  messages: [],
};

describe("session mapping and live events", () => {
  it("groups waiting, active, and recent sessions by recency", () => {
    const grouped = groupSessions([
      { ...session, id: "recent", lastActivity: 1 },
      { ...session, id: "active", status: "working", lastActivity: 2 },
      { ...session, id: "waiting", status: "waiting", attention: true, lastActivity: 3 },
    ]);
    expect(grouped.waiting.map((entry) => entry.id)).toEqual(["waiting"]);
    expect(grouped.active.map((entry) => entry.id)).toEqual(["active"]);
    expect(grouped.recent.map((entry) => entry.id)).toEqual(["recent"]);
  });

  it("coalesces assistant deltas and applies item, activity, status, and route updates", () => {
    let current = applySessionEvent(session, {
      kind: "assistant.delta",
      itemId: "agent-1",
      turnId: "turn-1",
      text: "Hel",
    });
    current = applySessionEvent(current, {
      kind: "assistant.delta",
      itemId: "agent-1",
      turnId: "turn-1",
      text: "lo",
    });
    expect(current.messages).toEqual([
      expect.objectContaining({ id: "agent-1", text: "Hello", kind: "assistant" }),
    ]);
    current = applySessionEvent(current, {
      kind: "item",
      turnId: "turn-1",
      item: { id: "tool-1", kind: "tool", description: "Web search", status: "inProgress" },
    });
    current = applySessionEvent(current, {
      kind: "activity",
      label: "Planning",
      text: "Step one",
      append: true,
    });
    current = applySessionEvent(current, {
      kind: "route",
      model: "gpt-test",
      reasoningEffort: "high",
      accessLevel: "auto",
    });
    expect(current.messages?.at(-1)).toEqual(expect.objectContaining({ id: "tool-1" }));
    expect(current.activityText).toBe("Step one");
    expect(current).toEqual(
      expect.objectContaining({ model: "gpt-test", reasoningEffort: "high", accessLevel: "auto" }),
    );
    current = applySessionEvent(current, { kind: "status", status: "completed", turnId: "turn-1" });
    expect(current.activeTurnId).toBeNull();
    expect(current.activityText).toBe("Step one");
  });

  it("presents the latest meaningful live activity like Android", () => {
    let current = applySessionEvent(session, {
      kind: "item",
      phase: "started",
      turnId: "turn-1",
      item: {
        id: "command-1",
        kind: "command",
        description: "/bin/bash -lc 'npm test'",
        status: "inProgress",
      },
    });
    expect(liveActivityLabel(current)).toBe("Running command");
    expect(liveActivityMessage(current)).toBe("/bin/bash -lc 'npm test'");
    current = applySessionEvent(current, {
      kind: "activity",
      label: "Running command",
      text: "",
      append: false,
    });
    expect(liveActivityMessage(current)).toBe("/bin/bash -lc 'npm test'");
    current = applySessionEvent(current, {
      kind: "activity",
      label: "Thinking",
      text: "Checking files\nPlanning the next change",
      append: false,
    });
    expect(liveActivityMessage(current)).toBe("Planning the next change");
  });

  it("does not treat route changes as conversation activity", () => {
    const current = applySessionEvent({ ...session, lastActivity: 123 }, {
      kind: "route",
      model: "gpt-test",
      observedAt: 999,
    });

    expect(current.lastActivity).toBe(123);
    expect(current.model).toBe("gpt-test");
  });

  it("applies context usage without treating it as conversation activity", () => {
    const current = applySessionEvent({ ...session, lastActivity: 123 }, {
      kind: "usage",
      observedAt: 999,
      tokenUsage: {
        total: { totalTokens: 2_500_000 },
        last: { totalTokens: 121_800, cachedInputTokens: 100_000, outputTokens: 800 },
        modelContextWindow: 1_000_000,
      },
    });

    expect(current.lastActivity).toBe(123);
    expect(current.tokenUsage).toEqual(expect.objectContaining({
      last: expect.objectContaining({ totalTokens: 121_800 }),
      modelContextWindow: 1_000_000,
    }));
  });

  it("requires an explicit status event to reactivate a terminal session", () => {
    const completed = applySessionEvent(session, {
      kind: "status",
      status: "completed",
      turnId: "turn-1",
      completedAt: 200,
    });
    const delayed = applySessionEvent(completed, {
      kind: "assistant.delta",
      turnId: "turn-1",
      itemId: "assistant-1",
      text: "delayed text",
      observedAt: 199,
    });

    expect(delayed).toEqual(expect.objectContaining({
      status: "completed",
      activeTurnId: null,
      messages: [expect.objectContaining({ id: "assistant-1", text: "delayed text" })],
    }));
    const reactivated = applySessionEvent(delayed, {
      kind: "status",
      status: "working",
      turnId: "turn-2",
      observedAt: 201,
    });
    const staleOldTurn = applySessionEvent(reactivated, {
      kind: "assistant.delta",
      turnId: "turn-1",
      itemId: "assistant-1",
      text: " still old",
      observedAt: 201,
    });
    expect(staleOldTurn).toEqual(expect.objectContaining({
      status: "working",
      activeTurnId: "turn-2",
      messages: [expect.objectContaining({ id: "assistant-1", text: "delayed text still old" })],
    }));
    expect(applySessionEvent(staleOldTurn, {
      kind: "assistant.delta",
      turnId: "turn-2",
      itemId: "assistant-2",
      text: "new turn",
      observedAt: 202,
    })).toEqual(expect.objectContaining({ status: "working" }));
  });

  it("keeps new-session defaults separate from unknown existing-session settings", () => {
    const models: ModelInfo[] = [
      { id: "hidden", displayName: "Hidden", visible: false, isDefault: false, reasoningEfforts: ["low"] },
      { id: "dynamic", displayName: "Dynamic", visible: true, isDefault: true, reasoningEfforts: ["medium", "high"], defaultReasoningEffort: "medium" },
    ];
    const access: AccessLevelInfo[] = [{ id: "workspace", displayName: "Workspace" }];
    expect(routeForSession(null, models, access)).toEqual({
      model: "dynamic",
      reasoningEffort: "medium",
      accessLevel: "workspace",
    });
    expect(routeForSession(session, models, access)).toEqual({
      model: "",
      reasoningEffort: "",
      accessLevel: "",
    });
  });

  it("keeps a newer live setting when a stale refresh arrives and isolates sessions", () => {
    const newer = {
      ...session,
      accessLevel: "full",
      model: "gpt-new",
      reasoningEffort: "high",
      settingsRevision: 4,
    };
    const stale = {
      ...session,
      accessLevel: "ask",
      model: "gpt-old",
      reasoningEffort: "low",
      settingsRevision: 3,
    };
    expect(reconcileSessionSettings(newer, stale)).toEqual(newer);
    expect(reconcileSessionSettings({ ...newer, id: "other" }, stale)).toEqual(stale);
    expect(applySessionEvent(newer, {
      kind: "route",
      accessLevel: "ask",
      settingsRevision: 2,
    })).toEqual(newer);
  });

  it("isolates colliding event streams by provider", () => {
    const sessions: SessionSummary[] = [
      { ...session, provider: "codex", id: "same" },
      { ...session, provider: "claude-code", id: "same" },
    ];
    const updated = applySessionSummaryEventBatch(sessions, new Map([
      ["11:claude-codesame", [{ kind: "status", status: "working" }]],
    ]));
    expect(updated[0].status).toBe("idle");
    expect(updated[1].status).toBe("working");
  });
});
