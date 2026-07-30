import { useMemo } from "react";
import { useSharedClock } from "./clock";
import { formatAge, formatElapsed, shortRepository } from "./dashboard";
import type { StoredHost } from "./storage";
import { aggregateHostSnapshots, type HostOverviewSnapshot, type UnifiedAttentionItem } from "./unified";

interface Props {
  hosts: StoredHost[];
  snapshots: Map<string, HostOverviewSnapshot>;
  onOpenHost: (hostId: string) => void;
  onOpenSession: (item: UnifiedAttentionItem | { hostId: string; sessionId: string }) => void;
  onReconnect: (hostId: string) => void;
  onEdit: (hostId: string) => void;
  onForget: (hostId: string) => void;
}

export function UnifiedDashboard({ hosts, snapshots, onOpenHost, onOpenSession, onReconnect, onEdit, onForget }: Props) {
  const now = useSharedClock();
  const totals = useMemo(() => aggregateHostSnapshots(hosts.map(({ id }) => id), snapshots), [hosts, snapshots]);
  const attention = useMemo(() => hosts.flatMap((host) => (snapshots.get(host.id)?.attention ?? []).map((item) => ({ host, item })))
    .sort((left, right) => (left.item.startedAt ?? now) - (right.item.startedAt ?? now)), [hosts, now, snapshots]);
  return <main className="unified-dashboard">
    <header className="dashboard-heading">
      <div><span className="eyebrow">All saved hosts</span><h1>Unified overview</h1><p>Client-side status across independently paired Foreman hosts.</p></div>
      <span className="connection-limit">Up to 4 live connections · {totals.staleHosts} stale</span>
    </header>
    <section className="unified-totals" aria-label="Aggregate totals">
      <UnifiedMetric label="Hosts online" value={`${totals.connectedHosts}/${totals.hosts}`} />
      <UnifiedMetric label="Active" value={totals.active} />
      <UnifiedMetric label="Waiting / approval" value={totals.waiting} />
      <UnifiedMetric label="Failed" value={totals.failed} />
      <UnifiedMetric label="Longest-running turn" value={totals.oldestTurn ? formatElapsed(totals.oldestTurn.startedAt, now) : "—"} onClick={totals.oldestTurn ? () => onOpenSession(totals.oldestTurn!) : undefined} />
      <UnifiedMetric label="Latest completion" value={totals.latestCompletion ? formatAge(totals.latestCompletion.completedAt, now) : "—"} onClick={totals.latestCompletion ? () => onOpenSession(totals.latestCompletion!) : undefined} />
    </section>

    <section className="unified-section" aria-labelledby="host-overview-title">
      <header><div><span className="eyebrow">Host health</span><h2 id="host-overview-title">Saved hosts</h2></div><span>{hosts.length}</span></header>
      <div className="host-overview-grid">
        {hosts.map((host) => <HostOverviewCard key={host.id} host={host} snapshot={snapshots.get(host.id)} now={now} onOpen={() => onOpenHost(host.id)} onReconnect={() => onReconnect(host.id)} onEdit={() => onEdit(host.id)} onForget={() => onForget(host.id)} />)}
      </div>
    </section>

    <section className="unified-section attention-queue" aria-labelledby="combined-attention-title">
      <header><div><span className="eyebrow">Across every host</span><h2 id="combined-attention-title">Needs attention</h2></div><span>{attention.length}</span></header>
      {attention.length ? <div className="unified-attention-list">{attention.map(({ host, item }) => {
        const stale = snapshots.get(host.id)?.connection !== "connected";
        return <article key={`${host.id}:${item.sessionId}:${item.approvalId ?? item.type}`} className={stale ? "stale" : ""}>
          <span className={`attention-type ${item.type}`}>{item.type === "approval" ? "Approval" : item.type === "input" ? "Input" : "Failed"}</span>
          <div><strong>{item.sessionTitle}</strong><small>{host.displayName} · {shortRepository(item.repository)}</small></div>
          <span className="attention-host">{host.host}</span>
          <time>{formatAge(item.startedAt, now)}{stale ? " · stale" : ""}</time>
          <button onClick={() => onOpenSession(item)}>Open</button>
        </article>;
      })}</div> : <p className="unified-empty">Nothing across the connected hosts needs attention.</p>}
    </section>
  </main>;
}

function UnifiedMetric({ label, value, onClick }: { label: string; value: string | number; onClick?: () => void }) {
  const content = <><strong>{value}</strong><span>{label}</span></>;
  return onClick ? <button className="unified-metric" onClick={onClick}>{content}</button> : <div className="unified-metric">{content}</div>;
}

function HostOverviewCard({ host, snapshot, now, onOpen, onReconnect, onEdit, onForget }: { host: StoredHost; snapshot?: HostOverviewSnapshot; now: number; onOpen: () => void; onReconnect: () => void; onEdit: () => void; onForget: () => void }) {
  const live = snapshot?.connection === "connected";
  const connection = snapshot?.connection ?? host.lastKnownStatus;
  const runtime = snapshot?.runtimeMode === "shared" ? "Shared Desktop" : snapshot?.runtimeMode === "fallback" ? "Fallback" : host.runtimeMode === "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE" ? "Shared Desktop" : host.runtimeMode ? "Fallback" : "Unknown";
  return <article className={`host-overview-card ${live ? "live" : "stale"}`}>
    <header><div><h3>{host.displayName}</h3><small>{host.host}:{host.webPort}</small></div><span className={`host-connection ${connection}`}>{connection}</span></header>
    {!live && <p className="stale-warning"><strong>Stale snapshot</strong> · Last connected {formatAge(host.lastConnectedAt ?? snapshot?.observedAt, now)}</p>}
    <dl>
      <div><dt>Versions</dt><dd>Foreman {snapshot?.foremanVersion ?? "—"} · Codex {snapshot?.codexVersion ?? "—"}</dd></div>
      <div><dt>Runtime</dt><dd>{runtime}{snapshot && !snapshot.runtimeConnected ? " · unavailable" : ""}</dd></div>
      <div><dt>Work</dt><dd>{snapshot?.active ?? 0} active · {snapshot?.waiting ?? 0} waiting · {snapshot?.failed ?? 0} failed{!live ? " (stale)" : ""}</dd></div>
      <div><dt>Oldest turn</dt><dd>{snapshot?.oldestTurn ? formatElapsed(snapshot.oldestTurn.startedAt, now) : "—"}</dd></div>
      <div><dt>Latest activity</dt><dd>{formatAge(snapshot?.latestActivity, now)}</dd></div>
    </dl>
    <footer>
      <button className="primary" onClick={onOpen}>Open host</button>
      {!live && <button onClick={onReconnect}>Reconnect</button>}
      <button onClick={onEdit}>Edit</button>
      <button className="danger-link" onClick={onForget}>Forget</button>
    </footer>
  </article>;
}
