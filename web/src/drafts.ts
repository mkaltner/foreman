import { sessionIdentityKey } from "./unified";

export type MessageDrafts = ReadonlyMap<string, string>;

export function messageDraft(
  drafts: MessageDrafts,
  hostId: string,
  sessionId: string,
): string {
  return drafts.get(sessionIdentityKey({ hostId, sessionId })) ?? "";
}

export function updateMessageDraft(
  drafts: MessageDrafts,
  hostId: string,
  sessionId: string,
  text: string,
): Map<string, string> {
  const next = new Map(drafts);
  const key = sessionIdentityKey({ hostId, sessionId });
  if (text) next.set(key, text);
  else next.delete(key);
  return next;
}
