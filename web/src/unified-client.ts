import { ForemanWebClient, parseEndpoint, type ClientHooks, type ConnectionState, type Endpoint } from "./client";
import {
  applySessionSummaryEvent,
  reconcileSessionSummaries,
  type ApprovalEventPayload,
  type ApprovalRequest,
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
      const [sessionResult, approvalResult, statusResult] = await Promise.all([
        client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("session.list"),
        client.request<{ approvals: ApprovalRequest[] } & Record<string, unknown>>("approval.list"),
        client.request<ServiceStatus & Record<string, unknown>>("service.status"),
      ]);
      if (this.clients.get(host.id) !== client) return;
      this.projections.set(host.id, {
        connection: "connected",
        sessions: sessionResult.sessions,
        approvals: approvalResult.approvals,
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
      if (payload.event.kind === "lifecycle" && payload.event.action === "removed") {
        projection.sessions = projection.sessions.filter(({ id }) => id !== payload.sessionId);
      } else if (payload.event.session) {
        projection.sessions = reconcileSessionSummaries(projection.sessions, [payload.event.session]);
      } else {
        projection.sessions = projection.sessions.map((session) => session.id === payload.sessionId
          ? applySessionSummaryEvent(session, payload.event)
          : session);
      }
    } else if (["approval.requested", "approval.updated", "approval.resolved"].includes(message.type)) {
      const approval = (message.payload as unknown as ApprovalEventPayload).approval;
      if (!approval?.id) return;
      projection.approvals = projection.approvals.some(({ id }) => id === approval.id)
        ? projection.approvals.map((item) => item.id === approval.id ? approval : item)
        : [...projection.approvals, approval];
    } else return;
    this.emit(hostId);
  }

  private update(hostId: string, update: Partial<Projection>): void {
    const previous = this.projections.get(hostId) ?? {
      connection: "disconnected" as ConnectionState,
      sessions: [],
      approvals: [],
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
    ));
  }
}
