import { describe, expect, it } from "vitest";
import {
  applySessionEvent,
  groupSessions,
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
    expect(current.activityText).toBe("");
  });

  it("selects only dynamic model, effort, and access data", () => {
    const models: ModelInfo[] = [
      { id: "hidden", displayName: "Hidden", visible: false, isDefault: false, reasoningEfforts: ["low"] },
      { id: "dynamic", displayName: "Dynamic", visible: true, isDefault: true, reasoningEfforts: ["medium", "high"], defaultReasoningEffort: "medium" },
    ];
    const access: AccessLevelInfo[] = [{ id: "workspace", displayName: "Workspace" }];
    expect(routeForSession(session, models, access)).toEqual({
      model: "dynamic",
      reasoningEffort: "medium",
      accessLevel: "workspace",
    });
  });
});
