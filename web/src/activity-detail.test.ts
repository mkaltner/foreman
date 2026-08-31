import { describe, expect, it } from "vitest";
import {
  activityStatusTone,
  conversationBlocks,
  formatActivityOutcome,
  formatActivitySummary,
} from "./activity-detail";
import type { ConversationItem } from "./protocol";

describe("activity detail", () => {
  it("collapses mixed zero and non-zero completed activity without changing order or outcomes", () => {
    const activity: ConversationItem[] = [
      { id: "status", kind: "command", description: "git status", status: "completed", exitCode: 0 },
      { id: "search", kind: "command", description: "rg needle", status: "completed", exitCode: 1 },
      { id: "read", kind: "tool", description: "Read file", status: "completed" },
      { id: "probe", kind: "tool", description: "Probe", status: "completed", exitCode: 7 },
    ];

    const blocks = conversationBlocks(activity, "focused");

    expect(blocks).toHaveLength(1);
    expect(blocks[0].collapsedActivity).toBe(true);
    expect(blocks[0].items).toEqual(activity);
    expect(blocks[0].items.map(({ id, status, exitCode }) => ({ id, status, exitCode }))).toEqual([
      { id: "status", status: "completed", exitCode: 0 },
      { id: "search", status: "completed", exitCode: 1 },
      { id: "read", status: "completed", exitCode: undefined },
      { id: "probe", status: "completed", exitCode: 7 },
    ]);
    expect(formatActivitySummary(blocks[0].items)).toBe("2 commands · 2 tools · 2 non-zero");
    expect(formatActivityOutcome(blocks[0].items[1])).toBe("Completed · Exited 1");
  });

  it("collapses an all-nonzero group without inferring intent from command text", () => {
    const activity: ConversationItem[] = [
      { id: "build", kind: "command", description: "build production", status: "completed", exitCode: 2 },
      { id: "search", kind: "command", description: "rg optional", status: "completed", exitCode: 1 },
    ];

    const blocks = conversationBlocks(activity, "focused");

    expect(blocks).toEqual([{ items: activity, collapsedActivity: true }]);
    expect(formatActivitySummary(activity)).toBe("2 commands · 2 non-zero");
  });

  it("keeps active, execution-error, interrupted, and blocked activity distinct", () => {
    const activity: ConversationItem[] = [
      { id: "done", kind: "tool", status: "completed", exitCode: 1 },
      { id: "running", kind: "command", status: "inProgress" },
      { id: "error", kind: "command", status: "executionError", exitCode: 127 },
      { id: "failed", kind: "command", status: "failed", exitCode: 2 },
      { id: "interrupted", kind: "tool", status: "interrupted" },
      { id: "blocked", kind: "tool", status: "denied" },
    ];

    const blocks = conversationBlocks(activity, "focused");

    expect(blocks.map(({ collapsedActivity, items }) => ({ collapsedActivity, ids: items.map(({ id }) => id) }))).toEqual([
      { collapsedActivity: true, ids: ["done"] },
      { collapsedActivity: false, ids: ["running"] },
      { collapsedActivity: false, ids: ["error"] },
      { collapsedActivity: false, ids: ["failed"] },
      { collapsedActivity: false, ids: ["interrupted"] },
      { collapsedActivity: false, ids: ["blocked"] },
    ]);
    expect(activityStatusTone(activity[1])).toBe("active");
    expect(activity.slice(2).map(activityStatusTone)).toEqual(["attention", "attention", "attention", "attention"]);
    expect(formatActivityOutcome(activity[2])).toBe("Execution error · Exited 127");
  });

  it("keeps protected and search-highlighted items directly reachable", () => {
    const activity: ConversationItem[] = [
      { id: "before", kind: "command", status: "completed", exitCode: 1 },
      { id: "protected", kind: "tool", status: "completed" },
      { id: "after", kind: "command", status: "completed", exitCode: 0 },
    ];

    const blocks = conversationBlocks(activity, "focused", new Set(["protected"]));

    expect(blocks.map(({ collapsedActivity, items }) => ({ collapsedActivity, ids: items.map(({ id }) => id) }))).toEqual([
      { collapsedActivity: true, ids: ["before"] },
      { collapsedActivity: false, ids: ["protected"] },
      { collapsedActivity: true, ids: ["after"] },
    ]);
  });

  it("keeps full mode complete and chronological", () => {
    const messages: ConversationItem[] = [
      { id: "user", kind: "user", text: "Please test" },
      { id: "nonzero", kind: "command", status: "completed", exitCode: 1 },
      { id: "running", kind: "tool", status: "running" },
      { id: "assistant", kind: "assistant", text: "Done" },
    ];

    const full = conversationBlocks(messages, "full", new Set(["nonzero"]));

    expect(full.map(({ items }) => items[0])).toEqual(messages);
    expect(full.every(({ collapsedActivity }) => !collapsedActivity)).toBe(true);
  });
});
