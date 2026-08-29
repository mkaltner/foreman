import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { UnifiedDashboard } from "./UnifiedDashboard";
import type { StoredHost } from "./storage";
import type { HostOverviewSnapshot } from "./unified";

const host = (id: string): StoredHost => ({
  id, displayName: `Host ${id}`, host: `${id}.local`, tcpPort: 8765, webPort: 8766,
  deviceToken: `token-${id}`, pairedAt: 1, lastConnectedAt: 1000, lastKnownStatus: "disconnected",
  runtimeMode: null, isDefault: id === "one",
});

describe("unified dashboard", () => {
  it("shows five hosts, marks cached data stale, and navigates attention with compound identity", () => {
    const hosts = ["one", "two", "three", "four", "five"].map(host);
    const snapshot: HostOverviewSnapshot = {
      hostId: "two", observedAt: 2000, connection: "disconnected", foremanVersion: "1", codexVersion: "2",
      runtimeMode: "fallback", runtimeConnected: true, active: 1, waiting: 1, failed: 0,
      oldestTurn: { hostId: "two", sessionId: "same", title: "Collision", startedAt: 100 },
      latestCompletion: null, latestActivity: 500,
      attention: [{ hostId: "two", sessionId: "same", approvalId: "apr", sessionTitle: "Collision", repository: "/work/repo", type: "approval", startedAt: 200 }],
    };
    const open = vi.fn();
    const reconnect = vi.fn();
    render(<UnifiedDashboard hosts={hosts} activeHostId="one" snapshots={new Map([["two", snapshot]])} onOpenHost={vi.fn()} onOpenSession={open} onReconnect={reconnect} onEdit={vi.fn()} onForget={vi.fn()} />);
    expect(screen.getAllByText(/Stale snapshot/)).toHaveLength(5);
    expect(screen.getByText("0/5")).toBeInTheDocument();
    expect(screen.getByText("Foreman-managed Codex runtime")).toBeInTheDocument();
    expect(screen.queryByText("Fallback")).not.toBeInTheDocument();
    const attentionOpen = screen.getAllByRole("button", { name: "Open" }).at(-1)!;
    fireEvent.click(attentionOpen);
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ hostId: "two", sessionId: "same" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Reconnect" })[1]);
    expect(reconnect).toHaveBeenCalledWith("two");
  });

  it("opens a live attention item on its exact host and session", () => {
    const live: HostOverviewSnapshot = {
      hostId: "one", observedAt: Date.now(), connection: "connected", foremanVersion: "1", codexVersion: "2",
      runtimeMode: "shared", runtimeConnected: true, active: 0, waiting: 1, failed: 0,
      oldestTurn: null, latestCompletion: null, latestActivity: Date.now(),
      attention: [{ hostId: "one", sessionId: "same", sessionTitle: "Needs input", repository: "/repo", type: "input", startedAt: Date.now() }],
    };
    const open = vi.fn();
    render(<UnifiedDashboard hosts={[host("one")]} activeHostId="one" snapshots={new Map([["one", live]])} onOpenHost={vi.fn()} onOpenSession={open} onReconnect={vi.fn()} onEdit={vi.fn()} onForget={vi.fn()} />);
    fireEvent.click(screen.getAllByRole("button", { name: "Open" }).at(-1)!);
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ hostId: "one", sessionId: "same", type: "input" }));
  });
});
