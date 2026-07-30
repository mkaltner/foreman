import {
  ForemanError,
  MAX_FRAME_BYTES,
  PROTOCOL_VERSION,
  isWireMessage,
  type HelloPayload,
  type WireMessage,
} from "./protocol";

export interface Endpoint {
  host: string;
  port: number;
  httpUrl: string;
  wsUrl: string;
}

export type ConnectionState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting";

export function inferPagePort(
  pagePort = window.location.port,
  pageProtocol = window.location.protocol,
): number {
  if (pagePort) return Number(pagePort);
  if (pageProtocol === "https:") return 443;
  if (pageProtocol === "http:") return 80;
  return 8766;
}

interface SocketLike {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

type SocketFactory = (url: string) => SocketLike;
type TimerFactory = (callback: () => void, delay: number) => number;

export function parseEndpoint(
  rawHost: string,
  rawPort: string | number = 8766,
  pageProtocol = window.location.protocol,
): Endpoint {
  const input = rawHost.trim();
  if (!input) throw new ForemanError("Host is required", "invalidHost");
  if (/[/#?]/.test(input.replace(/^https?:\/\//i, ""))) {
    throw new ForemanError("Enter a host name or IP address without a path", "invalidHost");
  }
  const port = typeof rawPort === "number" ? rawPort : Number(rawPort);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new ForemanError("Web port must be between 1 and 65535", "invalidHost");
  }
  const explicitScheme = /^https?:\/\//i.test(input);
  const secure = explicitScheme
    ? input.toLowerCase().startsWith("https://")
    : pageProtocol === "https:";
  let parsed: URL;
  try {
    parsed = new URL(explicitScheme ? input : `http://${input}`);
  } catch {
    throw new ForemanError(
      "Enter a valid host; wrap IPv6 addresses in brackets",
      "invalidHost",
    );
  }
  if (parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new ForemanError("Enter a host name or IP address only", "invalidHost");
  }
  const normalizedHost = parsed.hostname.replace(/^\[|\]$/g, "");
  const host = normalizedHost.includes(":") ? `[${normalizedHost}]` : normalizedHost;
  if (!normalizedHost) throw new ForemanError("Host is required", "invalidHost");
  const authority = `${host}:${port}`;
  return {
    host: normalizedHost,
    port,
    httpUrl: `${secure ? "https" : "http"}://${authority}`,
    wsUrl: `${secure ? "wss" : "ws"}://${authority}/ws`,
  };
}

interface PendingRequest {
  resolve: (message: WireMessage) => void;
  reject: (error: Error) => void;
  timeout: number;
}

export interface ClientHooks {
  onEvent: (message: WireMessage) => void;
  onState: (state: ConnectionState, detail?: string) => void;
  onHello?: (hello: HelloPayload) => void;
  onAuthenticationRejected?: (detail: string) => void;
}

export class ForemanWebClient {
  private socket: SocketLike | null = null;
  private sequence = 0;
  private generation = 0;
  private pending = new Map<string, PendingRequest>();
  private reconnectAttempt = 0;
  private reconnectTimer: number | null = null;
  private reconnectEnabled = false;
  private endpoint: Endpoint | null = null;
  private token = "";
  private onReady: ((reconnected: boolean) => Promise<void>) | null = null;
  private hasConnected = false;

  constructor(
    private readonly hooks: ClientHooks,
    private readonly socketFactory: SocketFactory = (url) => new WebSocket(url),
    private readonly timerFactory: TimerFactory = (callback, delay) =>
      window.setTimeout(callback, delay),
  ) {}

  async pair(
    endpoint: Endpoint,
    pairingKey: string,
    deviceName: string,
  ): Promise<string> {
    this.disconnect();
    await this.open(endpoint, "connecting");
    await this.hello();
    const result = await this.request<{ deviceToken: string }>("pair", {
      pairingKey: pairingKey.trim(),
      deviceName: deviceName.trim(),
    });
    return result.deviceToken;
  }

  async start(
    endpoint: Endpoint,
    token: string,
    onReady: (reconnected: boolean) => Promise<void>,
  ): Promise<void> {
    this.disconnect();
    this.endpoint = endpoint;
    this.token = token;
    this.onReady = onReady;
    this.reconnectEnabled = true;
    this.hasConnected = false;
    await this.connectAuthenticated(false);
  }

  async request<T extends Record<string, unknown>>(
    type: string,
    payload: Record<string, unknown> = {},
  ): Promise<T> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new ForemanError("Foreman is not connected", "disconnected");
    }
    const id = `web-${++this.sequence}`;
    const message = JSON.stringify({
      version: PROTOCOL_VERSION,
      id,
      type,
      payload,
    });
    if (new TextEncoder().encode(message).byteLength > MAX_FRAME_BYTES) {
      throw new ForemanError("Message exceeds Foreman's 16 MiB frame limit", "frameTooLarge");
    }
    return new Promise<T>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new ForemanError("Foreman did not respond in time", "timeout"));
      }, 120_000);
      this.pending.set(id, {
        resolve: (response) => resolve(response.payload as T),
        reject,
        timeout,
      });
      try {
        this.socket!.send(message);
      } catch (error) {
        window.clearTimeout(timeout);
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error("Send failed"));
      }
    });
  }

  disconnect(): void {
    this.reconnectEnabled = false;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.generation += 1;
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "Client disconnect");
    this.rejectPending(new ForemanError("Disconnected", "disconnected"));
    this.hooks.onState("disconnected");
  }

  private async connectAuthenticated(reconnected: boolean): Promise<void> {
    if (!this.endpoint) return;
    try {
      await this.open(this.endpoint, reconnected ? "reconnecting" : "connecting");
      await this.hello();
      await this.request("authenticate", { deviceToken: this.token });
      this.reconnectAttempt = 0;
      this.hooks.onState("connected");
      const wasConnected = this.hasConnected;
      this.hasConnected = true;
      await this.onReady?.(reconnected || wasConnected);
    } catch (error) {
      if (!this.reconnectEnabled) throw error;
      this.scheduleReconnect(error instanceof Error ? error.message : "Connection failed");
      if (!reconnected && !this.hasConnected) throw error;
    }
  }

  private async hello(): Promise<void> {
    const hello = await this.request<HelloPayload & Record<string, unknown>>("hello");
    if (hello.protocolVersion !== PROTOCOL_VERSION) {
      throw new ForemanError("This Foreman service uses an incompatible protocol", "incompatibleProtocol");
    }
    this.hooks.onHello?.(hello);
  }

  private open(endpoint: Endpoint, state: ConnectionState): Promise<void> {
    this.hooks.onState(state);
    const generation = ++this.generation;
    const previous = this.socket;
    if (previous && previous.readyState < WebSocket.CLOSING) previous.close(1000, "Reconnect");
    return new Promise((resolve, reject) => {
      let settled = false;
      const socket = this.socketFactory(endpoint.wsUrl);
      this.socket = socket;
      socket.onopen = () => {
        settled = true;
        resolve();
      };
      socket.onmessage = (event) => {
        if (generation === this.generation) this.receive(event.data);
      };
      socket.onerror = () => {
        if (!settled) {
          settled = true;
          reject(new ForemanError("Cannot connect to Foreman", "unavailable"));
        }
      };
      socket.onclose = (event) => {
        if (generation !== this.generation) return;
        this.socket = null;
        if (event.code === 4003) {
          const detail = "This client token was revoked. Pair this browser again to reconnect.";
          this.reconnectEnabled = false;
          this.rejectPending(new ForemanError(detail, "unauthorized"));
          this.hooks.onState("disconnected", detail);
          this.hooks.onAuthenticationRejected?.(detail);
          return;
        }
        const detail = settled ? "Connection lost" : "Cannot connect to Foreman";
        if (!settled) {
          settled = true;
          reject(new ForemanError(detail, "unavailable"));
        }
        this.rejectPending(new ForemanError(detail, "disconnected"));
        if (this.reconnectEnabled) this.scheduleReconnect(detail);
        else this.hooks.onState("disconnected", detail);
      };
    });
  }

  private receive(raw: unknown): void {
    if (typeof raw !== "string") return;
    let message: unknown;
    try {
      message = JSON.parse(raw);
    } catch {
      this.hooks.onState("disconnected", "Foreman sent malformed JSON");
      return;
    }
    if (!isWireMessage(message)) {
      this.hooks.onState("disconnected", "Foreman sent an incompatible message");
      return;
    }
    const id = message.id;
    if (id) {
      const pending = this.pending.get(id);
      if (pending) {
        this.pending.delete(id);
        window.clearTimeout(pending.timeout);
        if (message.type === "error") {
          const payload = message.payload as { code?: string; message?: string };
          pending.reject(
            new ForemanError(payload.message ?? "Request failed", payload.code ?? "requestFailed"),
          );
        } else {
          pending.resolve(message);
        }
        return;
      }
    }
    this.hooks.onEvent(message);
  }

  private scheduleReconnect(detail: string): void {
    if (!this.reconnectEnabled || this.reconnectTimer !== null) return;
    this.hooks.onState("reconnecting", detail);
    const delays = [500, 1_000, 2_000, 5_000, 10_000];
    const delay = delays[Math.min(this.reconnectAttempt++, delays.length - 1)];
    this.reconnectTimer = this.timerFactory(() => {
      this.reconnectTimer = null;
      void this.connectAuthenticated(true);
    }, delay);
  }

  private rejectPending(error: Error): void {
    this.pending.forEach((pending) => {
      window.clearTimeout(pending.timeout);
      pending.reject(error);
    });
    this.pending.clear();
  }
}
