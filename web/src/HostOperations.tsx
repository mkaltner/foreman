import { useEffect, useState } from "react";
import type { ConnectionState } from "./client";
import type { DiagnosticEvent } from "./protocol";
import { CopyFeedbackButton } from "./CopyFeedbackButton";

export type RestartPhase =
  | "idle"
  | "scheduling"
  | "scheduled"
  | "reconnecting"
  | "succeeded"
  | "timedOut"
  | "failed";

export function restartPhaseAfterConnection(
  phase: RestartPhase,
  connection: ConnectionState,
): RestartPhase {
  if (phase === "scheduled" && connection !== "connected") return "reconnecting";
  if (phase === "reconnecting" && connection === "connected") return "succeeded";
  return phase;
}

export function diagnosticsText(events: DiagnosticEvent[]): string {
  return events.map((event) => {
    const request = event.requestCategory ? ` [${event.requestCategory}]` : "";
    const ids = event.ids?.clientId ? ` (${event.ids.clientId})` : "";
    return `${event.timestamp} ${event.severity.toUpperCase()} ${event.category}${request}: ${event.message}${ids}`;
  }).join("\n");
}

function restartLabel(phase: RestartPhase): string {
  switch (phase) {
    case "scheduling": return "Scheduling restart…";
    case "scheduled": return "Restart scheduled; waiting for Foreman to stop…";
    case "reconnecting": return "Foreman is restarting; reconnecting…";
    case "succeeded": return "Restart complete; Foreman is connected.";
    case "timedOut": return "Restart timed out before Foreman returned.";
    case "failed": return "Restart could not be scheduled.";
    default: return "";
  }
}

export function HostOperations({
  connection,
  disabled,
  remoteRestartEnabled,
  restartBlocked = false,
  fetchDiagnostics,
  scheduleRestart,
}: {
  connection: ConnectionState;
  disabled: boolean;
  remoteRestartEnabled: boolean;
  restartBlocked?: boolean;
  fetchDiagnostics: () => Promise<DiagnosticEvent[]>;
  scheduleRestart: () => Promise<{ scheduled: boolean; timeoutSeconds?: number }>;
}) {
  const [events, setEvents] = useState<DiagnosticEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [restartPhase, setRestartPhase] = useState<RestartPhase>("idle");
  const [restartDeadline, setRestartDeadline] = useState<number | null>(null);

  const refresh = async () => {
    setLoading(true);
    setError("");
    try {
      setEvents(await fetchDiagnostics());
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Diagnostics could not be loaded");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setRestartPhase((phase) => restartPhaseAfterConnection(phase, connection));
  }, [connection]);

  useEffect(() => {
    if ((restartPhase !== "scheduled" && restartPhase !== "reconnecting") || restartDeadline === null) return;
    const timeout = window.setTimeout(
      () => setRestartPhase("timedOut"),
      Math.max(0, restartDeadline - Date.now()),
    );
    return () => window.clearTimeout(timeout);
  }, [restartDeadline, restartPhase]);

  const restart = async () => {
    if (restartBlocked) return;
    if (!window.confirm("Restart Foreman? Connected clients will briefly disconnect and reconnect automatically. Desktop Codex will not be restarted.")) return;
    setRestartPhase("scheduling");
    try {
      const result = await scheduleRestart();
      if (!result.scheduled) throw new Error("Restart was not scheduled");
      setRestartDeadline(Date.now() + (result.timeoutSeconds ?? 45) * 1000);
      setRestartPhase("scheduled");
    } catch (caught) {
      setRestartPhase("failed");
      setError(caught instanceof Error ? caught.message : "Restart could not be scheduled");
    }
  };

  return <details className="host-operations" onToggle={(event) => {
    if (event.currentTarget.open && events.length === 0 && !loading) void refresh();
  }}>
    <summary>Diagnostics <span>{events.length ? `${events.length} entries` : "sanitized"}</span></summary>
    <div className="diagnostic-actions">
      <button onClick={() => void refresh()} disabled={disabled || loading}>{loading ? "Refreshing…" : "Refresh"}</button>
      <CopyFeedbackButton text={diagnosticsText(events)} disabled={!events.length} className="diagnostic-copy" />
      <button className="restart-service" onClick={() => void restart()} disabled={disabled || !remoteRestartEnabled || restartBlocked || restartPhase === "scheduling" || restartPhase === "scheduled" || restartPhase === "reconnecting"}>Restart Foreman</button>
    </div>
    {!remoteRestartEnabled && <p className="diagnostic-note">Remote restart is disabled on this host.</p>}
    {remoteRestartEnabled && restartBlocked && <p className="diagnostic-note">Restart is unavailable while sessions are active or waiting for attention.</p>}
    {restartPhase !== "idle" && <p className={`restart-progress ${restartPhase}`} role="status">{restartLabel(restartPhase)}</p>}
    {error && <p className="diagnostic-error" role="alert">{error}</p>}
    {!loading && !error && events.length === 0 && <p className="diagnostic-note">No diagnostic events are available.</p>}
    {events.length > 0 && <ol className="diagnostic-list">{events.map((event, index) => <li key={`${event.timestamp}-${event.category}-${index}`}>
      <time>{new Date(event.timestamp).toLocaleString()}</time>
      <span className={`diagnostic-severity ${event.severity}`}>{event.severity}</span>
      <strong>{event.category}</strong>
      <span>{event.message}{event.requestCategory ? ` · ${event.requestCategory}` : ""}</span>
    </li>)}</ol>}
  </details>;
}
