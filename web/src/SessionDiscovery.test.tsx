import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SessionSearchControls, SessionSearchResults } from "./SessionDiscovery";
import { DEFAULT_SESSION_FILTERS } from "./session-search";

describe("SessionSearchControls", () => {
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
});
