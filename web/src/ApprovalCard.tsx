import { useEffect, useMemo, useRef, useState } from "react";
import { useSharedClock } from "./clock";
import { formatAge } from "./dashboard";
import type {
  ApprovalDecision,
  ApprovalPermissions,
  ApprovalRequest,
} from "./protocol";

interface PermissionChoice {
  id: string;
  label: string;
  add: (target: ApprovalPermissions) => void;
}

export function approvalAttentionLabel(approval: ApprovalRequest): string {
  return {
    command: "Waiting for command approval",
    fileChange: "Waiting for file-change approval",
    permission: "Waiting for permission grant",
    unsupportedInput: "Waiting for unsupported user input",
    unsupportedForm: "Waiting for unsupported user input",
  }[approval.type];
}

export function permissionChoices(approval: ApprovalRequest): PermissionChoice[] {
  const requested = approval.requestedPermissions ?? {};
  const choices: PermissionChoice[] = [];
  for (const kind of ["read", "write"] as const) {
    requested.fileSystem?.[kind]?.forEach((path, index) => choices.push({
      id: `${kind}-${index}`,
      label: `${kind === "read" ? "Read" : "Write"}: ${path}`,
      add: (target) => {
        target.fileSystem ??= {};
        target.fileSystem[kind] = [...(target.fileSystem[kind] ?? []), path];
      },
    }));
  }
  requested.fileSystem?.entries?.forEach((entry, index) => choices.push({
    id: `entry-${index}`,
    label: `${entry.access}: ${formatPermissionPath(entry.path)}`,
    add: (target) => {
      target.fileSystem ??= {};
      target.fileSystem.entries = [...(target.fileSystem.entries ?? []), entry];
      if (requested.fileSystem?.globScanMaxDepth) {
        target.fileSystem.globScanMaxDepth = requested.fileSystem.globScanMaxDepth;
      }
    },
  }));
  if (requested.network?.enabled === true) choices.push({
    id: "network",
    label: "Network access",
    add: (target) => { target.network = { enabled: true }; },
  });
  return choices;
}

function formatPermissionPath(path: Record<string, unknown>): string {
  if (path.type === "path" && typeof path.path === "string") return path.path;
  if (path.type === "glob_pattern" && typeof path.pattern === "string") return path.pattern;
  if (path.type === "special" && path.value && typeof path.value === "object") {
    const value = path.value as Record<string, unknown>;
    return [value.kind, value.path, value.subpath].filter((item) => typeof item === "string").join(": ");
  }
  return "Requested filesystem path";
}

function permissionPayload(choices: PermissionChoice[], selected: Set<string>): ApprovalPermissions {
  const result: ApprovalPermissions = {};
  choices.filter((choice) => selected.has(choice.id)).forEach((choice) => choice.add(result));
  return result;
}

export function ApprovalCard({
  approval,
  focused = false,
  connected,
  onRespond,
}: {
  approval: ApprovalRequest;
  focused?: boolean;
  connected: boolean;
  onRespond: (approvalId: string, decision: Record<string, unknown>) => Promise<void>;
}) {
  const now = useSharedClock();
  const rootRef = useRef<HTMLElement>(null);
  const [localSubmitting, setLocalSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [selectedPermissions, setSelectedPermissions] = useState<Set<string>>(new Set());
  const [scope, setScope] = useState("");
  const choices = useMemo(() => permissionChoices(approval), [approval]);
  const disabled = !connected || localSubmitting || approval.status !== "pending";

  useEffect(() => {
    if (!focused) return;
    const frame = requestAnimationFrame(() => {
      rootRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
      rootRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focused]);

  const respond = async (decision: Record<string, unknown>) => {
    if (disabled) return;
    setLocalSubmitting(true);
    setError("");
    try {
      await onRespond(approval.id, decision);
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "Approval response failed";
      setError(/already resolved/i.test(message) ? "Already resolved in another client." : message);
      setLocalSubmitting(false);
    }
  };

  const statusText = localSubmitting || approval.status === "submitting"
    ? "Submitting decision…"
    : approval.status === "resolved"
      ? approval.resolution === "resolvedElsewhere" ? "Already resolved in another client." : "Approval resolved."
      : approval.status === "expired" ? "This approval is no longer available." : null;

  return (
    <article
      ref={rootRef}
      id={`approval-${approval.id}`}
      className={`approval-card approval-${approval.type} ${focused ? "approval-focused" : ""}`}
      tabIndex={-1}
      aria-labelledby={`approval-title-${approval.id}`}
    >
      <header>
        <div><span className="approval-kicker">Approval required</span><h2 id={`approval-title-${approval.id}`}>{approval.title}</h2></div>
        <time>{formatAge(approval.startedAt ?? approval.createdAt, now)}</time>
      </header>
      {approval.reason && <p className="approval-reason">{approval.reason}</p>}
      {approval.type === "command" && <CommandDetails approval={approval} />}
      {approval.type === "fileChange" && <FileDetails approval={approval} />}
      {approval.type === "permission" && (
        <fieldset className="permission-choices" disabled={disabled}>
          <legend>Select only the access you want to grant</legend>
          {choices.map((choice) => <label key={choice.id}><input type="checkbox" checked={selectedPermissions.has(choice.id)} onChange={(event) => setSelectedPermissions((previous) => { const next = new Set(previous); if (event.target.checked) next.add(choice.id); else next.delete(choice.id); return next; })} /> <span>{choice.label}</span></label>)}
          <label className="scope-choice"><span>Grant scope</span><select value={scope} onChange={(event) => setScope(event.target.value)}><option value="">Choose scope</option>{approval.availableScopes?.map((value) => <option key={value} value={value}>{value === "turn" ? "This turn" : "This session"}</option>)}</select></label>
        </fieldset>
      )}
      {(approval.type === "unsupportedInput" || approval.type === "unsupportedForm") && <p className="unsupported-request">{approval.unsupportedMessage} {approval.availableDecisions.length === 0 && "Open another compatible Codex client to answer it."}</p>}
      {statusText && <p className="approval-status" role="status">{statusText}</p>}
      {error && <p className="approval-error" role="alert">{error}</p>}
      <div className="approval-actions">
        {approval.type === "permission" ? <>
          <button className="approval-primary" disabled={disabled || !selectedPermissions.size || !scope} onClick={() => void respond({ type: "grant", scope, permissions: permissionPayload(choices, selectedPermissions) })}>Grant selected</button>
          <button className="approval-danger" disabled={disabled} onClick={() => void respond({ type: "deny" })}>Deny all</button>
        </> : approval.availableDecisions.map((decision) => <DecisionButton key={decision.optionId ?? decision.type} decision={decision} disabled={disabled} onClick={() => void respond({ type: decision.type, ...(decision.optionId ? { optionId: decision.optionId } : {}) })} />)}
      </div>
    </article>
  );
}

function CommandDetails({ approval }: { approval: ApprovalRequest }) {
  return <div className="approval-details">
    {approval.networkContext && <p><strong>Network</strong><span>{approval.networkContext.protocol}://{approval.networkContext.host}</span></p>}
    {!!approval.commandActions?.length && <ul>{approval.commandActions.map((action, index) => <li key={index}>{action.type}: {action.name || action.path || action.query || action.command}</li>)}</ul>}
    {approval.command && <pre><code>{approval.command}</code></pre>}
    {approval.cwd && <p><strong>Working directory</strong><code>{approval.cwd}</code></p>}
    <PermissionSummary permissions={approval.requestedPermissions} />
  </div>;
}

function FileDetails({ approval }: { approval: ApprovalRequest }) {
  return <div className="approval-details"><p><strong>Affected files</strong><span>{approval.fileCount ?? approval.fileChanges?.length ?? 0}</span></p><ul>{approval.fileChanges?.map((change) => <li key={change.path}><code>{change.path}</code>{change.summary && <small>+{change.summary.addedLines} −{change.summary.removedLines}</small>}</li>)}</ul>{approval.grantRoot && <p><strong>Proposed write root</strong><code>{approval.grantRoot}</code></p>}</div>;
}

function PermissionSummary({ permissions }: { permissions?: ApprovalPermissions }) {
  if (!permissions || (!permissions.fileSystem && !permissions.network)) return null;
  return <details><summary>Additional access requested</summary><ul>{permissions.fileSystem?.read?.map((path) => <li key={`read-${path}`}>Read <code>{path}</code></li>)}{permissions.fileSystem?.write?.map((path) => <li key={`write-${path}`}>Write <code>{path}</code></li>)}{permissions.fileSystem?.entries?.map((entry, index) => <li key={index}>{entry.access} <code>{formatPermissionPath(entry.path)}</code></li>)}{permissions.network?.enabled && <li>Network access</li>}</ul></details>;
}

function DecisionButton({ decision, disabled, onClick }: { decision: ApprovalDecision; disabled: boolean; onClick: () => void }) {
  const broad = ["acceptForSession", "acceptWithExecpolicyAmendment", "applyNetworkPolicyAmendment"].includes(decision.type);
  const deny = ["decline", "cancel"].includes(decision.type);
  return <button className={broad ? "approval-broad" : deny ? "approval-danger" : "approval-primary"} disabled={disabled} onClick={onClick}>{decision.label}</button>;
}
