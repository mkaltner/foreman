import { memo, useCallback, useEffect, useMemo, useState } from "react";
import type { ConnectionState } from "./client";
import { useSharedClock } from "./clock";
import {
  dashboardCounts,
  formatDuration,
  formatElapsed,
  isRecent,
  needsAttention,
  repositoryGroups,
  sessionMatchesFilter,
  shortRepository,
  sortDashboardSessions,
  type DashboardFilter,
} from "./dashboard";
import type { ServiceStatus, SessionSummary } from "./protocol";
import {
  loadDashboardPreferences,
  saveDashboardPreferences,
  type DashboardPreferences,
} from "./storage";
import { formatActivity, reasoningLabel } from "./ui";

interface DashboardProps {
  sessions: SessionSummary[];
  serviceStatus: ServiceStatus | null;
  connection: ConnectionState;
  disabled: boolean;
  onOpen: (id: string) => void;
  onInterrupt: (session: SessionSummary) => void;
  onRefresh: () => void;
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
  connection,
  disabled,
  onOpen,
  onInterrupt,
  onRefresh,
}: DashboardProps) {
  const [preferences, setPreferences] = useState<DashboardPreferences>(loadDashboardPreferences);
  const now = Date.now();
  const repositories = useMemo(() => repositoryGroups(sessions, now), [sessions, now]);
  const updatePreferences = (next: DashboardPreferences) => {
    setPreferences(next);
    saveDashboardPreferences(next);
  };
  const dismissed = useMemo(
    () => new Set(preferences.dismissedFailures),
    [preferences.dismissedFailures],
  );
  const visible = useMemo(
    () => sessions.filter((session) => !(
      session.status === "failed" && dismissed.has(failureKey(session))
    )),
    [dismissed, sessions],
  );
  const filtered = useMemo(
    () => visible.filter((session) =>
      (!preferences.repository || session.repository === preferences.repository) &&
      sessionMatchesFilter(session, preferences.filter, now)
    ),
    [now, preferences.filter, preferences.repository, visible],
  );
  const sorted = useMemo(
    () => sortDashboardSessions(filtered, serviceStatus),
    [filtered, serviceStatus],
  );
  const attention = sorted.filter((session) => needsAttention(session, serviceStatus));
  const active = sorted.filter((session) =>
    session.status === "working" && !needsAttention(session, serviceStatus)
  );
  const recent = sorted.filter((session) => isRecent(session, now)).slice(0, 12);
  const counts = dashboardCounts(visible, now);
  const filteredRepositories = repositoryGroups(filtered, now);

  useEffect(() => {
    if (
      preferences.repository &&
      !sessions.some((session) => session.repository === preferences.repository)
    ) {
      const next = { ...preferences, repository: "" };
      setPreferences(next);
      saveDashboardPreferences(next);
    }
  }, [preferences, sessions]);

  const dismissFailure = useCallback((session: SessionSummary) => {
    const key = failureKey(session);
    updatePreferences({
      ...preferences,
      dismissedFailures: [
        ...preferences.dismissedFailures.filter((entry) => entry !== key),
        key,
      ],
    });
  }, [preferences]);

  return (
    <main className="dashboard-page">
      <header className="dashboard-heading">
        <div>
          <span className="eyebrow">Live operations</span>
          <h1>Dashboard</h1>
          <p>Supervise every Codex session from one calm control surface.</p>
        </div>
        <button className="icon-button" onClick={onRefresh} disabled={disabled} aria-label="Refresh dashboard">↻</button>
      </header>

      <section className="dashboard-overview" aria-label="Foreman overview">
        <HealthPanel status={serviceStatus} connection={connection} />
        <div className="summary-strip">
          <SummaryMetric label="Active" value={counts.active} icon="▶" onClick={() => updatePreferences({ ...preferences, filter: "active" })} />
          <SummaryMetric label="Waiting" value={counts.waiting} icon="◷" onClick={() => updatePreferences({ ...preferences, filter: "waiting" })} />
          <SummaryMetric label="Failed" value={counts.failed} icon="!" onClick={() => updatePreferences({ ...preferences, filter: "failed" })} />
          <SummaryMetric label="Completed recently" value={counts.recent} icon="✓" onClick={() => updatePreferences({ ...preferences, filter: "recent" })} />
        </div>
      </section>

      <section className="dashboard-controls" aria-label="Dashboard filters">
        <div className="filter-tabs" role="group" aria-label="Session status">
          {FILTERS.map((filter) => (
            <button
              key={filter.id}
              className={preferences.filter === filter.id ? "selected" : ""}
              aria-pressed={preferences.filter === filter.id}
              onClick={() => updatePreferences({ ...preferences, filter: filter.id })}
            >{filter.label}</button>
          ))}
        </div>
        <label className="repository-filter">
          <span>Repository</span>
          <select
            value={preferences.repository}
            onChange={(event) => updatePreferences({ ...preferences, repository: event.target.value })}
          >
            <option value="">All repositories</option>
            {repositories.map((repository) => (
              <option key={repository.id} value={repository.id}>{repository.name}</option>
            ))}
          </select>
        </label>
      </section>

      {attention.length > 0 && (
        <DashboardSection title="Needs attention" count={attention.length} className="attention-section">
          <div className="monitor-grid">
            {attention.map((session) => (
              <MonitoringCard
                key={session.id}
                session={session}
                disconnected={serviceStatus?.codex.connected === false}
                disabled={disabled}
                onOpen={onOpen}
                onInterrupt={onInterrupt}
                onDismiss={session.status === "failed" ? dismissFailure : undefined}
              />
            ))}
          </div>
        </DashboardSection>
      )}

      {(active.length > 0 || preferences.filter === "active") && (
        <DashboardSection title="Active work" count={active.length}>
          {active.length ? (
            <div className="monitor-grid">
              {active.map((session) => (
                <MonitoringCard
                  key={session.id}
                  session={session}
                  disconnected={false}
                  disabled={disabled}
                  onOpen={onOpen}
                  onInterrupt={onInterrupt}
                />
              ))}
            </div>
          ) : <EmptySection text="No active sessions match this filter." />}
        </DashboardSection>
      )}

      {(filteredRepositories.length > 0 || preferences.filter === "all") && (
        <DashboardSection title="Repositories" count={filteredRepositories.length}>
          {filteredRepositories.length ? (
            <div className="repository-grid">
              {filteredRepositories.map((repository) => (
                <details className="repository-card" key={repository.id}>
                  <summary>
                    <span className="repository-identity">
                      <strong>{repository.name}</strong>
                      <small title={repository.id}>{repository.id}</small>
                    </span>
                    <span className="repository-counts">
                      {!!repository.active && <span>▶ {repository.active} active</span>}
                      {!!repository.waiting && <span>◷ {repository.waiting} waiting</span>}
                      {!!repository.failed && <span>! {repository.failed} failed</span>}
                      {!!repository.recent && <span>✓ {repository.recent} recent</span>}
                    </span>
                    <span className="repository-latest">{formatActivity(repository.lastActivity)}</span>
                  </summary>
                  <div className="repository-sessions">
                    {repository.sessions.map((session) => (
                      <button key={session.id} onClick={() => onOpen(session.id)}>
                        <span>{session.title}</span><StatusLabel status={session.status} />
                      </button>
                    ))}
                  </div>
                </details>
              ))}
            </div>
          ) : <EmptySection text="No repositories match this filter." />}
        </DashboardSection>
      )}

      {(recent.length > 0 || preferences.filter === "recent") && (
        <DashboardSection title="Recently completed" count={recent.length}>
          {recent.length ? (
            <div className="recent-list">
              {recent.map((session) => (
                <button className="recent-row" key={session.id} onClick={() => onOpen(session.id)}>
                  <StatusIcon status={session.status} />
                  <span className="recent-main"><strong>{session.title}</strong><small>{shortRepository(session.repository)} · {session.activityText || session.activityLabel || "Turn finished"}</small></span>
                  <StatusLabel status={session.status} />
                  <span className="recent-duration">{formatDuration(session.turnDurationMs)}</span>
                  <time>{formatActivity(session.terminalAt)}</time>
                </button>
              ))}
            </div>
          ) : <EmptySection text="No terminal turns were observed in the last hour." />}
        </DashboardSection>
      )}

      {!attention.length && !active.length && !recent.length && preferences.filter !== "all" && (
        <EmptySection text="No sessions match this filter." />
      )}
    </main>
  );
}

const MonitoringCard = memo(function MonitoringCard({
  session,
  disconnected,
  disabled,
  onOpen,
  onInterrupt,
  onDismiss,
}: {
  session: SessionSummary;
  disconnected: boolean;
  disabled: boolean;
  onOpen: (id: string) => void;
  onInterrupt: (session: SessionSummary) => void;
  onDismiss?: (session: SessionSummary) => void;
}) {
  const attention = session.status === "waiting" || session.status === "failed" || disconnected;
  const activity = disconnected
    ? "Active runtime disconnected"
    : session.status === "waiting"
      ? session.waitType === "input" ? "Waiting for input" : "Waiting for approval"
      : session.activityLabel || (session.status === "working" ? "Thinking" : "Turn failed");
  return (
    <article className={`monitor-card ${attention ? "needs-attention" : ""}`}>
      <button className="monitor-card-main" onClick={() => onOpen(session.id)} aria-label={`Open ${session.title}`}>
        <div className="monitor-title-row">
          <StatusIcon status={disconnected ? "disconnected" : session.status} />
          <span><strong>{session.title}</strong><small title={session.repository}>{shortRepository(session.repository)}</small></span>
          <StatusLabel status={disconnected ? "disconnected" : session.status} />
        </div>
        <div className="monitor-activity">
          <strong>{activity}</strong>
          <p>{session.failureSummary || session.waitDescription || session.activityText || "Monitoring live Codex activity"}</p>
        </div>
        <dl className="monitor-metadata">
          {(session.status === "working" || session.status === "waiting") && (
            <div><dt>Elapsed</dt><dd><ElapsedTime startedAt={session.activeTurnStartedAt} /></dd></div>
          )}
          <div><dt>Model</dt><dd>{session.model || "Default"}</dd></div>
          <div><dt>Reasoning</dt><dd>{session.reasoningEffort ? reasoningLabel(session.reasoningEffort) : "Default"}</dd></div>
          {session.accessLevel && <div><dt>Access</dt><dd>{session.accessLevel}</dd></div>}
          <div><dt>Updated</dt><dd>{formatActivity(session.lastActivity)}</dd></div>
        </dl>
      </button>
      {session.status === "waiting" && (
        <p className="unsupported-wait">Foreman can show this wait, but approval and structured input must currently be handled in another compatible Codex client.</p>
      )}
      <div className="monitor-actions">
        <button onClick={() => onOpen(session.id)}>{session.status === "waiting" ? "Open wait" : "Open"}</button>
        {session.status === "working" && session.activeTurnId && (
          <button className="interrupt" disabled={disabled} onClick={() => onInterrupt(session)}>Interrupt</button>
        )}
        {onDismiss && <button className="dismiss-action" onClick={() => onDismiss(session)}>Dismiss</button>}
      </div>
    </article>
  );
});

export function ElapsedTime({ startedAt }: { startedAt?: number | null }) {
  const now = useSharedClock();
  return <time>{formatElapsed(startedAt, now)}</time>;
}

function HealthPanel({ status, connection }: { status: ServiceStatus | null; connection: ConnectionState }) {
  const connected = connection === "connected";
  const mode = !status || !status.codex.connected
    ? "Codex unavailable"
    : status.codex.mode === "shared"
      ? "Shared Desktop runtime"
      : "Foreman-owned fallback runtime";
  return (
    <article className="health-panel">
      <div className="health-title">
        <div><span className="eyebrow">Host status</span><h2>{connected ? "Foreman online" : connection === "reconnecting" ? "Reconnecting to Foreman" : "Foreman disconnected"}</h2></div>
        <span className={`health-state ${connected ? "healthy" : "offline"}`}><i />{connected ? "Connected" : connection}</span>
      </div>
      <div className="health-runtime"><StatusIcon status={status?.codex.connected ? "working" : "disconnected"} /><span><strong>{mode}</strong><small>{status?.codex.mode === "fallback" ? "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE" : status?.codex.connected ? "Live Codex communication available" : "Runtime needs attention"}</small></span></div>
      <dl className="health-details">
        <div><dt>Foreman</dt><dd>{status?.foremanVersion ?? "—"}</dd></div>
        <div><dt>Codex</dt><dd>{status?.codex.version ?? "—"}</dd></div>
        <div><dt>Uptime</dt><dd>{status ? formatDuration(status.uptimeSeconds * 1000) : "—"}</dd></div>
        <div><dt>Listeners</dt><dd>{status ? `web :${status.listeners.webPort ?? "—"} · TCP :${status.listeners.tcpPort}` : "—"}</dd></div>
        {status?.activeBrowserConnections != null && <div><dt>Browsers</dt><dd>{status.activeBrowserConnections}</dd></div>}
        <div><dt>Codex contact</dt><dd>{formatServiceTime(status?.codex.lastCommunication)}</dd></div>
        <div className="health-root"><dt>Repository root</dt><dd title={status?.repositoryRoot}>{status?.repositoryRoot ?? "—"}</dd></div>
      </dl>
    </article>
  );
}

function SummaryMetric({ label, value, icon, onClick }: { label: string; value: number; icon: string; onClick: () => void }) {
  return <button className="summary-metric" onClick={onClick}><span aria-hidden="true">{icon}</span><strong>{value}</strong><small>{label}</small></button>;
}

function DashboardSection({ title, count, className = "", children }: { title: string; count: number; className?: string; children: React.ReactNode }) {
  return <section className={`dashboard-section ${className}`}><header><h2>{title}</h2><span>{count}</span></header>{children}</section>;
}

function StatusIcon({ status }: { status: string }) {
  const symbols: Record<string, string> = { working: "▶", waiting: "◷", completed: "✓", failed: "!", interrupted: "■", disconnected: "↯", idle: "○" };
  return <span className={`status-icon ${status}`} aria-hidden="true">{symbols[status] ?? "○"}</span>;
}

function StatusLabel({ status }: { status: string }) {
  const labels: Record<string, string> = { working: "Active", waiting: "Waiting", completed: "Completed", failed: "Failed", interrupted: "Interrupted", disconnected: "Disconnected", idle: "Idle" };
  return <span className={`status-pill ${status}`}>{labels[status] ?? status}</span>;
}

function EmptySection({ text }: { text: string }) {
  return <div className="dashboard-empty"><span aria-hidden="true">◇</span><p>{text}</p></div>;
}

function formatServiceTime(value?: string | null): string {
  if (!value) return "—";
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? "—" : formatActivity(timestamp);
}

function failureKey(session: SessionSummary): string {
  return `${session.id}:${session.terminalAt ?? session.lastActivity ?? 0}`;
}
