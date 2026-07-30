import { describe, expect, it } from "vitest";
import { messageDraft, updateMessageDraft } from "./drafts";

describe("message drafts", () => {
  it("preserves independent text for each session", () => {
    let drafts = updateMessageDraft(new Map(), "host-home", "session-one", "First draft");
    drafts = updateMessageDraft(drafts, "host-home", "session-two", "Second draft");

    expect(messageDraft(drafts, "host-home", "session-one")).toBe("First draft");
    expect(messageDraft(drafts, "host-home", "session-two")).toBe("Second draft");
  });

  it("isolates colliding session IDs by host and clears submitted drafts", () => {
    let drafts = updateMessageDraft(new Map(), "host-home", "same-session", "Home draft");
    drafts = updateMessageDraft(drafts, "host-work", "same-session", "Work draft");
    drafts = updateMessageDraft(drafts, "host-home", "same-session", "");

    expect(messageDraft(drafts, "host-home", "same-session")).toBe("");
    expect(messageDraft(drafts, "host-work", "same-session")).toBe("Work draft");
  });
});
