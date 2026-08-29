import type { ProviderId } from "./protocol";

export interface SessionPresence {
  provider: ProviderId;
  sessionId: string;
}

export function sessionPresenceKey(provider: ProviderId, sessionId: string): string {
  return `${provider}:${sessionId}`;
}

export function parseSessionPresence(payload: unknown): Set<string> {
  const sessions = payload && typeof payload === "object"
    ? (payload as { sessions?: unknown }).sessions
    : null;
  if (!Array.isArray(sessions)) return new Set();
  return new Set(sessions.flatMap((candidate) => {
    if (!candidate || typeof candidate !== "object") return [];
    const { provider, sessionId } = candidate as { provider?: unknown; sessionId?: unknown };
    if ((provider !== "codex" && provider !== "claude-code") || typeof sessionId !== "string" || !sessionId) {
      return [];
    }
    return [sessionPresenceKey(provider, sessionId)];
  }));
}

export function sessionIsFocused(
  focusedSessions: ReadonlySet<string>,
  provider: ProviderId,
  sessionId: string,
): boolean {
  return focusedSessions.has(sessionPresenceKey(provider, sessionId));
}
