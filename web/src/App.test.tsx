import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, { appShellClassName, ConversationView, LinkedUserText, NewSessionDialog, RouteSelect, SetupView } from "./App";
import type { SessionSummary } from "./protocol";
import { inferPagePort } from "./client";
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
      repositoryId: ".",
      model: "model-test",
      reasoningEffort: "high",
      accessLevel: "ask",
    });
  });

  it("requires a repository selection and carries changed route settings", () => {
    const create = vi.fn().mockResolvedValue(undefined);
    render(<NewSessionDialog repositories={[{ id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false }]} repositoryRoot="/projects" {...routeProps} onClose={vi.fn()} onCreate={create} />);

    const button = screen.getByRole("button", { name: "Create" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Repository"), { target: { value: "foreman" } });
    fireEvent.change(screen.getByLabelText("Reasoning"), { target: { value: "low" } });
    fireEvent.change(screen.getByLabelText("Access"), { target: { value: "full" } });
    fireEvent.click(button);

    expect(create).toHaveBeenCalledWith({
      repositoryId: "foreman",
      model: "model-test",
      reasoningEffort: "low",
      accessLevel: "full",
    });
  });
});
