import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, { AccountUsageDock, appShellClassName, ConversationView, LinkedUserText, Markdown, NewSessionDialog, ProviderSettings, RouteSelect, SessionList, SetupView, reconcileSessionPending, sessionActionRequest, workspaceFileTarget } from "./App";
import type { ApprovalRequest, SessionSummary } from "./protocol";
import { inferPagePort } from "./client";
import { DEFAULT_SESSION_FILTERS } from "./session-search";
import { loadHostRegistry, loadRememberedSession, loadSessionSearch, saveHostRegistry, saveRememberedSession, type StoredHost } from "./storage";

const clientMock = vi.hoisted(() => ({
  pair: vi.fn(),
  start: vi.fn(),
  request: vi.fn(),
  disconnect: vi.fn(),
  onState: undefined as undefined | ((state: "connected") => void),
  onEvent: undefined as undefined | ((message: unknown) => void),
}));

vi.mock("./client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./client")>();
  return {
    ...actual,
    ForemanWebClient: class {
      constructor(options: { onState: (state: "connected") => void; onEvent: (message: unknown) => void }) {
        clientMock.onState = options.onState;
        clientMock.onEvent = options.onEvent;
      }
      pair = clientMock.pair;
      start = clientMock.start;
      request = clientMock.request;
      disconnect = clientMock.disconnect;
    },
  };
});

vi.mock("./unified-client", () => ({
  UnifiedHostConnections: class {
    start() {}
    stop() {}
    reconnect() {}
  },
}));

function storedHost(id: string, displayName: string, isDefault: boolean): StoredHost {
  return {
    id,
    displayName,
    host: `${id}.local`,
    tcpPort: 8765,
    webPort: 8766,
    deviceToken: `token-${id}`,
    pairedAt: 1,
    lastConnectedAt: null,
    lastKnownStatus: "disconnected",
    runtimeMode: null,
    isDefault,
  };
}

function forgetButton(displayName: string): HTMLButtonElement {
  const card = [...document.querySelectorAll<HTMLElement>(".saved-host")].find(
    (candidate) => candidate.querySelector("strong")?.textContent === displayName,
  );
  if (!card) throw new Error(`Saved host ${displayName} was not rendered`);
  return within(card).getByRole("button", { name: "Forget" });
}

function mockConnectedState(
  sessions: SessionSummary[],
  providers: Array<{ id: "codex" | "claude-code"; displayName: string; enabled: boolean; available: boolean; capabilities?: string[] }> = [
    { id: "codex", displayName: "Codex", enabled: true, available: true },
  ],
  read: (session: SessionSummary) => Promise<{ session: SessionSummary }> = async (session) => ({ session }),
  failedSessionLists: ReadonlySet<"codex" | "claude-code"> = new Set(),
): void {
  clientMock.start.mockImplementation(async (
    _endpoint: unknown,
    _token: string,
    onReady: (reconnected: boolean) => Promise<void>,
  ) => {
    clientMock.onState?.("connected");
    return onReady(false);
  });
  clientMock.request.mockImplementation(async (type: string, payload?: Record<string, unknown>) => {
    switch (type) {
      case "provider.list":
        return { providers: providers.map((provider) => ({ ...provider, capabilities: provider.capabilities ?? [], limitations: [] })) };
      case "approval.list": return { approvals: [] };
      case "input.list": return { inputs: [] };
      case "provider.session.list":
        if (failedSessionLists.has(payload?.provider as "codex" | "claude-code")) {
          throw new Error("Provider session list is temporarily unavailable");
        }
        return { sessions: sessions.filter((session) =>
          (session.provider ?? "codex") === payload?.provider &&
          Boolean(session.archived) === (payload?.scope === "archived")
        ) };
      case "provider.session.read": {
        const session = sessions.find((candidate) =>
          candidate.id === payload?.sessionId && (candidate.provider ?? "codex") === payload?.provider
        );
        if (!session) throw new Error("Session is missing");
        return read(session);
      }
      case "session.restore": {
        const session = sessions.find((candidate) =>
          candidate.id === payload?.sessionId && (candidate.provider ?? "codex") === "codex"
        );
        if (!session) throw new Error("Session is missing");
        return { restored: true, session: { ...session, archived: false, readOnly: false, capabilities: ["session.read", "session.archive", "session.delete"] } };
      }
      case "model.list": return { models: [] };
      case "access.list": return { levels: [] };
      case "provider.model.list": return { models: [] };
      case "provider.permission.list": return { modes: [] };
      case "service.status": return {
        foremanVersion: "test",
        connected: true,
        uptimeSeconds: 1,
        repositoryRoot: "/projects",
        codex: { connected: true, mode: "shared", runtimeStatus: "ready" },
        listeners: { tcpPort: 8765, webPort: 8766 },
      };
      case "usage.status": return { providers: {} };
      case "repository.list": return { repositories: [] };
      case "client.list": return { clients: [] };
      default: return {};
    }
  });
}

describe("host navigation history", () => {
  const home = storedHost("home", "Home", true);
  const work = storedHost("work", "Work", false);

  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState(null, "", "/");
    clientMock.pair.mockReset().mockResolvedValue("paired-token");
    clientMock.start.mockReset().mockResolvedValue(undefined);
    clientMock.request.mockReset().mockResolvedValue({});
    clientMock.disconnect.mockReset();
    clientMock.onState = undefined;
    clientMock.onEvent = undefined;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    window.history.replaceState(null, "", "/");
  });

  it("opens the sessions root after pairing a host", async () => {
    render(<App />);

    fireEvent.change(screen.getByLabelText("Host"), { target: { value: "new.local" } });
    fireEvent.change(screen.getByLabelText("Pairing code"), { target: { value: "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "Connect" }));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Sessions" })).toBeInTheDocument());
    expect(window.location.pathname).toBe("/sessions");
    expect(new URLSearchParams(window.location.search).get("host")).toBe(loadHostRegistry().activeHostId);
  });

  it("opens the sessions root for the replacement host after forgetting the active host", async () => {
    saveHostRegistry({ hosts: [home, work], activeHostId: home.id });
    window.history.replaceState(null, "", `/settings?host=${home.id}`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<App />);

    fireEvent.click(forgetButton(home.displayName));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Sessions" })).toBeInTheDocument());
    expect(window.location.pathname).toBe("/sessions");
    expect(window.location.search).toBe(`?host=${work.id}`);
    expect(loadHostRegistry().activeHostId).toBe(work.id);
  });

  it("resumes the last session when Sessions is entered from Settings", async () => {
    const session: SessionSummary = {
      provider: "claude-code",
      id: "claude-thread",
      repositoryId: "repo",
      repository: "/projects/foreman",
      title: "Claude thread",
      status: "idle",
      messages: [],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/claude-code/${session.id}?host=${home.id}`);
    mockConnectedState([session], [
      { id: "codex", displayName: "Codex", enabled: true, available: true },
      { id: "claude-code", displayName: "Claude Code", enabled: true, available: true },
    ]);

    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
    fireEvent.click(screen.getByRole("button", { name: "Settings" }));
    fireEvent.click(screen.getByRole("button", { name: "Sessions" }));

    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
    expect(window.location.pathname).toBe(`/sessions/claude-code/${session.id}`);
  });

  it("keeps Color mode separate from the curated Theme selector and applies selection immediately", async () => {
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/settings?host=${home.id}`);
    render(<App />);

    expect(screen.getByRole("combobox", { name: "Color mode" })).toHaveValue("system");
    expect(screen.getByRole("button", { name: /High Contrast/ })).toHaveAttribute("aria-pressed", "false");
    const harbor = screen.getByRole("button", { name: /Harbor/ });
    expect(harbor).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(harbor);

    await waitFor(() => expect(document.documentElement.dataset.foremanTheme).toBe("harbor"));
    expect(harbor).toHaveAttribute("aria-pressed", "true");
    expect(localStorage.getItem(`foreman.appearance.v2.${home.id}`)).toContain('"themeId":"harbor"');
    expect(screen.queryByText("Accent")).not.toBeInTheDocument();
  });

  it("resumes the last session when Sessions is entered from Dashboard", async () => {
    const session: SessionSummary = { id: "dashboard-return", repository: "/repo", title: "Dashboard return", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    mockConnectedState([session]);

    render(<App />);
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    fireEvent.click(within(document.querySelector(".topbar nav")!).getByRole("button", { name: "Dashboard" }));
    await waitFor(() => expect(window.location.pathname).toBe("/dashboard"));
    fireEvent.click(screen.getByRole("button", { name: "Sessions" }));

    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
  });

  it("restores the last session after a reload or fresh root launch", async () => {
    const session: SessionSummary = { id: "thread-reopen", repository: "/repo", title: "Remember me", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    mockConnectedState([session]);

    const first = render(<App />);
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    first.unmount();
    window.history.replaceState(null, "", `/?host=${home.id}`);
    render(<App />);

    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
    expect(window.location.pathname).toBe(`/sessions/codex/${session.id}`);
  });

  it("deep-opens an archived transcript through the read-only provider scope after reload", async () => {
    const archived: SessionSummary = {
      provider: "codex",
      id: "archived-reopen",
      repository: "/projects/foreman",
      title: "Archived reopen",
      status: "idle",
      archived: true,
      readOnly: true,
      capabilities: ["session.read", "session.restore"],
      messages: [{ id: "answer", kind: "assistant", text: "Safe archived transcript" }],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: archived.id });
    window.history.replaceState(null, "", `/sessions/codex/${archived.id}?host=${home.id}&scope=archived`);
    mockConnectedState([archived], [{
      id: "codex",
      displayName: "Codex",
      enabled: true,
      available: true,
      capabilities: ["session.archived.list", "session.restore"],
    }]);

    const first = render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(archived.title));
    expect(screen.getByText("Archived · Read only")).toBeInTheDocument();
    expect(document.querySelector(".composer")).toBeNull();
    expect(clientMock.request).toHaveBeenCalledWith("provider.session.list", { provider: "codex", scope: "archived" });
    expect(clientMock.request).toHaveBeenCalledWith("provider.session.read", {
      provider: "codex",
      sessionId: archived.id,
      scope: "archived",
    });
    expect(clientMock.request).not.toHaveBeenCalledWith("session.resume", expect.anything());
    expect(clientMock.request).not.toHaveBeenCalledWith("provider.session.subscribe", expect.objectContaining({ sessionId: archived.id }));

    first.unmount();
    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(archived.title));
    expect(screen.getByText("Safe archived transcript")).toBeInTheDocument();
  });

  it("restores an archived card once and moves the same identity to normal scope", async () => {
    const archived: SessionSummary = {
      provider: "codex",
      id: "restore-once",
      repository: "/projects/foreman",
      title: "Restore once",
      status: "idle",
      archived: true,
      readOnly: true,
      capabilities: ["session.read", "session.restore"],
      messages: [],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: archived.id });
    window.history.replaceState(null, "", `/sessions/codex/${archived.id}?host=${home.id}&scope=archived`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    mockConnectedState([archived], [{
      id: "codex",
      displayName: "Codex",
      enabled: true,
      available: true,
      capabilities: ["session.archived.list", "session.restore"],
    }]);
    render(<App />);
    await screen.findByText("Archived · Read only");
    const restore = document.querySelector<HTMLButtonElement>(".archived-read-only button")!;

    fireEvent.click(restore);
    fireEvent.click(restore);

    await waitFor(() => expect(screen.queryByText("Archived · Read only")).not.toBeInTheDocument());
    expect(clientMock.request.mock.calls.filter(([type]) => type === "session.restore")).toHaveLength(1);
    expect(document.querySelectorAll(".session-card h3")).toHaveLength(1);
    await waitFor(() => expect(window.location.search).not.toContain("scope=archived"));
    expect(loadRememberedSession(home.id)).toEqual({ hostId: home.id, provider: "codex", sessionId: archived.id });
  });

  it("keeps normal discovery filters while a selected archive event precedes its response", async () => {
    const session: SessionSummary = {
      provider: "codex",
      id: "archive-selected",
      repository: "/projects/foreman",
      title: "Archive selected",
      status: "idle",
      capabilities: ["session.read", "session.archive", "session.delete"],
      messages: [],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    mockConnectedState([session], [{
      id: "codex",
      displayName: "Codex",
      enabled: true,
      available: true,
      capabilities: ["session.archived.list", "session.restore"],
    }]);
    const request = clientMock.request.getMockImplementation()!;
    let finishArchive: (() => void) | undefined;
    clientMock.request.mockImplementation((type: string, payload?: Record<string, unknown>) => {
      if (type !== "session.archive") return request(type, payload);
      session.archived = true;
      session.readOnly = true;
      clientMock.onEvent?.({
        version: 1,
        type: "session.event",
        payload: {
          provider: "codex",
          sessionId: session.id,
          event: { kind: "lifecycle", action: "archived" },
        },
      });
      return new Promise((resolve) => {
        finishArchive = () => resolve({ archived: true });
      });
    });

    const mounted = render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    const persistedFilters = loadSessionSearch(home.id);

    fireEvent.click(screen.getByRole("button", { name: "Archive" }));

    await waitFor(() => expect(window.location.pathname).toBe("/sessions"));
    expect(window.location.search).not.toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toBe(persistedFilters);
    expect(loadRememberedSession(home.id)).toBeNull();
    expect(screen.getByText("Select a session")).toBeInTheDocument();
    expect(screen.queryByText("Loading session…")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: session.title })).not.toBeInTheDocument();
    expect(finishArchive).toBeTypeOf("function");

    await act(async () => finishArchive?.());
    expect(window.location.search).not.toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toBe(persistedFilters);

    mounted.unmount();
    render(<App />);
    await screen.findByText("Select a session");
    expect(window.location.pathname).toBe("/sessions");
    expect(window.location.search).not.toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toBe(persistedFilters);
    expect(screen.queryByRole("heading", { name: session.title })).not.toBeInTheDocument();
  });

  it("preserves the open session and filters when another session is archived", async () => {
    const selected: SessionSummary = {
      provider: "codex",
      id: "still-selected",
      repository: "/projects/foreman",
      title: "Still selected",
      status: "idle",
      messages: [],
    };
    const archived: SessionSummary = {
      provider: "codex",
      id: "archive-other",
      repository: "/projects/foreman",
      title: "Archive other",
      status: "idle",
      capabilities: ["session.archive"],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${selected.id}?host=${home.id}&provider=codex`);
    mockConnectedState([selected, archived]);
    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(selected.title));
    await waitFor(() => expect(loadSessionSearch(home.id)).toContain("provider=codex"));
    const persistedFilters = loadSessionSearch(home.id);

    archived.archived = true;
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: {
        provider: "codex",
        sessionId: archived.id,
        event: { kind: "lifecycle", action: "archived" },
      },
    }));

    await waitFor(() => expect(screen.queryByRole("heading", { name: archived.title })).not.toBeInTheDocument());
    expect(document.querySelector(".conversation-header h1")).toHaveTextContent(selected.title);
    expect(window.location.pathname).toBe(`/sessions/codex/${selected.id}`);
    expect(window.location.search).not.toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toBe(persistedFilters);
    expect(loadRememberedSession(home.id)?.sessionId).toBe(selected.id);
  });

  it("closes a selected session archived by another client without changing filters", async () => {
    const session: SessionSummary = {
      provider: "codex",
      id: "externally-archived-selected",
      repository: "/projects/foreman",
      title: "Externally archived selected",
      status: "completed",
      lastActivity: Date.now() / 1000,
      messages: [],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(
      null,
      "",
      `/sessions/codex/${session.id}?host=${home.id}&provider=codex&repo=%2Fprojects%2Fforeman&status=completed&date=30d&sort=oldest`,
    );
    mockConnectedState([session]);
    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(session.title));
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    await waitFor(() => expect(loadSessionSearch(home.id)).toContain("provider=codex"));
    const persistedFilters = loadSessionSearch(home.id);

    session.archived = true;
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: {
        provider: "codex",
        sessionId: session.id,
        event: { kind: "lifecycle", action: "archived" },
      },
    }));

    await screen.findByText("Select a session");
    expect(window.location.pathname).toBe("/sessions");
    expect(window.location.search).not.toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toBe(persistedFilters);
    expect(loadRememberedSession(home.id)).toBeNull();
    expect(screen.queryByText("Loading session…")).not.toBeInTheDocument();
  });

  it("reconciles external archive and restore events across provider scopes", async () => {
    const session: SessionSummary = {
      provider: "codex",
      id: "external-lifecycle",
      repository: "/projects/foreman",
      title: "External lifecycle",
      status: "idle",
      capabilities: ["session.read", "session.archive", "session.delete"],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    mockConnectedState([session], [{
      id: "codex",
      displayName: "Codex",
      enabled: true,
      available: true,
      capabilities: ["session.archived.list", "session.restore"],
    }]);
    const mounted = render(<App />);
    await screen.findByRole("heading", { name: session.title });

    session.archived = true;
    session.readOnly = true;
    session.capabilities = ["session.read", "session.restore"];
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: { provider: "codex", sessionId: session.id, event: { kind: "lifecycle", action: "archived" } },
    }));
    await waitFor(() => expect(screen.queryByRole("heading", { name: session.title })).not.toBeInTheDocument());
    fireEvent.click(screen.getByText("Filters"));
    fireEvent.change(screen.getByLabelText("Sessions"), { target: { value: "archived" } });
    await waitFor(() => expect(screen.getByRole("heading", { name: session.title })).toBeInTheDocument());
    expect(document.querySelector(".status-pill.archived")).toBeInTheDocument();
    expect(window.location.search).toContain("scope=archived");
    expect(loadSessionSearch(home.id)).toContain("scope=archived");

    mounted.unmount();
    render(<App />);
    await waitFor(() => expect(screen.getByRole("heading", { name: session.title })).toBeInTheDocument());
    expect(document.querySelector(".status-pill.archived")).toBeInTheDocument();

    session.archived = false;
    session.readOnly = false;
    session.capabilities = ["session.read", "session.archive", "session.delete"];
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: { provider: "codex", sessionId: session.id, event: { kind: "lifecycle", action: "restored" } },
    }));
    await waitFor(() => expect(screen.queryByRole("heading", { name: session.title })).not.toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("Sessions"), { target: { value: "normal" } });
    await waitFor(() => expect(screen.getByRole("heading", { name: session.title })).toBeInTheDocument());
    expect(document.querySelector(".status-pill.archived")).not.toBeInTheDocument();
  });

  it("refreshes an open archived detail when an external restore omits its projection", async () => {
    const session: SessionSummary = {
      provider: "codex",
      id: "externally-restored-detail",
      repository: "/projects/foreman",
      title: "Externally restored detail",
      status: "idle",
      archived: true,
      readOnly: true,
      capabilities: ["session.read", "session.restore"],
      messages: [],
    };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(
      null,
      "",
      `/sessions/codex/${session.id}?host=${home.id}&scope=archived`,
    );
    mockConnectedState([session], [{
      id: "codex",
      displayName: "Codex",
      enabled: true,
      available: true,
      capabilities: ["session.archived.list", "session.restore"],
    }]);
    render(<App />);
    await screen.findByText("Archived · Read only");

    session.archived = false;
    session.readOnly = false;
    session.capabilities = ["session.read", "session.archive", "session.delete"];
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: {
        provider: "codex",
        sessionId: session.id,
        event: { kind: "lifecycle", action: "restored" },
      },
    }));

    await waitFor(() => expect(screen.queryByText("Archived · Read only")).not.toBeInTheDocument());
    expect(clientMock.request.mock.calls.filter(([type, payload]) =>
      type === "provider.session.read" &&
      payload?.sessionId === session.id &&
      payload?.scope !== "archived"
    )).toHaveLength(1);
    expect(window.location.pathname).toBe(`/sessions/codex/${session.id}`);
    expect(window.location.search).not.toContain("scope=archived");
  });

  it("uses server activity for live work and ignores metadata observation time", async () => {
    const session: SessionSummary = {
      id: "thread-timed",
      repository: "/repo",
      title: "Timed session",
      status: "idle",
      lastActivity: 1_000,
      messages: [],
    };
    vi.spyOn(Date, "now").mockReturnValue(2_000_000);
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    mockConnectedState([session]);
    render(<App />);

    const card = await screen.findByRole("heading", { name: session.title });
    expect(card.closest("article")).toHaveTextContent("16m ago");
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: {
        sessionId: session.id,
        event: {
          kind: "metadata",
          type: "thread/goal/cleared",
          observedAt: 1_990,
        },
      },
    }));
    expect(card.closest("article")).toHaveTextContent("16m ago");

    act(() => clientMock.onEvent?.({
      version: 1,
      type: "approval.requested",
      payload: {
        approval: {
          id: "approval-timed",
          sessionId: session.id,
          type: "command",
          title: "Approval required",
          createdAt: 1,
          status: "pending",
        },
        activityAt: 1_990,
        observedAt: 2_000,
      },
    }));
    await waitFor(() => expect(card.closest("article")).toHaveTextContent("Just now"));
  });

  it("keeps last sessions isolated while switching hosts", async () => {
    const homeSession: SessionSummary = { id: "home-thread", repository: "/home", title: "Home session", status: "idle", messages: [] };
    const workSession: SessionSummary = { id: "work-thread", provider: "claude-code", repositoryId: "work", repository: "/work", title: "Work session", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home, work], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: homeSession.id });
    saveRememberedSession({ hostId: work.id, provider: "claude-code", sessionId: workSession.id });
    window.history.replaceState(null, "", `/?host=${home.id}`);
    mockConnectedState([homeSession, workSession], [
      { id: "codex", displayName: "Codex", enabled: true, available: true },
      { id: "claude-code", displayName: "Claude Code", enabled: true, available: true },
    ]);

    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(homeSession.title));
    fireEvent.change(screen.getByLabelText("Saved host"), { target: { value: work.id } });

    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(workSession.title));
    expect(window.location.search).toContain(`host=${work.id}`);
  });

  it("clears a missing session after an authoritative synchronization", async () => {
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: "gone" });
    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    mockConnectedState([]);

    render(<App />);

    await screen.findByRole("heading", { name: "Sessions" });
    await waitFor(() => expect(loadRememberedSession(home.id)).toBeNull());
    expect(window.location.pathname).toBe("/sessions");
  });

  it("clears memory when its provider is disabled", async () => {
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "claude-code", sessionId: "disabled" });
    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    mockConnectedState([], [
      { id: "codex", displayName: "Codex", enabled: true, available: true },
      { id: "claude-code", displayName: "Claude Code", enabled: false, available: true },
    ]);

    render(<App />);

    await screen.findByRole("heading", { name: "Sessions" });
    await waitFor(() => expect(loadRememberedSession(home.id)).toBeNull());
  });

  it("keeps valid memory after transient read failure", async () => {
    const session: SessionSummary = { id: "retry", repository: "/repo", title: "Retry", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: session.id });
    window.history.replaceState(null, "", `/?host=${home.id}`);
    mockConnectedState([session], undefined, async () => { throw new Error("Connection closed"); });

    render(<App />);

    await screen.findByRole("heading", { name: "Sessions" });
    expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id);
  });

  it("does not erase memory during temporary provider unavailability", async () => {
    const session: SessionSummary = { id: "outage", repository: "/repo", title: "Outage", status: "idle", messages: [] };
    const providers = [{ id: "codex" as const, displayName: "Codex", enabled: true, available: true }];
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    mockConnectedState([session], providers);

    render(<App />);
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    providers[0].available = false;
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "provider.event",
      payload: { providers },
    }));

    await screen.findByText("Provider unavailable");
    expect(screen.getByText(session.title)).toBeInTheDocument();
    expect(screen.getByText("No recent activity")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    expect(loadRememberedSession(home.id)).toMatchObject({ provider: "codex", sessionId: session.id });
  });

  it("keeps other providers synchronized when one session list temporarily fails", async () => {
    const codex: SessionSummary = { id: "codex-ok", repository: "/repo", title: "Codex remains", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "claude-code", sessionId: "claude-retry" });
    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    mockConnectedState([codex], [
      { id: "codex", displayName: "Codex", enabled: true, available: true },
      { id: "claude-code", displayName: "Claude Code", enabled: true, available: true },
    ], async (session) => ({ session }), new Set(["claude-code"]));

    render(<App />);

    await screen.findByText(codex.title);
    expect(loadRememberedSession(home.id)?.sessionId).toBe("claude-retry");
  });

  it("clears remembered identity when the authoritative lifecycle removes the session", async () => {
    const session: SessionSummary = { id: "deleted", repository: "/repo", title: "Deleted", status: "idle", messages: [] };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    mockConnectedState([session]);

    render(<App />);
    await waitFor(() => expect(loadRememberedSession(home.id)?.sessionId).toBe(session.id));
    act(() => clientMock.onEvent?.({
      version: 1,
      type: "session.event",
      payload: {
        provider: "codex",
        sessionId: session.id,
        event: { kind: "lifecycle", action: "removed" },
      },
    }));

    await screen.findByRole("heading", { name: "Sessions" });
    expect(loadRememberedSession(home.id)).toBeNull();
    expect(window.location.pathname).toBe("/sessions");
  });

  it("resumes memory from /sessions while Dashboard and detail URLs remain explicit", async () => {
    const remembered: SessionSummary = { id: "remembered", repository: "/repo", title: "Remembered", status: "idle", messages: [] };
    const linked: SessionSummary = { ...remembered, id: "linked", title: "Linked" };
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: remembered.id });
    mockConnectedState([remembered, linked]);

    window.history.replaceState(null, "", `/sessions?host=${home.id}`);
    const resumedView = render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(remembered.title));
    resumedView.unmount();

    window.history.replaceState(null, "", `/dashboard?host=${home.id}`);
    const dashboardView = render(<App />);
    await waitFor(() => expect(window.location.pathname).toBe("/dashboard"));
    dashboardView.unmount();

    window.history.replaceState(null, "", `/sessions/codex/${linked.id}?host=${home.id}`);
    render(<App />);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(linked.title));
    expect(loadRememberedSession(home.id)?.sessionId).toBe(linked.id);
  });

  it("does not let an older restore override a newer Dashboard choice", async () => {
    const session: SessionSummary = { id: "slow", repository: "/repo", title: "Slow", status: "idle", messages: [] };
    let resolveRead!: (value: { session: SessionSummary }) => void;
    const read = new Promise<{ session: SessionSummary }>((resolve) => { resolveRead = resolve; });
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: session.id });
    window.history.replaceState(null, "", `/?host=${home.id}`);
    mockConnectedState([session], undefined, () => read);

    render(<App />);
    await waitFor(() => expect(clientMock.request).toHaveBeenCalledWith(
      "provider.session.read",
      expect.objectContaining({ sessionId: session.id }),
    ));
    fireEvent.click(within(document.querySelector(".topbar nav")!).getByRole("button", { name: "Dashboard" }));
    await act(async () => resolveRead({ session }));

    await waitFor(() => expect(window.location.pathname).toBe("/dashboard"));
    expect(document.querySelector(".conversation-header h1")).toBeNull();
  });

  it("keeps the active host while opening the sessions root after forgetting another host", async () => {
    saveHostRegistry({ hosts: [home, work], activeHostId: home.id });
    window.history.replaceState(null, "", `/settings?host=${home.id}`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<App />);

    fireEvent.click(forgetButton(work.displayName));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Sessions" })).toBeInTheDocument());
    expect(window.location.pathname).toBe("/sessions");
    expect(window.location.search).toBe(`?host=${home.id}`);
    expect(loadHostRegistry()).toMatchObject({
      activeHostId: home.id,
      hosts: [{ id: home.id }],
    });
  });

  it("restores an approval from the server when a conversation is reopened", async () => {
    const session: SessionSummary = {
      id: "thread-1",
      repository: "/projects/foreman",
      title: "Approval session",
      status: "waiting",
      attention: true,
      activeTurnId: "turn-1",
      messages: [],
    };
    const approval: ApprovalRequest = {
      id: "approval-1",
      sessionId: session.id,
      turnId: session.activeTurnId,
      type: "command",
      title: "Command requires approval",
      createdAt: 1,
      status: "pending",
      availableDecisions: [{ type: "accept", label: "Allow" }, { type: "decline", label: "Deny" }],
    };
    let approvalReads = 0;
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/sessions/codex/${session.id}?host=${home.id}`);
    clientMock.start.mockImplementation(async (
      _endpoint: unknown,
      _token: string,
      onReady: (reconnected: boolean) => Promise<void>,
    ) => onReady(false));
    clientMock.request.mockImplementation(async (type: string) => {
      switch (type) {
        case "provider.list": return { providers: [{ id: "codex", displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] }] };
        case "approval.list": return { approvals: approvalReads++ === 0 ? [] : [approval] };
        case "input.list": return { inputs: [] };
        case "provider.session.list": return { sessions: [session] };
        case "provider.session.read": return { session };
        case "model.list": return { models: [] };
        case "access.list": return { levels: [] };
        case "service.status": return { repositoryRoot: "/projects" };
        case "usage.status": return { providers: {} };
        case "repository.list": return { repositories: [] };
        case "client.list": return { clients: [] };
        default: return {};
      }
    });

    render(<App />);
    await screen.findByRole("button", { name: "‹ Sessions" });
    expect(screen.queryByText(approval.title)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "‹ Sessions" }));
    fireEvent.click(await screen.findByRole("heading", { name: session.title }));

    expect(await screen.findByText(approval.title)).toBeInTheDocument();
    expect(approvalReads).toBe(2);
  });

  it("does not let an obsolete session open overwrite newer navigation", async () => {
    const first: SessionSummary = {
      id: "thread-a",
      repository: "/projects/foreman",
      title: "Session A",
      status: "idle",
      messages: [],
    };
    const second: SessionSummary = { ...first, id: "thread-b", title: "Session B" };
    let resolveFirst!: (value: { session: SessionSummary }) => void;
    const firstRead = new Promise<{ session: SessionSummary }>((resolve) => { resolveFirst = resolve; });
    saveHostRegistry({ hosts: [home], activeHostId: home.id });
    window.history.replaceState(null, "", `/?host=${home.id}`);
    clientMock.start.mockImplementation(async (
      _endpoint: unknown,
      _token: string,
      onReady: (reconnected: boolean) => Promise<void>,
    ) => onReady(false));
    clientMock.request.mockImplementation(async (type: string, payload?: Record<string, unknown>) => {
      switch (type) {
        case "provider.list": return { providers: [{ id: "codex", displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] }] };
        case "approval.list": return { approvals: [] };
        case "input.list": return { inputs: [] };
        case "provider.session.list": return { sessions: [first, second] };
        case "provider.session.read": return payload?.sessionId === first.id ? firstRead : { session: second };
        case "model.list": return { models: [] };
        case "access.list": return { levels: [] };
        case "service.status": return { repositoryRoot: "/projects" };
        case "usage.status": return { providers: {} };
        case "repository.list": return { repositories: [] };
        case "client.list": return { clients: [] };
        default: return {};
      }
    });

    render(<App />);
    fireEvent.click((await screen.findAllByRole("heading", { name: first.title }))[0]);
    fireEvent.click(screen.getAllByRole("heading", { name: second.title })[0]);
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(second.title));

    await act(async () => { resolveFirst({ session: first }); });
    await waitFor(() => expect(document.querySelector(".conversation-header h1")).toHaveTextContent(second.title));
  });
});

describe("Foreman setup", () => {
  it("captures a display name and explicit web port for each host", () => {
    const onConnect = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<SetupView error="" busy={false} onConnect={onConnect} />);

    expect(screen.getByLabelText("Web port")).toHaveValue(String(inferPagePort()));
    expect([...container.querySelectorAll("form label")].map((label) => label.firstChild?.textContent))
      .toEqual(["Host", "Web port", "Host display name", "Pairing code", "Device name"]);
    fireEvent.change(screen.getByLabelText("Host"), {
      target: { value: "foreman.local" },
    });
    fireEvent.change(screen.getByLabelText("Pairing code"), {
      target: { value: "123456" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Connect" }));

    expect(onConnect).toHaveBeenCalledWith(
      {
        displayName: "foreman.local",
        host: "foreman.local",
        tcpPort: 8765,
        webPort: inferPagePort(),
        deviceName: "Web browser",
      },
      "123456",
    );
  });
});

describe("page scrolling", () => {
  it("uses a content-height shell only for settings", () => {
    expect(appShellClassName("settings")).toBe("app-shell settings-shell");
    expect(appShellClassName("dashboard")).toBe("app-shell");
    expect(appShellClassName("sessions")).toBe("app-shell");
    expect(appShellClassName("detail")).toBe("app-shell");
  });
});

describe("pending request restoration", () => {
  it("replaces stale session state while preserving newer live events", () => {
    const stale = { id: "stale", sessionId: "target", status: "pending" };
    const removedAfterResolution = { id: "removed", sessionId: "target", status: "pending" };
    const changed = { id: "changed", sessionId: "target", status: "pending" };
    const resolvedDuringRefresh = { ...changed, status: "resolved" };
    const arrivedDuringRefresh = { id: "new-live", sessionId: "target", status: "pending" };
    const otherSession = { id: "other", sessionId: "other-session", status: "pending" };
    const restored = { id: "restored", sessionId: "target", status: "pending" };

    expect(reconcileSessionPending(
      [otherSession, stale, resolvedDuringRefresh, arrivedDuringRefresh],
      [removedAfterResolution, restored, changed],
      "target",
      [removedAfterResolution, stale, changed],
    )).toEqual([
      otherSession,
      restored,
      resolvedDuringRefresh,
      arrivedDuringRefresh,
    ]);
  });
});

describe("Claude session deletion", () => {
  const session: SessionSummary = {
    provider: "claude-code",
    id: "claude-session",
    sessionId: "claude-session",
    repositoryId: "foreman",
    repository: "/projects/foreman",
    title: "Claude work",
    status: "resumable",
    source: "external",
    capabilities: ["session.read", "session.resume", "session.delete"],
  };

  it("uses the provider-aware destructive request with exact workspace identity", () => {
    expect(sessionActionRequest("delete", session)).toEqual({
      type: "provider.session.delete",
      payload: {
        provider: "claude-code",
        sessionId: "claude-session",
        repositoryId: "foreman",
        confirm: true,
      },
    });
    expect(() => sessionActionRequest("archive", session)).toThrow(/does not support session archive/i);
  });

  it("shows Delete but does not invent a Claude archive action", () => {
    const onAction = vi.fn();
    const onPin = vi.fn();
    const onHide = vi.fn();
    const sessionList = () => (
      <SessionList
        results={[{ session, pinned: false, hidden: false, matches: [] }]}
        filters={DEFAULT_SESSION_FILTERS}
        repositoryOptions={[]}
        searchLoading={false}
        searchError=""
        selectedId={null}
        selectedProvider="codex"
        disabled={false}
        onOpen={vi.fn()}
        onRefresh={vi.fn()}
        onNew={vi.fn()}
        onAction={onAction}
        onFilters={vi.fn()}
        onSearchNow={vi.fn()}
        onPin={onPin}
        onHide={onHide}
      />
    );
    render(sessionList());

    expect(screen.queryByRole("button", { name: "Archive" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Pin Claude work" }));
    fireEvent.click(screen.getByRole("button", { name: "Hide" }));
    expect(onPin).toHaveBeenCalledWith("claude-code", "claude-session");
    expect(onHide).toHaveBeenCalledWith("claude-code", "claude-session");
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onAction).toHaveBeenCalledWith("delete", session);
    const repositoryGroup = screen.getByRole("button", { name: /Workspace: \/projects\/foreman/ });
    expect(repositoryGroup).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(repositoryGroup);
    expect(repositoryGroup).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();

  });

  it("uses only task-usable providers for badges and safely labels unavailable historical sessions", () => {
    const codexSession: SessionSummary = { id: "codex-session", repository: "/repo", title: "Codex work", status: "idle" };
    const baseProps = {
      results: [{ session: codexSession, pinned: false, hidden: false, matches: [] }],
      filters: DEFAULT_SESSION_FILTERS,
      repositoryOptions: [],
      searchLoading: false,
      searchError: "",
      selectedId: null,
      selectedProvider: "codex" as const,
      disabled: false,
      onOpen: vi.fn(),
      onRefresh: vi.fn(),
      onNew: vi.fn(),
      onAction: vi.fn(),
      onFilters: vi.fn(),
      onSearchNow: vi.fn(),
      onPin: vi.fn(),
      onHide: vi.fn(),
    };
    const codex = { id: "codex" as const, displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] };
    const claude = { id: "claude-code" as const, displayName: "Claude Code", enabled: true, available: false, capabilities: [], limitations: [] };
    const view = render(<SessionList {...baseProps} providers={[codex]} providerCatalogLoaded />);

    expect(document.querySelector(".session-card .provider-badge")).toBeNull();
    view.rerender(<SessionList {...baseProps} providers={[codex, claude]} providerCatalogLoaded />);
    expect(document.querySelector(".session-card .provider-badge")).toBeNull();
    view.rerender(<SessionList {...baseProps} results={[{ session, pinned: false, hidden: false, matches: [] }]} providers={[codex, claude]} providerCatalogLoaded />);
    expect(document.querySelector(".session-card .provider-badge")).toHaveTextContent("Claude Code");
    expect(screen.getByText("Provider unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    view.rerender(<SessionList {...baseProps} providers={[codex, { ...claude, enabled: false }]} providerCatalogLoaded />);
    expect(document.querySelector(".session-card .provider-badge")).toBeNull();
    view.rerender(<SessionList {...baseProps} providers={[codex]} providerCatalogLoaded={false} />);
    expect(document.querySelector(".session-card .provider-badge")).toHaveTextContent("Codex");
  });
});

describe("session card repository context", () => {
  const repository: SessionSummary = {
    id: "repository-session",
    repository: "/projects/foreman/src",
    title: "Repository work",
    status: "working",
    lastActivity: 1_700_000_300,
  };
  const workspace: SessionSummary = {
    id: "workspace-session",
    repository: "/home/operator",
    title: "Workspace work",
    status: "idle",
    lastActivity: 1_700_000_200,
  };
  const results = [repository, workspace].map((session, index) => ({
    session,
    pinned: index === 0,
    hidden: false,
    matches: [],
  }));
  const baseProps = {
    results,
    repositories: [{ id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false }],
    repositoryRoot: "/projects",
    repositoryOptions: [],
    searchLoading: false,
    searchError: "",
    selectedId: null,
    selectedProvider: "codex" as const,
    disabled: false,
    onOpen: vi.fn(),
    onRefresh: vi.fn(),
    onNew: vi.fn(),
    onAction: vi.fn(),
    onFilters: vi.fn(),
    onSearchNow: vi.fn(),
    onPin: vi.fn(),
    onHide: vi.fn(),
  };

  it("removes repository and workspace rows from matching groups without removing other card content", () => {
    const view = render(<SessionList {...baseProps} filters={DEFAULT_SESSION_FILTERS} groupByRepository />);
    const repositoryCard = screen.getByRole("heading", { name: "Repository work" }).closest("article") as HTMLElement;
    const workspaceCard = screen.getByRole("heading", { name: "Workspace work" }).closest("article") as HTMLElement;

    expect(screen.getByRole("button", { name: /Collapse Repository: foreman/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Collapse Workspace: \/home\/operator/ })).toBeInTheDocument();
    expect(repositoryCard).toHaveClass("repository-metadata-suppressed");
    expect(workspaceCard).toHaveClass("repository-metadata-suppressed");
    expect(repositoryCard.querySelector(".repository")).toBeNull();
    expect(workspaceCard.querySelector(".repository")).toBeNull();
    expect(within(repositoryCard).getByText("Active")).toBeInTheDocument();
    expect(within(repositoryCard).getByLabelText("Unpin Repository work")).toBeInTheDocument();
    expect(repositoryCard.querySelector(".session-meta")).toBeInTheDocument();

    view.rerender(<SessionList {...baseProps} filters={DEFAULT_SESSION_FILTERS} groupByRepository={false} />);
    expect(within(screen.getByRole("heading", { name: "Repository work" }).closest("article") as HTMLElement).getByText("src")).toBeInTheDocument();
    expect(within(screen.getByRole("heading", { name: "Workspace work" }).closest("article") as HTMLElement).getByText("operator")).toBeInTheDocument();
  });

  it("keeps full identity in filtered results even when grouping is enabled", () => {
    render(<SessionList {...baseProps} filters={{ ...DEFAULT_SESSION_FILTERS, query: "work" }} groupByRepository />);

    expect(screen.getByText("/projects/foreman/src")).toBeInTheDocument();
    expect(screen.getByText("/home/operator")).toBeInTheDocument();
    expect(screen.getByLabelText("Unpin Repository work")).toBeInTheDocument();
  });

  it("preserves repository grouping and collapsed state in Archived scope", () => {
    const restore = vi.fn();
    const archivedResults = results.map((item) => ({
      ...item,
      session: {
        ...item.session,
        status: "idle",
        archived: true,
        readOnly: true,
        capabilities: ["session.read", "session.restore"],
      },
    }));
    render(<SessionList
      {...baseProps}
      results={archivedResults}
      filters={{ ...DEFAULT_SESSION_FILTERS, scope: "archived" }}
      groupByRepository
      onRestore={restore}
    />);

    const group = screen.getByRole("button", { name: /Collapse Repository: foreman/ });
    expect(group).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByText("Archived").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Archive" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("heading", { name: "Repository work" }).closest("article")!).getByRole("button", { name: "Restore" }));
    expect(restore).toHaveBeenCalledWith(expect.objectContaining({ id: "repository-session", archived: true }));
    fireEvent.click(group);
    expect(group).toHaveAttribute("aria-expanded", "false");
  });
});

describe("user message links", () => {
  it("opens safe bare links without interpreting other message text as markup", () => {
    render(<LinkedUserText text={'Review <b>literal</b> at https://example.com/pr/11.'} />);
    expect(screen.getByText("<b>literal</b>", { exact: false })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "https://example.com/pr/11" });
    expect(link).toHaveAttribute("href", "https://example.com/pr/11");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer noopener");
  });
});

describe("session context usage", () => {
  it("keeps per-session context in the conversation header", () => {
    render(<ConversationView
      session={{
        id: "usage-session",
        repository: "/projects/foreman",
        title: "Usage session",
        status: "idle",
        model: "gpt-test",
        reasoningEffort: "high",
        accessLevel: "auto",
        messages: [
          { id: "compact-1", kind: "compaction", description: "Context compacted", compactionTrigger: "auto", preTokens: 900_000, postTokens: 120_000, durationMs: 2_000 },
          { id: "user-1", kind: "user", text: "Hello", turnId: "turn-1" },
          { id: "assistant-1", kind: "assistant", text: "Hi", turnId: "turn-1" },
        ],
        tokenUsage: {
          total: { totalTokens: 2_500_000 },
          last: { totalTokens: 200_000, cachedInputTokens: 150_000, outputTokens: 800 },
          modelContextWindow: 1_000_000,
        },
      }}
      approvals={[]}
      models={[{ id: "gpt-test", displayName: "GPT Test", visible: true, isDefault: true, reasoningEfforts: ["high"] }]}
      accessLevels={[{ id: "auto", displayName: "Approve for me" }]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft=""
      onDraftChange={vi.fn()}
      onBack={vi.fn()}
      onRequest={vi.fn()}
      onError={vi.fn()}
    />);

    const trigger = screen.getByRole("button", { name: "Context usage, 80% left" });
    const header = trigger.closest("header");
    expect(header).toHaveClass("conversation-header");
    const status = within(header as HTMLElement).getByText("Idle");
    expect(status.compareDocumentPosition(trigger) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(trigger);

    const panel = screen.getByRole("complementary", { name: "Session info" });
    expect(within(panel).getByText("200k / 1m tokens")).toBeInTheDocument();
    expect(within(panel).getByText(/Conversation history normally compacts automatically/)).toBeInTheDocument();
    expect(within(panel).getByRole("meter", { name: "Context used" })).toHaveAttribute("aria-valuenow", "20");
    expect(within(panel).getByText("3 items · 1 turn")).toBeInTheDocument();
    expect(within(panel).getByText("Compactions").nextSibling).toHaveTextContent("1");
    expect(within(panel).getByText("Automatic · 900k → 120k")).toBeInTheDocument();
    expect(within(panel).getByText("2.5m total")).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("complementary", { name: "Session info" })).not.toBeInTheDocument();
  });

  it("shows account-wide limits beneath the session list", () => {
    render(<SessionList
      results={[]}
      accountUsage={{
        providers: {
          codex: {
            available: true,
            rateLimits: {
              limitId: "codex",
              primary: { usedPercent: 2, windowDurationMins: 300, resetsAt: 1_800_000_000 },
              secondary: { usedPercent: 11, windowDurationMins: 10_080, resetsAt: 1_800_086_400 },
            },
          },
          "claude-code": {
            available: true,
            experimental: true,
            observedAt: 1_800_000_000,
            rateLimits: {
              primary: { usedPercent: 15, windowDurationMins: 300, resetsAt: 1_800_000_000 },
              secondary: { usedPercent: 28, windowDurationMins: 10_080, resetsAt: 1_800_086_400 },
            },
          },
        },
      }}
      filters={DEFAULT_SESSION_FILTERS}
      repositoryOptions={[]}
      searchLoading={false}
      searchError=""
      selectedId={null}
      selectedProvider="codex"
      disabled={false}
      onOpen={vi.fn()}
      onRefresh={vi.fn()}
      onNew={vi.fn()}
      onAction={vi.fn()}
      onFilters={vi.fn()}
      onSearchNow={vi.fn()}
      onPin={vi.fn()}
      onHide={vi.fn()}
    />);

    const trigger = screen.getByRole("button", { name: "Account usage, Codex 89% left, Claude 72% left" });
    expect(trigger.closest(".session-pane")).toBeInTheDocument();
    fireEvent.click(trigger);
    const panel = screen.getByRole("complementary", { name: "Account usage" });
    expect(within(panel).getByText("Across providers")).toBeInTheDocument();
    expect(within(panel).getByRole("meter", { name: "Codex 5-hour limit used" })).toHaveAttribute("aria-valuenow", "2");
    expect(within(panel).getByRole("meter", { name: "Codex Weekly limit used" })).toHaveAttribute("aria-valuenow", "11");
    expect(within(panel).getByRole("meter", { name: "Claude 5-hour limit used" })).toHaveAttribute("aria-valuenow", "15");
    expect(within(panel).getByRole("meter", { name: "Claude Weekly limit used" })).toHaveAttribute("aria-valuenow", "28");
    expect(within(panel).getByText("Experimental")).toBeInTheDocument();
    fireEvent.focusIn(document.body);
    expect(screen.queryByRole("complementary", { name: "Account usage" })).not.toBeInTheDocument();
  });

  it("omits disabled and enabled-but-unavailable providers from account usage status", () => {
    render(<AccountUsageDock
      usage={{ providers: {
        codex: { available: true, rateLimits: { primary: { usedPercent: 12 } } },
        "claude-code": { available: true, rateLimits: { primary: { usedPercent: 34 } } },
      } }}
      providers={[
        { id: "codex", displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] },
        { id: "claude-code", displayName: "Claude Code", enabled: true, available: false, capabilities: [], limitations: [] },
      ]}
    />);

    expect(screen.getByRole("button", { name: "Account usage, 88% left" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account usage, 88% left" }));
    expect(screen.queryByText("Across providers")).not.toBeInTheDocument();
    expect(screen.queryByText("Codex")).not.toBeInTheDocument();
    expect(screen.queryByText("Claude")).not.toBeInTheDocument();
  });

  it("shows only Claude usage on a Claude-only host and no usage when neither provider is usable", () => {
    const usage = { providers: {
      codex: { available: true, rateLimits: { primary: { usedPercent: 12 } } },
      "claude-code": { available: true, rateLimits: { primary: { usedPercent: 34 } } },
    } };
    const codex = { id: "codex" as const, displayName: "Codex", enabled: true, available: false, capabilities: [], limitations: [] };
    const claude = { id: "claude-code" as const, displayName: "Claude Code", enabled: true, available: true, capabilities: [], limitations: [] };
    const view = render(<AccountUsageDock usage={usage} providers={[codex, claude]} />);

    expect(screen.getByRole("button", { name: "Account usage, 66% left" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account usage, 66% left" }));
    expect(screen.queryByText("Across providers")).not.toBeInTheDocument();
    expect(screen.queryByText("Codex")).not.toBeInTheDocument();

    view.rerender(<AccountUsageDock usage={usage} providers={[codex, { ...claude, available: false }]} />);
    expect(screen.queryByRole("button", { name: /Account usage/ })).not.toBeInTheDocument();
  });
});

describe("assistant workspace file links", () => {
  it("parses absolute paths with encoded spaces and optional source locations", () => {
    expect(workspaceFileTarget("/projects/My%20App/readme.md:28")).toEqual({
      path: "/projects/My App/readme.md",
      line: 28,
    });
    expect(workspaceFileTarget("docs/readme.md:28")).toBeNull();
    expect(workspaceFileTarget("https://example.com/readme.md:28")).toBeNull();
  });

  it("opens a linked workspace file at the requested line", async () => {
    const onRequest = vi.fn().mockResolvedValue({
      path: "/projects/My App/readme.md",
      content: "first\nsecond\nthird",
    });
    render(<ConversationView
      session={{
        id: "file-link",
        repository: "/projects/My App",
        title: "File link",
        status: "idle",
        messages: [{ id: "answer", kind: "assistant", text: "Open [the file](</projects/My App/readme.md:2>)." }],
      }}
      approvals={[]}
      models={[]}
      accessLevels={[]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft=""
      onDraftChange={vi.fn()}
      onBack={vi.fn()}
      onRequest={onRequest}
      onError={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("link", { name: "the file" }));

    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("workspace.file.read", {
      path: "/projects/My App/readme.md",
    }));
    expect(screen.getByRole("dialog", { name: "Workspace file /projects/My App/readme.md" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("second", { exact: false }).closest("span")).toHaveClass("selected");
  });

  it("previews Markdown files without a source location and toggles to source", async () => {
    const onRequest = vi.fn().mockResolvedValue({
      path: "/projects/readme.md",
      content: "# Rendered project\n\nThis is **Markdown**.\n\n| Feature | Status |\n| --- | --- |\n| Tables | Working |\n\n- [x] **Task lists**\n\n- **Status:** Complete",
    });
    render(<ConversationView
      session={{
        id: "markdown-preview",
        repository: "/projects",
        title: "Markdown preview",
        status: "idle",
        messages: [{ id: "answer", kind: "assistant", text: "Open [the readme](/projects/readme.md)." }],
      }}
      approvals={[]}
      models={[]}
      accessLevels={[]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft=""
      onDraftChange={vi.fn()}
      onBack={vi.fn()}
      onRequest={onRequest}
      onError={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("link", { name: "the readme" }));

    expect(await screen.findByRole("heading", { name: "Rendered project" })).toBeInTheDocument();
    expect(screen.getByText("Markdown")).toHaveProperty("tagName", "STRONG");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Feature" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Tables" })).toBeInTheDocument();
    const task = screen.getByRole("checkbox");
    expect(task).toBeChecked();
    expect(task).toBeDisabled();
    expect(task.closest("li")).toHaveClass("task-list-item");
    expect(task.parentElement).toHaveProperty("tagName", "P");
    expect(screen.getByRole("tab", { name: "Preview" })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("tab", { name: "Source" }));
    expect(screen.queryByRole("heading", { name: "Rendered project" })).not.toBeInTheDocument();
    expect(screen.getByText("# Rendered project", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");
  });

  it("keeps the newest file when reads complete out of order", async () => {
    type FileResult = { path: string; content: string };
    let resolveFirst!: (value: FileResult) => void;
    let resolveSecond!: (value: FileResult) => void;
    const first = new Promise<FileResult>((resolve) => { resolveFirst = resolve; });
    const second = new Promise<FileResult>((resolve) => { resolveSecond = resolve; });
    const onRequest = vi.fn((_type: string, payload?: Record<string, unknown>) =>
      payload?.path === "/projects/first.md" ? first : second,
    );
    render(<ConversationView
      session={{
        id: "file-race",
        repository: "/projects",
        title: "File race",
        status: "idle",
        messages: [{ id: "answer", kind: "assistant", text: "Open [first](/projects/first.md) or [second](/projects/second.md)." }],
      }}
      approvals={[]}
      models={[]}
      accessLevels={[]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft=""
      onDraftChange={vi.fn()}
      onBack={vi.fn()}
      onRequest={onRequest as <T extends Record<string, unknown>>(type: string, payload?: Record<string, unknown>) => Promise<T>}
      onError={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("link", { name: "first" }));
    fireEvent.click(screen.getByRole("link", { name: "second" }));
    await act(async () => {
      resolveSecond({ path: "/projects/second.md", content: "newest" });
      await second;
    });
    expect(screen.getByRole("dialog", { name: "Workspace file /projects/second.md" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: /Opening/ })).not.toBeInTheDocument();

    await act(async () => {
      resolveFirst({ path: "/projects/first.md", content: "stale" });
      await first;
    });
    expect(screen.getByRole("dialog", { name: "Workspace file /projects/second.md" })).toBeInTheDocument();
    expect(screen.getByText("newest", { exact: false })).toBeInTheDocument();
    expect(screen.queryByText("stale", { exact: false })).not.toBeInTheDocument();
  });
});

describe("route selector", () => {
  it("renders a themed option menu and changes the selected route", () => {
    const onChange = vi.fn();
    render(
      <RouteSelect
        label="Access"
        value="full"
        options={[
          { value: "ask", label: "Ask for approval", description: "Always ask first" },
          { value: "full", label: "Full access", description: "Unrestricted", warning: true },
        ]}
        disabled={false}
        onChange={onChange}
      />,
    );

    const trigger = screen.getByRole("button", { name: "Access: Full access" });
    expect(trigger).toHaveClass("warning");
    expect(screen.getByText("Access")).not.toHaveClass("warning");
    fireEvent.click(trigger);
    expect(screen.getByRole("listbox", { name: "Access options" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: /Ask for approval/ }));
    expect(onChange).toHaveBeenCalledWith("ask");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("keeps a disabled route focusable and exposes its reason without opening", () => {
    render(
      <RouteSelect
        label="Model"
        value="model-test"
        options={[{ value: "model-test", label: "Model Test" }]}
        disabled
        disabledReason="Available when this turn finishes"
        onChange={vi.fn()}
      />,
    );

    const trigger = screen.getByRole("button", { name: "Model: Model Test" });
    const descriptionId = trigger.getAttribute("aria-describedby");
    expect(trigger).toHaveAttribute("aria-disabled", "true");
    expect(trigger).not.toHaveAttribute("tabindex");
    expect(descriptionId).toBeTruthy();
    expect(document.getElementById(descriptionId!)).toHaveClass("sr-only");
    expect(trigger).toHaveAccessibleDescription("Available when this turn finishes");
    trigger.focus();
    expect(trigger).toHaveFocus();
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
});

describe("assistant code blocks", () => {
  it("copies fenced code blocks without adding controls to inline code", async () => {
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });

    try {
      render(<Markdown text={"Run `npm ci`, then:\n\n```sh\nnpm ci\nnpm test\n```"} />);

      const copy = screen.getByRole("button", { name: "Copy" });
      expect(screen.getAllByRole("button", { name: "Copy" })).toHaveLength(1);
      expect(copy.textContent).toBe("");
      fireEvent.click(copy);

      await waitFor(() => expect(writeText).toHaveBeenCalledWith("npm ci\nnpm test"));
      const copied = await screen.findByRole("button", { name: "Copied" });
      expect(copied).toHaveClass("copied");
      expect(copied).toHaveTextContent("✓");
    } finally {
      if (clipboardDescriptor) Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
      else Reflect.deleteProperty(navigator, "clipboard");
    }
  });

  it("copies through the local-host fallback when the Clipboard API is unavailable", async () => {
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    const execCommandDescriptor = Object.getOwnPropertyDescriptor(document, "execCommand");
    const execCommand = vi.fn().mockReturnValue(true);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
    Object.defineProperty(document, "execCommand", { configurable: true, value: execCommand });

    try {
      render(<Markdown text={"```\nforeman status\n```"} />);
      fireEvent.click(screen.getByRole("button", { name: "Copy" }));

      await waitFor(() => expect(execCommand).toHaveBeenCalledWith("copy"));
      expect(await screen.findByRole("button", { name: "Copied" })).toBeInTheDocument();
    } finally {
      if (clipboardDescriptor) Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
      else Reflect.deleteProperty(navigator, "clipboard");
      if (execCommandDescriptor) Object.defineProperty(document, "execCommand", execCommandDescriptor);
      else Reflect.deleteProperty(document, "execCommand");
    }
  });

  it("prevents overlapping clipboard attempts", async () => {
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    let finishCopy: () => void = () => undefined;
    const pendingCopy = new Promise<void>((resolve) => { finishCopy = resolve; });
    const writeText = vi.fn().mockReturnValue(pendingCopy);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });

    try {
      render(<Markdown text={"```\nnpm test\n```"} />);
      const copy = screen.getByRole("button", { name: "Copy" });

      fireEvent.click(copy);
      const copying = screen.getByRole("button", { name: "Copying" });
      expect(copying).toBeEnabled();
      expect(copying.textContent).toBe("");
      fireEvent.click(copy);
      expect(writeText).toHaveBeenCalledTimes(1);

      await act(async () => finishCopy());
      expect(screen.getByRole("button", { name: "Copied" })).toBeEnabled();
    } finally {
      if (clipboardDescriptor) Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
      else Reflect.deleteProperty(navigator, "clipboard");
    }
  });
});

describe("conversation drafts", () => {
  const session = (id: string): SessionSummary => ({
    id,
    repository: "/projects/foreman",
    title: `Session ${id}`,
    status: "idle",
    messages: [],
  });

  const renderConversation = (selected: SessionSummary, draft: string, onDraftChange = vi.fn()) => (
    <ConversationView
      key={selected.id}
      session={selected}
      approvals={[]}
      models={[]}
      accessLevels={[]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft={draft}
      onDraftChange={onDraftChange}
      onBack={vi.fn()}
      onRequest={vi.fn().mockResolvedValue({})}
      onError={vi.fn()}
    />
  );

  it("restores the controlled draft after tab and session changes", () => {
    const firstChange = vi.fn();
    const view = render(renderConversation(session("one"), "First session draft", firstChange));
    expect(screen.getByRole("textbox")).toHaveValue("First session draft");

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "First session edited" } });
    expect(firstChange).toHaveBeenCalledWith("First session edited");

    view.rerender(renderConversation(session("two"), "Second session draft"));
    expect(screen.getByRole("textbox")).toHaveValue("Second session draft");

    view.unmount();
    render(renderConversation(session("one"), "First session edited"));
    expect(screen.getByRole("textbox")).toHaveValue("First session edited");
  });

  it("remounts session-local composer state when switching sessions", () => {
    const view = render(renderConversation(session("one"), ""));
    const firstComposer = screen.getByRole("textbox");

    view.rerender(renderConversation(session("two"), ""));

    expect(screen.getByRole("textbox")).not.toBe(firstComposer);
  });

  it("renders archived transcripts read-only and suppresses active-session mutations", () => {
    const restore = vi.fn();
    render(<ConversationView
      session={{
        ...session("archived"),
        archived: true,
        readOnly: true,
        capabilities: ["session.read", "session.restore"],
        messages: [{ id: "answer", kind: "assistant", text: "Archived answer" }],
      }}
      approvals={[{
        id: "approval-archived",
        sessionId: "archived",
        itemId: "answer",
        type: "command",
        title: "Must not be actionable",
        createdAt: 1,
        status: "pending",
        availableDecisions: [],
      }]}
      models={[{ id: "gpt-test", displayName: "Test", reasoningEfforts: [], visible: true, isDefault: true }]}
      accessLevels={[{ id: "ask", displayName: "Ask" }]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft="stale draft"
      onDraftChange={vi.fn()}
      onBack={vi.fn()}
      onRequest={vi.fn().mockResolvedValue({})}
      onError={vi.fn()}
      onRestore={restore}
    />);

    expect(screen.getByText("Archived answer")).toBeInTheDocument();
    expect(screen.getByText("Archived · Read only")).toBeInTheDocument();
    expect(screen.queryByText("Must not be actionable")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Restore" }));
    expect(restore).toHaveBeenCalledOnce();
  });

  it("keeps following live messages when the saved scroll position updates", () => {
    const frames: FrameRequestCallback[] = [];
    const animationFrame = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    const onScrollPosition = vi.fn();
    const first = {
      ...session("one"),
      status: "working",
      activeTurnId: "turn-one",
      messages: [{ id: "assistant-one", kind: "assistant" as const, text: "First" }],
    };
    const props = {
      approvals: [],
      models: [],
      accessLevels: [],
      connected: true,
      highlightItemId: null,
      focusedApprovalId: null,
      draft: "",
      onDraftChange: vi.fn(),
      onBack: vi.fn(),
      onRequest: vi.fn().mockResolvedValue({}),
      onError: vi.fn(),
      onScrollPosition,
    };
    const view = render(<ConversationView {...props} session={first} />);
    const transcript = document.querySelector<HTMLElement>(".transcript")!;
    Object.defineProperties(transcript, {
      clientHeight: { configurable: true, value: 200 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 800 },
      scrollTo: { configurable: true, value: vi.fn() },
    });
    frames.splice(0).forEach((callback) => callback(0));
    fireEvent.scroll(transcript);
    expect(onScrollPosition).toHaveBeenLastCalledWith(800);

    view.rerender(
      <ConversationView
        {...props}
        initialScrollTop={800}
        session={{
          ...first,
          messages: [
            ...first.messages,
            { id: "assistant-two", kind: "assistant", text: "Second" },
          ],
        }}
      />,
    );
    frames.splice(0).forEach((callback) => callback(0));
    animationFrame.mockRestore();

    expect(screen.queryByRole("button", { name: /Jump to latest/ })).not.toBeInTheDocument();
    expect(transcript.scrollTo).toHaveBeenLastCalledWith({ top: 1_000 });
  });

  it("offers jump to latest after the user deliberately scrolls away", () => {
    const frames: FrameRequestCallback[] = [];
    const animationFrame = vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    const first = {
      ...session("one"),
      messages: [{ id: "assistant-one", kind: "assistant" as const, text: "First" }],
    };
    const view = render(renderConversation(first, ""));
    const transcript = document.querySelector<HTMLElement>(".transcript")!;
    Object.defineProperties(transcript, {
      clientHeight: { configurable: true, value: 200 },
      scrollHeight: { configurable: true, value: 1_000 },
      scrollTop: { configurable: true, writable: true, value: 300 },
      scrollTo: { configurable: true, value: vi.fn() },
    });
    frames.splice(0).forEach((callback) => callback(0));
    fireEvent.scroll(transcript);

    view.rerender(renderConversation({
      ...first,
      messages: [
        ...first.messages,
        { id: "assistant-two", kind: "assistant", text: "Second" },
      ],
    }, ""));
    animationFrame.mockRestore();

    expect(screen.getByRole("button", { name: /Jump to latest/ })).toBeInTheDocument();
  });

  it("updates access on the existing session as soon as it is selected", async () => {
    const confirmation = vi.spyOn(window, "confirm").mockReturnValue(true);
    const onRequest = vi.fn().mockResolvedValue({ updated: true });
    render(
      <ConversationView
        session={{ ...session("one"), accessLevel: "ask" }}
        approvals={[]}
        models={[]}
        accessLevels={[
          { id: "ask", displayName: "Ask for approval", description: "Ask first" },
          { id: "full", displayName: "Full access", description: "Unrestricted" },
        ]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft=""
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={onRequest}
        onError={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Access: Ask for approval" }));
    fireEvent.click(screen.getByRole("option", { name: /Full access/ }));

    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("session.settings", {
      sessionId: "one",
      accessLevel: "full",
    }));
    expect(screen.getByRole("button", { name: "Access: Full access" })).toBeInTheDocument();
    confirmation.mockRestore();
  });

  it("uses acknowledged settings and restores the prior value after a failed update", async () => {
    const onError = vi.fn();
    const onRequest = vi.fn()
      .mockResolvedValueOnce({
        updated: true,
        session: { ...session("one"), accessLevel: "ask", settingsRevision: 2 },
      })
      .mockRejectedValueOnce(new Error("Reconnect and try again"));
    render(
      <ConversationView
        session={{ ...session("one"), accessLevel: "ask", settingsRevision: 1 }}
        approvals={[]}
        models={[]}
        accessLevels={[
          { id: "ask", displayName: "Ask for approval" },
          { id: "auto", displayName: "Approve for me" },
        ]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft=""
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={onRequest}
        onError={onError}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Access: Ask for approval" }));
    fireEvent.click(screen.getByRole("option", { name: /Approve for me/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Access: Ask for approval" })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Access: Ask for approval" }));
    fireEvent.click(screen.getByRole("option", { name: /Approve for me/ }));
    await waitFor(() => expect(onError).toHaveBeenCalledWith("Reconnect and try again"));
    expect(screen.getByRole("button", { name: "Access: Ask for approval" })).toBeInTheDocument();
  });

  it.each(["working", "waiting", "stopping"])("locks every route control while a turn is %s", (status) => {
    render(
      <ConversationView
        session={{ ...session("one"), status, activeTurnId: "turn-1", model: "model-test", reasoningEffort: "high", accessLevel: "ask" }}
        approvals={[]}
        models={[{ id: "model-test", displayName: "Model Test", visible: true, isDefault: true, reasoningEfforts: ["high"] }]}
        accessLevels={[{ id: "ask", displayName: "Ask for approval" }]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft="Steer safely"
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={vi.fn().mockResolvedValue({})}
        onError={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "Access: Ask for approval" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Model: Model Test" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Reasoning: High" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Access: Ask for approval" })).toHaveAccessibleDescription("Available when this turn finishes");
    expect(screen.queryByText("Model, reasoning, and access are available when this turn finishes.")).not.toBeInTheDocument();
  });

  it("locks Claude route controls without showing a persistent helper message", () => {
    render(
      <ConversationView
        session={{
          ...session("claude-active"),
          provider: "claude-code",
          status: "working",
          activeTurnId: "turn-1",
          model: "sonnet",
          permissionMode: "default",
        }}
        approvals={[]}
        models={[{ id: "sonnet", displayName: "Sonnet", visible: true, isDefault: true, reasoningEfforts: [] }]}
        accessLevels={[{ id: "default", displayName: "Default" }]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft=""
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={vi.fn().mockResolvedValue({})}
        onError={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "Permission: Default" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Model: Sonnet" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Permission: Default" })).toHaveAccessibleDescription("Available when this turn finishes");
    expect(screen.queryByText("Model and permission are available when this turn finishes.")).not.toBeInTheDocument();
  });

  it("shows known server route values while catalog metadata is reconnecting", () => {
    render(
      <ConversationView
        session={{ ...session("one"), status: "working", activeTurnId: "turn-1", model: "gpt-known", reasoningEffort: "high", accessLevel: "full", settingsRevision: 2 }}
        approvals={[]}
        models={[]}
        accessLevels={[]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft=""
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={vi.fn().mockResolvedValue({})}
        onError={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "Access: full" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Model: gpt-known" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Reasoning: high" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByText("Server default")).not.toBeInTheDocument();
  });

  it("steers an active Codex turn without replacement route settings", async () => {
    const onRequest = vi.fn().mockResolvedValue({ accepted: true });
    render(
      <ConversationView
        session={{ ...session("one"), status: "working", activeTurnId: "turn-1", model: "model-test", reasoningEffort: "high", accessLevel: "full" }}
        approvals={[]}
        models={[{ id: "model-test", displayName: "Model Test", visible: true, isDefault: true, reasoningEfforts: ["high"] }]}
        accessLevels={[{ id: "full", displayName: "Full access" }]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft="Keep going"
        onDraftChange={vi.fn()}
        onBack={vi.fn()}
        onRequest={onRequest}
        onError={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Steer" }));
    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("turn.steer", {
      sessionId: "one",
      turnId: "turn-1",
      text: "Keep going",
      images: [],
    }));
  });

  it("resumes an external Claude session with exact model and permission values", async () => {
    const onRequest = vi.fn().mockResolvedValue({ accepted: true });
    const onDraftChange = vi.fn();
    render(
      <ConversationView
        session={{
          ...session("external"),
          provider: "claude-code",
          source: "external",
          state: "resumable",
          status: "resumable",
          repositoryId: ".",
          model: "sonnet",
          permissionMode: "default",
        }}
        approvals={[]}
        models={[
          { id: "sonnet", displayName: "Sonnet", reasoningEfforts: [], visible: true, isDefault: true },
          { id: "haiku", displayName: "Haiku", reasoningEfforts: [], visible: true, isDefault: false },
        ]}
        accessLevels={[
          { id: "default", displayName: "Default", description: "Ask when required" },
          { id: "dontAsk", displayName: "Don’t ask", description: "Deny unapproved actions" },
        ]}
        connected
        highlightItemId={null}
        focusedApprovalId={null}
        draft="Continue safely"
        onDraftChange={onDraftChange}
        onBack={vi.fn()}
        onRequest={onRequest}
        onError={vi.fn()}
      />,
    );

    expect(screen.getByText(/Not live-attached/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Model: Sonnet" }));
    fireEvent.click(screen.getByRole("option", { name: /Haiku/ }));
    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("provider.session.settings", {
      provider: "claude-code",
      sessionId: "external",
      repositoryId: ".",
      model: "haiku",
    }));
    fireEvent.click(screen.getByRole("button", { name: "Permission: Default" }));
    fireEvent.click(screen.getByRole("option", { name: /Don’t ask/ }));
    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("provider.session.settings", {
      provider: "claude-code",
      sessionId: "external",
      repositoryId: ".",
      permissionMode: "dontAsk",
    }));
    fireEvent.click(screen.getByRole("button", { name: "Resume in Foreman" }));

    await waitFor(() => expect(onRequest).toHaveBeenCalledWith("provider.session.resume", {
      provider: "claude-code",
      sessionId: "external",
      repositoryId: ".",
      text: "Continue safely",
      model: "haiku",
      permissionMode: "dontAsk",
    }));
    expect(onDraftChange).toHaveBeenCalledWith("");
    expect(screen.queryByTitle("Attach images")).not.toBeInTheDocument();
  });
});

describe("conversation activity detail", () => {
  const session: SessionSummary = {
    id: "activity",
    repository: "/projects/foreman",
    title: "Activity",
    status: "idle",
    messages: [
      { id: "command", kind: "command", description: "git status", status: "completed", exitCode: 0 },
      { id: "tool", kind: "tool", description: "Read file", status: "completed" },
      { id: "failed", kind: "command", description: "run tests", status: "failed", exitCode: 1 },
    ],
  };
  const props = {
    session,
    approvals: [],
    models: [],
    accessLevels: [],
    connected: true,
    highlightItemId: null,
    focusedApprovalId: null,
    draft: "",
    onDraftChange: vi.fn(),
    onBack: vi.fn(),
    onRequest: vi.fn().mockResolvedValue({}),
    onError: vi.fn(),
  };

  it("groups completed non-zero outcomes with a neutral count and precise expanded wording", () => {
    const { container } = render(<ConversationView {...props} />);
    const group = container.querySelector("details.collapsed-activity");
    expect(screen.getByText("Completed activity")).toBeInTheDocument();
    expect(screen.getByText("2 commands · 1 tool · 1 non-zero")).toBeInTheDocument();
    expect(group).not.toHaveAttribute("open");
    expect(container.querySelector(".transcript > .tool-card")).toBeNull();
    fireEvent.click(screen.getByText("Details"));
    expect(group).toHaveAttribute("open");
    expect(screen.getByText("Failed · Exited 1")).toBeInTheDocument();
  });

  it("renders every activity item directly in full mode", () => {
    const { container } = render(<ConversationView {...props} activityDetail="full" />);
    expect(container.querySelector("details.collapsed-activity")).toBeNull();
    expect(container.querySelectorAll(".transcript > .tool-card")).toHaveLength(3);
  });

  it("keeps structured attention states and a highlighted completed item directly reachable", () => {
    const attentionSession: SessionSummary = {
      ...session,
      messages: [
        { id: "nonzero", kind: "command", description: "probe", status: "completed", exitCode: 1 },
        { id: "build", kind: "command", description: "gradle test", status: "failed", exitCode: 2 },
        { id: "running", kind: "command", description: "live", status: "running" },
        { id: "error", kind: "command", description: "could not execute", status: "executionError", exitCode: 127 },
        { id: "unresolved", kind: "command", description: "unresolved", status: "failed" },
        { id: "interrupted", kind: "tool", description: "stopped", status: "interrupted" },
        { id: "blocked", kind: "tool", description: "permission", status: "denied" },
        { id: "highlighted", kind: "tool", description: "search target", status: "completed" },
      ],
    };

    const { container } = render(<ConversationView {...props} session={attentionSession} highlightItemId="highlighted" />);

    expect(screen.getByText("2 commands · 2 non-zero")).toBeInTheDocument();
    expect(container.querySelectorAll(".transcript > .tool-card")).toHaveLength(6);
    expect(container.querySelector("#message-running")).toHaveClass("activity-active");
    expect(container.querySelectorAll(".transcript > .activity-attention")).toHaveLength(4);
    expect(screen.getByText("Execution error · Exited 127")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("Interrupted")).toBeInTheDocument();
    expect(screen.getByText("Denied")).toBeInTheDocument();
    expect(container.querySelector("#message-highlighted")).toHaveClass("search-highlight");
  });
});

describe("NewSessionDialog", () => {
  const routeProps = {
    models: [{ id: "model-test", displayName: "Model Test", reasoningEfforts: ["low", "high"], defaultReasoningEffort: "high", visible: true, isDefault: true }],
    accessLevels: [{ id: "ask", displayName: "Ask for approval" }, { id: "full", displayName: "Full access" }],
  };

  it("hides provider selection and defaults to the sole enabled provider", () => {
    render(<NewSessionDialog
      repositories={[]}
      repositoryRoot="/projects"
      {...routeProps}
      providers={[
        { id: "codex", displayName: "Codex", enabled: false, available: false, capabilities: [], limitations: [] },
        { id: "claude-code", displayName: "Claude Code", enabled: true, available: true, capabilities: [], limitations: [] },
      ]}
      claudeModels={[{ id: "sonnet", displayName: "Sonnet", reasoningEfforts: [], visible: true, isDefault: true }]}
      claudePermissionModes={[{ id: "default", displayName: "Default" }]}
      onClose={vi.fn()}
      onCreate={vi.fn()}
    />);

    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    expect(screen.queryByText("Claude Code")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Initial prompt")).toBeInTheDocument();
  });

  it("keeps the loading catalog distinct and transitions without an invalid provider", () => {
    const codex = { id: "codex" as const, displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] };
    const view = render(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[]} providerCatalogLoaded={false} onClose={vi.fn()} onCreate={vi.fn()} />);

    expect(screen.getByText("Loading providers…")).toBeInTheDocument();
    expect(screen.queryByText("Codex is unavailable on this host.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start in workspace" })).toBeDisabled();

    view.rerender(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[codex]} providerCatalogLoaded onClose={vi.fn()} onCreate={vi.fn()} />);
    expect(screen.queryByText("Loading providers…")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start in workspace" })).toBeEnabled();
  });

  it("excludes enabled-but-unavailable choices and restores selection when both become usable", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    const codex = { id: "codex" as const, displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] };
    const claude = { id: "claude-code" as const, displayName: "Claude Code", enabled: true, available: false, capabilities: [], limitations: [] };
    const view = render(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[codex]} onClose={vi.fn()} onCreate={create} />);

    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    view.rerender(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[codex, claude]} onClose={vi.fn()} onCreate={create} />);
    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    expect(screen.queryByText("Claude Code")).not.toBeInTheDocument();

    view.rerender(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[codex, { ...claude, available: true }]} onClose={vi.fn()} onCreate={create} />);
    expect(screen.getByLabelText("Provider")).toHaveValue("codex");
    expect(screen.getByRole("option", { name: "Claude Code" })).toBeInTheDocument();

    view.rerender(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} providers={[{ ...codex, enabled: false }, { ...claude, available: true }]} onClose={vi.fn()} onCreate={create} />);
    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Initial prompt")).toBeInTheDocument();
  });

  it("starts in the configured workspace when no repositories exist", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    render(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} onClose={vi.fn()} onCreate={create} />);

    expect(screen.getByText("No Git repositories yet")).toBeInTheDocument();
    expect(screen.getByText("/projects")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Start in workspace" }));

    expect(create).toHaveBeenCalledWith({
      provider: "codex",
      repositoryId: ".",
      model: "model-test",
      reasoningEffort: "high",
      accessLevel: "ask",
    });
  });

  it("defaults to the workspace root and orders route settings like the composer", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<NewSessionDialog repositories={[{ id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false }]} repositoryRoot="/projects" {...routeProps} onClose={vi.fn()} onCreate={create} />);

    const button = screen.getByRole("button", { name: "Create" });
    expect(screen.getByLabelText("Workspace")).toHaveValue(".");
    expect(button).toBeEnabled();
    expect([...container.querySelectorAll(".new-session-settings > label")].map((label) => label.firstChild?.textContent))
      .toEqual(["Access", "Model", "Reasoning"]);
    fireEvent.click(button);
    expect(create).toHaveBeenLastCalledWith(expect.objectContaining({ repositoryId: "." }));

    fireEvent.change(screen.getByLabelText("Workspace"), { target: { value: "foreman" } });
    fireEvent.change(screen.getByLabelText("Reasoning"), { target: { value: "low" } });
    fireEvent.change(screen.getByLabelText("Access"), { target: { value: "full" } });
    fireEvent.click(button);

    expect(create).toHaveBeenLastCalledWith({
      provider: "codex",
      repositoryId: "foreman",
      model: "model-test",
      reasoningEffort: "low",
      accessLevel: "full",
    });
  });

  it("returns to the workspace root when repository discovery removes the selection", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    const repository = { id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false };
    const view = render(<NewSessionDialog repositories={[repository]} repositoryRoot="/projects" {...routeProps} onClose={vi.fn()} onCreate={create} />);

    fireEvent.change(screen.getByLabelText("Workspace"), { target: { value: "foreman" } });
    view.rerender(<NewSessionDialog repositories={[]} repositoryRoot="/projects" {...routeProps} onClose={vi.fn()} onCreate={create} />);
    fireEvent.click(screen.getByRole("button", { name: "Start in workspace" }));

    expect(create).toHaveBeenLastCalledWith(expect.objectContaining({ repositoryId: "." }));
  });

  it("starts Claude with the adapter model and exact permission mode", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    render(<NewSessionDialog
      repositories={[]}
      repositoryRoot="/projects"
      {...routeProps}
      providers={[
        { id: "codex", displayName: "Codex", available: true, capabilities: [], limitations: [] },
        { id: "claude-code", displayName: "Claude Code", available: true, capabilities: [], limitations: [] },
      ]}
      claudeModels={[
        { id: "sonnet", displayName: "Sonnet", reasoningEfforts: [], visible: true, isDefault: true },
        { id: "haiku", displayName: "Haiku", reasoningEfforts: [], visible: true, isDefault: false },
      ]}
      claudePermissionModes={[
        { id: "default", displayName: "Default", description: "Ask when required" },
        { id: "bypassPermissions", displayName: "Bypass permissions", description: "Unrestricted/high risk", highRisk: true },
      ]}
      onClose={vi.fn()}
      onCreate={create}
    />);

    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "claude-code" } });
    expect(screen.queryByLabelText("Reasoning")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Initial prompt"), { target: { value: "Read the project" } });
    fireEvent.change(screen.getByLabelText("Claude model"), { target: { value: "haiku" } });
    fireEvent.change(screen.getByLabelText("Permission mode"), { target: { value: "bypassPermissions" } });
    fireEvent.click(screen.getByRole("button", { name: "Start Claude session" }));

    expect(create).toHaveBeenCalledWith({
      provider: "claude-code",
      repositoryId: ".",
      text: "Read the project",
      model: "haiku",
      permissionMode: "bypassPermissions",
    });
    expect(screen.getAllByText(/high risk/i).length).toBeGreaterThan(0);
  });

  it("shows an actionable state when neither enabled provider is usable", () => {
    render(<NewSessionDialog
      repositories={[]}
      repositoryRoot="/projects"
      {...routeProps}
      providers={[
        { id: "codex", displayName: "Codex", available: false, capabilities: [], limitations: [] },
        { id: "claude-code", displayName: "Claude Code", available: false, capabilities: [], limitations: [], unavailableReason: "node-missing" },
      ]}
      onClose={vi.fn()}
      onCreate={vi.fn()}
    />);
    expect(screen.queryByLabelText("Provider")).not.toBeInTheDocument();
    expect(screen.getByText("No provider is available for tasks.")).toBeInTheDocument();
    expect(screen.getByText(/Settings → Providers/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start in workspace" })).toBeDisabled();
  });
});

describe("ProviderSettings", () => {
  it("updates providers and protects the last enabled provider", async () => {
    const update = vi.fn().mockResolvedValue(undefined);
    const providers = [
      { id: "codex" as const, displayName: "Codex", enabled: true, available: true, capabilities: [], limitations: [] },
      { id: "claude-code" as const, displayName: "Claude Code", enabled: true, available: true, capabilities: [], limitations: [] },
    ];
    const view = render(<ProviderSettings providers={providers} onProviderEnabled={update} />);

    fireEvent.click(screen.getByRole("checkbox", { name: /Claude Code/ }));
    expect(update).toHaveBeenCalledWith("claude-code", false);
    await waitFor(() => expect(screen.getByRole("checkbox", { name: /Claude Code/ })).toBeEnabled());

    view.rerender(<ProviderSettings
      providers={providers.map((provider) => ({ ...provider, enabled: provider.id === "claude-code" }))}
      onProviderEnabled={update}
    />);
    expect(screen.getByRole("checkbox", { name: /Claude Code/ })).toBeDisabled();
    expect(screen.getByText(/at least one available provider required/)).toBeInTheDocument();

    view.rerender(<ProviderSettings
      providers={[providers[0], { ...providers[1], available: false }]}
      onProviderEnabled={update}
    />);
    expect(screen.getByRole("checkbox", { name: /Codex/ })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: /Claude Code/ })).toBeEnabled();
  });

  it("shows unavailable providers unchecked with distinct configuration, installation, and runtime state", () => {
    render(<ProviderSettings
      providers={[{
        id: "claude-code",
        displayName: "Claude Code",
        installed: false,
        enabled: false,
        available: false,
        capabilities: [],
        limitations: [],
      }]}
      onProviderEnabled={vi.fn()}
    />);

    expect(screen.getByRole("checkbox", { name: /Claude Code/ })).not.toBeChecked();
    expect(screen.getByText(/Disabled · Not installed · Unavailable/)).toBeInTheDocument();
  });
});
