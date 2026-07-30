import { describe, expect, it } from "vitest";
import type { ApprovalRequest, InputRequest, ServiceStatus, SessionSummary } from "./protocol";
import { aggregateHostSnapshots, liveBackgroundHostIds, projectHostSnapshot, sessionIdentityKey } from "./unified";

const status: ServiceStatus = {
  foremanVersion: "0.1.0",
  connected: true,
  uptimeSeconds: 10,
  codex: { connected: true, mode: "shared", runtimeStatus: "ready", version: "1.2.3" },
  listeners: { tcpPort: 8765, webPort: 8766 },
  repositoryRoot: "/work",
};

const session = (id: string, state: string, startedAt: number): SessionSummary => ({
  id,
  repository: `/work/${id}`,
  title: id,
  status: state,
  activeTurnStartedAt: startedAt,
  lastActivity: startedAt + 10,
  terminalAt: ["completed", "failed"].includes(state) ? startedAt + 20 : null,
});

describe("unified multi-host projection", () => {
  it("aggregates counts and global oldest/latest values", () => {
    const home = projectHostSnapshot("home", [session("a", "working", 100), session("done", "completed", 500)], [], status, "connected", 1_000_000);
    const work = projectHostSnapshot("work", [session("b", "waiting", 200), session("bad", "failed", 700)], [], status, "connected", 1_000_000);
    const totals = aggregateHostSnapshots(["home", "work"], new Map([["home", home], ["work", work]]));
    expect(totals).toMatchObject({ hosts: 2, connectedHosts: 2, staleHosts: 0, active: 1, waiting: 1, failed: 1 });
    expect(totals.oldestTurn).toMatchObject({ hostId: "home", sessionId: "a" });
    expect(totals.latestCompletion).toMatchObject({ hostId: "work", sessionId: "bad" });
  });

  it("isolates colliding session IDs by host identity", () => {
    const home = projectHostSnapshot("home", [session("same", "working", 100)], [], status, "connected");
    const work = projectHostSnapshot("work", [session("same", "failed", 200)], [], status, "connected");
    expect(sessionIdentityKey(home.oldestTurn!)).not.toBe(sessionIdentityKey(work.attention[0]));
    expect(work.attention[0]).toMatchObject({ hostId: "work", sessionId: "same", type: "failed" });
  });

  it("projects approval navigation with the host and session", () => {
    const approval: ApprovalRequest = {
      id: "apr-1", sessionId: "same", type: "command", title: "Approve", createdAt: 300,
      status: "pending", availableDecisions: [],
    };
    const snapshot = projectHostSnapshot("work", [session("same", "waiting", 200)], [approval], status, "connected");
    expect(snapshot.attention).toEqual([expect.objectContaining({ hostId: "work", sessionId: "same", approvalId: "apr-1" })]);
  });

  it("distinguishes structured input from approvals in needs-attention projection", () => {
    const input: InputRequest = {
      id: "inp-1", sessionId: "same", source: "mcp", title: "Input", fields: [], supported: false,
      canDecline: true, canCancel: true, createdAt: 301, status: "pending",
    };
    const snapshot = projectHostSnapshot("work", [session("same", "waiting", 200)], [], status, "connected", 1_000_000, [input]);
    expect(snapshot.attention).toEqual([expect.objectContaining({ approvalId: "inp-1", type: "input" })]);
  });

  it("bounds web sockets and rotates five saved hosts without including the active host", () => {
    const ids = ["one", "two", "three", "four", "five"];
    expect(liveBackgroundHostIds(ids, "one", 0)).toEqual(["two", "three", "four"]);
    expect(liveBackgroundHostIds(ids, "one", 1)).toEqual(["three", "four", "five"]);
    expect(liveBackgroundHostIds(ids, "one", 2)).toEqual(["four", "five", "two"]);
  });
});
