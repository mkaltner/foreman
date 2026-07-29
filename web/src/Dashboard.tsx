import { memo, useCallback, useEffect, useMemo, useState } from "react";
import type { ConnectionState } from "./client";
import { useSharedClock } from "./clock";
import {
  attentionState,
  dashboardCounts,
  formatAge,
  formatDuration,
  formatElapsed,
  isRecent,
  oldestActiveSession,
  repositoryGroups,
  sessionMatchesFilter,
  shortRepository,
  sortDashboardSessions,
  type AttentionState,
  type DashboardFilter,
  type RecentActivityEntry,
  type RepositoryGroup,
} from "./dashboard";
import type { PairedClient, RepositoryInfo, ServiceStatus, SessionSummary } from "./protocol";
import {
  loadDashboardPreferences,
  saveDashboardPreferences,
  type DashboardPreferences,
} from "./storage";
import { reasoningLabel } from "./ui";

interface DashboardProps {
  sessions: SessionSummary[];
  serviceStatus: ServiceStatus | null;
  repositories?: RepositoryInfo[];
  recentActivity?: RecentActivityEntry[];
  pairedClients?: PairedClient[];
  connection: ConnectionState;
  disabled: boolean;
  onOpen: (id: string) => void;
  onInterrupt: (session: SessionSummary) => void;
  onRefresh: () => void;
  onRevokeClient?: (client: PairedClient) => Promise<void>;
}

const FILTERS: Array<{ id: DashboardFilter; label: string }> = [
  { id: "all", label: "All" },
  { id: "active", label: "Active" },
  { id: "waiting", label: "Waiting" },
  { id: "failed", label: "Failed" },
  { id: "recent", label: "Recently completed" },
];

export function Dashboard({
  sessions,
  serviceStatus,
  repositories: discoveredRepositories = [],
  recentActivity = [],
  pairedClients = [],
  connection,
  disabled,
  onOpen,
  onInterrupt,
  onRefresh,
  onRevokeClient,
}: DashboardProps) {
  const [preferences, setPreferences] = useState<DashboardPreferences>(loadDashboardPreferences);
  const now = useSharedClock();
  const repositories = useMemo(
    () => repositoryGroups(sessions, now, discoveredRepositories, serviceStatus?.repositoryRoot),
    [discoveredRepositories, now, serviceStatus?.repositoryRoot, sessions],
  );
  const updatePreferences = useCallback((next: DashboardPreferences) => {
    setPreferences(next);
    saveDashboardPreferences(next);
  }, []);
  const dismissed = useMemo(() => new Set(preferences.dismissedFailures), [preferences.dismissedFailures]);
  const visibleSessions = useMemo(() => sessions.filter((session) => {
    const attention = attentionState(session, serviceStatus, now);
    return !attention || !dismissed.has(attentionKey(session, attention, serviceStatus));
  }), [dismissed, now, serviceStatus, sessions]);
  const filtered = useMemo(
    () => visibleSessions.filter((session) =>
      (!preferences.repository || repositoryIdentity(session, repositories) === preferences.repository) &&
      sessionMatchesFilter(session, preferences.filter, now)
    ),
    [now, preferences.filter, preferences.repository, repositories, visibleSessions],
  );
  const sorted = useMemo(
    () => sortDashboardSessions(filtered, serviceStatus, now),
    [filtered, now, serviceStatus],
  );
  const attentionPairs = sorted
    .map((session) => ({ session, attention: attentionState(session, serviceStatus, now) }))
    .filter((entry): entry is { session: SessionSummary; attention: AttentionState } =>
      entry.attention !== null && !dismissed.has(attentionKey(entry.session, entry.attention, serviceStatus))
    );
  const attentionIds = new Set(attentionPairs.map(({ session }) => session.id));
  const active = sorted.filter((session) => session.status === "working" && !attentionIds.has(session.id));
  const recent = sorted.filter((session) => isRecent(session, now)).slice(0, 12);
  const counts = dashboardCounts(visibleSessions, now);
  const filteredRepositories = repositoryGroups(filtered, now, discoveredRepositories, serviceStatus?.repositoryRoot);
  const gitRepositories = filteredRepositories.filter((group) => group.kind === "repository");
  const workspaces = filteredRepositories.filter((group) => group.kind === "workspace");
  const oldest = oldestActiveSession(filtered);

  useEffect(() => {
    if (preferences.repository && !repositories.some((repository) => repository.id === preferences.repository)) {
      updatePreferences({ ...preferences, repository: "" });
    }
  }, [preferences, repositories, updatePreferences]);

  const dismissAttention = useCallback((session: SessionSummary, attention: AttentionState) => {
    const key = attentionKey(session, attention, serviceStatus);
    updatePreferences({
      ...preferences,
      dismissedFailures: [...preferences.dismissedFailures.filter((entry) => entry !== key), key],
    });
  }, [preferences, serviceStatus, updatePreferences]);

  return (
    <main className="dashboard-page">
      <header className="dashboard-heading">
        <div><span className="eyebrow">Live operations</span><h1>Dashboard</h1><p>Supervise every Codex session from one calm control surface.</p></div>
        <button className="icon-button" onClick={onRefresh} disabled={disabled} aria-label="Refresh dashboard">↻</button>
      </header>

      <section className="dashboard-overview" aria-label="Foreman overview">
        <HealthPanel status={serviceStatus} connection={connection} now={now} clients={pairedClients} disabled={disabled} onRevokeClient={onRevokeClient} />
        <aside className="summary-strip" aria-label="Operational summary">
          <header><div><span className="eyebrow">Operational summary</span><strong>Work at a glance</strong></div><small>Live</small></header>
          <div className="summary-grid">
            <SummaryMetric label="Active" value={counts.active} icon="▶" onClick={() => updatePreferences({ ...preferences, filter: "active" })} />
            <SummaryMetric label="Waiting" value={counts.waiting} icon="◷" onClick={() => updatePreferences({ ...preferences, filter: "waiting" })} />
            <SummaryMetric label="Failed" value={counts.failed} icon="!" onClick={() => updatePreferences({ ...preferences, filter: "failed" })} />
            <SummaryMetric label="Completed recently" value={counts.recent} icon="✓" onClick={() => updatePreferences({ ...preferences, filter: "recent" })} />
          </div>
        </aside>
      </section>

      <section className="dashboard-controls" aria-label="Dashboard filters">
        <div className="filter-tabs" role="group" aria-label="Session status">
          {FILTERS.map((filter) => <button key={filter.id} className={preferences.filter === filter.id ? "selected" : ""} aria-pressed={preferences.filter === filter.id} onClick={() => updatePreferences({ ...preferences, filter: filter.id })}>{filter.label}</button>)}
        </div>
        <label className="repository-filter"><span>Workspace</span><select value={preferences.repository} onChange={(event) => updatePreferences({ ...preferences, repository: event.target.value })}><option value="">All repositories and workspaces</option>{repositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.kind === "workspace" ? "Workspace: " : ""}{repository.name}</option>)}</select></label>
      </section>

      {oldest && (
        <section className="oldest-turn" aria-label="Oldest active turn">
          <div><span className="eyebrow">Oldest active turn</span><strong>{oldest.title}</strong><small>{shortRepository(oldest.repository)}</small></div>
          <div className="oldest-activity"><strong>{oldest.activityLabel || "Thinking"}</strong><small>{oldest.activityText || routeDetails(oldest)}</small></div>
          <time>{formatElapsed(oldest.activeTurnStartedAt, now)}</time>
          <button onClick={() => onOpen(oldest.id)} aria-label={`Open oldest active turn ${oldest.title}`}>Open</button>
        </section>
      )}

      {attentionPairs.length > 0 && (
        <DashboardSection title="Needs attention" count={attentionPairs.length} className="attention-section">
          <div className="monitor-grid">{attentionPairs.map(({ session, attention }) => <MonitoringCard key={session.id} session={session} attention={attention} now={now} disabled={disabled} onOpen={onOpen} onInterrupt={onInterrupt} onDismiss={() => dismissAttention(session, attention)} />)}</div>
        </DashboardSection>
      )}

      {(active.length > 0 || preferences.filter === "active") && (
        <DashboardSection title="Active work" count={active.length}>
          {active.length ? <div className="monitor-grid">{active.map((session) => <MonitoringCard key={session.id} session={session} attention={null} now={now} disabled={disabled} onOpen={onOpen} onInterrupt={onInterrupt} />)}</div> : <EmptySection text="No active sessions match this filter." />}
        </DashboardSection>
      )}

      <RepositorySection title="Repositories" groups={gitRepositories} now={now} onOpen={onOpen} />
      {workspaces.length > 0 && <RepositorySection title="Other workspaces" groups={workspaces} now={now} onOpen={onOpen} />}

      {(recent.length > 0 || preferences.filter === "recent") && (
        <DashboardSection title="Recently completed" count={recent.length}>
          {recent.length ? <div className="recent-list">{recent.map((session) => <button className="recent-row" key={session.id} onClick={() => onOpen(session.id)}><StatusIcon status={session.status} /><span className="recent-main"><strong>{session.title}</strong><small>{shortRepository(session.repository)} · {session.activityText || session.activityLabel || "Turn finished"}</small></span><StatusLabel status={session.status} /><span className="recent-duration">{formatDuration(session.turnDurationMs)}</span><time>{formatAge(session.terminalAt, now)}</time></button>)}</div> : <EmptySection text="No terminal turns were observed in the last hour." />}
        </DashboardSection>
      )}

      {recentActivity.length > 0 && (
        <DashboardSection title="Recent activity" count={recentActivity.length}>
          <div className="activity-feed">{recentActivity.map((entry) => <button key={entry.id} onClick={() => onOpen(entry.sessionId)} aria-label={`Open ${entry.title}`}><time>{new Date(entry.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time><span><strong>{entry.title}</strong><small>{shortRepository(entry.repository)}</small></span><p>{entry.description}</p><i aria-hidden="true">›</i></button>)}</div>
        </DashboardSection>
      )}

      {!attentionPairs.length && !active.length && !recent.length && preferences.filter !== "all" && <EmptySection text="No sessions match this filter." />}
    </main>
  );
}

const MonitoringCard = memo(function MonitoringCard({ session, attention, now, disabled, onOpen, onInterrupt, onDismiss }: { session: SessionSummary; attention: AttentionState | null; now: number; disabled: boolean; onOpen: (id: string) => void; onInterrupt: (session: SessionSummary) => void; onDismiss?: () => void }) {
  const status = attention?.type === "disconnected" ? "disconnected" : attention?.type === "stale" ? "stale" : session.status;
  const activity = attention?.label || session.activityLabel || (session.status === "working" ? "Thinking" : "Turn failed");
  const stateSince = attention?.since;
  return <article className={`monitor-card ${attention ? "needs-attention" : ""}`}>
    <button className="monitor-card-main" onClick={() => onOpen(session.id)} aria-label={`Open ${session.title}`}>
      <div className="monitor-title-row"><StatusIcon status={status} /><span><strong>{session.title}</strong><small title={session.repository}>{shortRepository(session.repository)}</small></span><StatusLabel status={status} /></div>
      {attention
        ? <><div className="monitor-activity"><strong>{activity}</strong><p>{session.failureSummary || session.waitDescription || session.activityText || "Monitoring live Codex activity"}</p></div><div className="route-details">{routeDetails(session)}</div></>
        : <div className="monitor-compact-line"><strong>{activity}</strong><span>{routeDetails(session)}</span></div>}
      <dl className="monitor-metadata">
        <div><dt>{attention ? "In state" : "Elapsed"}</dt><dd>{attention && stateSince ? formatElapsed(stateSince, now) : formatElapsed(session.activeTurnStartedAt, now)}</dd></div>
        <div><dt>Session event</dt><dd>{formatAge(session.lastActivity, now)}</dd></div>
      </dl>
    </button>
    {(attention?.type === "approval" || attention?.type === "input") && <p className="unsupported-wait">Foreman can show this wait, but approval and structured input must currently be handled in another compatible Codex client.</p>}
    <div className="monitor-actions"><button onClick={() => onOpen(session.id)} aria-label={`Open session ${session.title}`}>Open</button>{session.status === "working" && session.activeTurnId && !attention && <button className="interrupt" disabled={disabled} onClick={() => onInterrupt(session)} aria-label={`Interrupt ${session.title}`}>Interrupt</button>}{onDismiss && <button className="dismiss-action" onClick={onDismiss} aria-label="Dismiss">Dismiss</button>}</div>
  </article>;
});

export function ElapsedTime({ startedAt }: { startedAt?: number | null }) {
  const now = useSharedClock();
  return <time>{formatElapsed(startedAt, now)}</time>;
}

function HealthPanel({ status, connection, now, clients, disabled, onRevokeClient }: { status: ServiceStatus | null; connection: ConnectionState; now: number; clients: PairedClient[]; disabled: boolean; onRevokeClient?: (client: PairedClient) => Promise<void> }) {
  const [revoking, setRevoking] = useState<string | null>(null);
  const connected = connection === "connected";
  const runtimeConnected = status?.codex.connected === true;
  const mode = !status || !runtimeConnected ? "Codex unavailable" : status.codex.mode === "shared" ? "Shared Desktop runtime" : "Foreman-owned fallback runtime";
  const eventTimestamp = status?.codex.lastEvent ? Date.parse(status.codex.lastEvent) : null;
  const eventRecent = eventTimestamp !== null && now - eventTimestamp <= 30_000;
  const eventLabel = !runtimeConnected ? "Runtime disconnected" : eventTimestamp === null ? "Runtime connected · no events observed" : eventRecent ? `Last runtime event: ${formatAge(eventTimestamp, now)}` : `No runtime events for ${formatDuration(now - eventTimestamp)}`;
  const eventAge = !runtimeConnected ? "Disconnected" : eventTimestamp === null ? "No events observed" : eventRecent ? formatAge(eventTimestamp, now) : `No events for ${formatDuration(now - eventTimestamp)}`;
  return <article className="health-panel">
    <div className="health-title"><div><span className="eyebrow">Host status</span><h2>{connected ? "Foreman online" : connection === "reconnecting" ? "Reconnecting to Foreman" : "Foreman disconnected"}</h2></div><span className={`health-state ${connected ? "healthy" : "offline"}`}><i />{connected ? "Connected" : connection}</span></div>
    <div className="health-runtime"><StatusIcon status={runtimeConnected ? "working" : "disconnected"} /><span><strong>{mode}</strong><small>{status?.codex.mode === "fallback" ? "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE" : !runtimeConnected ? "Runtime needs attention" : eventLabel}</small></span></div>
    <dl className="health-details">
      <div><dt>Foreman</dt><dd>{status?.foremanVersion ?? "—"}</dd></div><div><dt>Codex</dt><dd>{status?.codex.version ?? "—"}</dd></div><div><dt>Uptime</dt><dd>{status ? formatDuration(status.uptimeSeconds * 1000 + Math.max(0, now - (status.receivedAt ?? now))) : "—"}</dd></div><div><dt>Clients</dt><dd>{status ? `${status.activeBrowserConnections ?? 0} browser · ${status.activeTcpConnections ?? 0} Android` : "—"}</dd></div>
      <div><dt>Runtime event</dt><dd className={!eventRecent && runtimeConnected ? "quiet" : ""}>{eventAge}</dd></div><div><dt>Successful request</dt><dd>{formatAge(status?.codex.lastSuccessfulRequest, now)}</dd></div><div><dt>Attached</dt><dd>{formatAge(status?.codex.attachedAt, now)}</dd></div><div><dt>Threads</dt><dd>{status ? `${status.codex.loadedThreadCount ?? 0} loaded · ${status.codex.subscribedThreadCount ?? 0} subscribed` : "—"}</dd></div>
      <div className="health-root"><dt>Repository root</dt><dd title={status?.repositoryRoot}>{status?.repositoryRoot ?? "—"}</dd></div><div><dt>Listeners</dt><dd>{status ? `web :${status.listeners.webPort ?? "—"} · TCP :${status.listeners.tcpPort}` : "—"}</dd></div>
    </dl>
    {clients.length > 0 && <details className="client-diagnostics"><summary>Clients and access <span>{clients.filter((client) => client.connected).length} connected</span></summary><div className="client-list">{clients.map((client) => <div className="client-row" key={client.id}><span className={`client-presence ${client.connected ? "online" : "offline"}`} aria-hidden="true">{client.connected ? "●" : "○"}</span><span className="client-identity"><strong>{client.name}</strong><small>{clientTypeLabel(client.type)} · {client.connected ? `${client.connectionCount} connection${client.connectionCount === 1 ? "" : "s"}` : "Not connected"}{client.current ? " · This browser" : ""}</small></span><time>{client.pairedAt ? `Paired ${formatAge(client.pairedAt, now)}` : "Pairing date unavailable"}</time><button className="revoke-client" disabled={disabled || revoking !== null} onClick={() => {
      const warning = client.current
        ? `Revoke ${client.name}? This will sign out this browser immediately.`
        : `Revoke ${client.name}? Every live connection using this token will be disconnected.`;
      if (!window.confirm(warning) || !onRevokeClient) return;
      setRevoking(client.id);
      void onRevokeClient(client).catch(() => undefined).finally(() => setRevoking(null));
    }} aria-label={`Revoke token for ${client.name}`}>{revoking === client.id ? "Revoking…" : "Revoke"}</button></div>)}</div><p className="client-note">Revoking removes only the selected authentication token. It does not delete sessions or repositories.</p></details>}
    {status && <details className="runtime-diagnostics"><summary>Runtime details</summary><dl><div><dt>Foreman ownership</dt><dd>{status.codex.ownedByForeman ? "Yes" : "No"}</dd></div><div><dt>App-server PID</dt><dd>{status.codex.appServerPid ?? "Shared runtime"}</dd></div>{status.codex.socketPath && <div><dt>Socket</dt><dd title={status.codex.socketPath}>{status.codex.socketPath}</dd></div>}</dl></details>}
  </article>;
}

function clientTypeLabel(type: PairedClient["type"]): string {
  return type === "browser" ? "Browser" : type === "android" ? "Android" : type === "mixed" ? "Browser and Android" : "Client";
}

function RepositorySection({ title, groups, now, onOpen }: { title: string; groups: RepositoryGroup[]; now: number; onOpen: (id: string) => void }) {
  if (!groups.length) return null;
  return <DashboardSection title={title} count={groups.length}><div className="repository-grid">{groups.map((repository) => <details className="repository-card" key={repository.id}><summary><span className="repository-identity"><strong>{repository.name}</strong><small title={repository.id}>{repository.id}</small></span><span className="repository-counts">{!!repository.active && <span>▶ {repository.active} active</span>}{!!repository.waiting && <span>◷ {repository.waiting} waiting</span>}{!!repository.failed && <span>! {repository.failed} failed</span>}{!!repository.recent && <span>✓ {repository.recent} completed recently</span>}</span><RepositoryActivity repository={repository} now={now} /></summary><div className="repository-sessions">{repository.sessions.map((session) => <button key={session.id} onClick={() => onOpen(session.id)}><span>{session.title}</span><StatusLabel status={session.status} /></button>)}</div></details>)}</div></DashboardSection>;
}

function RepositoryActivity({ repository, now }: { repository: RepositoryGroup; now: number }) {
  if (repository.currentActivity || repository.longestActiveDurationMs != null) {
    return <span className="repository-latest">
      <span>Current: {repository.currentActivity || "Active"}{repository.longestActiveDurationMs != null ? ` · oldest turn ${formatDuration(repository.longestActiveDurationMs)}` : ""}</span>
      {repository.latestCompletionAt != null && <span>Last completion: {formatAge(repository.latestCompletionAt, now)}</span>}
    </span>;
  }
  return <span className="repository-latest">{repository.latestCompletionAt != null ? `Last completion: ${formatAge(repository.latestCompletionAt, now)}` : "No recent activity"}</span>;
}

function routeDetails(session: SessionSummary): string {
  return `${session.model || "Default model"} · ${session.reasoningEffort ? reasoningLabel(session.reasoningEffort) : "Default effort"} · ${session.accessLevel || "Default access"}`;
}

function repositoryIdentity(session: SessionSummary, groups: RepositoryGroup[]): string {
  return groups.find((group) => group.sessions.some((entry) => entry.id === session.id))?.id ?? session.repository;
}

function attentionKey(session: SessionSummary, attention: AttentionState, status: ServiceStatus | null): string {
  const marker = attention.since ?? status?.codex.lastCommunication ?? session.lastActivity ?? 0;
  return `${session.id}:${session.status}:${attention.type}:${session.waitType ?? ""}:${marker}`;
}

function SummaryMetric({ label, value, icon, onClick }: { label: string; value: number; icon: string; onClick: () => void }) { return <button className="summary-metric" onClick={onClick}><span aria-hidden="true">{icon}</span><strong>{value}</strong><small>{label}</small></button>; }
function DashboardSection({ title, count, className = "", children }: { title: string; count: number; className?: string; children: React.ReactNode }) { return <section className={`dashboard-section ${className}`}><header><h2>{title}</h2><span>{count}</span></header>{children}</section>; }
function StatusIcon({ status }: { status: string }) { const symbols: Record<string, string> = { working: "▶", waiting: "◷", completed: "✓", failed: "!", interrupted: "■", disconnected: "↯", stale: "◴", idle: "○" }; return <span className={`status-icon ${status}`} aria-hidden="true">{symbols[status] ?? "○"}</span>; }
function StatusLabel({ status }: { status: string }) { const labels: Record<string, string> = { working: "Active", waiting: "Waiting", completed: "Completed", failed: "Failed", interrupted: "Interrupted", disconnected: "Disconnected", stale: "Stale", idle: "Idle" }; return <span className={`status-pill ${status}`}>{labels[status] ?? status}</span>; }
function EmptySection({ text }: { text: string }) { return <div className="dashboard-empty"><span aria-hidden="true">◇</span><p>{text}</p></div>; }
