import { sessionIdentityKey } from "./unified";
import type { ProviderId } from "./protocol";

export type MessageDrafts = ReadonlyMap<string, string>;

export function messageDraft(
  drafts: MessageDrafts,
  hostId: string,
  provider: ProviderId,
  sessionId: string,
): string {
  return drafts.get(sessionIdentityKey({ hostId, provider, sessionId })) ?? "";
}

export function updateMessageDraft(
  drafts: MessageDrafts,
  hostId: string,
  provider: ProviderId,
  sessionId: string,
  text: string,
): Map<string, string> {
  const next = new Map(drafts);
  const key = sessionIdentityKey({ hostId, provider, sessionId });
  if (text) next.set(key, text);
  else next.delete(key);
  return next;
}
