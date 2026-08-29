import { providerSessionKey, sessionProvider, type RepositoryInfo, type SessionSearchResult, type SessionSummary } from "./protocol";

export type SearchStatus = "active" | "waiting" | "completed" | "failed" | "interrupted";
export type DateRange = "all" | "today" | "7d" | "30d" | "custom";
export type HiddenMode = "visible" | "hidden";
export type SearchSort = "relevance" | "recent" | "oldest" | "status";

export interface SessionFilters {
  query: string;
  repository: string;
  statuses: SearchStatus[];
  dateRange: DateRange;
  dateFrom: string;
  dateTo: string;
  pinnedOnly: boolean;
  hidden: HiddenMode;
  sort: SearchSort;
}

export interface RepositoryFilterOption {
  id: string;
  label: string;
}

export interface VisibleSession {
  session: SessionSummary;
  matches: SessionSearchResult["matches"];
  pinned: boolean;
  hidden: boolean;
}

export interface RepositorySessionGroup {
  repository: RepositoryFilterOption;
  sessions: VisibleSession[];
}

export type CollapsedRepositoriesByHost = ReadonlyMap<string, ReadonlySet<string>>;

export function toggleCollapsedRepository(
  collapsedByHost: CollapsedRepositoriesByHost,
  hostId: string,
  repositoryId: string,
): CollapsedRepositoriesByHost {
  const next = new Map(collapsedByHost);
  const collapsed = new Set(next.get(hostId) ?? []);
  if (collapsed.has(repositoryId)) collapsed.delete(repositoryId);
  else collapsed.add(repositoryId);
  if (collapsed.size === 0) next.delete(hostId);
  else next.set(hostId, collapsed);
  return next;
}

export const DEFAULT_SESSION_FILTERS: SessionFilters = {
  query: "",
  repository: "",
  statuses: [],
  dateRange: "all",
  dateFrom: "",
  dateTo: "",
  pinnedOnly: false,
  hidden: "visible",
  sort: "relevance",
};

export function repositoryIdentity(
  path: string,
  repositories: RepositoryInfo[],
  repositoryRoot: string,
): RepositoryFilterOption {
  const cwd = normalizePath(path);
  const known = repositories.map((repository) => {
    const absolute = repository.path.startsWith("/")
      ? repository.path
      : `${repositoryRoot}/${repository.path}`;
    return { repository, path: normalizePath(absolute) };
  }).sort((left, right) => right.path.length - left.path.length);
  const match = known.find(({ path: root }) => cwd === root || cwd.startsWith(`${root}/`));
  return match
    ? { id: match.path, label: `Repository: ${match.repository.name}` }
    : { id: cwd, label: `Workspace: ${cwd || "(unknown)"}` };
}

export function repositoryFilterOptions(
  sessions: SessionSummary[],
  repositories: RepositoryInfo[],
  repositoryRoot: string,
): RepositoryFilterOption[] {
  const options = new Map<string, RepositoryFilterOption>();
  sessions.forEach((session) => {
    const option = repositoryIdentity(session.repository, repositories, repositoryRoot);
    options.set(option.id, option);
  });
  return [...options.values()].sort((left, right) => left.label.localeCompare(right.label));
}

export function repositorySessionGroups(
  sessions: VisibleSession[],
  repositories: RepositoryInfo[],
  repositoryRoot: string,
): RepositorySessionGroup[] {
  const groups = new Map<string, RepositorySessionGroup>();
  sessions.forEach((session) => {
    const repository = repositoryIdentity(session.session.repository, repositories, repositoryRoot);
    const group = groups.get(repository.id);
    if (group) group.sessions.push(session);
    else groups.set(repository.id, { repository, sessions: [session] });
  });
  return [...groups.values()].map((group) => ({
    ...group,
    sessions: group.sessions
      .map((session, index) => ({ session, index }))
      .sort((left, right) =>
        repositoryActivityRank(left.session.session) - repositoryActivityRank(right.session.session)
        || Number(right.session.pinned) - Number(left.session.pinned)
        || left.index - right.index)
      .map(({ session }) => session),
  }));
}

export function dateBounds(
  filters: Pick<SessionFilters, "dateRange" | "dateFrom" | "dateTo">,
  now = new Date(),
): { dateFrom: number | null; dateTo: number | null } {
  if (filters.dateRange === "all") return { dateFrom: null, dateTo: null };
  if (filters.dateRange === "custom") {
    return {
      dateFrom: localDateStart(filters.dateFrom),
      dateTo: localDateEnd(filters.dateTo),
    };
  }
  const start = new Date(now);
  start.setHours(0, 0, 0, 0);
  if (filters.dateRange === "7d") start.setDate(start.getDate() - 6);
  if (filters.dateRange === "30d") start.setDate(start.getDate() - 29);
  return { dateFrom: start.getTime() / 1000, dateTo: null };
}

export function filterSessions(
  sessions: SessionSummary[],
  filters: SessionFilters,
  pinnedIds: ReadonlySet<string>,
  hiddenIds: ReadonlySet<string>,
  searchResults: SessionSearchResult[],
  repositories: RepositoryInfo[],
  repositoryRoot: string,
  now = new Date(),
): VisibleSession[] {
  const query = filters.query.trim().toLowerCase();
  const remote = new Map(searchResults.map((result) => [providerSessionKey(sessionProvider(result.session), result.session.id), result]));
  const source = new Map(sessions.map((session) => [providerSessionKey(sessionProvider(session), session.id), session]));
  searchResults.forEach(({ session }) => {
    const key = providerSessionKey(sessionProvider(session), session.id);
    const live = source.get(key);
    source.set(key, live ? { ...session, ...live } : session);
  });
  const bounds = dateBounds(filters, now);
  const visible = [...source.values()].filter((session) => {
    const key = providerSessionKey(sessionProvider(session), session.id);
    const hidden = identitySetHas(hiddenIds, session);
    if ((filters.hidden === "hidden") !== hidden) return false;
    if (filters.pinnedOnly && !identitySetHas(pinnedIds, session)) return false;
    const identity = repositoryIdentity(session.repository, repositories, repositoryRoot);
    if (filters.repository && filters.repository !== identity.id) return false;
    if (filters.statuses.length && !filters.statuses.some((status) => statusMatches(session.status, status))) {
      return false;
    }
    const activity = timestampSeconds(session.lastActivity);
    if (bounds.dateFrom !== null && (activity === null || activity < bounds.dateFrom)) return false;
    if (bounds.dateTo !== null && (activity === null || activity > bounds.dateTo)) return false;
    if (!query) return true;
    const local = `${session.title}\n${identity.label}\n${identity.id}\n${session.repository}`
      .toLowerCase().includes(query);
    return local || remote.has(key);
  }).map((session) => ({
    session,
    matches: remote.get(providerSessionKey(sessionProvider(session), session.id))?.matches ?? [],
    pinned: identitySetHas(pinnedIds, session),
    hidden: identitySetHas(hiddenIds, session),
  }));
  return visible.sort((left, right) => compareVisible(left, right, filters, query));
}

export function activeFilterCount(filters: SessionFilters): number {
  return Number(!!filters.query.trim()) + Number(!!filters.repository) +
    Number(filters.statuses.length > 0) + Number(filters.dateRange !== "all") +
    Number(filters.pinnedOnly) + Number(filters.hidden === "hidden") +
    Number(filters.sort !== "relevance");
}

export function parseSessionFilters(search: string): SessionFilters {
  const params = new URLSearchParams(search);
  const statuses = params.getAll("status")
    .flatMap((value) => value.split(","))
    .filter(isSearchStatus);
  const dateRange = isDateRange(params.get("date")) ? params.get("date") as DateRange : "all";
  const sort = isSearchSort(params.get("sort")) ? params.get("sort") as SearchSort : "relevance";
  return {
    ...DEFAULT_SESSION_FILTERS,
    query: (params.get("q") ?? "").slice(0, 500),
    repository: params.get("repo") ?? "",
    statuses: [...new Set(statuses)],
    dateRange,
    dateFrom: params.get("from") ?? "",
    dateTo: params.get("to") ?? "",
    pinnedOnly: params.get("pinned") === "1",
    hidden: params.get("hidden") === "1" ? "hidden" : "visible",
    sort,
  };
}

export function sessionFiltersSearch(filters: SessionFilters): string {
  const params = new URLSearchParams();
  if (filters.query.trim()) params.set("q", filters.query.trim());
  if (filters.repository) params.set("repo", filters.repository);
  filters.statuses.forEach((status) => params.append("status", status));
  if (filters.dateRange !== "all") params.set("date", filters.dateRange);
  if (filters.dateRange === "custom" && filters.dateFrom) params.set("from", filters.dateFrom);
  if (filters.dateRange === "custom" && filters.dateTo) params.set("to", filters.dateTo);
  if (filters.pinnedOnly) params.set("pinned", "1");
  if (filters.hidden === "hidden") params.set("hidden", "1");
  if (filters.sort !== "relevance") params.set("sort", filters.sort);
  const value = params.toString();
  return value ? `?${value}` : "";
}

function compareVisible(
  left: VisibleSession,
  right: VisibleSession,
  filters: SessionFilters,
  query: string,
): number {
  if (left.pinned !== right.pinned) return left.pinned ? -1 : 1;
  if (filters.sort === "oldest") return (left.session.lastActivity ?? 0) - (right.session.lastActivity ?? 0);
  if (filters.sort === "status") {
    const order = ["waiting", "working", "failed", "interrupted", "completed", "idle"];
    return order.indexOf(left.session.status) - order.indexOf(right.session.status) || recent(left, right);
  }
  if (filters.sort === "relevance" && query) {
    const rank = (item: VisibleSession) => {
      const title = item.session.title.toLowerCase();
      if (title === query) return 0;
      if (title.includes(query)) return 1;
      if (item.matches.some((match) => match.kind === "workspace")) return 2;
      return 3;
    };
    const ranked = rank(left) - rank(right);
    if (ranked) return ranked;
  }
  const attention = (item: VisibleSession) => item.session.status === "waiting" || item.session.attention
    ? 0 : item.session.status === "working" ? 1 : 2;
  return attention(left) - attention(right) || recent(left, right) || left.session.id.localeCompare(right.session.id);
}

function repositoryActivityRank(session: SessionSummary): number {
  if (session.status === "working" && !session.attention && session.source !== "external") return 0;
  if (session.status === "waiting" || session.attention) return 1;
  return 2;
}

function identitySetHas(values: ReadonlySet<string>, session: SessionSummary): boolean {
  return values.has(providerSessionKey(sessionProvider(session), session.id))
    || (sessionProvider(session) === "codex" && values.has(session.id));
}

function recent(left: VisibleSession, right: VisibleSession): number {
  return (right.session.lastActivity ?? 0) - (left.session.lastActivity ?? 0);
}

function statusMatches(actual: string, selected: SearchStatus): boolean {
  if (selected === "active") return actual === "working";
  if (selected === "completed") return actual === "completed" || actual === "idle";
  return actual === selected;
}

function timestampSeconds(value?: number | null): number | null {
  if (typeof value !== "number") return null;
  return value > 10_000_000_000 ? value / 1000 : value;
}

function normalizePath(value: string): string {
  return value.replace(/\/+$/, "") || "/";
}

function localDateStart(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? null : date.getTime() / 1000;
}

function localDateEnd(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T23:59:59.999`);
  return Number.isNaN(date.getTime()) ? null : date.getTime() / 1000;
}

function isSearchStatus(value: string): value is SearchStatus {
  return ["active", "waiting", "completed", "failed", "interrupted"].includes(value);
}

function isDateRange(value: string | null): value is DateRange {
  return ["all", "today", "7d", "30d", "custom"].includes(value ?? "");
}

function isSearchSort(value: string | null): value is SearchSort {
  return ["relevance", "recent", "oldest", "status"].includes(value ?? "");
}
