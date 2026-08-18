import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, { appShellClassName, ConversationView, LinkedUserText, Markdown, NewSessionDialog, RouteSelect, SessionList, SetupView, sessionActionRequest } from "./App";
import type { SessionSummary } from "./protocol";
import { inferPagePort } from "./client";
import { DEFAULT_SESSION_FILTERS } from "./session-search";
import { loadHostRegistry, saveHostRegistry, type StoredHost } from "./storage";

const clientMock = vi.hoisted(() => ({
  pair: vi.fn(),
  start: vi.fn(),
  request: vi.fn(),
  disconnect: vi.fn(),
}));

vi.mock("./client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./client")>();
  return {
    ...actual,
    ForemanWebClient: class {
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
    expect(window.location.pathname).toBe("/");
    expect(new URLSearchParams(window.location.search).get("host")).toBe(loadHostRegistry().activeHostId);
  });

  it("opens the sessions root for the replacement host after forgetting the active host", async () => {
    saveHostRegistry({ hosts: [home, work], activeHostId: home.id });
    window.history.replaceState(null, "", `/settings?host=${home.id}`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<App />);

    fireEvent.click(forgetButton(home.displayName));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Sessions" })).toBeInTheDocument());
    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe(`?host=${work.id}`);
    expect(loadHostRegistry().activeHostId).toBe(work.id);
  });

  it("keeps the active host while opening the sessions root after forgetting another host", async () => {
    saveHostRegistry({ hosts: [home, work], activeHostId: home.id });
    window.history.replaceState(null, "", `/settings?host=${home.id}`);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<App />);

    fireEvent.click(forgetButton(work.displayName));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Sessions" })).toBeInTheDocument());
    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe(`?host=${home.id}`);
    expect(loadHostRegistry()).toMatchObject({
      activeHostId: home.id,
      hosts: [{ id: home.id }],
    });
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
    render(<SessionList
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
    />);

    expect(screen.queryByRole("button", { name: "Archive" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Pin Claude work" }));
    fireEvent.click(screen.getByRole("button", { name: "Hide" }));
    expect(onPin).toHaveBeenCalledWith("claude-code", "claude-session");
    expect(onHide).toHaveBeenCalledWith("claude-code", "claude-session");
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onAction).toHaveBeenCalledWith("delete", session);
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

      const copy = screen.getByRole("button", { name: "Copy code" });
      expect(screen.getAllByRole("button", { name: /Copy code/ })).toHaveLength(1);
      fireEvent.click(copy);

      await waitFor(() => expect(writeText).toHaveBeenCalledWith("npm ci\nnpm test"));
      expect(screen.getByRole("button", { name: "Code copied" })).toHaveTextContent("Copied");
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
      fireEvent.click(screen.getByRole("button", { name: "Copy code" }));

      await waitFor(() => expect(execCommand).toHaveBeenCalledWith("copy"));
      expect(screen.getByRole("button", { name: "Code copied" })).toBeInTheDocument();
    } finally {
      if (clipboardDescriptor) Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
      else Reflect.deleteProperty(navigator, "clipboard");
      if (execCommandDescriptor) Object.defineProperty(document, "execCommand", execCommandDescriptor);
      else Reflect.deleteProperty(document, "execCommand");
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

    expect(onRequest).toHaveBeenCalledWith("session.settings", {
      sessionId: "one",
      accessLevel: "full",
    });
    expect(screen.getByRole("button", { name: "Access: Full access" })).toBeInTheDocument();
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
    fireEvent.click(screen.getByRole("button", { name: "Permission: Default" }));
    fireEvent.click(screen.getByRole("option", { name: /Don’t ask/ }));
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
      { id: "failed", kind: "command", description: "run tests", status: "completed", exitCode: 1 },
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

  it("defaults to a collapsed routine group while leaving failures visible", () => {
    const { container } = render(<ConversationView {...props} />);
    expect(screen.getByText("2 completed activity items")).toBeInTheDocument();
    expect(container.querySelector("details.collapsed-activity")).not.toHaveAttribute("open");
    expect(container.querySelector(".transcript > .tool-card p")).toHaveTextContent("run tests");
  });

  it("renders every activity item directly in full mode", () => {
    const { container } = render(<ConversationView {...props} activityDetail="full" />);
    expect(container.querySelector("details.collapsed-activity")).toBeNull();
    expect(container.querySelectorAll(".transcript > .tool-card")).toHaveLength(3);
  });
});

describe("NewSessionDialog", () => {
  const routeProps = {
    models: [{ id: "model-test", displayName: "Model Test", reasoningEfforts: ["low", "high"], defaultReasoningEffort: "high", visible: true, isDefault: true }],
    accessLevels: [{ id: "ask", displayName: "Ask for approval" }, { id: "full", displayName: "Full access" }],
  };

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

  it("explains an unavailable Claude provider without exposing internals", () => {
    render(<NewSessionDialog
      repositories={[]}
      repositoryRoot="/projects"
      {...routeProps}
      providers={[
        { id: "codex", displayName: "Codex", available: true, capabilities: [], limitations: [] },
        { id: "claude-code", displayName: "Claude Code", available: false, capabilities: [], limitations: [], unavailableReason: "node-missing" },
      ]}
      onClose={vi.fn()}
      onCreate={vi.fn()}
    />);
    fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "claude-code" } });
    expect(screen.getByText("Claude Code is unavailable on this host.")).toBeInTheDocument();
    expect(screen.getByText("Node.js 20 or newer is missing.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start Claude session" })).toBeDisabled();
  });
});
