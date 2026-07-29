export const PROTOCOL_VERSION = 1;
export const MAX_FRAME_BYTES = 16 * 1024 * 1024;

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
  kind: "user" | "assistant" | "command" | "tool";
  text?: string;
  description?: string;
  status?: string;
  exitCode?: number | null;
  turnId?: string | null;
  images?: ImagePayload[];
  imageCount?: number;
}

export interface SessionSummary {
  id: string;
  repository: string;
  title: string;
  status: string;
  lastActivity?: number | null;
  attention?: boolean;
  messages?: ConversationItem[];
  activeTurnId?: string | null;
  activityLabel?: string;
  activityText?: string;
  model?: string | null;
  reasoningEffort?: string | null;
  accessLevel?: string | null;
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
}

export interface SessionEventPayload {
  sessionId: string;
  event: SessionEvent;
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

export function applySessionEvent(
  session: SessionSummary,
  event: SessionEvent,
): SessionSummary {
  if (event.kind === "status") {
    const active = event.status === "working" || event.status === "waiting";
    return {
      ...session,
      status: event.status ?? session.status,
      attention: event.status === "waiting",
      activeTurnId: active ? (event.turnId ?? session.activeTurnId) : null,
      activityLabel: active ? session.activityLabel : "",
      activityText: active ? session.activityText : "",
    };
  }

  if (event.kind === "assistant.delta") {
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
    return {
      ...session,
      status: "working",
      activeTurnId: event.turnId ?? session.activeTurnId,
      messages,
      activityLabel: "Responding",
      activityText: "",
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
    const text = event.text ?? "";
    const label = event.label ?? session.activityLabel ?? "Working";
    return {
      ...session,
      activityLabel: label,
      activityText: event.append
        ? `${session.activityText ?? ""}${text}`
        : text || (label === session.activityLabel ? session.activityText ?? "" : ""),
    };
  }

  if (event.kind === "route") {
    return {
      ...session,
      model: event.model ?? session.model,
      reasoningEffort: event.reasoningEffort ?? session.reasoningEffort,
      accessLevel: event.accessLevel ?? session.accessLevel,
    };
  }
  return session;
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
  const model =
    models.find((entry) => entry.id === session?.model && entry.visible) ??
    models.find((entry) => entry.isDefault && entry.visible) ??
    models.find((entry) => entry.visible);
  const effort = model?.reasoningEfforts.includes(session?.reasoningEffort ?? "")
    ? session?.reasoningEffort
    : model?.defaultReasoningEffort ?? model?.reasoningEfforts[0];
  const access =
    accessLevels.find((entry) => entry.id === session?.accessLevel) ?? accessLevels[0];
  return {
    model: model?.id ?? "",
    reasoningEffort: effort ?? "",
    accessLevel: access?.id ?? "",
  };
}
