import { describe, expect, it } from "vitest";
import type { RepositoryInfo, SessionSearchResult, SessionSummary } from "./protocol";
import {
  DEFAULT_SESSION_FILTERS,
  dateBounds,
  filterSessions,
  parseSessionFilters,
  repositoryFilterOptions,
  sessionFiltersSearch,
} from "./session-search";

const repositories: RepositoryInfo[] = [
  { id: "foreman", name: "foreman", path: "foreman", branch: "main", dirty: false },
];
const sessions: SessionSummary[] = [
  { id: "active", title: "Build WebSocket endpoint", repository: "/projects/foreman/src", status: "working", lastActivity: 1_700_000_300 },
  { id: "waiting", title: "Review release", repository: "/home/operator", status: "waiting", lastActivity: 1_700_000_200 },
  { id: "done", title: "Long completed title", repository: "/projects/foreman", status: "idle", lastActivity: 1_700_000_100 },
];

describe("session discovery semantics", () => {
  it("combines transcript, repository, status, date, pin, and hidden criteria", () => {
    const remote: SessionSearchResult[] = [{
      session: sessions[0],
      matches: [{ kind: "assistant", snippet: "Added the websocket handler", itemId: "item-1" }],
    }];
    const filters = {
      ...DEFAULT_SESSION_FILTERS,
      query: "handler",
      repository: "/projects/foreman",
      statuses: ["active" as const],
      dateRange: "custom" as const,
      dateFrom: "2023-11-14",
      dateTo: "2023-11-15",
      pinnedOnly: true,
    };
    const visible = filterSessions(
      sessions,
      filters,
      new Set(["active"]),
      new Set(["waiting"]),
      remote,
      repositories,
      "/projects",
      new Date("2023-11-15T12:00:00"),
    );
    expect(visible.map(({ session }) => session.id)).toEqual(["active"]);
    expect(visible[0].matches[0].snippet).toContain("websocket");
  });

  it("excludes hidden sessions by default and restores them through Hidden", () => {
    const hidden = new Set(["waiting"]);
    expect(filterSessions(sessions, DEFAULT_SESSION_FILTERS, new Set(), hidden, [], repositories, "/projects").map(({ session }) => session.id))
      .not.toContain("waiting");
    expect(filterSessions(sessions, { ...DEFAULT_SESSION_FILTERS, hidden: "hidden" }, new Set(), hidden, [], repositories, "/projects").map(({ session }) => session.id))
      .toEqual(["waiting"]);
  });

  it("sorts pins before attention, active work, and recency", () => {
    const visible = filterSessions(sessions, DEFAULT_SESSION_FILTERS, new Set(["done"]), new Set(), [], repositories, "/projects");
    expect(visible.map(({ session }) => session.id)).toEqual(["done", "waiting", "active"]);
  });

  it("maps Completed to idle and identifies canonical repositories and workspaces", () => {
    const completed = filterSessions(sessions, { ...DEFAULT_SESSION_FILTERS, statuses: ["completed"] }, new Set(), new Set(), [], repositories, "/projects");
    expect(completed.map(({ session }) => session.id)).toEqual(["done"]);
    expect(repositoryFilterOptions(sessions, repositories, "/projects")).toEqual([
      { id: "/projects/foreman", label: "Repository: foreman" },
      { id: "/home/operator", label: "Workspace: /home/operator" },
    ]);
  });

  it("round trips robust, bookmarkable URL state without local ID lists", () => {
    const filters = parseSessionFilters("?q=websocket&status=active&status=failed&repo=%2Fprojects%2Fforeman&date=7d&pinned=1&hidden=1&sort=recent");
    expect(filters.statuses).toEqual(["active", "failed"]);
    const search = sessionFiltersSearch(filters);
    expect(parseSessionFilters(search)).toEqual(filters);
    expect(search).not.toContain("session-id");
  });

  it("uses local-day bounds", () => {
    const bounds = dateBounds({ dateRange: "today", dateFrom: "", dateTo: "" }, new Date(2026, 6, 29, 12));
    expect(new Date(bounds.dateFrom! * 1000).getHours()).toBe(0);
  });
});
