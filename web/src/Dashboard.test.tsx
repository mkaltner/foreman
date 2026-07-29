import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Dashboard, ElapsedTime } from "./Dashboard";
import type { ServiceStatus, SessionSummary } from "./protocol";

const now = 1_720_000_000_000;
const status: ServiceStatus = {
  foremanVersion: "0.1.0-alpha.3",
  connected: true,
  uptimeSeconds: 1234,
  codex: {
    connected: true,
    mode: "fallback",
    runtimeStatus: "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE",
    version: "0.145.0",
    lastCommunication: new Date(now).toISOString(),
  },
  listeners: { tcpPort: 8765, webPort: 8766 },
  repositoryRoot: "/projects",
  activeBrowserConnections: 2,
};
const sessions: SessionSummary[] = [
  {
    id: "active",
    repository: "/projects/foreman",
    title: "Build dashboard",
    status: "working",
    activeTurnId: "turn-1",
    activeTurnStartedAt: now / 1000 - 12,
    activityLabel: "Running tests",
    model: "gpt-5.6",
    reasoningEffort: "high",
    accessLevel: "auto",
    lastActivity: now / 1000,
  },
  {
    id: "waiting",
    repository: "/projects/foreman",
    title: "Deploy release",
    status: "waiting",
    activeTurnId: "turn-2",
    activeTurnStartedAt: now / 1000 - 90,
    waitType: "approval",
    waitDescription: "Approval is required in another compatible Codex client.",
    lastActivity: now / 1000,
  },
  {
    id: "failed",
    repository: "/projects/other",
    title: "Run checks",
    status: "failed",
    failureSummary: "Tests failed safely",
    terminalAt: now / 1000 - 30,
    turnDurationMs: 5000,
  },
];

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe("monitoring dashboard", () => {
  it("renders health, active work, approval waits, failures, and repository groups", () => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
    render(<Dashboard sessions={sessions} serviceStatus={status} connection="connected" disabled={false} onOpen={vi.fn()} onInterrupt={vi.fn()} onRefresh={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "Operational summary" })).toHaveTextContent("Work at a glance");
    expect(screen.getByText("Foreman-owned fallback runtime")).toBeInTheDocument();
    expect(screen.getByText("SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Needs attention" })).toBeInTheDocument();
    expect(screen.getByText("Waiting for approval")).toBeInTheDocument();
    expect(screen.getByText("Tests failed safely")).toBeInTheDocument();
    expect(screen.getByText(/approval and structured input must currently/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Active work" })).toBeInTheDocument();
    expect(screen.getAllByText("foreman").length).toBeGreaterThan(0);
  });

  it("filters locally and dismisses failures without deleting a session", () => {
    const open = vi.fn();
    render(<Dashboard sessions={sessions} serviceStatus={status} connection="connected" disabled={false} onOpen={open} onInterrupt={vi.fn()} onRefresh={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Failed" }));
    expect(screen.getAllByText("Run checks")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText("Run checks")).not.toBeInTheDocument();
    expect(open).not.toHaveBeenCalled();
    expect(localStorage.getItem("foreman.dashboard.v1")).toContain("failed");
  });

  it("uses one shared interval for multiple elapsed turn clocks", () => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
    const interval = vi.spyOn(window, "setInterval");
    render(<><ElapsedTime startedAt={now / 1000 - 12} /><ElapsedTime startedAt={now / 1000 - 74} /></>);
    expect(interval).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByText("13s")).toBeInTheDocument();
    expect(screen.getByText("1m 15s")).toBeInTheDocument();
  });

  it("distinguishes shared Desktop, fallback, and unavailable runtime health", () => {
    const props = { sessions: [], connection: "connected" as const, disabled: false, onOpen: vi.fn(), onInterrupt: vi.fn(), onRefresh: vi.fn() };
    const { rerender } = render(<Dashboard {...props} serviceStatus={{ ...status, codex: { ...status.codex, mode: "shared", runtimeStatus: "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE" } }} />);
    expect(screen.getByText("Shared Desktop runtime")).toBeInTheDocument();
    expect(screen.queryByText("SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE")).not.toBeInTheDocument();
    rerender(<Dashboard {...props} serviceStatus={{ ...status, codex: { ...status.codex, connected: false, mode: "unavailable" } }} />);
    expect(screen.getByText("Codex unavailable")).toBeInTheDocument();
    expect(screen.getByText("Runtime needs attention")).toBeInTheDocument();
  });

  it("shows freshness, client counts, route details, and runtime disclosure", () => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
    render(<Dashboard sessions={[sessions[0]]} repositories={[{ id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false }]} serviceStatus={{ ...status, activeTcpConnections: 1, codex: { ...status.codex, mode: "shared", lastEvent: new Date(now - 3000).toISOString(), lastSuccessfulRequest: new Date(now - 8000).toISOString(), attachedAt: new Date(now - 60_000).toISOString(), loadedThreadCount: 4, subscribedThreadCount: 2, ownedByForeman: false, appServerPid: 123 } }} connection="connected" disabled={false} onOpen={vi.fn()} onInterrupt={vi.fn()} onRefresh={vi.fn()} />);
    expect(screen.getAllByText("Last Codex event: 3s ago")).toHaveLength(2);
    expect(screen.getByText("2 browser · 1 Android")).toBeInTheDocument();
    expect(screen.getByText("4 loaded · 2 subscribed")).toBeInTheDocument();
    expect(screen.getAllByText("gpt-5.6 · High · auto")).toHaveLength(2);
    fireEvent.click(screen.getByText("Runtime details"));
    expect(screen.getByText("123")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Repositories" })).toBeInTheDocument();
  });

  it("lists paired clients and confirms token revocation", async () => {
    const revoke = vi.fn().mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<Dashboard sessions={[]} serviceStatus={status} pairedClients={[
      { id: "browser", name: "Office browser", type: "browser", pairedAt: new Date(now - 60_000).toISOString(), connected: true, connectionCount: 1, current: true },
      { id: "phone", name: "Pixel", type: "android", pairedAt: new Date(now - 120_000).toISOString(), connected: false, connectionCount: 0, current: false },
    ]} connection="connected" disabled={false} onOpen={vi.fn()} onInterrupt={vi.fn()} onRefresh={vi.fn()} onRevokeClient={revoke} />);

    fireEvent.click(screen.getByText(/Connected clients and paired tokens/));
    expect(screen.getByText("Office browser")).toBeInTheDocument();
    expect(screen.getByText("Pixel")).toBeInTheDocument();
    expect(screen.getByText(/Browser · 1 connection · This browser/)).toBeInTheDocument();
    expect(screen.getByText(/Android · Not connected/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revoke token for Office browser" }));
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("sign out this browser immediately"));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith(expect.objectContaining({ id: "browser" })));
  });

  it("returns a dismissed attention item after a material status change", () => {
    const waiting = sessions[1];
    const props = { serviceStatus: status, connection: "connected" as const, disabled: false, onOpen: vi.fn(), onInterrupt: vi.fn(), onRefresh: vi.fn() };
    const { rerender } = render(<Dashboard {...props} sessions={[waiting]} />);
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText("Deploy release")).not.toBeInTheDocument();
    rerender(<Dashboard {...props} sessions={[{ ...waiting, status: "working", waitType: null, lastActivity: Date.now() / 1000 }]} />);
    expect(screen.getAllByText("Deploy release").length).toBeGreaterThan(0);
  });
});
