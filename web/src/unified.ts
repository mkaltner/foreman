import type { ConnectionState } from "./client";
import { sessionProvider, type ApprovalRequest, type InputRequest, type ProviderId, type ServiceStatus, type SessionSummary } from "./protocol";

export const MAX_WEB_HOST_CONNECTIONS = 4;
export const WEB_HOST_ROTATION_MS = 60_000;

export interface GlobalSessionIdentity {
  hostId: string;
  provider?: ProviderId;
  sessionId: string;
}

export interface UnifiedAttentionItem extends GlobalSessionIdentity {
  approvalId?: string;
  sessionTitle: string;
  repository: string;
  type: "approval" | "input" | "failed";
  startedAt: number | null;
}

export interface HostOverviewSnapshot {
  hostId: string;
  observedAt: number;
  connection: ConnectionState;
  foremanVersion: string | null;
  codexVersion: string | null;
  runtimeMode: "shared" | "fallback" | "unavailable" | null;
  runtimeConnected: boolean;
  active: number;
  waiting: number;
  failed: number;
  oldestTurn: { hostId: string; provider?: ProviderId; sessionId: string; title: string; startedAt: number } | null;
  latestCompletion: { hostId: string; provider?: ProviderId; sessionId: string; title: string; completedAt: number } | null;
  latestActivity: number | null;
  attention: UnifiedAttentionItem[];
}

export interface UnifiedTotals {
  hosts: number;
  connectedHosts: number;
  staleHosts: number;
  active: number;
  waiting: number;
  failed: number;
  oldestTurn: HostOverviewSnapshot["oldestTurn"];
  latestCompletion: HostOverviewSnapshot["latestCompletion"];
}

function millis(value?: number | null): number | null {
  if (!value) return null;
  return value < 10_000_000_000 ? value * 1000 : value;
}

export function projectHostSnapshot(
  hostId: string,
  sessions: SessionSummary[],
  approvals: ApprovalRequest[],
  status: ServiceStatus | null,
  connection: ConnectionState,
  observedAt = Date.now(),
  inputs: InputRequest[] = [],
): HostOverviewSnapshot {
  const pending = approvals.filter(({ status }) => status === "pending" || status === "submitting");
  const pendingInputs = inputs.filter(({ status }) => status === "pending" || status === "submitting");
  const requestSessions = new Set([...pending.map(({ sessionId }) => sessionId), ...pendingInputs.map(({ sessionId }) => sessionId)]);
  const activeSessions = sessions.filter(({ status }) => status === "working");
  const waitingSessions = sessions.filter((session) => sessionProvider(session) === "codex" && (session.status === "waiting" || session.attention));
  const failedSessions = sessions.filter((session) => sessionProvider(session) === "codex" && session.status === "failed");
  const oldest = [...activeSessions, ...waitingSessions]
    .filter(({ activeTurnStartedAt }) => millis(activeTurnStartedAt) !== null)
    .sort((left, right) => millis(left.activeTurnStartedAt)! - millis(right.activeTurnStartedAt)!)[0];
  const latest = sessions
    .filter((session) => ["completed", "failed", "interrupted", "idle"].includes(session.status))
    .filter(({ terminalAt }) => millis(terminalAt) !== null)
    .sort((left, right) => millis(right.terminalAt)! - millis(left.terminalAt)!)[0];
  const attention: UnifiedAttentionItem[] = pending.map((approval) => {
    const session = sessions.find(({ id }) => id === approval.sessionId);
    return {
      hostId,
      provider: session ? sessionProvider(session) : "codex",
      sessionId: approval.sessionId,
      approvalId: approval.id,
      sessionTitle: session?.title || "Codex session",
      repository: session?.repository || "",
      type: "approval",
      startedAt: millis(approval.startedAt ?? approval.createdAt),
    };
  });
  pendingInputs.forEach((input) => {
    const session = sessions.find(({ id }) => id === input.sessionId);
    attention.push({
      hostId,
      provider: session ? sessionProvider(session) : "codex",
      sessionId: input.sessionId,
      approvalId: input.id,
      sessionTitle: session?.title || "Codex session",
      repository: session?.repository || "",
      type: "input",
      startedAt: millis(input.createdAt),
    });
  });
  waitingSessions
    .filter(({ id }) => !requestSessions.has(id))
    .forEach((session) => attention.push({
      hostId,
      provider: sessionProvider(session),
      sessionId: session.id,
      sessionTitle: session.title,
      repository: session.repository,
      type: session.waitType === "input" ? "input" : "approval",
      startedAt: millis(session.statusChangedAt ?? session.activeTurnStartedAt ?? session.lastActivity),
    }));
  failedSessions.forEach((session) => attention.push({
    hostId,
    provider: sessionProvider(session),
    sessionId: session.id,
    sessionTitle: session.title,
    repository: session.repository,
    type: "failed",
    startedAt: millis(session.terminalAt ?? session.lastActivity),
  }));

  return {
    hostId,
    observedAt,
    connection,
    foremanVersion: status?.foremanVersion ?? null,
    codexVersion: status?.codex.version ?? null,
    runtimeMode: status?.codex.mode ?? null,
    runtimeConnected: status?.codex.connected === true,
    active: activeSessions.length,
    waiting: waitingSessions.length,
    failed: failedSessions.length,
    oldestTurn: oldest ? {
      hostId,
      provider: sessionProvider(oldest),
      sessionId: oldest.id,
      title: oldest.title,
      startedAt: millis(oldest.activeTurnStartedAt)!,
    } : null,
    latestCompletion: latest ? {
      hostId,
      provider: sessionProvider(latest),
      sessionId: latest.id,
      title: latest.title,
      completedAt: millis(latest.terminalAt)!,
    } : null,
    latestActivity: sessions.reduce<number | null>((result, session) => {
      const value = millis(session.lastActivity);
      return value !== null && (result === null || value > result) ? value : result;
    }, null),
    attention: attention.sort((left, right) => (left.startedAt ?? observedAt) - (right.startedAt ?? observedAt)),
  };
}

export function aggregateHostSnapshots(
  hostIds: string[],
  snapshots: Map<string, HostOverviewSnapshot>,
): UnifiedTotals {
  const available = hostIds.map((id) => snapshots.get(id)).filter((value): value is HostOverviewSnapshot => !!value);
  const oldestTurn = available.flatMap(({ oldestTurn }) => oldestTurn ? [oldestTurn] : [])
    .sort((left, right) => left.startedAt - right.startedAt)[0] ?? null;
  const latestCompletion = available.flatMap(({ latestCompletion }) => latestCompletion ? [latestCompletion] : [])
    .sort((left, right) => right.completedAt - left.completedAt)[0] ?? null;
  return {
    hosts: hostIds.length,
    connectedHosts: available.filter(({ connection }) => connection === "connected").length,
    staleHosts: hostIds.length - available.filter(({ connection }) => connection === "connected").length,
    active: available.reduce((sum, { active }) => sum + active, 0),
    waiting: available.reduce((sum, { waiting }) => sum + waiting, 0),
    failed: available.reduce((sum, { failed }) => sum + failed, 0),
    oldestTurn,
    latestCompletion,
  };
}

export function mergeHostSnapshot(
  snapshots: Map<string, HostOverviewSnapshot>,
  snapshot: HostOverviewSnapshot,
): Map<string, HostOverviewSnapshot> {
  const next = new Map(snapshots);
  next.set(snapshot.hostId, snapshot);
  return next;
}

export function sessionIdentityKey(identity: GlobalSessionIdentity): string {
  const provider = identity.provider ?? "codex";
  return `${identity.hostId.length}:${identity.hostId}${provider.length}:${provider}${identity.sessionId}`;
}

export function liveBackgroundHostIds(
  hostIds: string[],
  activeHostId: string | null,
  offset: number,
): string[] {
  const candidates = hostIds.filter((id) => id !== activeHostId);
  const limit = Math.max(0, MAX_WEB_HOST_CONNECTIONS - (activeHostId ? 1 : 0));
  if (candidates.length <= limit) return candidates;
  return Array.from({ length: limit }, (_, index) => candidates[(offset + index) % candidates.length]);
}
