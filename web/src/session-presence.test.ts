import { describe, expect, it } from "vitest";
import {
  parseSessionPresence,
  sessionIsFocused,
  sessionPresenceKey,
  SessionPresenceProjectionGuard,
} from "./session-presence";

describe("session presence", () => {
  it("accepts provider/session projections and deduplicates them", () => {
    const focused = parseSessionPresence({
      sessions: [
        { provider: "codex", sessionId: "one" },
        { provider: "codex", sessionId: "one" },
        { provider: "claude-code", sessionId: "two" },
        { provider: "unknown", sessionId: "ignored" },
        { provider: "codex", sessionId: "" },
        null,
      ],
    });

    expect([...focused]).toEqual(["codex:one", "claude-code:two"]);
    expect(sessionIsFocused(focused, "codex", "one")).toBe(true);
    expect(sessionIsFocused(focused, "claude-code", "one")).toBe(false);
  });

  it("treats malformed payloads as empty and keeps providers distinct", () => {
    expect(parseSessionPresence({ sessions: "invalid" }).size).toBe(0);
    expect(sessionPresenceKey("codex", "same")).not.toBe(sessionPresenceKey("claude-code", "same"));
  });

  it("rejects stale request results after a newer request or event", () => {
    const guard = new SessionPresenceProjectionGuard();
    const firstRequest = guard.beginRequest();
    const secondRequest = guard.beginRequest();

    expect(guard.isCurrent(firstRequest)).toBe(false);
    expect(guard.isCurrent(secondRequest)).toBe(true);

    guard.invalidate();
    expect(guard.isCurrent(secondRequest)).toBe(false);
  });
});
