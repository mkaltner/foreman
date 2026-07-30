import { afterEach, describe, expect, it, vi } from "vitest";
import type { ClientHooks, Endpoint } from "./client";
import type { StoredHost } from "./storage";
import { UnifiedHostConnections, type OverviewClient } from "./unified-client";

const host = (id: string): StoredHost => ({
  id, displayName: id, host: `${id}.local`, tcpPort: 8765, webPort: 8766, deviceToken: id,
  pairedAt: 1, lastConnectedAt: null, lastKnownStatus: "disconnected", runtimeMode: null, isDefault: false,
});

class FakeOverviewClient implements OverviewClient {
  disconnected = false;
  constructor(private hooks: ClientHooks, private started: string[]) {}
  async start(endpoint: Endpoint, _token: string, onReady: (reconnected: boolean) => Promise<void>) {
    this.started.push(endpoint.host);
    this.hooks.onState("connected");
    await onReady(false);
  }
  async request<T extends Record<string, unknown>>(type: string): Promise<T> {
    const payload = type === "session.list" ? { sessions: [] }
      : type === "approval.list" ? { approvals: [] }
        : { foremanVersion: "1", connected: true, uptimeSeconds: 1, codex: { connected: true, mode: "shared", runtimeStatus: "ready" }, listeners: { tcpPort: 8765 }, repositoryRoot: "/" };
    return payload as unknown as T;
  }
  disconnect() { this.disconnected = true; this.hooks.onState("disconnected"); }
}

describe("web unified host sockets", () => {
  afterEach(() => vi.useRealTimers());

  it("keeps three background sockets beside the active host and rotates the fifth host", async () => {
    vi.useFakeTimers();
    const started: string[] = [];
    const clients: FakeOverviewClient[] = [];
    const tested = new UnifiedHostConnections(vi.fn(), (hooks) => {
      const client = new FakeOverviewClient(hooks, started);
      clients.push(client);
      return client;
    });
    tested.start(["one", "two", "three", "four", "five"].map(host), "one");
    await vi.runAllTicks();
    expect(started).toEqual(["two.local", "three.local", "four.local"]);
    expect(clients.filter(({ disconnected }) => !disconnected)).toHaveLength(3);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(started).toContain("five.local");
    expect(clients.filter(({ disconnected }) => !disconnected)).toHaveLength(3);
    tested.reconnect("two");
    await vi.runAllTicks();
    expect(started.filter((value) => value === "two.local")).toHaveLength(2);
    expect(clients.filter(({ disconnected }) => !disconnected)).toHaveLength(3);
    tested.stop();
  });
});
