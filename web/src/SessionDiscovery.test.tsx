import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SessionSearchControls, SessionSearchResults } from "./SessionDiscovery";
import { DEFAULT_SESSION_FILTERS } from "./session-search";

describe("SessionSearchControls", () => {
  const codexArchiveProvider = {
    id: "codex" as const,
    displayName: "Codex",
    enabled: true,
    available: true,
    capabilities: ["session.archived.list", "session.restore"],
    limitations: [],
  };

  it("clears, searches immediately, and exposes active filter count", () => {
    const change = vi.fn();
    const search = vi.fn();
    render(<SessionSearchControls filters={{ ...DEFAULT_SESSION_FILTERS, query: "socket", statuses: ["active"] }} repositories={[]} loading={false} onChange={change} onSearchNow={search} />);
    expect(screen.getByLabelText("2 active filters")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByLabelText("Search sessions"), { key: "Enter" });
    expect(search).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByLabelText("Clear search"));
    expect(change).toHaveBeenCalledWith(expect.objectContaining({ query: "" }));
  });

  it("moves keyboard focus from search to the first result", () => {
    render(<><SessionSearchControls filters={{ ...DEFAULT_SESSION_FILTERS, query: "socket" }} repositories={[]} loading={false} onChange={vi.fn()} onSearchNow={vi.fn()} /><SessionSearchResults results={[{ session: { id: "one", title: "Socket work", repository: "/repo", status: "working" }, matches: [], pinned: false, hidden: false }]} query="socket" loading={false} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} /></>);
    const input = screen.getByLabelText("Search sessions");
    input.focus();
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(document.querySelector("[data-search-result]")).toHaveFocus();
  });

  it("dismisses filters with Done, Escape, and an outside click", () => {
    render(<SessionSearchControls filters={DEFAULT_SESSION_FILTERS} repositories={[]} loading={false} onChange={vi.fn()} onSearchNow={vi.fn()} />);
    const details = document.querySelector("details");
    fireEvent.click(screen.getByText("Filters"));
    expect(details).toHaveAttribute("open");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByText("Filters"));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByText("Filters"));
    fireEvent.mouseDown(document.body);
    expect(details).not.toHaveAttribute("open");
  });

  it("gates Archived on explicit enabled-provider capability and explains unsupported hosts", () => {
    const change = vi.fn();
    const { rerender } = render(<SessionSearchControls filters={DEFAULT_SESSION_FILTERS} repositories={[]} providers={[]} loading={false} onChange={change} onSearchNow={vi.fn()} />);
    fireEvent.click(screen.getByText("Filters"));
    expect(screen.getByRole("option", { name: "Archived" })).toBeDisabled();
    expect(screen.getByText(/no enabled provider advertises support/i)).toBeInTheDocument();

    rerender(<SessionSearchControls filters={DEFAULT_SESSION_FILTERS} repositories={[]} providers={[codexArchiveProvider]} loading={false} onChange={change} onSearchNow={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Sessions"), { target: { value: "archived" } });
    expect(change).toHaveBeenCalledWith(expect.objectContaining({ scope: "archived" }));
  });

  it("shows loading, empty, error, snippets, and accessible local actions", () => {
    const { rerender } = render(<SessionSearchResults results={[]} query="missing" loading={true} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByText("Searching sessions…")).toBeInTheDocument();
    rerender(<SessionSearchResults results={[]} query="missing" loading={false} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByText("No matching sessions")).toBeInTheDocument();
    rerender(<SessionSearchResults results={[]} query="missing" loading={false} error="offline" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("offline");
    rerender(<SessionSearchResults results={[{ session: { id: "one", title: "Socket work", repository: "/repo", status: "working", lastActivity: 123 }, matches: [{ kind: "user", snippet: "Add a WebSocket endpoint" }], pinned: false, hidden: false }]} query="socket" loading={false} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByText("Add a WebSocket endpoint")).toBeInTheDocument();
    expect(screen.getByLabelText("Pin Socket work")).toBeInTheDocument();
    expect(screen.getByLabelText("Hide Socket work")).toBeInTheDocument();
  });

  it("renders a normalized long-prompt title without exposing boilerplate", () => {
    render(<SessionSearchResults results={[{ session: { id: "long", title: "Build Foreman monitoring dashboard", repository: "/projects/foreman", status: "idle" }, matches: [], pinned: false, hidden: false }]} query="" loading={false} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} />);
    expect(screen.getByText("Build Foreman monitoring dashboard")).toBeInTheDocument();
    expect(screen.queryByText(/GitHub:/)).not.toBeInTheDocument();
  });

  it("pins and hides Claude results with provider-aware identity", () => {
    const pin = vi.fn();
    const hide = vi.fn();
    render(<SessionSearchResults results={[{
      session: { provider: "claude-code", id: "same-id", title: "Claude result", repository: "/repo", status: "resumable" },
      matches: [],
      pinned: false,
      hidden: false,
    }]} query="" loading={false} error="" onOpen={vi.fn()} onPin={pin} onHide={hide} />);

    fireEvent.click(screen.getByLabelText("Pin Claude result"));
    fireEvent.click(screen.getByLabelText("Hide Claude result"));
    expect(pin).toHaveBeenCalledWith("claude-code", "same-id");
    expect(hide).toHaveBeenCalledWith("claude-code", "same-id");
  });

  it("removes a search-result provider badge without removing its identity behavior", () => {
    const open = vi.fn();
    const result = [{
      session: { provider: "claude-code" as const, id: "same-id", title: "Claude result", repository: "/repo", status: "resumable" },
      matches: [],
      pinned: false,
      hidden: false,
    }];
    render(<SessionSearchResults results={result} query="" loading={false} error="" showProviderIdentity={false} onOpen={open} onPin={vi.fn()} onHide={vi.fn()} />);

    expect(document.querySelector(".search-result .provider-badge")).toBeNull();
    fireEvent.click(screen.getByText("Claude result"));
    expect(open).toHaveBeenCalledWith("claude-code", "same-id", undefined);
  });

  it("distinguishes archived results and keeps Restore beside the session", () => {
    const restore = vi.fn();
    const archived = {
      provider: "codex" as const,
      id: "archived-one",
      title: "Archived work",
      repository: "/repo",
      status: "idle",
      archived: true,
      readOnly: true,
      capabilities: ["session.read", "session.restore"],
    };
    render(<SessionSearchResults results={[{ session: archived, matches: [], pinned: false, hidden: false }]} query="" loading={false} error="" onOpen={vi.fn()} onPin={vi.fn()} onHide={vi.fn()} onRestore={restore} />);

    expect(screen.getByText("Archived")).toBeInTheDocument();
    expect(screen.getByText("Archived").closest("article")).toHaveClass("archived");
    fireEvent.click(screen.getByRole("button", { name: "Restore" }));
    expect(restore).toHaveBeenCalledWith(archived);
  });
});
