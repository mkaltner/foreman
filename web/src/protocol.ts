export const PROTOCOL_VERSION = 1;
export const MAX_FRAME_BYTES = 16 * 1024 * 1024;
export type ProviderId = "codex" | "claude-code";

export function isProviderId(value: unknown): value is ProviderId {
  return value === "codex" || value === "claude-code";
}

export interface ProviderInfo {
  id: ProviderId;
  displayName: string;
  enabled?: boolean;
  available: boolean;
  version?: string | null;
  cliVersion?: string | null;
  sdkVersion?: string | null;
  nodeVersion?: string | null;
  capabilities: string[];
  limitations: string[];
  unavailableReason?: "cli-missing" | "node-missing" | "sdk-missing" | "authentication-unavailable" | "adapter-unavailable" | null;
}

export function providerEnabled(provider: Pick<ProviderInfo, "enabled">): boolean {
  return provider.enabled !== false;
}

export function soleEnabledProvider(
  providers: readonly ProviderInfo[],
  catalogLoaded: boolean,
): ProviderInfo | null {
  if (!catalogLoaded) return null;
  const enabled = providers.filter(providerEnabled);
  return enabled.length === 1 ? enabled[0] : null;
}

export function shouldShowProviderIdentity(
  providers: readonly ProviderInfo[],
  catalogLoaded: boolean,
): boolean {
  return soleEnabledProvider(providers, catalogLoaded) === null;
}

export function providerCatalogResponseIsCurrent(
  requestHostId: string,
  activeHostId: string | null,
  requestRevision: number,
  currentRevision: number,
): boolean {
  return requestHostId === activeHostId && requestRevision === currentRevision;
}

export interface WireMessage<T = Record<string, unknown>> {
  version: number;
  id?: string | null;
  type: string;
  payload: T;
}

export interface HelloPayload {
  server: string;
  protocolVersion: number;
  codexRuntime: string;
  codexConnected: boolean;
  capabilities: Record<string, boolean>;
}

export interface RepositoryInfo {
  id: string;
  name: string;
  path: string;
  branch: string;
  dirty: boolean;
}

export interface ImagePayload {
  mimeType: "image/jpeg" | "image/png" | "image/webp";
  data: string;
}

export interface ConversationItem {
  id: string;
  kind: "user" | "assistant" | "command" | "tool" | "compaction";
  text?: string;
  description?: string;
  status?: string;
  exitCode?: number | null;
  turnId?: string | null;
  images?: ImagePayload[];
  imageCount?: number;
  compactionTrigger?: "auto" | "manual";
  preTokens?: number;
  postTokens?: number;
  durationMs?: number;
}

export interface TokenUsageBreakdown {
  totalTokens: number;
  inputTokens?: number;
  cachedInputTokens?: number;
  cacheWriteInputTokens?: number;
  outputTokens?: number;
  reasoningOutputTokens?: number;
}

export interface ThreadTokenUsage {
  total?: TokenUsageBreakdown;
  last?: TokenUsageBreakdown;
  modelContextWindow?: number;
}

export interface RateLimitWindow {
  id?: string;
  label?: string;
  limitId?: string;
  limitName?: string;
  usedPercent: number;
  windowDurationMins?: number;
  resetsAt?: number;
}

export interface RateLimitSnapshot {
  limitId?: string;
  limitName?: string;
  primary?: RateLimitWindow | null;
  secondary?: RateLimitWindow | null;
  windows?: RateLimitWindow[];
  planType?: string;
  rateLimitReachedType?: string;
}

export interface ProviderAccountUsage {
  available: boolean;
  rateLimits?: RateLimitSnapshot;
  experimental?: boolean;
  observedAt?: number;
  availabilityReason?: string;
}

export interface AccountUsage {
  providers: Partial<Record<ProviderId, ProviderAccountUsage>>;
}

export interface SessionSummary {
  provider?: ProviderId;
  id: string;
  sessionId?: string;
  cwd?: string;
  repositoryId?: string;
  repository: string;
  title: string;
  status: string;
  lastActivity?: number | null;
  observedAt?: number | null;
  attention?: boolean;
  messages?: ConversationItem[];
  activeTurnId?: string | null;
  activityLabel?: string;
  activityText?: string;
  model?: string | null;
  reasoningEffort?: string | null;
  accessLevel?: string | null;
  permissionMode?: string | null;
  settingsRevision?: number;
  source?: "managed" | "external";
  state?: string;
  capabilities?: string[];
  liveAttached?: boolean;
  externalLimitation?: string | null;
  activeTurnStartedAt?: number | null;
  terminalAt?: number | null;
  turnDurationMs?: number | null;
  failureSummary?: string | null;
  waitType?: "approval" | "input" | null;
  waitDescription?: string | null;
  statusChangedAt?: number | null;
  tokenUsage?: ThreadTokenUsage;
}

export interface SessionSearchMatch {
  kind: "title" | "workspace" | "user" | "assistant" | "command" | "tool";
  snippet: string;
  turnId?: string | null;
  itemId?: string | null;
}

export interface SessionSearchResult {
  session: SessionSummary;
  matches: SessionSearchMatch[];
}

export interface ServiceStatus {
  receivedAt?: number;
  foremanVersion: string;
  connected: boolean;
  remoteRestartEnabled?: boolean;
  uptimeSeconds: number;
  codex: {
    connected: boolean;
    mode: "shared" | "fallback" | "unavailable";
    runtimeStatus: string;
    version?: string | null;
    lastCommunication?: string | null;
    lastEvent?: string | null;
    lastSuccessfulRequest?: string | null;
    attachedAt?: string | null;
    loadedThreadCount?: number;
    subscribedThreadCount?: number;
    ownedByForeman?: boolean;
    appServerPid?: number | null;
    socketPath?: string | null;
  };
  listeners: {
    tcpPort: number;
    webPort?: number | null;
  };
  repositoryRoot: string;
  activeBrowserConnections?: number;
  activeTcpConnections?: number;
}

export interface DiagnosticEvent {
  timestamp: string;
  severity: "info" | "warning" | "error";
  category: string;
  message: string;
  ids?: { clientId?: string };
  requestCategory?: string;
}

export interface PairedClient {
  id: string;
  name: string;
  type: "browser" | "android" | "mixed" | "unknown";
  pairedAt?: string | null;
  connected: boolean;
  connectionCount: number;
  current: boolean;
}

export interface ModelInfo {
  id: string;
  displayName: string;
  description?: string;
  reasoningEfforts: string[];
  defaultReasoningEffort?: string | null;
  visible: boolean;
  isDefault: boolean;
  inputModalities?: string[];
}

export interface AccessLevelInfo {
  id: string;
  displayName: string;
  description?: string;
}

export interface PermissionModeInfo {
  id: string;
  displayName: string;
  description?: string;
  highRisk?: boolean;
}

export interface ApprovalDecision {
  type: string;
  label: string;
  optionId?: string;
  scopes?: Array<"turn" | "session">;
  amendment?: string[];
  networkAmendment?: { host?: string | null; action?: "allow" | "deny" | null };
}

export interface ApprovalPermissions {
  fileSystem?: {
    read?: string[];
    write?: string[];
    entries?: Array<{ access: "read" | "write" | "deny"; path: Record<string, unknown> }>;
    globScanMaxDepth?: number;
  };
  network?: { enabled?: boolean };
}

export interface ApprovalRequest {
  id: string;
  sessionId: string;
  turnId?: string | null;
  itemId?: string | null;
  type: "command" | "fileChange" | "permission";
  title: string;
  createdAt: number;
  startedAt?: number | null;
  reason?: string | null;
  status: "pending" | "submitting" | "resolved" | "expired";
  resolution?: string | null;
  availableDecisions: ApprovalDecision[];
  command?: string | null;
  commandActions?: Array<Record<string, string | null>>;
  cwd?: string | null;
  networkContext?: { host?: string | null; protocol?: string | null };
  requestedPermissions?: ApprovalPermissions;
  fileChanges?: Array<{
    path: string;
    kind: string;
    summary?: { addedLines: number; removedLines: number };
  }>;
  fileCount?: number;
  grantRoot?: string | null;
  availableScopes?: Array<"turn" | "session">;
}

export interface ApprovalEventPayload {
  approval: ApprovalRequest;
}

export interface InputOption {
  value: string;
  label: string;
  description?: string;
}

export interface InputField {
  id: string;
  type: "singleChoice" | "multipleChoice" | "shortText" | "longText" | "boolean" | "confirmation";
  label: string;
  description?: string | null;
  required: boolean;
  secret?: boolean;
  options?: InputOption[];
  allowOther?: boolean;
  minSelections?: number;
  maxSelections?: number;
  minLength?: number;
  maxLength?: number;
  default?: string | string[] | boolean;
}

export interface InputRequest {
  id: string;
  sessionId: string;
  turnId?: string | null;
  itemId?: string | null;
  source: "codex" | "mcp";
  title: string;
  message?: string | null;
  serverName?: string | null;
  fields: InputField[];
  supported: boolean;
  unsupportedMessage?: string | null;
  canDecline: boolean;
  canCancel: boolean;
  autoResolutionMs?: number | null;
  createdAt: number;
  status: "pending" | "submitting" | "resolved" | "expired";
  resolution?: string | null;
}

export interface InputEventPayload {
  input: InputRequest;
}

export interface SessionEvent {
  kind: string;
  type?: string;
  status?: string;
  turnId?: string | null;
  itemId?: string | null;
  text?: string;
  label?: string;
  append?: boolean;
  phase?: string;
  item?: ConversationItem | null;
  error?: unknown;
  model?: string;
  reasoningEffort?: string;
  accessLevel?: string;
  permissionMode?: string;
  settingsRevision?: number;
  action?: "created" | "removed";
  session?: SessionSummary;
  startedAt?: number | null;
  completedAt?: number | null;
  durationMs?: number | null;
  failureSummary?: string | null;
  waitType?: "approval" | "input";
  waitDescription?: string | null;
  activityAt?: number | null;
  observedAt?: number;
  tokenUsage?: ThreadTokenUsage;
}

export interface SessionEventPayload {
  provider?: ProviderId;
  sessionId: string;
  event: SessionEvent;
}

export function sessionProvider(session: Pick<SessionSummary, "provider">): ProviderId {
  return session.provider ?? "codex";
}

export function providerSessionKey(
  provider: ProviderId,
  sessionId: string,
): string {
  return `${provider.length}:${provider}${sessionId}`;
}

export class ForemanError extends Error {
  constructor(
    message: string,
    public readonly code = "requestFailed",
  ) {
    super(message);
    this.name = "ForemanError";
  }
}

export function isWireMessage(value: unknown): value is WireMessage {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<WireMessage>;
  return (
    candidate.version === PROTOCOL_VERSION &&
    typeof candidate.type === "string" &&
    !!candidate.payload &&
    typeof candidate.payload === "object" &&
    !Array.isArray(candidate.payload)
  );
}

export type SessionGroup = "waiting" | "active" | "recent";

export function groupSessions(
  sessions: SessionSummary[],
): Record<SessionGroup, SessionSummary[]> {
  const grouped: Record<SessionGroup, SessionSummary[]> = {
    waiting: [],
    active: [],
    recent: [],
  };
  [...sessions]
    .sort((a, b) => (b.lastActivity ?? 0) - (a.lastActivity ?? 0))
    .forEach((session) => {
      if (session.attention || session.status === "waiting") grouped.waiting.push(session);
      else if (session.status === "working") grouped.active.push(session);
      else grouped.recent.push(session);
    });
  return grouped;
}

export function liveActivityLabel(session: SessionSummary): string {
  if (session.activityLabel?.trim()) return session.activityLabel;
  const activeItem = [...(session.messages ?? [])].reverse().find((item) =>
    ["inprogress", "running"].includes((item.status ?? "").toLowerCase()),
  );
  if (activeItem?.kind === "command") return "Running command";
  if (activeItem?.kind === "tool" && /^web search/i.test(activeItem.description ?? "")) {
    return "Searching";
  }
  if (activeItem?.kind === "tool") return "Using tool";
  return "Thinking";
}

export function liveActivityMessage(session: SessionSummary): string | null {
  return session.activityText?.trim().split("\n").filter(Boolean).at(-1) ?? null;
}

function isTerminalSession(session: SessionSummary): boolean {
  return ["completed", "failed", "interrupted"].includes(session.status);
}

function eventActivityAt(event: SessionEvent): number | undefined {
  return "activityAt" in event
    ? event.activityAt ?? undefined
    : event.observedAt;
}

function isStaleTurnEvent(session: SessionSummary, event: SessionEvent): boolean {
  if (isTerminalSession(session)) return true;
  return Boolean(
    session.activeTurnId &&
    event.turnId &&
    session.activeTurnId !== event.turnId,
  );
}

export function applySessionEvent(
  session: SessionSummary,
  event: SessionEvent,
): SessionSummary {
  if (event.kind === "status") {
    const active = event.status === "working" || event.status === "waiting";
    const terminal = ["completed", "failed", "interrupted"].includes(event.status ?? "");
    return {
      ...session,
      status: event.status ?? session.status,
      statusChangedAt: event.status && event.status !== session.status
        ? (eventActivityAt(event) ?? session.statusChangedAt)
        : session.statusChangedAt,
      attention: event.status === "waiting",
      activeTurnId: active ? (event.turnId ?? session.activeTurnId) : null,
      activeTurnStartedAt: active
        ? (event.startedAt ?? session.activeTurnStartedAt)
        : null,
      terminalAt: terminal ? (event.completedAt ?? eventActivityAt(event) ?? session.terminalAt) : null,
      turnDurationMs: terminal ? (event.durationMs ?? session.turnDurationMs) : null,
      failureSummary: event.status === "failed"
        ? (event.failureSummary ?? session.failureSummary)
        : null,
      waitType: event.status === "waiting" ? (event.waitType ?? session.waitType) : null,
      waitDescription: event.status === "waiting"
        ? (event.waitDescription ?? session.waitDescription)
        : null,
      activityLabel: event.status === "waiting"
        ? event.waitType === "input" ? "Waiting for input" : "Waiting for approval"
        : session.activityLabel,
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
  }

  if (event.kind === "assistant.delta") {
    const stale = isStaleTurnEvent(session, event);
    const itemId = event.itemId || `assistant-${event.turnId || "active"}`;
    const messages = [...(session.messages ?? [])];
    const index = messages.findIndex((item) => item.id === itemId);
    if (index >= 0) {
      messages[index] = {
        ...messages[index],
        text: `${messages[index].text ?? ""}${event.text ?? ""}`,
      };
    } else {
      messages.push({
        id: itemId,
        kind: "assistant",
        text: event.text ?? "",
        turnId: event.turnId,
      });
    }
    // Keep late text in the full transcript, but do not let a buffered delta
    // revive a terminal session or replace a newer turn's live projection.
    if (stale) return { ...session, messages };
    return {
      ...session,
      status: "working",
      activeTurnId: event.turnId ?? session.activeTurnId,
      messages,
      activityLabel: "Responding",
      activityText: "",
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
  }

  if (event.kind === "item" && event.item) {
    const item = { ...event.item, turnId: event.turnId ?? event.item.turnId };
    const messages = [...(session.messages ?? [])];
    const index = messages.findIndex((existing) => existing.id === item.id);
    if (index >= 0) messages[index] = { ...messages[index], ...item };
    else messages.push(item);
    const updated = {
      ...session,
      status: "working",
      activeTurnId: event.turnId ?? session.activeTurnId,
      messages,
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
    if (event.phase === "started") {
      return {
        ...updated,
        activityLabel: liveActivityLabel({ ...updated, activityLabel: "" }),
        activityText: item.description ?? "",
      };
    }
    if (event.phase === "completed") {
      return { ...updated, activityLabel: "Thinking", activityText: "" };
    }
    return updated;
  }

  if (event.kind === "activity") {
    if (isStaleTurnEvent(session, event)) return session;
    const text = event.text ?? "";
    const label = event.label ?? session.activityLabel ?? "Working";
    return {
      ...session,
      activityLabel: label,
      activityText: event.append
        ? `${session.activityText ?? ""}${text}`
        : text || (label === session.activityLabel ? session.activityText ?? "" : ""),
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
  }

  if (event.kind === "route") {
    if (
      event.settingsRevision !== undefined &&
      (session.settingsRevision ?? 0) > event.settingsRevision
    ) return session;
    return {
      ...session,
      model: event.model ?? session.model,
      reasoningEffort: event.reasoningEffort ?? session.reasoningEffort,
      accessLevel: event.accessLevel ?? session.accessLevel,
      permissionMode: event.permissionMode ?? session.permissionMode,
      settingsRevision: event.settingsRevision ?? session.settingsRevision,
    };
  }
  if (
    event.kind === "usage" &&
    Number.isFinite(event.tokenUsage?.last?.totalTokens) &&
    Number.isFinite(event.tokenUsage?.modelContextWindow) &&
    (event.tokenUsage?.last?.totalTokens ?? -1) >= 0 &&
    (event.tokenUsage?.modelContextWindow ?? 0) > 0
  ) {
    return { ...session, tokenUsage: event.tokenUsage };
  }
  return session;
}

export function applySessionSummaryEvent(
  session: SessionSummary,
  event: SessionEvent,
): SessionSummary {
  if (
    (event.kind === "assistant.delta" || event.kind === "activity") &&
    isStaleTurnEvent(session, event)
  ) return session;
  if (event.kind === "assistant.delta") {
    return {
      ...session,
      status: "working",
      attention: false,
      activeTurnId: event.turnId ?? session.activeTurnId,
      activityLabel: "Responding",
      activityText: "",
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
  }
  if (event.kind === "item") {
    if (event.phase === "completed") {
      return {
        ...session,
        activityLabel: "Thinking",
        lastActivity: eventActivityAt(event) ?? session.lastActivity,
      };
    }
    if (event.phase !== "started" || !event.item) {
      return { ...session, lastActivity: eventActivityAt(event) ?? session.lastActivity };
    }
    const label = event.item.kind === "command"
      ? "Running command"
      : /^web search/i.test(event.item.description ?? "")
        ? "Searching the web"
        : event.item.description?.startsWith("Editing ")
          ? event.item.description
          : "Using tool";
    return {
      ...session,
      status: "working",
      activeTurnId: event.turnId ?? session.activeTurnId,
      activityLabel: label,
      activityText: event.item.kind === "command" ? "" : event.item.description ?? "",
      lastActivity: eventActivityAt(event) ?? session.lastActivity,
    };
  }
  const updated = applySessionEvent({ ...session, messages: undefined }, event);
  return { ...updated, messages: undefined };
}

export function reconcileSessionSummaries(
  previous: SessionSummary[],
  incoming: SessionSummary[],
): SessionSummary[] {
  const prior = new Map(previous.map((session) => [providerSessionKey(sessionProvider(session), session.id), session]));
  return incoming.map((session) => {
    const existing = prior.get(providerSessionKey(sessionProvider(session), session.id));
    const reconciled = reconcileSessionSettings(existing, session);
    if (
      session.status === "idle" &&
      existing &&
      ["completed", "failed", "interrupted"].includes(existing.status) &&
      existing.terminalAt
    ) {
      return {
        ...reconciled,
        status: existing.status,
        terminalAt: existing.terminalAt,
        turnDurationMs: existing.turnDurationMs,
        failureSummary: existing.failureSummary,
        activityLabel: existing.activityLabel,
        activityText: existing.activityText,
      };
    }
    return reconciled;
  });
}

export function reconcileSessionSettings(
  previous: SessionSummary | null | undefined,
  incoming: SessionSummary,
): SessionSummary {
  if (!previous || providerSessionKey(sessionProvider(previous), previous.id) !== providerSessionKey(sessionProvider(incoming), incoming.id)) {
    return incoming;
  }
  if ((previous.settingsRevision ?? 0) <= (incoming.settingsRevision ?? 0)) {
    return incoming;
  }
  return {
    ...incoming,
    model: previous.model,
    reasoningEffort: previous.reasoningEffort,
    accessLevel: previous.accessLevel,
    permissionMode: previous.permissionMode,
    settingsRevision: previous.settingsRevision,
  };
}

export function applySessionSummaryEventBatch(
  sessions: SessionSummary[],
  buffered: ReadonlyMap<string, SessionEvent[]>,
): SessionSummary[] {
  return sessions.map((session) => {
    const events = buffered.get(providerSessionKey(sessionProvider(session), session.id))
      ?? (sessionProvider(session) === "codex" ? buffered.get(session.id) : undefined);
    return events ? events.reduce(applySessionSummaryEvent, session) : session;
  });
}

export interface RouteSelection {
  model: string;
  reasoningEffort: string;
  accessLevel: string;
}

export function routeForSession(
  session: SessionSummary | null,
  models: ModelInfo[],
  accessLevels: AccessLevelInfo[],
): RouteSelection {
  if (session !== null) {
    return {
      model: session.model ?? "",
      reasoningEffort: session.reasoningEffort ?? "",
      accessLevel: session.accessLevel ?? "",
    };
  }
  const model = models.find((entry) => entry.isDefault && entry.visible)
    ?? models.find((entry) => entry.visible);
  return {
    model: model?.id ?? "",
    reasoningEffort: model?.defaultReasoningEffort ?? model?.reasoningEfforts[0] ?? "",
    accessLevel: accessLevels[0]?.id ?? "",
  };
}
