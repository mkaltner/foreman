import type { ServiceStatus, SessionSummary } from "./protocol";

export const RECENT_WINDOW_MS = 60 * 60 * 1000;
export type DashboardFilter = "all" | "active" | "waiting" | "failed" | "recent";

export interface DashboardCounts {
  active: number;
  waiting: number;
  failed: number;
  recent: number;
}

export interface RepositoryGroup {
  id: string;
  name: string;
  sessions: SessionSummary[];
  active: number;
  waiting: number;
  failed: number;
  recent: number;
  lastActivity: number | null;
}

export function timestampMilliseconds(value?: number | null): number | null {
  if (!value) return null;
  return value < 10_000_000_000 ? value * 1000 : value;
}

export function isTerminal(session: SessionSummary): boolean {
  return ["completed", "failed", "interrupted"].includes(session.status);
}

export function isRecent(session: SessionSummary, now = Date.now()): boolean {
  const terminal = timestampMilliseconds(session.terminalAt);
  return isTerminal(session) && terminal !== null && now - terminal <= RECENT_WINDOW_MS;
}

export function needsAttention(
  session: SessionSummary,
  serviceStatus?: ServiceStatus | null,
): boolean {
  return session.status === "waiting" || session.status === "failed" || (
    session.status === "working" && serviceStatus?.codex.connected === false
  );
}

export function dashboardCounts(
  sessions: SessionSummary[],
  now = Date.now(),
): DashboardCounts {
  return {
    active: sessions.filter((session) => session.status === "working").length,
    waiting: sessions.filter((session) => session.status === "waiting").length,
    failed: sessions.filter((session) => session.status === "failed").length,
    recent: sessions.filter((session) => isRecent(session, now)).length,
  };
}

export function sessionMatchesFilter(
  session: SessionSummary,
  filter: DashboardFilter,
  now = Date.now(),
): boolean {
  if (filter === "active") return session.status === "working";
  if (filter === "waiting") return session.status === "waiting";
  if (filter === "failed") return session.status === "failed";
  if (filter === "recent") return isRecent(session, now);
  return true;
}

export function sortDashboardSessions(
  sessions: SessionSummary[],
  serviceStatus?: ServiceStatus | null,
): SessionSummary[] {
  return [...sessions].sort((left, right) => {
    const priority = (session: SessionSummary) => needsAttention(session, serviceStatus)
      ? 0
      : session.status === "working" ? 1 : 2;
    return priority(left) - priority(right) ||
      (right.lastActivity ?? 0) - (left.lastActivity ?? 0);
  });
}

export function repositoryGroups(
  sessions: SessionSummary[],
  now = Date.now(),
): RepositoryGroup[] {
  const groups = new Map<string, SessionSummary[]>();
  sessions.forEach((session) => {
    const id = session.repository || "(no repository)";
    groups.set(id, [...(groups.get(id) ?? []), session]);
  });
  return [...groups.entries()].map(([id, grouped]) => ({
    id,
    name: shortRepository(id),
    sessions: sortDashboardSessions(grouped),
    active: grouped.filter((session) => session.status === "working").length,
    waiting: grouped.filter((session) => session.status === "waiting").length,
    failed: grouped.filter((session) => session.status === "failed").length,
    recent: grouped.filter((session) => isRecent(session, now)).length,
    lastActivity: grouped.reduce<number | null>(
      (latest, session) => Math.max(latest ?? 0, session.lastActivity ?? 0) || null,
      null,
    ),
  })).sort((left, right) =>
    (right.waiting + right.failed) - (left.waiting + left.failed) ||
    right.active - left.active ||
    (right.lastActivity ?? 0) - (left.lastActivity ?? 0) ||
    left.id.localeCompare(right.id)
  );
}

export function formatElapsed(startedAt?: number | null, now = Date.now()): string {
  const started = timestampMilliseconds(startedAt);
  if (started === null) return "—";
  return formatDuration(Math.max(0, now - started));
}

export function formatDuration(durationMs?: number | null): string {
  if (durationMs == null) return "—";
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours) return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  if (minutes) return `${minutes}m ${String(seconds).padStart(2, "0")}s`;
  return `${seconds}s`;
}

export function shortRepository(path: string): string {
  if (!path || path === "(no repository)") return "No repository";
  return path.replace(/\/$/, "").split("/").at(-1) || path;
}
