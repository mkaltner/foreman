import { useEffect, useRef, type KeyboardEvent } from "react";
import { activeFilterCount, type RepositoryFilterOption, type SessionFilters, type VisibleSession } from "./session-search";
import { sessionProvider, type ProviderId } from "./protocol";

export function SessionSearchControls({
  filters,
  repositories,
  loading,
  onChange,
  onSearchNow,
}: {
  filters: SessionFilters;
  repositories: RepositoryFilterOption[];
  loading: boolean;
  onChange: (filters: SessionFilters) => void;
  onSearchNow: () => void;
}) {
  const count = activeFilterCount(filters);
  const panelRef = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    const close = () => panelRef.current?.removeAttribute("open");
    const outside = (event: MouseEvent) => {
      const panel = panelRef.current;
      if (panel?.open && event.target instanceof Node && !panel.contains(event.target)) close();
    };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    document.addEventListener("mousedown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, []);
  const keyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      onSearchNow();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      document.querySelector<HTMLElement>("[data-search-result]")?.focus();
    } else if (event.key === "Escape" && filters.query) {
      onChange({ ...filters, query: "" });
    }
  };
  return (
    <section className="session-discovery" aria-label="Session search and filters">
      <div className="search-row">
        <label className="search-field">
          <span aria-hidden="true">⌕</span>
          <input
            type="search"
            value={filters.query}
            placeholder="Search titles and transcripts"
            aria-label="Search sessions"
            onChange={(event) => onChange({ ...filters, query: event.target.value })}
            onKeyDown={keyDown}
          />
          {loading && <i className="search-spinner" role="status" aria-label="Searching" />}
          {filters.query && <button type="button" onClick={() => onChange({ ...filters, query: "" })} aria-label="Clear search">×</button>}
        </label>
        <details className="filter-panel" ref={panelRef}>
          <summary>Filters{count > 0 && <b aria-label={`${count} active filters`}>{count}</b>}</summary>
          <div className="filter-grid">
            <label>Repository or workspace
              <select value={filters.repository} onChange={(event) => onChange({ ...filters, repository: event.target.value })}>
                <option value="">All repositories and workspaces</option>
                {repositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.label}</option>)}
              </select>
            </label>
            <fieldset><legend>Status</legend><div className="status-options">
              {(["active", "waiting", "completed", "failed", "interrupted"] as const).map((status) => <label key={status}><input type="checkbox" checked={filters.statuses.includes(status)} onChange={() => onChange({ ...filters, statuses: filters.statuses.includes(status) ? filters.statuses.filter((value) => value !== status) : [...filters.statuses, status] })} />{titleCase(status)}</label>)}
            </div></fieldset>
            <label>Date
              <select value={filters.dateRange} onChange={(event) => onChange({ ...filters, dateRange: event.target.value as SessionFilters["dateRange"] })}>
                <option value="all">Any time</option><option value="today">Today</option><option value="7d">Last 7 days</option><option value="30d">Last 30 days</option><option value="custom">Custom</option>
              </select>
            </label>
            {filters.dateRange === "custom" && <div className="custom-dates"><label>From<input type="date" value={filters.dateFrom} onChange={(event) => onChange({ ...filters, dateFrom: event.target.value })} /></label><label>To<input type="date" value={filters.dateTo} onChange={(event) => onChange({ ...filters, dateTo: event.target.value })} /></label></div>}
            <label>Visibility
              <select value={filters.hidden} onChange={(event) => onChange({ ...filters, hidden: event.target.value as SessionFilters["hidden"] })}><option value="visible">Normal sessions</option><option value="hidden">Hidden sessions</option></select>
            </label>
            <label className="toggle-filter"><input type="checkbox" checked={filters.pinnedOnly} onChange={(event) => onChange({ ...filters, pinnedOnly: event.target.checked })} />Pinned only</label>
            <label>Sort
              <select value={filters.sort} onChange={(event) => onChange({ ...filters, sort: event.target.value as SessionFilters["sort"] })}><option value="relevance">Relevance</option><option value="recent">Most recent</option><option value="oldest">Oldest</option><option value="status">Status</option></select>
            </label>
            <div className="filter-actions">
              <button className="secondary clear-filters" type="button" disabled={!count} onClick={() => onChange({ query: "", repository: "", statuses: [], dateRange: "all", dateFrom: "", dateTo: "", pinnedOnly: false, hidden: "visible", sort: "relevance" })}>Clear filters</button>
              <button className="primary" type="button" onClick={() => panelRef.current?.removeAttribute("open")}>Done</button>
            </div>
          </div>
        </details>
      </div>
    </section>
  );
}

export function SessionSearchResults({
  results,
  query,
  loading,
  error,
  onOpen,
  onPin,
  onHide,
}: {
  results: VisibleSession[];
  query: string;
  loading: boolean;
  error: string;
  onOpen: (provider: ProviderId, id: string, itemId?: string | null) => void;
  onPin: (provider: ProviderId, id: string) => void;
  onHide: (provider: ProviderId, id: string) => void;
}) {
  if (error) return <div className="search-state error" role="alert"><strong>Search failed</strong><span>{error}</span></div>;
  if (loading && results.length === 0) return <div className="search-state" role="status">Searching sessions…</div>;
  if (results.length === 0) return <div className="search-state"><strong>No matching sessions</strong><span>Try clearing a filter or using a shorter substring.</span></div>;
  return <div className="search-results" aria-live="polite">
    {results.map(({ session, matches, pinned, hidden }, index) => <article className="search-result" key={`${sessionProvider(session)}:${session.id}`}>
      <button data-search-result={index === 0 ? "first" : ""} className="search-result-main" onClick={() => onOpen(sessionProvider(session), session.id, matches.find((match) => match.itemId)?.itemId)}>
        <span className="search-result-title"><strong>{session.title}</strong><ProviderBadge provider={sessionProvider(session)} /><StatusLabel status={session.status} /></span>
        <small title={session.repository}>{session.repository || "Unknown workspace"}</small>
        {matches.slice(0, 3).map((match, matchIndex) => <p key={`${match.itemId ?? match.kind}-${matchIndex}`}><span>{match.kind}</span>{match.snippet}</p>)}
        {!matches.length && query && <p><span>title</span>{session.title}</p>}
        <time>{formatActivity(session.lastActivity)}</time>
      </button>
      <div className="search-result-actions">
        <button className={pinned ? "selected" : ""} onClick={() => onPin(sessionProvider(session), session.id)} aria-label={`${pinned ? "Unpin" : "Pin"} ${session.title}`} title={pinned ? "Unpin session" : "Pin session"}>{pinned ? "★" : "☆"}</button>
        <button onClick={() => onHide(sessionProvider(session), session.id)} aria-label={`${hidden ? "Restore" : "Hide"} ${session.title}`}>{hidden ? "Restore" : "Hide"}</button>
      </div>
    </article>)}
  </div>;
}

function ProviderBadge({ provider }: { provider: ProviderId }) {
  return <span className={`provider-badge ${provider}`}>{provider === "claude-code" ? "Claude Code" : "Codex"}</span>;
}

function StatusLabel({ status }: { status: string }) {
  const value = status === "working" ? "Active" : status === "idle" ? "Completed" : titleCase(status);
  return <span className={`status-pill ${status}`}>{value}</span>;
}

function formatActivity(value?: number | null): string {
  if (!value) return "Activity time unavailable";
  return new Date(value < 10_000_000_000 ? value * 1000 : value).toLocaleString();
}

function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}
