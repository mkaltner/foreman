import { describe, expect, it } from "vitest";
import { conversationBlocks } from "./activity-detail";
import type { ConversationItem } from "./protocol";

describe("activity detail", () => {
  const messages: ConversationItem[] = [
    { id: "user", kind: "user", text: "Please test" },
    { id: "command", kind: "command", description: "git status", status: "completed", exitCode: 0 },
    { id: "tool", kind: "tool", description: "Read file", status: "completed" },
    { id: "failed", kind: "command", description: "run tests", status: "completed", exitCode: 1 },
    { id: "assistant", kind: "assistant", text: "I found the issue" },
    { id: "approval-item", kind: "tool", description: "Protected", status: "completed" },
  ];

  it("groups only routine successful work in focused mode", () => {
    const focused = conversationBlocks(messages, "focused", new Set(["approval-item"]));
    expect(focused).toHaveLength(5);
    expect(focused[1]).toMatchObject({
      collapsedActivity: true,
      items: [{ id: "command" }, { id: "tool" }],
    });
    expect(focused[2]).toMatchObject({ collapsedActivity: false, items: [{ id: "failed" }] });
    expect(focused.at(-1)).toMatchObject({
      collapsedActivity: false,
      items: [{ id: "approval-item" }],
    });
  });

  it("keeps every item visible in full mode", () => {
    const full = conversationBlocks(messages, "full");
    expect(full.map(({ items }) => items[0].id)).toEqual(messages.map(({ id }) => id));
    expect(full.every(({ collapsedActivity }) => !collapsedActivity)).toBe(true);
  });
});
