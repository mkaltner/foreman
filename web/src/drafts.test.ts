import { describe, expect, it } from "vitest";
import { messageDraft, updateMessageDraft } from "./drafts";

describe("message drafts", () => {
  it("preserves independent text for each session", () => {
    let drafts = updateMessageDraft(new Map(), "host-home", "codex", "session-one", "First draft");
    drafts = updateMessageDraft(drafts, "host-home", "claude-code", "session-two", "Second draft");

    expect(messageDraft(drafts, "host-home", "codex", "session-one")).toBe("First draft");
    expect(messageDraft(drafts, "host-home", "claude-code", "session-two")).toBe("Second draft");
  });

  it("isolates colliding session IDs by host and clears submitted drafts", () => {
    let drafts = updateMessageDraft(new Map(), "host-home", "codex", "same-session", "Home draft");
    drafts = updateMessageDraft(drafts, "host-home", "claude-code", "same-session", "Claude draft");
    drafts = updateMessageDraft(drafts, "host-work", "codex", "same-session", "Work draft");
    drafts = updateMessageDraft(drafts, "host-home", "codex", "same-session", "");

    expect(messageDraft(drafts, "host-home", "codex", "same-session")).toBe("");
    expect(messageDraft(drafts, "host-home", "claude-code", "same-session")).toBe("Claude draft");
    expect(messageDraft(drafts, "host-work", "codex", "same-session")).toBe("Work draft");
  });
});
