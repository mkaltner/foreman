import { ForemanWebClient, parseEndpoint, type ClientHooks, type ConnectionState, type Endpoint } from "./client";
import {
  applySessionSummaryEvent,
  providerUsableForTasks,
  providerSessionKey,
  reconcileSessionSummaries,
  sessionProvider,
  type ApprovalEventPayload,
  type ApprovalRequest,
  type InputEventPayload,
  type InputRequest,
  type ProviderInfo,
  type ServiceStatus,
  type SessionEventPayload,
  type SessionSummary,
  type WireMessage,
} from "./protocol";
import type { StoredHost } from "./storage";
import {
  liveBackgroundHostIds,
  projectHostSnapshot,
  WEB_HOST_ROTATION_MS,
  type HostOverviewSnapshot,
} from "./unified";

interface Projection {
  connection: ConnectionState;
  sessions: SessionSummary[];
  approvals: ApprovalRequest[];
  inputs: InputRequest[];
  status: ServiceStatus | null;
}

export interface OverviewClient {
  start(endpoint: Endpoint, token: string, onReady: (reconnected: boolean) => Promise<void>): Promise<void>;
  request<T extends Record<string, unknown>>(type: string, payload?: Record<string, unknown>): Promise<T>;
  disconnect(): void;
}

export class UnifiedHostConnections {
  private hosts: StoredHost[] = [];
  private activeHostId: string | null = null;
  private offset = 0;
  private clients = new Map<string, OverviewClient>();
  private projections = new Map<string, Projection>();
  private rotationTimer: number | null = null;

  constructor(
    private readonly onSnapshot: (snapshot: HostOverviewSnapshot) => void,
    private readonly clientFactory: (hooks: ClientHooks) => OverviewClient = (hooks) => new ForemanWebClient(hooks),
  ) {}

  start(hosts: StoredHost[], activeHostId: string | null): void {
    this.hosts = hosts;
    this.activeHostId = activeHostId;
    this.offset = 0;
    this.reconcile();
    if (this.rotationTimer !== null) window.clearInterval(this.rotationTimer);
    if (hosts.length > 4) {
      this.rotationTimer = window.setInterval(() => {
        this.offset += 1;
        this.reconcile();
      }, WEB_HOST_ROTATION_MS);
    }
  }

  stop(): void {
    if (this.rotationTimer !== null) window.clearInterval(this.rotationTimer);
    this.rotationTimer = null;
    this.clients.forEach((client) => client.disconnect());
    this.clients.clear();
  }

  reconnect(hostId: string): void {
    const client = this.clients.get(hostId);
    if (client) client.disconnect();
    this.clients.delete(hostId);
    const candidates = this.hosts.filter(({ id }) => id !== this.activeHostId);
    const requestedIndex = candidates.findIndex(({ id }) => id === hostId);
    if (requestedIndex < 0) return;
    this.offset = requestedIndex;
    this.reconcile();
  }

  private reconcile(): void {
    const selected = new Set(liveBackgroundHostIds(
      this.hosts.map(({ id }) => id),
      this.activeHostId,
      this.offset,
    ));
    this.clients.forEach((client, hostId) => {
      if (selected.has(hostId)) return;
      client.disconnect();
      this.clients.delete(hostId);
      this.update(hostId, { connection: "disconnected" });
    });
    this.hosts.filter(({ id }) => selected.has(id) && !this.clients.has(id)).forEach((host) => this.connect(host));
  }

  private connect(host: StoredHost): void {
    const client = this.clientFactory({
      onEvent: (message) => this.event(host.id, message),
      onState: (connection) => this.update(host.id, { connection }),
      onAuthenticationRejected: () => this.update(host.id, { connection: "disconnected" }),
    });
    this.clients.set(host.id, client);
    void client.start(parseEndpoint(host.host, host.webPort), host.deviceToken, async () => {
      const providerResult = await client.request<{ providers: ProviderInfo[] } & Record<string, unknown>>("provider.list");
      const codexAvailable = providerResult.providers.some((provider) => provider.id === "codex" && providerUsableForTasks(provider));
      const claudeAvailable = providerResult.providers.some((provider) => provider.id === "claude-code" && providerUsableForTasks(provider));
      const [codexSessionResult, claudeSessionResult, approvalResult, inputResult, statusResult] = await Promise.all([
        codexAvailable
          ? client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("provider.session.list", { provider: "codex" })
          : Promise.resolve({ sessions: [] as SessionSummary[] }),
        claudeAvailable
          ? client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("provider.session.list", { provider: "claude-code" })
          : Promise.resolve({ sessions: [] as SessionSummary[] }),
        codexAvailable
          ? client.request<{ approvals: ApprovalRequest[] } & Record<string, unknown>>("approval.list")
          : Promise.resolve({ approvals: [] as ApprovalRequest[] }),
        codexAvailable
          ? client.request<{ inputs: InputRequest[] } & Record<string, unknown>>("input.list").catch(() => ({ inputs: [] as InputRequest[] }))
          : Promise.resolve({ inputs: [] as InputRequest[] }),
        client.request<ServiceStatus & Record<string, unknown>>("service.status"),
      ]);
      if (this.clients.get(host.id) !== client) return;
      this.projections.set(host.id, {
        connection: "connected",
        sessions: [
          ...codexSessionResult.sessions.map((session) => ({ ...session, provider: "codex" as const })),
          ...claudeSessionResult.sessions.map((session) => ({ ...session, provider: "claude-code" as const })),
        ],
        approvals: approvalResult.approvals,
        inputs: inputResult.inputs ?? [],
        status: statusResult,
      });
      this.emit(host.id);
    }).catch(() => undefined);
  }

  private event(hostId: string, message: WireMessage): void {
    const projection = this.projections.get(hostId);
    if (!projection) return;
    if (message.type === "service.event") {
      projection.status = message.payload as unknown as ServiceStatus;
    } else if (message.type === "session.event") {
      const payload = message.payload as unknown as SessionEventPayload;
      if (!payload.sessionId || !payload.event) return;
      const provider = payload.provider ?? "codex";
      const identity = providerSessionKey(provider, payload.sessionId);
      if (payload.event.kind === "lifecycle" && payload.event.action === "removed") {
        projection.sessions = projection.sessions.filter((session) => providerSessionKey(sessionProvider(session), session.id) !== identity);
      } else if (payload.event.session) {
        projection.sessions = reconcileSessionSummaries(projection.sessions, [{ ...payload.event.session, provider }]);
      } else {
        projection.sessions = projection.sessions.map((session) => providerSessionKey(sessionProvider(session), session.id) === identity
          ? applySessionSummaryEvent(session, payload.event)
          : session);
      }
    } else if (["approval.requested", "approval.updated", "approval.resolved"].includes(message.type)) {
      const approval = (message.payload as unknown as ApprovalEventPayload).approval;
      if (!approval?.id) return;
      projection.approvals = projection.approvals.some(({ id }) => id === approval.id)
        ? projection.approvals.map((item) => item.id === approval.id ? approval : item)
        : [...projection.approvals, approval];
    } else if (["input.requested", "input.updated", "input.resolved"].includes(message.type)) {
      const input = (message.payload as unknown as InputEventPayload).input;
      if (!input?.id) return;
      projection.inputs = projection.inputs.some(({ id }) => id === input.id)
        ? projection.inputs.map((item) => item.id === input.id ? input : item)
        : [...projection.inputs, input];
    } else return;
    this.emit(hostId);
  }

  private update(hostId: string, update: Partial<Projection>): void {
    const previous = this.projections.get(hostId) ?? {
      connection: "disconnected" as ConnectionState,
      sessions: [],
      approvals: [],
      inputs: [],
      status: null,
    };
    this.projections.set(hostId, { ...previous, ...update });
    this.emit(hostId);
  }

  private emit(hostId: string): void {
    const projection = this.projections.get(hostId);
    if (!projection) return;
    this.onSnapshot(projectHostSnapshot(
      hostId,
      projection.sessions,
      projection.approvals,
      projection.status,
      projection.connection,
      Date.now(),
      projection.inputs,
    ));
  }
}
