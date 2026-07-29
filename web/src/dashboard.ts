import type {
  RepositoryInfo,
  ServiceStatus,
  SessionEvent,
  SessionSummary,
} from "./protocol";

export const RECENT_WINDOW_MS = 60 * 60 * 1000;
export const STALE_ACTIVE_TURN_MS = 10 * 60 * 1000;
export const RECENT_CODEX_EVENT_MS = 30 * 1000;
export const MAX_RECENT_ACTIVITY = 20;
export type DashboardFilter = "all" | "active" | "waiting" | "failed" | "recent";
export type AttentionType = "approval" | "input" | "failed" | "disconnected" | "stale";

export interface DashboardCounts {
  active: number;
  waiting: number;
  failed: number;
  recent: number;
}

export interface AttentionState {
  type: AttentionType;
  label: string;
  since: number | null;
}

export interface RepositoryGroup {
  id: string;
  name: string;
  kind: "repository" | "workspace";
  sessions: SessionSummary[];
  active: number;
  waiting: number;
  failed: number;
  recent: number;
  lastActivity: number | null;
  longestActiveDurationMs: number | null;
  latestCompletionAt: number | null;
}

export interface RecentActivityEntry {
  id: string;
  sessionId: string;
  title: string;
  repository: string;
  category: "session" | "turn" | "activity" | "waiting" | "terminal";
  description: string;
  timestamp: number;
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

export function isStaleActive(
  session: SessionSummary,
  serviceStatus?: ServiceStatus | null,
  now = Date.now(),
  thresholdMs = STALE_ACTIVE_TURN_MS,
): boolean {
  const lastEvent = timestampMilliseconds(session.lastActivity);
  return session.status === "working" &&
    serviceStatus?.codex.connected === true &&
    lastEvent !== null &&
    now - lastEvent >= thresholdMs;
}

export function attentionState(
  session: SessionSummary,
  serviceStatus?: ServiceStatus | null,
  now = Date.now(),
  staleThresholdMs = STALE_ACTIVE_TURN_MS,
): AttentionState | null {
  if (session.status === "waiting") {
    return {
      type: session.waitType === "input" ? "input" : "approval",
      label: session.waitType === "input" ? "Waiting for input" : "Waiting for approval",
      since: timestampMilliseconds(session.statusChangedAt ?? session.lastActivity),
    };
  }
  if (session.status === "failed") {
    return { type: "failed", label: "Failed", since: timestampMilliseconds(session.terminalAt) };
  }
  if (session.status === "working" && serviceStatus?.codex.connected === false) {
    return {
      type: "disconnected",
      label: "Runtime disconnected",
      since: parseServiceTimestamp(serviceStatus.codex.lastCommunication),
    };
  }
  if (isStaleActive(session, serviceStatus, now, staleThresholdMs)) {
    return { type: "stale", label: "No recent activity", since: timestampMilliseconds(session.lastActivity) };
  }
  return null;
}

export function needsAttention(
  session: SessionSummary,
  serviceStatus?: ServiceStatus | null,
  now = Date.now(),
): boolean {
  return attentionState(session, serviceStatus, now) !== null;
}

export function oldestActiveSession(sessions: SessionSummary[]): SessionSummary | null {
  return sessions
    .filter((session) => ["working", "waiting"].includes(session.status) && timestampMilliseconds(session.activeTurnStartedAt) !== null)
    .sort((left, right) =>
      timestampMilliseconds(left.activeTurnStartedAt)! - timestampMilliseconds(right.activeTurnStartedAt)!
    )[0] ?? null;
}

export function dashboardCounts(sessions: SessionSummary[], now = Date.now()): DashboardCounts {
  return {
    active: sessions.filter((session) => session.status === "working").length,
    waiting: sessions.filter((session) => session.status === "waiting").length,
    failed: sessions.filter((session) => session.status === "failed").length,
    recent: sessions.filter((session) => isRecent(session, now)).length,
  };
}

export function sessionMatchesFilter(session: SessionSummary, filter: DashboardFilter, now = Date.now()): boolean {
  if (filter === "active") return session.status === "working";
  if (filter === "waiting") return session.status === "waiting";
  if (filter === "failed") return session.status === "failed";
  if (filter === "recent") return isRecent(session, now);
  return true;
}

export function sortDashboardSessions(
  sessions: SessionSummary[],
  serviceStatus?: ServiceStatus | null,
  now = Date.now(),
): SessionSummary[] {
  return [...sessions].sort((left, right) => {
    const priority = (session: SessionSummary) => attentionState(session, serviceStatus, now)
      ? 0
      : session.status === "working" ? 1 : 2;
    return priority(left) - priority(right) ||
      (right.lastActivity ?? 0) - (left.lastActivity ?? 0);
  });
}

export function repositoryGroups(
  sessions: SessionSummary[],
  now = Date.now(),
  repositories: RepositoryInfo[] = [],
  repositoryRoot = "",
): RepositoryGroup[] {
  const known = repositories.map((repository) => ({
    info: repository,
    canonical: normalizePath(repository.path.startsWith("/")
      ? repository.path
      : `${repositoryRoot}/${repository.path}`),
  })).sort((left, right) => right.canonical.length - left.canonical.length);
  const groups = new Map<string, { sessions: SessionSummary[]; name: string; kind: "repository" | "workspace" }>();
  sessions.forEach((session) => {
    const cwd = normalizePath(session.repository);
    const repository = known.find(({ canonical }) => cwd === canonical || cwd.startsWith(`${canonical}/`));
    const id = repository?.canonical || cwd || "(no repository)";
    const current = groups.get(id) ?? {
      sessions: [],
      name: repository?.info.name || shortRepository(id),
      kind: repository ? "repository" as const : "workspace" as const,
    };
    current.sessions.push(session);
    groups.set(id, current);
  });
  return [...groups.entries()].map(([id, group]) => {
    const activeStarts = group.sessions
      .filter((session) => ["working", "waiting"].includes(session.status))
      .map((session) => timestampMilliseconds(session.activeTurnStartedAt))
      .filter((value): value is number => value !== null);
    const completions = group.sessions
      .map((session) => timestampMilliseconds(session.terminalAt))
      .filter((value): value is number => value !== null);
    return {
      id,
      name: group.name,
      kind: group.kind,
      sessions: sortDashboardSessions(group.sessions, null, now),
      active: group.sessions.filter((session) => session.status === "working").length,
      waiting: group.sessions.filter((session) => session.status === "waiting").length,
      failed: group.sessions.filter((session) => session.status === "failed").length,
      recent: group.sessions.filter((session) => isRecent(session, now)).length,
      lastActivity: maximum(group.sessions.map((session) => timestampMilliseconds(session.lastActivity))),
      longestActiveDurationMs: activeStarts.length ? now - Math.min(...activeStarts) : null,
      latestCompletionAt: completions.length ? Math.max(...completions) : null,
    };
  }).sort((left, right) =>
    (right.waiting + right.failed) - (left.waiting + left.failed) ||
    right.active - left.active ||
    (right.lastActivity ?? 0) - (left.lastActivity ?? 0) ||
    left.id.localeCompare(right.id)
  );
}

export function recordRecentActivity(
  entries: RecentActivityEntry[],
  session: SessionSummary | undefined,
  event: SessionEvent,
  now = Date.now(),
): RecentActivityEntry[] {
  if (!session || event.kind === "assistant.delta") return entries;
  const projected = describeEvent(event);
  if (!projected) return entries;
  const timestamp = timestampMilliseconds(event.observedAt) ?? now;
  const next: RecentActivityEntry = {
    id: `${session.id}:${timestamp}:${projected.category}`,
    sessionId: session.id,
    title: session.title,
    repository: session.repository,
    category: projected.category,
    description: projected.description,
    timestamp,
  };
  const coalesce = projected.category === "activity" &&
    entries[0]?.sessionId === session.id &&
    entries[0]?.category === "activity" &&
    timestamp - entries[0].timestamp < 5_000;
  if (coalesce && entries[0].description === next.description) return entries;
  return [next, ...(coalesce ? entries.slice(1) : entries)].slice(0, MAX_RECENT_ACTIVITY);
}

function describeEvent(event: SessionEvent): Pick<RecentActivityEntry, "category" | "description"> | null {
  if (event.kind === "lifecycle" && event.action === "created") return { category: "session", description: "Session started" };
  if (event.kind === "status") {
    if (event.status === "working") return { category: "turn", description: "Turn started" };
    if (event.status === "waiting") return { category: "waiting", description: event.waitType === "input" ? "Waiting for input" : "Waiting for approval" };
    if (event.status === "completed") return { category: "terminal", description: "Turn completed" };
    if (event.status === "failed") return { category: "terminal", description: "Turn failed" };
    if (event.status === "interrupted") return { category: "terminal", description: "Turn interrupted" };
  }
  if (event.kind === "activity" && event.label) return { category: "activity", description: event.label };
  if (event.kind === "item" && event.phase === "started") {
    if (event.item?.kind === "command") return { category: "activity", description: "Running command" };
    const description = event.item?.description ?? "Using tool";
    return { category: "activity", description: description.slice(0, 100) };
  }
  return null;
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

export function formatAge(value?: number | string | null, now = Date.now()): string {
  const timestamp = typeof value === "string" ? Date.parse(value) : timestampMilliseconds(value);
  if (timestamp == null || Number.isNaN(timestamp)) return "—";
  return `${formatDuration(Math.max(0, now - timestamp))} ago`;
}

export function shortRepository(path: string): string {
  if (!path || path === "(no repository)") return "No repository";
  return path.replace(/\/$/, "").split("/").at(-1) || path;
}

function parseServiceTimestamp(value?: string | null): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
}

function normalizePath(path: string): string {
  if (!path) return "";
  return path.replace(/\/{2,}/g, "/").replace(/\/$/, "");
}

function maximum(values: Array<number | null>): number | null {
  const present = values.filter((value): value is number => value !== null);
  return present.length ? Math.max(...present) : null;
}
