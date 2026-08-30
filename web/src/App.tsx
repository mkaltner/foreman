import {
  Fragment,
  type ChangeEvent,
  type ClipboardEvent,
  type FormEvent,
  isValidElement,
  type ReactNode,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ApprovalCard, approvalAttentionLabel } from "./ApprovalCard";
import { InputCard, inputAttentionLabel } from "./InputCard";
import { AboutSection } from "./about";
import { Dashboard } from "./Dashboard";
import { conversationBlocks, type ActivityDetail } from "./activity-detail";
import { messageDraft, updateMessageDraft } from "./drafts";
import { UnifiedDashboard } from "./UnifiedDashboard";
import { UnifiedHostConnections } from "./unified-client";
import { forgetHostSnapshot, loadHostSnapshots, saveHostSnapshots } from "./unified-storage";
import { mergeHostSnapshot, projectHostSnapshot, sessionIdentityKey, type HostOverviewSnapshot, type UnifiedAttentionItem } from "./unified";
import { SessionSearchControls, SessionSearchResults } from "./SessionDiscovery";
import { formatDuration, recordRecentActivity, type RecentActivityEntry } from "./dashboard";
import {
  ForemanWebClient,
  inferPagePort,
  parseEndpoint,
  type ConnectionState,
} from "./client";
import { clipboardImageFiles, processImages, type ProcessedImage } from "./images";
import {
  browserNotificationState,
  clearTurnNotification,
  notificationStateDescription,
  requestBrowserNotifications,
  showBrowserTestNotification,
  showTurnNotification,
  TurnNotificationMonitor,
  type BrowserNotificationState,
  type NotificationDeliveryMethod,
  type TurnNotification,
} from "./notifications";
import {
  parseSessionPresence,
  sessionIsFocused,
  sessionPresenceKey,
  SessionPresenceProjectionGuard,
  type SessionPresence,
} from "./session-presence";
import {
  setRepositoryOverride,
  type NotificationPreferences,
  type RepositoryNotificationOverride,
} from "./notification-preferences";
import {
  applySessionEvent,
  applySessionSummaryEventBatch,
  applySessionSummaryEvent,
  liveActivityLabel,
  liveActivityMessage,
  providerEnabled,
  providerCatalogResponseIsCurrent,
  shouldShowProviderIdentity,
  soleEnabledProvider,
  isProviderId,
  routeForSession,
  providerSessionKey,
  reconcileSessionSummaries,
  reconcileSessionSettings,
  sessionProvider,
  type AccountUsage,
  type AccessLevelInfo,
  type ApprovalEventPayload,
  type ApprovalRequest,
  type HelloPayload,
  type InputEventPayload,
  type InputRequest,
  type ModelInfo,
  type PermissionModeInfo,
  type ProviderId,
  type ProviderInfo,
  type PairedClient,
  type RepositoryInfo,
  type ServiceStatus,
  type DiagnosticEvent,
  type SessionEvent,
  type SessionEventPayload,
  type SessionSummary,
  type SessionSearchResult,
  type WireMessage,
} from "./protocol";
import {
  ACCENTS,
  addStoredHost,
  createStoredHost,
  forgetStoredHost,
  hostIdFromUrl,
  loadAppearance,
  loadCollapsedRepositories,
  loadHostNotificationOverride,
  loadHostRegistry,
  loadNotificationPreferences,
  loadSessionSearch,
  loadSessionOrganization,
  saveAppearance,
  saveCollapsedRepositories,
  clearHostNotificationOverride,
  clearRememberedSession,
  loadRememberedSession,
  saveHostRegistry,
  saveNotificationPreferences,
  saveRememberedSession,
  saveSessionSearch,
  saveSessionOrganization,
  selectStoredHost,
  suggestedHostDisplayName,
  updateStoredHost,
  withHostInSearch,
  type Appearance,
  type HostRegistry,
  type NewStoredHost,
  type StoredHost,
} from "./storage";
import {
  activeFilterCount,
  dateBounds,
  filterSessions,
  parseSessionFilters,
  repositoryIdentity,
  repositoryFilterOptions,
  repositorySessionGroups,
  sessionFiltersSearch,
  showSessionCardRepository,
  toggleCollapsedRepository,
  type CollapsedRepositoriesByHost,
  type SessionCardRenderContext,
  type SessionFilters,
  type RepositoryFilterOption,
  type VisibleSession,
} from "./session-search";
import { applyAppearance } from "./theme";
import {
  confirmSessionAction,
  createSubmissionGuard,
  formatActivity,
  isNearBottom,
  linkifyPlainText,
  parseAssistantContent,
  parseWebRoute,
  reasoningDescription,
  reasoningLabel,
  webRoutePath,
  type AppDirective,
  type WebRoute,
} from "./ui";

export type View = "dashboard" | "sessions" | "detail" | "settings";

interface WorkspaceFileTarget {
  path: string;
  line?: number;
}

interface WorkspaceFile extends WorkspaceFileTarget {
  content: string;
}

export function appShellClassName(view: View): string {
  return view === "settings" ? "app-shell settings-shell" : "app-shell";
}

export function sessionActionRequest(
  action: "archive" | "delete",
  session: SessionSummary,
): { type: string; payload: Record<string, unknown> } {
  const provider = sessionProvider(session);
  if (provider === "claude-code") {
    if (action !== "delete") throw new Error("Claude Code does not support session archive");
    if (!session.repositoryId) throw new Error("Claude session workspace is unavailable");
    return {
      type: "provider.session.delete",
      payload: {
        provider,
        sessionId: session.id,
        repositoryId: session.repositoryId,
        confirm: true,
      },
    };
  }
  return {
    type: `session.${action}`,
    payload: {
      sessionId: session.id,
      ...(action === "delete" ? { confirm: true } : {}),
    },
  };
}
type PairingSettings = Omit<NewStoredHost, "deviceToken"> & { deviceName: string };

export function reconcileSessionPending<T extends { id: string; sessionId: string }>(
  current: T[],
  refreshed: T[],
  sessionId: string,
  baseline: T[],
): T[] {
  const baselineById = new Map(
    baseline.filter((item) => item.sessionId === sessionId).map((item) => [item.id, item]),
  );
  const currentSessionIds = new Set(
    current.filter((item) => item.sessionId === sessionId).map((item) => item.id),
  );
  const removedIds = new Set(
    [...baselineById.keys()].filter((id) => !currentSessionIds.has(id)),
  );
  const newer = current.filter((item) =>
    item.sessionId === sessionId && baselineById.get(item.id) !== item
  );
  const protectedIds = new Set([...removedIds, ...newer.map((item) => item.id)]);
  return [
    ...current.filter((item) => item.sessionId !== sessionId),
    ...refreshed.filter((item) => item.sessionId === sessionId && !protectedIds.has(item.id)),
    ...newer,
  ];
}

function App() {
  const requestedRoute = useRef(parseWebRoute(window.location.pathname)).current;
  const initialRegistry = useRef(loadHostRegistry()).current;
  const requestedHostId = hostIdFromUrl();
  const initialHostId = initialRegistry.hosts.some(({ id }) => id === requestedHostId)
    ? requestedHostId
    : initialRegistry.activeHostId;
  const rememberedAtLaunch = loadRememberedSession(initialHostId);
  const normalizedLaunchPath = window.location.pathname.replace(/\/+$/, "") || "/";
  const initialRoute = useRef<WebRoute>(
    (normalizedLaunchPath === "/" || normalizedLaunchPath === "/sessions") && rememberedAtLaunch
      ? {
        view: "detail",
        provider: rememberedAtLaunch.provider,
        sessionId: rememberedAtLaunch.sessionId,
      }
      : requestedRoute,
  ).current;
  const initialFilters = useRef(parseSessionFilters(
    window.location.search || (initialHostId ? loadSessionSearch(initialHostId) : ""),
  )).current;
  const [hostRegistry, setHostRegistry] = useState<HostRegistry>(() =>
    initialHostId ? selectStoredHost(initialRegistry, initialHostId) : initialRegistry,
  );
  const hostRegistryRef = useRef(hostRegistry);
  const activeHost = hostRegistry.hosts.find(({ id }) => id === hostRegistry.activeHostId) ?? null;
  const [appearance, setAppearance] = useState<Appearance>(() => loadAppearance(initialHostId));
  const [connection, setConnection] = useState<ConnectionState>("disconnected");
  const [connectionDetail, setConnectionDetail] = useState("");
  const [hello, setHello] = useState<HelloPayload | null>(null);
  const [serviceStatus, setServiceStatus] = useState<ServiceStatus | null>(null);
  const [accountUsage, setAccountUsage] = useState<AccountUsage | null>(null);
  const [pairedClients, setPairedClients] = useState<PairedClient[]>([]);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [current, setCurrent] = useState<SessionSummary | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const [selectedProvider, setSelectedProvider] = useState<ProviderId>(
    initialRoute.view === "detail" ? initialRoute.provider : "codex",
  );
  const [highlightItemId, setHighlightItemId] = useState<string | null>(null);
  const [focusedApprovalId, setFocusedApprovalId] = useState<string | null>(null);
  const [approvals, setApprovals] = useState<ApprovalRequest[]>([]);
  const [inputs, setInputs] = useState<InputRequest[]>([]);
  const selectedIdRef = useRef<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const selectedProviderRef = useRef<ProviderId>(
    initialRoute.view === "detail" ? initialRoute.provider : "codex",
  );
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [accessLevels, setAccessLevels] = useState<AccessLevelInfo[]>([]);
  const [providers, setProviders] = useState<ProviderInfo[]>([]);
  const [providerCatalogLoaded, setProviderCatalogLoaded] = useState(false);
  const [claudeModels, setClaudeModels] = useState<ModelInfo[]>([]);
  const [claudePermissionModes, setClaudePermissionModes] = useState<PermissionModeInfo[]>([]);
  const [repositories, setRepositories] = useState<RepositoryInfo[]>([]);
  const [recentActivity, setRecentActivity] = useState<RecentActivityEntry[]>([]);
  const [view, setView] = useState<View>(initialRoute.view);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [newSessionOpen, setNewSessionOpen] = useState(false);
  const [notificationPreferences, setNotificationPreferences] = useState(() =>
    loadNotificationPreferences(initialHostId)
  );
  const [hostNotificationOverride, setHostNotificationOverride] = useState(() =>
    loadHostNotificationOverride(initialHostId) !== null
  );
  const [notificationState, setNotificationState] = useState<BrowserNotificationState>(() =>
    browserNotificationState()
  );
  const [searchFilters, setSearchFilters] = useState<SessionFilters>(initialFilters);
  const [searchResults, setSearchResults] = useState<SessionSearchResult[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState("");
  const [searchRevision, setSearchRevision] = useState(0);
  const [organization, setOrganization] = useState(() => loadSessionOrganization(initialHostId));
  const [hostSetupOpen, setHostSetupOpen] = useState(false);
  const [hostSnapshots, setHostSnapshots] = useState<Map<string, HostOverviewSnapshot>>(() => loadHostSnapshots());
  const [messageDrafts, setMessageDrafts] = useState<ReadonlyMap<string, string>>(() => new Map());
  const [collapsedRepositoriesByHost, setCollapsedRepositoriesByHost] =
    useState<CollapsedRepositoriesByHost>(() => {
      const collapsed = new Map<string, ReadonlySet<string>>();
      if (initialHostId) collapsed.set(initialHostId, loadCollapsedRepositories(initialHostId));
      return collapsed;
    });
  const scrollPositions = useRef(new Map<string, number>());
  const notificationPreferencesRef = useRef(notificationPreferences);
  const searchFiltersRef = useRef(initialFilters);
  const lastImmediateSearch = useRef(0);
  const lastBackendSearchKey = useRef("");
  const searchGeneration = useRef(0);
  const sessionsRef = useRef<SessionSummary[]>([]);
  const sessionListAuthoritativeRef = useRef<Record<ProviderId, boolean>>({
    codex: false,
    "claude-code": false,
  });
  const repositoriesRef = useRef<RepositoryInfo[]>([]);
  const repositoryRootRef = useRef("");
  const currentRef = useRef<SessionSummary | null>(null);
  const connectedRef = useRef(false);
  const activeHostIdRef = useRef<string | null>(initialHostId);
  const viewRef = useRef<View>(initialRoute.view);
  const clientRef = useRef<ForemanWebClient | null>(null);
  const dashboardSubscriptions = useRef(new Set<string>());
  const pendingDashboardEvents = useRef(new Map<string, SessionEvent[]>());
  const dashboardFrame = useRef<number | null>(null);
  const notificationMonitor = useRef(new TurnNotificationMonitor());
  const focusedSessionsRef = useRef<Set<string>>(new Set());
  const publishedPresenceRef = useRef<string | null | undefined>(undefined);
  const presenceProjectionGuardRef = useRef(new SessionPresenceProjectionGuard());
  const sessionOpenGenerationRef = useRef(0);
  const providerCatalogRevisionRef = useRef(0);
  const refreshGenerationRef = useRef(0);
  const refreshStateRef = useRef<((hostId: string, reconnected?: boolean) => Promise<void>) | null>(null);
  const openSessionRef = useRef<(provider: ProviderId, id: string, updateHistory?: boolean) => void>(() => undefined);
  const enterSessionsRef = useRef<(replace?: boolean) => void>(() => undefined);
  const notificationOpenRef = useRef<(hostId: string, sessionId: string) => void>(() => undefined);
  const unifiedConnectionsRef = useRef<UnifiedHostConnections | null>(null);

  if (!unifiedConnectionsRef.current) {
    unifiedConnectionsRef.current = new UnifiedHostConnections((snapshot) => {
      setHostSnapshots((previous) => {
        const cached = previous.get(snapshot.hostId);
        const safeSnapshot = cached && (
          snapshot.connection !== "connected" || snapshot.foremanVersion === null
        ) ? { ...cached, connection: snapshot.connection } : snapshot;
        const next = mergeHostSnapshot(previous, safeSnapshot);
        saveHostSnapshots(next);
        return next;
      });
    });
  }

  const persistRegistry = useCallback((next: HostRegistry) => {
    hostRegistryRef.current = next;
    activeHostIdRef.current = next.activeHostId;
    saveHostRegistry(next);
    setHostRegistry(next);
  }, []);

  const mutateHost = useCallback((hostId: string, update: Parameters<typeof updateStoredHost>[2]) => {
    const next = updateStoredHost(hostRegistryRef.current, hostId, update);
    if (next === hostRegistryRef.current) return;
    hostRegistryRef.current = next;
    saveHostRegistry(next);
    setHostRegistry(next);
  }, []);

  const updateRoute = useCallback((route: WebRoute, replace = false) => {
    const path = webRoutePath(route);
    const search = withHostInSearch(
      sessionFiltersSearch(searchFiltersRef.current),
      activeHostIdRef.current,
    );
    if (window.location.pathname === path && window.location.search === search) return;
    window.history[replace ? "replaceState" : "pushState"](null, "", `${path}${search}`);
  }, []);

  const shouldDisplayTurnNotification = useCallback((notification: TurnNotification) => {
    const locallyFocused =
      document.visibilityState === "visible" &&
      document.hasFocus() &&
      viewRef.current === "detail" &&
      selectedProviderRef.current === "codex" &&
      selectedIdRef.current === notification.sessionId;
    return !locallyFocused && !sessionIsFocused(focusedSessionsRef.current, "codex", notification.sessionId);
  }, []);

  useEffect(() => applyAppearance(appearance), [appearance]);
  useEffect(() => {
    notificationPreferencesRef.current = notificationPreferences;
    notificationMonitor.current.configure(notificationPreferences, (notification) => {
      if (shouldDisplayTurnNotification(notification)) {
        void showTurnNotification(notification).catch(() => undefined);
      }
    });
  }, [notificationPreferences, shouldDisplayTurnNotification]);
  useEffect(() => { searchFiltersRef.current = searchFilters; }, [searchFilters]);
  useEffect(() => { sessionsRef.current = sessions; }, [sessions]);
  useEffect(() => { repositoriesRef.current = repositories; }, [repositories]);
  useEffect(() => {
    repositoryRootRef.current = serviceStatus?.repositoryRoot ?? "";
  }, [serviceStatus?.repositoryRoot]);
  useEffect(() => { currentRef.current = current; }, [current]);
  useEffect(() => { connectedRef.current = connection === "connected"; }, [connection]);
  useEffect(() => { viewRef.current = view; }, [view]);
  const overviewConnectionSignature = hostRegistry.hosts
    .map(({ id, host, webPort, deviceToken }) => `${id}:${host}:${webPort}:${deviceToken}`)
    .join("|");
  useEffect(() => {
    unifiedConnectionsRef.current?.start(hostRegistryRef.current.hosts, activeHostIdRef.current);
    return () => unifiedConnectionsRef.current?.stop();
  }, [overviewConnectionSignature, hostRegistry.activeHostId]);
  useEffect(() => {
    const hostId = activeHostIdRef.current;
    if (!hostId) return;
    setHostSnapshots((previous) => {
      const cached = previous.get(hostId);
      const snapshot = connection === "connected" && serviceStatus
        ? projectHostSnapshot(hostId, sessions, approvals, serviceStatus, connection, Date.now(), inputs)
        : cached ? { ...cached, connection } : projectHostSnapshot(hostId, [], [], null, connection);
      const next = mergeHostSnapshot(previous, snapshot);
      saveHostSnapshots(next);
      return next;
    });
  }, [approvals, connection, inputs, serviceStatus, sessions]);
  useEffect(() => {
    const refreshPermission = () => setNotificationState(browserNotificationState());
    window.addEventListener("focus", refreshPermission);
    document.addEventListener("visibilitychange", refreshPermission);
    return () => {
      window.removeEventListener("focus", refreshPermission);
      document.removeEventListener("visibilitychange", refreshPermission);
    };
  }, []);
  useEffect(() => {
    saveHostRegistry(hostRegistry);
    updateRoute(initialRoute, true);
    // Initial URL normalization only; later host changes are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (viewRef.current === "dashboard" || viewRef.current === "sessions") {
      const filtersSearch = sessionFiltersSearch(searchFilters);
      if (activeHostIdRef.current) saveSessionSearch(activeHostIdRef.current, filtersSearch);
      const search = withHostInSearch(filtersSearch, activeHostIdRef.current);
      window.history.replaceState(null, "", `${window.location.pathname}${search}`);
    }
  }, [searchFilters]);

  useEffect(() => {
    const generation = ++searchGeneration.current;
    const query = searchFilters.query.trim();
    const immediate = searchRevision !== lastImmediateSearch.current;
    lastImmediateSearch.current = searchRevision;
    if (!query || connection !== "connected") {
      setSearchResults([]);
      setSearchLoading(false);
      setSearchError("");
      return;
    }
    const backendSearchKey = JSON.stringify([
      query.toLocaleLowerCase(),
      searchFilters.repository,
      searchFilters.statuses,
      searchFilters.dateRange,
      searchFilters.dateFrom,
      searchFilters.dateTo,
    ]);
    if (lastBackendSearchKey.current !== backendSearchKey) {
      lastBackendSearchKey.current = backendSearchKey;
      setSearchResults([]);
    }
    setSearchLoading(true);
    setSearchError("");
    const timer = window.setTimeout(() => {
      const bounds = dateBounds(searchFilters);
      void clientRef.current?.request<{
        results: SessionSearchResult[];
      } & Record<string, unknown>>("session.search", {
        query,
        repository: searchFilters.repository || null,
        statuses: searchFilters.statuses,
        dateFrom: bounds.dateFrom,
        dateTo: bounds.dateTo,
        limit: 100,
      }).then((result) => {
        if (searchGeneration.current === generation) setSearchResults(result.results);
      }).catch((caught) => {
        if (searchGeneration.current === generation && !/cancel/i.test(String(caught))) {
          setSearchError(caught instanceof Error ? caught.message : "Search failed");
        }
      }).finally(() => {
        if (searchGeneration.current === generation) setSearchLoading(false);
      });
    }, immediate ? 0 : 300);
    return () => window.clearTimeout(timer);
  }, [
    connection,
    searchFilters.query,
    searchFilters.repository,
    searchFilters.statuses,
    searchFilters.dateRange,
    searchFilters.dateFrom,
    searchFilters.dateTo,
    searchRevision,
  ]);

  useEffect(() => {
    if (window.isSecureContext && "serviceWorker" in navigator) {
      void navigator.serviceWorker.register("/sw.js").catch(() => undefined);
    }
    const openFromNotification = (event: Event) => {
      const detail = (event as CustomEvent<{ hostId?: string; sessionId?: string }>).detail;
      if (detail?.hostId && detail.sessionId) notificationOpenRef.current(detail.hostId, detail.sessionId);
    };
    const serviceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === "notification.open" && typeof event.data.hostId === "string" && typeof event.data.sessionId === "string") {
        notificationOpenRef.current(event.data.hostId, event.data.sessionId);
      }
    };
    window.addEventListener("foreman.notification.open", openFromNotification);
    navigator.serviceWorker?.addEventListener("message", serviceWorkerMessage);
    return () => {
      window.removeEventListener("foreman.notification.open", openFromNotification);
      navigator.serviceWorker?.removeEventListener("message", serviceWorkerMessage);
    };
  }, []);

  const queueDashboardEvent = useCallback((provider: ProviderId, sessionId: string, event: SessionEvent) => {
    const pending = pendingDashboardEvents.current;
    const key = providerSessionKey(provider, sessionId);
    const events = pending.get(key) ?? [];
    pending.set(key, [...events.slice(-49), event]);
    if (dashboardFrame.current !== null) return;
    dashboardFrame.current = requestAnimationFrame(() => {
      dashboardFrame.current = null;
      const buffered = new Map(pendingDashboardEvents.current);
      pendingDashboardEvents.current.clear();
      setSessions((previous) => {
        const next = applySessionSummaryEventBatch(previous, buffered);
        sessionsRef.current = next;
        return next;
      });
    });
  }, []);

  useEffect(() => () => {
    if (dashboardFrame.current !== null) cancelAnimationFrame(dashboardFrame.current);
  }, []);

  const onEvent = useCallback((message: WireMessage) => {
    if (message.type === "session.presence.event") {
      presenceProjectionGuardRef.current.invalidate();
      focusedSessionsRef.current = parseSessionPresence(message.payload);
      return;
    }
    if (message.type === "service.event") {
      setServiceStatus({ ...(message.payload as unknown as ServiceStatus), receivedAt: Date.now() });
      void clientRef.current?.request<{ clients: PairedClient[] } & Record<string, unknown>>("client.list")
        .then((result) => setPairedClients(result.clients))
        .catch(() => undefined);
      return;
    }
    if (["approval.requested", "approval.updated", "approval.resolved"].includes(message.type)) {
      const approvalPayload = message.payload as unknown as ApprovalEventPayload;
      const approval = approvalPayload.approval;
      if (!approval?.id || !approval.sessionId) return;
      setApprovals((previous) => {
        const next = previous.some((item) => item.id === approval.id)
          ? previous.map((item) => item.id === approval.id ? approval : item)
          : [...previous, approval];
        return next;
      });
      const resolved = approval.status === "resolved" || approval.status === "expired";
      const updateSession = (session: SessionSummary): SessionSummary => session.id !== approval.sessionId ? session : {
        ...session,
        status: resolved && session.status === "waiting" ? "working" : "waiting",
        attention: !resolved,
        activeTurnId: approval.turnId ?? session.activeTurnId,
        waitType: resolved ? null : approval.type.startsWith("unsupported") ? "input" : "approval",
        waitDescription: resolved ? null : approvalAttentionLabel(approval),
        activityLabel: resolved ? "Approval resolved" : approvalAttentionLabel(approval),
        lastActivity: approvalPayload.activityAt ?? session.lastActivity,
      };
      setSessions((previous) => {
        const next = previous.map(updateSession);
        sessionsRef.current = next;
        return next;
      });
      setCurrent((previous) => previous ? updateSession(previous) : previous);
      const feedSession = sessionsRef.current.find((session) => session.id === approval.sessionId);
      const hostId = activeHostIdRef.current ?? "";
      if (resolved) {
        const tag = notificationMonitor.current.resolveApproval(hostId, approval.id);
        void clearTurnNotification(tag).catch(() => undefined);
      } else if (feedSession && message.type === "approval.requested") {
        const repositoryId = repositoryIdentity(
          feedSession.repository,
          repositoriesRef.current,
          repositoryRootRef.current,
        ).id;
        const notification = notificationMonitor.current.observeApproval(
          hostId,
          approval.sessionId,
          approval.id,
          repositoryId,
        );
        if (notification && shouldDisplayTurnNotification(notification)) {
          void showTurnNotification(notification).catch(() => undefined);
        }
      }
      if (feedSession && (message.type === "approval.requested" || message.type === "approval.resolved")) {
        setRecentActivity((previous) => recordRecentActivity(previous, feedSession, {
          kind: "activity",
          label: message.type === "approval.requested" ? "Approval requested" : "Approval resolved",
          activityAt: approvalPayload.activityAt,
          observedAt: approvalPayload.observedAt,
        }));
      }
      if (resolved) window.setTimeout(() => {
        setApprovals((previous) => previous.filter((item) => item.id !== approval.id));
        setFocusedApprovalId((current) => current === approval.id ? null : current);
      }, 5_000);
      return;
    }
    if (["input.requested", "input.updated", "input.resolved"].includes(message.type)) {
      const inputPayload = message.payload as unknown as InputEventPayload;
      const pending = inputPayload.input;
      if (!pending?.id || !pending.sessionId) return;
      setInputs((previous) => previous.some(({ id }) => id === pending.id)
        ? previous.map((item) => item.id === pending.id ? pending : item)
        : [...previous, pending]);
      const resolved = pending.status === "resolved" || pending.status === "expired";
      const updateSession = (session: SessionSummary): SessionSummary => session.id !== pending.sessionId ? session : {
        ...session,
        status: resolved && session.status === "waiting" ? "working" : "waiting",
        attention: !resolved,
        activeTurnId: pending.turnId ?? session.activeTurnId,
        waitType: resolved ? null : "input",
        waitDescription: resolved ? null : inputAttentionLabel(pending),
        activityLabel: resolved ? "Input request resolved" : inputAttentionLabel(pending),
        lastActivity: inputPayload.activityAt ?? session.lastActivity,
      };
      setSessions((previous) => {
        const next = previous.map(updateSession);
        sessionsRef.current = next;
        return next;
      });
      setCurrent((previous) => previous ? updateSession(previous) : previous);
      const feedSession = sessionsRef.current.find((session) => session.id === pending.sessionId);
      const hostId = activeHostIdRef.current ?? "";
      if (resolved) {
        const tag = notificationMonitor.current.resolveApproval(hostId, pending.id);
        void clearTurnNotification(tag).catch(() => undefined);
      } else if (feedSession && message.type === "input.requested") {
        const repositoryId = repositoryIdentity(feedSession.repository, repositoriesRef.current, repositoryRootRef.current).id;
        const notification = notificationMonitor.current.observeApproval(hostId, pending.sessionId, pending.id, repositoryId);
        if (notification && shouldDisplayTurnNotification(notification)) {
          void showTurnNotification(notification).catch(() => undefined);
        }
      }
      if (resolved) window.setTimeout(() => setInputs((previous) => previous.filter(({ id }) => id !== pending.id)), 5_000);
      return;
    }
    if (message.type === "provider.event") {
      const nextProviders = (message.payload as { providers?: ProviderInfo[] }).providers;
      if (Array.isArray(nextProviders)) {
        providerCatalogRevisionRef.current += 1;
        sessionListAuthoritativeRef.current = { codex: false, "claude-code": false };
        const enabled = new Set(
          nextProviders
            .filter((provider) => providerEnabled(provider) && isProviderId(provider.id))
            .map(({ id }) => id),
        );
        const openable = new Set(
          nextProviders
            .filter((provider) => providerEnabled(provider) && provider.available && isProviderId(provider.id))
            .map(({ id }) => id),
        );
        setProviders(nextProviders);
        setProviderCatalogLoaded(true);
        setSessions((previous) => {
          const next = previous.filter((session) => openable.has(sessionProvider(session)));
          sessionsRef.current = next;
          return next;
        });
        setSearchResults((previous) => previous.filter(({ session }) => openable.has(sessionProvider(session))));
        setCurrent((previous) => previous && openable.has(sessionProvider(previous)) ? previous : null);
        const hostId = activeHostIdRef.current;
        const remembered = loadRememberedSession(hostId);
        if (remembered && !enabled.has(remembered.provider)) clearRememberedSession(hostId);
        if (viewRef.current === "detail" && !openable.has(selectedProviderRef.current)) {
          sessionOpenGenerationRef.current += 1;
          selectedIdRef.current = null;
          selectedProviderRef.current = "codex";
          currentRef.current = null;
          viewRef.current = "sessions";
          setSelectedId(null);
          setSelectedProvider("codex");
          setCurrent(null);
          setView("sessions");
          updateRoute({ view: "sessions" }, true);
        }
        if (hostId) queueMicrotask(() => {
          void refreshStateRef.current?.(hostId).catch(() => undefined);
        });
      }
      return;
    }
    if (message.type === "usage.event") {
      setAccountUsage(message.payload as unknown as AccountUsage);
      return;
    }
    if (message.type !== "session.event") return;
    const payload = message.payload as unknown as SessionEventPayload;
    if (!payload.sessionId || !payload.event) return;
    const provider = payload.provider ?? "codex";
    if (!isProviderId(provider)) return;
    const identityKey = providerSessionKey(provider, payload.sessionId);
    const feedSession = payload.event.session ?? sessionsRef.current.find(
      (session) => session.id === payload.sessionId && sessionProvider(session) === provider,
    );
    if (provider === "codex") {
      setRecentActivity((previous) => recordRecentActivity(previous, feedSession, payload.event));
    }
    if (provider === "codex" && payload.event.kind !== "lifecycle" && payload.event.observedAt) {
      const lastEvent = new Date(
        payload.event.observedAt < 10_000_000_000
          ? payload.event.observedAt * 1000
          : payload.event.observedAt,
      ).toISOString();
      setServiceStatus((previous) => !previous || previous.codex.lastEvent === lastEvent
        ? previous
        : { ...previous, codex: { ...previous.codex, lastEvent } });
    }
    if (payload.event.kind === "lifecycle") {
      if (payload.event.action === "removed") {
        const hostId = activeHostIdRef.current;
        const remembered = loadRememberedSession(hostId);
        if (remembered?.provider === provider && remembered.sessionId === payload.sessionId) {
          clearRememberedSession(hostId);
        }
        setSessions((previous) => {
          const next = previous.filter((session) => providerSessionKey(sessionProvider(session), session.id) !== identityKey);
          sessionsRef.current = next;
          return next;
        });
        setSearchResults((previous) => previous.filter(({ session }) => providerSessionKey(sessionProvider(session), session.id) !== identityKey));
        setOrganization((previous) => {
          const next = {
            pinnedIds: previous.pinnedIds.filter((id) => id !== identityKey),
            hiddenIds: previous.hiddenIds.filter((id) => id !== identityKey),
          };
          if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
          return next;
        });
        if (selectedIdRef.current === payload.sessionId && selectedProviderRef.current === provider) {
          sessionOpenGenerationRef.current += 1;
          selectedIdRef.current = null;
          selectedProviderRef.current = "codex";
          currentRef.current = null;
          viewRef.current = "sessions";
          setSelectedId(null);
          setSelectedProvider("codex");
          setCurrent(null);
          setView("sessions");
          updateRoute({ view: "sessions" }, true);
        }
      } else if (payload.event.session) {
        setSessions((previous) => {
          const projected = { ...payload.event.session!, provider };
          const next = previous.some((session) => providerSessionKey(sessionProvider(session), session.id) === identityKey)
            ? previous.map((session) => providerSessionKey(sessionProvider(session), session.id) === identityKey ? projected : session)
            : [projected, ...previous];
          sessionsRef.current = next;
          return next;
        });
        setSearchRevision((value) => value + 1);
      }
      return;
    }
    if (payload.event.kind === "status" && payload.event.status) {
      if (searchFiltersRef.current.query.trim()) {
        setSearchRevision((value) => value + 1);
      }
      const observedSession = payload.event.session ?? sessionsRef.current.find(
        (session) => session.id === payload.sessionId && sessionProvider(session) === provider,
      );
      const repositoryId = repositoryIdentity(
        observedSession?.repository ?? "",
        repositoriesRef.current,
        repositoryRootRef.current,
      ).id;
      const notificationDecision = provider === "codex" ? notificationMonitor.current.observeDecision(
        {
          hostId: activeHostIdRef.current ?? "",
          sessionId: payload.sessionId,
          repositoryId,
          status: payload.event.status,
          turnId: payload.event.turnId ?? observedSession?.activeTurnId,
          activeTurnStartedAt: payload.event.startedAt ?? observedSession?.activeTurnStartedAt,
          waitType: payload.event.waitType ?? observedSession?.waitType,
        },
      ) : { notification: null };
      const displayNotification = notificationDecision.notification
        ? shouldDisplayTurnNotification(notificationDecision.notification)
        : false;
      if (notificationDecision.clearTag) {
        void clearTurnNotification(notificationDecision.clearTag)
          .then(() => notificationDecision.notification && displayNotification
            ? showTurnNotification(notificationDecision.notification)
            : undefined)
          .catch(() => undefined);
      } else if (notificationDecision.notification && displayNotification) {
        void showTurnNotification(notificationDecision.notification).catch(() => undefined);
      }
      const active = ["working", "waiting"].includes(payload.event.status);
      if (active && !dashboardSubscriptions.current.has(identityKey)) {
        dashboardSubscriptions.current.add(identityKey);
        void clientRef.current?.request("provider.session.subscribe", { provider, sessionId: payload.sessionId })
          .catch(() => dashboardSubscriptions.current.delete(identityKey));
      } else if (!active && (selectedIdRef.current !== payload.sessionId || selectedProviderRef.current !== provider)) {
        dashboardSubscriptions.current.delete(identityKey);
        void clientRef.current?.request("provider.session.unsubscribe", { provider, sessionId: payload.sessionId })
          .catch(() => undefined);
      }
      if (!sessionsRef.current.some((session) => providerSessionKey(sessionProvider(session), session.id) === identityKey)) {
        void clientRef.current?.request<{ sessions: SessionSummary[] } & Record<string, unknown>>(
          "provider.session.list", { provider },
        ).then((result) => {
          const incoming = result.sessions.map((session) => ({ ...session, provider }));
          setSessions((previous) => {
            const retained = previous.filter((session) => sessionProvider(session) !== provider);
            const next = reconcileSessionSummaries(previous, [...retained, ...incoming]);
            sessionsRef.current = next;
            return next;
          });
        }).catch(() => undefined);
      }
    }
    if (["activity", "assistant.delta"].includes(payload.event.kind)) {
      queueDashboardEvent(provider, payload.sessionId, payload.event);
    } else {
      setSessions((previous) => {
        const next = previous.map((session) =>
          session.id === payload.sessionId && sessionProvider(session) === provider
            ? applySessionSummaryEvent(session, payload.event)
            : session,
        );
        sessionsRef.current = next;
        return next;
      });
    }
    if (viewRef.current === "detail") {
      setCurrent((session) =>
        session?.id === payload.sessionId && sessionProvider(session) === provider ? applySessionEvent(session, payload.event) : session,
      );
    }
  }, [queueDashboardEvent, shouldDisplayTurnNotification]);

  const clearHostProjections = useCallback(() => {
    providerCatalogRevisionRef.current += 1;
    refreshGenerationRef.current += 1;
    sessionOpenGenerationRef.current += 1;
    searchGeneration.current += 1;
    if (dashboardFrame.current !== null) cancelAnimationFrame(dashboardFrame.current);
    dashboardFrame.current = null;
    pendingDashboardEvents.current.clear();
    dashboardSubscriptions.current.clear();
    notificationMonitor.current.dispose();
    notificationMonitor.current = new TurnNotificationMonitor();
    notificationMonitor.current.configure(notificationPreferencesRef.current, (notification) => {
      if (shouldDisplayTurnNotification(notification)) {
        void showTurnNotification(notification).catch(() => undefined);
      }
    });
    presenceProjectionGuardRef.current.invalidate();
    focusedSessionsRef.current = new Set();
    publishedPresenceRef.current = undefined;
    sessionsRef.current = [];
    sessionListAuthoritativeRef.current = { codex: false, "claude-code": false };
    currentRef.current = null;
    selectedIdRef.current = null;
    selectedProviderRef.current = "codex";
    setSessions([]);
    setCurrent(null);
    setSelectedId(null);
    setSelectedProvider("codex");
    setHighlightItemId(null);
    setFocusedApprovalId(null);
    setApprovals([]);
    setInputs([]);
    setModels([]);
    setAccessLevels([]);
    setProviders([]);
    setProviderCatalogLoaded(false);
    setClaudeModels([]);
    setClaudePermissionModes([]);
    setRepositories([]);
    setServiceStatus(null);
    setAccountUsage(null);
    setPairedClients([]);
    setRecentActivity([]);
    setSearchResults([]);
    setSearchLoading(false);
    setSearchError("");
    setHello(null);
    setBusy(false);
    setNewSessionOpen(false);
    setError("");
  }, [shouldDisplayTurnNotification]);

  const client = useMemo(
    () =>
      new ForemanWebClient({
        onEvent,
        onState: (state, detail) => {
          setConnection(state);
          setConnectionDetail(detail ?? "");
          const hostId = activeHostIdRef.current;
          if (hostId) mutateHost(hostId, {
            lastKnownStatus: state === "connected" ? "connected" : state === "reconnecting" ? "reconnecting" : "disconnected",
            ...(state === "connected" ? { lastConnectedAt: Date.now() } : {}),
          });
        },
        onHello: (nextHello) => {
          setHello(nextHello);
          const hostId = activeHostIdRef.current;
          if (hostId) mutateHost(hostId, { runtimeMode: nextHello.codexRuntime });
        },
        onAuthenticationRejected: (detail) => {
          clearHostProjections();
          setError(detail);
        },
      }),
    [clearHostProjections, mutateHost, onEvent],
  );
  clientRef.current = client;

  const publishSessionPresence = useCallback(() => {
    if (connection !== "connected" || hello?.capabilities.sessionPresence !== true) {
      presenceProjectionGuardRef.current.invalidate();
      publishedPresenceRef.current = undefined;
      if (connection !== "connected") focusedSessionsRef.current = new Set();
      return;
    }
    const focused =
      document.visibilityState === "visible" &&
      document.hasFocus() &&
      viewRef.current === "detail" &&
      selectedIdRef.current !== null;
    const key = focused
      ? sessionPresenceKey(selectedProviderRef.current, selectedIdRef.current!)
      : null;
    if (publishedPresenceRef.current === key) return;
    publishedPresenceRef.current = key;
    const payload = focused
      ? { provider: selectedProviderRef.current, sessionId: selectedIdRef.current! }
      : {};
    const projectionVersion = presenceProjectionGuardRef.current.beginRequest();
    void client.request<{ sessions: SessionPresence[] } & Record<string, unknown>>(
      "session.presence",
      payload,
    ).then((result) => {
      if (!presenceProjectionGuardRef.current.isCurrent(projectionVersion)) return;
      focusedSessionsRef.current = parseSessionPresence(result);
    }).catch(() => {
      if (publishedPresenceRef.current === key) publishedPresenceRef.current = undefined;
    });
  }, [client, connection, hello?.capabilities.sessionPresence]);

  useEffect(() => {
    publishSessionPresence();
    window.addEventListener("focus", publishSessionPresence);
    window.addEventListener("blur", publishSessionPresence);
    document.addEventListener("visibilitychange", publishSessionPresence);
    return () => {
      window.removeEventListener("focus", publishSessionPresence);
      window.removeEventListener("blur", publishSessionPresence);
      document.removeEventListener("visibilitychange", publishSessionPresence);
    };
  }, [publishSessionPresence, selectedId, selectedProvider, view]);

  const refreshState = useCallback(
    async (hostId: string, reconnected = false) => {
      const refreshGeneration = ++refreshGenerationRef.current;
      const providerCatalogRevision = providerCatalogRevisionRef.current;
      if (reconnected) setProviderCatalogLoaded(false);
      const providerResult = await client.request<{ providers: ProviderInfo[] } & Record<string, unknown>>("provider.list");
      const codexAvailable = providerResult.providers.some(
        (provider) => provider.id === "codex" && providerEnabled(provider) && provider.available,
      );
      const claudeAvailable = providerResult.providers.some(
        (provider) => provider.id === "claude-code" && providerEnabled(provider) && provider.available,
      );
      const [approvalResult, inputResult, codexSessionRequest, modelResult, accessResult, statusResult, usageResult, repositoryResult, clientResult] = await Promise.all([
        codexAvailable
          ? client.request<{ approvals: ApprovalRequest[] } & Record<string, unknown>>("approval.list")
          : Promise.resolve({ approvals: [] as ApprovalRequest[] }),
        codexAvailable
          ? client.request<{ inputs: InputRequest[] } & Record<string, unknown>>("input.list").catch(() => ({ inputs: [] as InputRequest[] }))
          : Promise.resolve({ inputs: [] as InputRequest[] }),
        codexAvailable
          ? client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("provider.session.list", { provider: "codex" })
            .then((result) => ({ result, authoritative: true }))
            .catch(() => ({ result: { sessions: [] as SessionSummary[] }, authoritative: false }))
          : Promise.resolve({ result: { sessions: [] as SessionSummary[] }, authoritative: false }),
        codexAvailable
          ? client.request<{ models: ModelInfo[] } & Record<string, unknown>>("model.list")
          : Promise.resolve({ models: [] as ModelInfo[] }),
        codexAvailable
          ? client.request<{ levels: AccessLevelInfo[] } & Record<string, unknown>>("access.list")
          : Promise.resolve({ levels: [] as AccessLevelInfo[] }),
        client.request<ServiceStatus & Record<string, unknown>>("service.status"),
        client.request<AccountUsage & Record<string, unknown>>("usage.status")
          .catch(() => ({ providers: {} })),
        client.request<{ repositories: RepositoryInfo[] } & Record<string, unknown>>("repository.list"),
        client.request<{ clients: PairedClient[] } & Record<string, unknown>>("client.list"),
      ]);
      const [claudeSessionRequest, claudeModelResult, claudePermissionResult] = claudeAvailable
        ? await Promise.all([
          client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("provider.session.list", { provider: "claude-code" })
            .then((result) => ({ result, authoritative: true }))
            .catch(() => ({ result: { sessions: [] as SessionSummary[] }, authoritative: false })),
          client.request<{ models: Array<{ id: string; displayName: string; description?: string }> } & Record<string, unknown>>("provider.model.list", { provider: "claude-code" }),
          client.request<{ modes: PermissionModeInfo[] } & Record<string, unknown>>("provider.permission.list", { provider: "claude-code" }),
        ])
        : [{ result: { sessions: [] as SessionSummary[] }, authoritative: false }, { models: [] }, { modes: [] as PermissionModeInfo[] }];
      if (
        !providerCatalogResponseIsCurrent(
          hostId,
          activeHostIdRef.current,
          providerCatalogRevision,
          providerCatalogRevisionRef.current,
        ) || refreshGeneration !== refreshGenerationRef.current
      ) return;
      sessionListAuthoritativeRef.current = {
        codex: codexSessionRequest.authoritative,
        "claude-code": claudeSessionRequest.authoritative,
      };
      setApprovals(approvalResult.approvals);
      setInputs(inputResult.inputs);
      setProviders(providerResult.providers);
      setProviderCatalogLoaded(true);
      const incoming = [
        ...(codexSessionRequest.authoritative
          ? codexSessionRequest.result.sessions
          : sessionsRef.current.filter((session) => sessionProvider(session) === "codex"))
          .map((session) => ({ ...session, provider: "codex" as const })),
        ...(claudeSessionRequest.authoritative
          ? claudeSessionRequest.result.sessions
          : sessionsRef.current.filter((session) => sessionProvider(session) === "claude-code"))
          .map((session) => ({ ...session, provider: "claude-code" as const })),
      ];
      const reconciled = reconcileSessionSummaries(sessionsRef.current, incoming);
      sessionsRef.current = reconciled;
      notificationMonitor.current.seed(reconciled.filter((session) => sessionProvider(session) === "codex").map((session) => ({
        hostId,
        sessionId: session.id,
        repositoryId: repositoryIdentity(
          session.repository,
          repositoryResult.repositories,
          statusResult.repositoryRoot,
        ).id,
        status: session.status,
        turnId: session.activeTurnId,
        activeTurnStartedAt: session.activeTurnStartedAt,
        waitType: session.waitType,
      })));
      setSessions(reconciled);
      const validIds = new Set(reconciled.map((session) => providerSessionKey(sessionProvider(session), session.id)));
      setOrganization((previous) => {
        const next = {
          pinnedIds: previous.pinnedIds.filter((id) => validIds.has(id)),
          hiddenIds: previous.hiddenIds.filter((id) => validIds.has(id)),
        };
        if (next.pinnedIds.length !== previous.pinnedIds.length || next.hiddenIds.length !== previous.hiddenIds.length) {
          saveSessionOrganization(next, hostId);
          return next;
        }
        return previous;
      });
      setModels(modelResult.models.filter((model) => model.visible));
      setAccessLevels(accessResult.levels);
      setClaudeModels(claudeModelResult.models.map((model, index) => ({
        ...model,
        reasoningEfforts: [],
        visible: true,
        isDefault: index === 0,
      })));
      setClaudePermissionModes(claudePermissionResult.modes);
      setServiceStatus({ ...statusResult, receivedAt: Date.now() });
      setAccountUsage(usageResult);
      setRepositories(repositoryResult.repositories);
      setPairedClients(clientResult.clients);
      if (reconnected) dashboardSubscriptions.current.clear();
      const wanted = new Set(
        reconciled
          .filter((session) => ["working", "waiting"].includes(session.status))
          .map((session) => providerSessionKey(sessionProvider(session), session.id)),
      );
      const stale = [...dashboardSubscriptions.current].filter((key) => !wanted.has(key));
      await Promise.all([
        ...reconciled.filter((session) => wanted.has(providerSessionKey(sessionProvider(session), session.id))).map((session) =>
          client.request("provider.session.subscribe", { provider: sessionProvider(session), sessionId: session.id }).then(() => {
            dashboardSubscriptions.current.add(providerSessionKey(sessionProvider(session), session.id));
          }).catch(() => undefined),
        ),
        ...stale.map((key) => {
          const session = sessionsRef.current.find((candidate) => providerSessionKey(sessionProvider(candidate), candidate.id) === key);
          return session ? client.request("provider.session.unsubscribe", { provider: sessionProvider(session), sessionId: session.id }).then(() => {
            dashboardSubscriptions.current.delete(key);
          }).catch(() => undefined)
            : Promise.resolve();
        }),
      ]);
      if (activeHostIdRef.current !== hostId || refreshGeneration !== refreshGenerationRef.current) return;
      const reopenGeneration = sessionOpenGenerationRef.current;
      const reopenId = selectedIdRef.current;
      if (reopenId && viewRef.current === "detail") {
        const reopenProvider = selectedProviderRef.current;
        const fallbackFromReopen = (clearStaleMemory: boolean) => {
          if (
            reopenGeneration !== sessionOpenGenerationRef.current ||
            activeHostIdRef.current !== hostId ||
            viewRef.current !== "detail" ||
            selectedIdRef.current !== reopenId ||
            selectedProviderRef.current !== reopenProvider
          ) return;
          if (clearStaleMemory) clearRememberedSession(hostId);
          selectedIdRef.current = null;
          selectedProviderRef.current = "codex";
          currentRef.current = null;
          viewRef.current = "sessions";
          setSelectedId(null);
          setSelectedProvider("codex");
          setCurrent(null);
          setBusy(false);
          setView("sessions");
          updateRoute({ view: "sessions" }, true);
        };
        const provider = providerResult.providers.find((candidate) => candidate.id === reopenProvider);
        const providerStillEnabled = provider !== undefined && providerEnabled(provider);
        const providerAvailable = providerStillEnabled && provider.available;
        const sessionListAuthoritative = reopenProvider === "codex"
          ? codexSessionRequest.authoritative
          : claudeSessionRequest.authoritative;
        const summary = reconciled.find(
          (session) => session.id === reopenId && sessionProvider(session) === reopenProvider,
        );
        if (!providerStillEnabled || (providerAvailable && sessionListAuthoritative && !summary)) {
          fallbackFromReopen(true);
          return;
        }
        if (!providerAvailable || !sessionListAuthoritative || !summary) {
          fallbackFromReopen(false);
          return;
        }
        try {
          const result = await client.request<
            { session: SessionSummary } & Record<string, unknown>
          >("provider.session.read", {
            provider: reopenProvider,
            sessionId: reopenId,
            ...(reopenProvider === "claude-code" ? { repositoryId: summary.repositoryId ?? "." } : {}),
          });
          if (
            reopenGeneration !== sessionOpenGenerationRef.current ||
            activeHostIdRef.current !== hostId ||
            viewRef.current !== "detail" ||
            selectedIdRef.current !== reopenId ||
            selectedProviderRef.current !== reopenProvider
          ) return;
          setCurrent((previous) => reconcileSessionSettings(previous, { ...result.session, provider: reopenProvider }));
          saveRememberedSession({ hostId, provider: reopenProvider, sessionId: reopenId });
          await client.request("provider.session.subscribe", { provider: reopenProvider, sessionId: reopenId });
        } catch {
          fallbackFromReopen(false);
        }
      }
    },
    [client, updateRoute],
  );
  refreshStateRef.current = refreshState;

  const connectHost = useCallback(
    async (host: StoredHost) => {
      setError("");
      try {
        const endpoint = parseEndpoint(host.host, host.webPort);
        await client.start(endpoint, host.deviceToken, (reconnected) => refreshState(host.id, reconnected));
      } catch (caught) {
        const message = caught instanceof Error ? caught.message : "Cannot connect to Foreman";
        setError(message);
        if (/token|authenticate|unauthorized|incompatible/i.test(message)) client.disconnect();
      }
    },
    [client, refreshState],
  );

  useEffect(() => {
    if (activeHost) void connectHost(activeHost);
    return () => client.disconnect();
  }, [client, connectHost, activeHost?.id, activeHost?.host, activeHost?.webPort, activeHost?.deviceToken]);

  const openSession = useCallback(
    async (provider: ProviderId, id: string, updateHistory = true, matchedItemId: string | null = null, approvalId: string | null = null) => {
      const generation = ++sessionOpenGenerationRef.current;
      const hostId = activeHostIdRef.current;
      const approvalsAtOpen = approvals.filter((approval) => approval.sessionId === id);
      const inputsAtOpen = inputs.filter((input) => input.sessionId === id);
      setError("");
      setBusy(true);
      selectedIdRef.current = id;
      selectedProviderRef.current = provider;
      setSelectedId(id);
      setSelectedProvider(provider);
      setHighlightItemId(matchedItemId);
      setFocusedApprovalId(approvalId);
      viewRef.current = "detail";
      setView("detail");
      if (updateHistory) updateRoute({ view: "detail", provider, sessionId: id });
      try {
        const summary = sessionsRef.current.find((session) => session.id === id && sessionProvider(session) === provider);
        const [result, pending] = await Promise.all([
          client.request<
            { session: SessionSummary } & Record<string, unknown>
          >("provider.session.read", {
            provider,
            sessionId: id,
            ...(provider === "claude-code" ? { repositoryId: summary?.repositoryId ?? "." } : {}),
          }),
          provider === "codex"
            ? Promise.all([
              client.request<{ approvals: ApprovalRequest[] } & Record<string, unknown>>("approval.list"),
              client.request<{ inputs: InputRequest[] } & Record<string, unknown>>("input.list")
                .catch(() => ({ inputs: null as InputRequest[] | null })),
            ]).then(([approvalResult, inputResult]) => ({
              approvals: approvalResult.approvals,
              inputs: inputResult.inputs,
            }))
            : Promise.resolve({ approvals: [] as ApprovalRequest[], inputs: null as InputRequest[] | null }),
        ]);
        if (
          generation !== sessionOpenGenerationRef.current ||
          activeHostIdRef.current !== hostId ||
          viewRef.current !== "detail" ||
          selectedIdRef.current !== id ||
          selectedProviderRef.current !== provider
        ) return;
        if (provider === "codex") {
          setApprovals((current) => reconcileSessionPending(current, pending.approvals, id, approvalsAtOpen));
          const refreshedInputs = pending.inputs;
          if (refreshedInputs !== null) {
            setInputs((current) => reconcileSessionPending(current, refreshedInputs, id, inputsAtOpen));
          }
        }
        setCurrent((previous) => reconcileSessionSettings(previous, { ...result.session, provider }));
        if (hostId) saveRememberedSession({ hostId, provider, sessionId: id });
        await client.request("provider.session.subscribe", { provider, sessionId: id });
      } catch (caught) {
        if (
          generation === sessionOpenGenerationRef.current &&
          activeHostIdRef.current === hostId &&
          viewRef.current === "detail" &&
          selectedIdRef.current === id &&
          selectedProviderRef.current === provider
        ) {
          setError(caught instanceof Error ? caught.message : "Session could not be loaded");
          selectedIdRef.current = null;
          selectedProviderRef.current = "codex";
          currentRef.current = null;
          viewRef.current = "sessions";
          setSelectedId(null);
          setSelectedProvider("codex");
          setCurrent(null);
          setView("sessions");
          updateRoute({ view: "sessions" }, true);
        }
      } finally {
        if (generation === sessionOpenGenerationRef.current) setBusy(false);
      }
    },
    [approvals, client, inputs, updateRoute],
  );
  openSessionRef.current = (provider, id, updateHistory = true) => { void openSession(provider, id, updateHistory); };

  const closeSelectedSession = useCallback(() => {
    sessionOpenGenerationRef.current += 1;
    setBusy(false);
    const sessionId = selectedIdRef.current;
    const provider = selectedProviderRef.current;
    const key = sessionId ? providerSessionKey(provider, sessionId) : "";
    if (sessionId && !dashboardSubscriptions.current.has(key)) {
      void client.request("provider.session.unsubscribe", { provider, sessionId }).catch(() => undefined);
    }
    selectedIdRef.current = null;
    selectedProviderRef.current = "codex";
    currentRef.current = null;
    setSelectedId(null);
    setSelectedProvider("codex");
    setCurrent(null);
  }, [client]);

  const restoreView = useCallback((route: WebRoute, openConnectedDetail: boolean) => {
    viewRef.current = route.view;
    setView(route.view);
    if (route.view !== "detail") {
      sessionOpenGenerationRef.current += 1;
      setBusy(false);
      selectedIdRef.current = null;
      selectedProviderRef.current = "codex";
      currentRef.current = null;
      setSelectedId(null);
      setSelectedProvider("codex");
      setCurrent(null);
      return;
    }
    selectedIdRef.current = route.sessionId;
    selectedProviderRef.current = route.provider;
    setSelectedId(route.sessionId);
    setSelectedProvider(route.provider);
    if (openConnectedDetail && (currentRef.current?.id !== route.sessionId || sessionProvider(currentRef.current) !== route.provider)) {
      openSessionRef.current(route.provider, route.sessionId, false);
    }
  }, []);

  const activateHost = useCallback((hostId: string, route?: WebRoute, replace = false, filtersSearch?: string) => {
    const registry = hostRegistryRef.current;
    if (!registry.hosts.some((host) => host.id === hostId)) return;
    const rememberedSession = !route || route.view === "sessions" ? loadRememberedSession(hostId) : null;
    const destination: WebRoute = rememberedSession
      ? { view: "detail", provider: rememberedSession.provider, sessionId: rememberedSession.sessionId }
      : route ?? { view: "sessions" };
    const switching = activeHostIdRef.current !== hostId;
    if (switching) {
      client.disconnect();
      clearHostProjections();
      const next = selectStoredHost(registry, hostId);
      persistRegistry(next);
      const nextAppearance = loadAppearance(hostId);
      const nextNotifications = loadNotificationPreferences(hostId);
      const nextOrganization = loadSessionOrganization(hostId);
      setAppearance(nextAppearance);
      setNotificationPreferences(nextNotifications);
      notificationPreferencesRef.current = nextNotifications;
      setHostNotificationOverride(loadHostNotificationOverride(hostId) !== null);
      setOrganization(nextOrganization);
      setCollapsedRepositoriesByHost((previous) => {
        const collapsed = new Map(previous);
        collapsed.set(hostId, loadCollapsedRepositories(hostId));
        return collapsed;
      });
    }
    const filters = parseSessionFilters(filtersSearch ?? loadSessionSearch(hostId));
    searchFiltersRef.current = filters;
    setSearchFilters(filters);
    restoreView(destination, !switching && connectedRef.current);
    const search = withHostInSearch(sessionFiltersSearch(filters), hostId);
    const path = webRoutePath(destination);
    window.history[replace ? "replaceState" : "pushState"](null, "", `${path}${search}`);
  }, [clearHostProjections, client, persistRegistry, restoreView]);

  notificationOpenRef.current = (hostId, sessionId) => {
    activateHost(hostId, { view: "detail", provider: "codex", sessionId });
  };

  useEffect(() => {
    const restoreRoute = () => {
      const route = parseWebRoute(window.location.pathname);
      const hostId = hostIdFromUrl();
      if (hostId) activateHost(hostId, route, true, window.location.search);
      else if (route.view === "sessions") enterSessionsRef.current(true);
      else restoreView(route, connectedRef.current);
    };
    window.addEventListener("popstate", restoreRoute);
    return () => window.removeEventListener("popstate", restoreRoute);
  }, [activateHost, restoreView]);

  const showDashboard = (replace = false) => {
    viewRef.current = "dashboard";
    setView("dashboard");
    closeSelectedSession();
    updateRoute({ view: "dashboard" }, replace);
  };

  const showSessionList = (replace = false) => {
    viewRef.current = "sessions";
    setView("sessions");
    closeSelectedSession();
    updateRoute({ view: "sessions" }, replace);
  };

  const enterSessions = (replace = false) => {
    const hostId = activeHostIdRef.current;
    const remembered = loadRememberedSession(hostId);
    if (!hostId || !remembered) {
      showSessionList(replace);
      return;
    }
    const authoritative = connectedRef.current && providerCatalogLoaded;
    const provider = providers.find((candidate) => candidate.id === remembered.provider);
    const providerStillEnabled = provider !== undefined && providerEnabled(provider);
    const providerAvailable = providerStillEnabled && provider.available;
    const sessionAvailable = sessionsRef.current.some(
      (session) => session.id === remembered.sessionId && sessionProvider(session) === remembered.provider,
    );
    if (authoritative && (
      !providerStillEnabled ||
      (providerAvailable && sessionListAuthoritativeRef.current[remembered.provider] && !sessionAvailable)
    )) {
      clearRememberedSession(hostId);
      showSessionList(replace);
      return;
    }
    if (authoritative && (!providerAvailable || !sessionAvailable)) {
      showSessionList(replace);
      return;
    }
    const route: WebRoute = {
      view: "detail",
      provider: remembered.provider,
      sessionId: remembered.sessionId,
    };
    restoreView(route, connectedRef.current);
    updateRoute(route, replace);
  };
  enterSessionsRef.current = enterSessions;

  const showSettings = () => {
    viewRef.current = "settings";
    setView("settings");
    closeSelectedSession();
    updateRoute({ view: "settings" });
  };

  const updateAppearance = (next: Appearance) => {
    setAppearance(next);
    saveAppearance(next, activeHostIdRef.current);
  };

  const updateNotificationPreferences = (next: NotificationPreferences) => {
    const hostId = activeHostIdRef.current;
    saveNotificationPreferences(next, hostNotificationOverride ? hostId : null);
    notificationPreferencesRef.current = next;
    setNotificationPreferences(next);
  };

  const updateHostNotificationOverride = (enabled: boolean) => {
    const hostId = activeHostIdRef.current;
    if (!hostId) return;
    if (enabled) {
      saveNotificationPreferences(notificationPreferencesRef.current, hostId);
    } else {
      clearHostNotificationOverride(hostId);
      const inherited = loadNotificationPreferences(null);
      notificationPreferencesRef.current = inherited;
      setNotificationPreferences(inherited);
    }
    setHostNotificationOverride(enabled);
  };

  const forget = (hostId: string) => {
    const wasActive = activeHostIdRef.current === hostId;
    if (wasActive) client.disconnect();
    const next = forgetStoredHost(hostRegistryRef.current, hostId);
    forgetHostSnapshot(hostId);
    setHostSnapshots((previous) => {
      const nextSnapshots = new Map(previous);
      nextSnapshots.delete(hostId);
      return nextSnapshots;
    });
    setCollapsedRepositoriesByHost((previous) => {
      const collapsed = new Map(previous);
      collapsed.delete(hostId);
      return collapsed;
    });
    persistRegistry(next);
    if (wasActive) {
      clearHostProjections();
      const nextId = next.activeHostId;
      if (nextId) {
        setAppearance(loadAppearance(nextId));
        const nextNotifications = loadNotificationPreferences(nextId);
        setNotificationPreferences(nextNotifications);
        notificationPreferencesRef.current = nextNotifications;
        setHostNotificationOverride(loadHostNotificationOverride(nextId) !== null);
        setOrganization(loadSessionOrganization(nextId));
        const filters = parseSessionFilters(loadSessionSearch(nextId));
        searchFiltersRef.current = filters;
        setSearchFilters(filters);
      }
    }
    setError("");
    restoreView({ view: "sessions" }, false);
    const search = next.activeHostId ? withHostInSearch("", next.activeHostId) : "";
    window.history.replaceState(null, "", `/sessions${search}`);
  };

  const pairHost = async (settings: PairingSettings, pairingKey: string) => {
    setBusy(true);
    setError("");
    const pairingClient = new ForemanWebClient({ onEvent: () => undefined, onState: () => undefined });
    try {
      const endpoint = parseEndpoint(settings.host, settings.webPort);
      const token = await pairingClient.pair(endpoint, pairingKey, settings.deviceName);
      const saved = createStoredHost({
        displayName: settings.displayName,
        host: endpoint.host,
        tcpPort: settings.tcpPort,
        webPort: endpoint.port,
        deviceToken: token,
      });
      const next = addStoredHost(hostRegistryRef.current, saved);
      client.disconnect();
      clearHostProjections();
      persistRegistry(next);
      setAppearance(loadAppearance(saved.id));
      const savedNotifications = loadNotificationPreferences(saved.id);
      setNotificationPreferences(savedNotifications);
      notificationPreferencesRef.current = savedNotifications;
      setHostNotificationOverride(false);
      setOrganization(loadSessionOrganization(saved.id));
      const filters = parseSessionFilters(loadSessionSearch(saved.id));
      searchFiltersRef.current = filters;
      setSearchFilters(filters);
      setHostSetupOpen(false);
      restoreView({ view: "sessions" }, false);
      window.history.pushState(null, "", `/sessions${withHostInSearch("", saved.id)}`);
    } catch (caught) {
      setError(setupError(caught));
    } finally {
      pairingClient.disconnect();
      setBusy(false);
    }
  };

  const dashboardOpen = useCallback((session: SessionSummary) => {
    void openSession(sessionProvider(session), session.id);
  }, [openSession]);
  const dashboardOpenApproval = useCallback((approval: ApprovalRequest) => {
    void openSession("codex", approval.sessionId, true, null, approval.id);
  }, [openSession]);
  const dashboardOpenInput = useCallback((input: InputRequest) => {
    void openSession("codex", input.sessionId, true, null, input.id);
  }, [openSession]);
  const searchResultOpen = useCallback((provider: ProviderId, id: string, itemId?: string | null) => {
    void openSession(provider, id, true, itemId ?? null);
  }, [openSession]);
  const dashboardInterrupt = useCallback((session: SessionSummary) => {
    if (!session.activeTurnId) return;
    const provider = sessionProvider(session);
    const type = provider === "claude-code" ? "provider.turn.interrupt" : "turn.interrupt";
    void client.request(type, {
      ...(provider === "claude-code" ? { provider } : {}),
      sessionId: session.id,
      ...(provider === "codex" ? { turnId: session.activeTurnId } : {}),
    }).catch((caught) => setError(caught instanceof Error ? caught.message : "Interrupt failed"));
  }, [client]);
  const dashboardRefresh = useCallback(() => {
    const hostId = activeHostIdRef.current;
    if (hostId) void refreshState(hostId).catch((caught) => setError(String(caught)));
  }, [refreshState]);
  const unifiedOpenSession = useCallback((item: UnifiedAttentionItem | { hostId: string; provider?: ProviderId; sessionId: string }) => {
    const wasActive = item.hostId === activeHostIdRef.current;
    activateHost(item.hostId, { view: "detail", provider: item.provider ?? "codex", sessionId: item.sessionId });
    setFocusedApprovalId("approvalId" in item ? item.approvalId ?? null : null);
    if (wasActive && !connectedRef.current) {
      const host = hostRegistryRef.current.hosts.find(({ id }) => id === item.hostId);
      if (host) void connectHost(host);
    }
  }, [activateHost, connectHost]);
  const unifiedReconnect = useCallback((hostId: string) => {
    if (hostId === activeHostIdRef.current) {
      const host = hostRegistryRef.current.hosts.find(({ id }) => id === hostId);
      if (host) {
        client.disconnect();
        void connectHost(host);
      }
    } else {
      unifiedConnectionsRef.current?.reconnect(hostId);
    }
  }, [client, connectHost]);
  const repositoryOptions = useMemo(
    () => repositoryFilterOptions(sessions, repositories, serviceStatus?.repositoryRoot ?? ""),
    [repositories, serviceStatus?.repositoryRoot, sessions],
  );
  const visibleSessions = useMemo(
    () => filterSessions(
      sessions,
      searchFilters,
      new Set(organization.pinnedIds),
      new Set(organization.hiddenIds),
      searchResults,
      repositories,
      serviceStatus?.repositoryRoot ?? "",
    ),
    [organization, repositories, searchFilters, searchResults, serviceStatus?.repositoryRoot, sessions],
  );
  const discoveryActive = activeFilterCount(searchFilters) > 0;
  const togglePin = useCallback((provider: ProviderId, id: string) => {
    const key = providerSessionKey(provider, id);
    setOrganization((previous) => {
      const pinnedIds = previous.pinnedIds.includes(key)
        ? previous.pinnedIds.filter((value) => value !== key)
        : [...previous.pinnedIds, key];
      const next = { ...previous, pinnedIds };
      if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
      return next;
    });
  }, []);
  const toggleHidden = useCallback((provider: ProviderId, id: string) => {
    const key = providerSessionKey(provider, id);
    setOrganization((previous) => {
      const hiddenIds = previous.hiddenIds.includes(key)
        ? previous.hiddenIds.filter((value) => value !== key)
        : [...previous.hiddenIds, key];
      const next = { ...previous, hiddenIds };
      if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
      return next;
    });
  }, []);
  const requestAndReconcile = useCallback(async <T extends Record<string, unknown>,>(
    type: string,
    payload?: Record<string, unknown>,
  ): Promise<T> => {
    const result = await client.request<T>(type, payload);
    const returned = result.session as SessionSummary | undefined;
    if (returned && typeof returned.id === "string") {
      const provider = payload?.provider === "claude-code" ? "claude-code" : sessionProvider(returned);
      const projected = { ...returned, provider };
      setCurrent((previous) => reconcileSessionSettings(previous, projected));
      setSessions((previous) => previous.map((session) =>
        session.id === projected.id && sessionProvider(session) === provider
          ? reconcileSessionSettings(session, {
            ...session,
            ...projected,
            messages: session.messages ?? projected.messages,
          })
          : session,
      ));
    }
    return result;
  }, [client]);

  if (!activeHost) {
    return (
      <SetupView
        error={error}
        busy={busy}
        onConnect={pairHost}
      />
    );
  }

  const connected = connection === "connected";
  return (
    <div className={appShellClassName(view)}>
      <header className="topbar">
        <button className="brand" onClick={() => showDashboard()} aria-label="Dashboard">
          <span className="brand-mark">F</span>
          <span>Foreman</span>
        </button>
        <HostSelector
          hosts={hostRegistry.hosts}
          activeHostId={activeHost.id}
          activeState={connection}
          detail={connectionDetail}
          onSelect={(hostId) => activateHost(
            hostId,
            viewRef.current === "dashboard"
              ? { view: "dashboard" }
              : viewRef.current === "settings"
                ? { view: "settings" }
                : undefined,
          )}
          onAdd={() => setHostSetupOpen(true)}
        />
        <nav>
          <button className={view === "dashboard" ? "active" : ""} onClick={() => showDashboard()}>
            Dashboard
          </button>
          <button className={view === "sessions" || view === "detail" ? "active" : ""} onClick={() => enterSessions()}>
            Sessions
          </button>
          <button className={view === "settings" ? "active" : ""} onClick={showSettings}>
            Settings
          </button>
        </nav>
      </header>

      {error && (
        <div className="error-banner" role="alert">
          {error}
          <button onClick={() => setError("")} aria-label="Dismiss error">×</button>
        </div>
      )}

      {view === "settings" ? (
        <SettingsView
          host={activeHost}
          hosts={hostRegistry.hosts}
          appearance={appearance}
          hello={hello}
          serverVersion={serviceStatus?.foremanVersion ?? null}
          connected={connected}
          providers={providers}
          onAppearance={updateAppearance}
          notificationPreferences={notificationPreferences}
          notificationState={notificationState}
          hostNotificationOverride={hostNotificationOverride}
          repositoryOptions={repositoryOptions}
          onNotificationPreferences={updateNotificationPreferences}
          onHostNotificationOverride={updateHostNotificationOverride}
          onNotificationPermission={async () => {
            const granted = await requestBrowserNotifications();
            const next = browserNotificationState();
            setNotificationState(next);
            if (!granted) setError(notificationStateDescription(next, false));
          }}
          onNotificationTest={showBrowserTestNotification}
          onProviderEnabled={async (provider, enabled) => {
            try {
              const result = await client.request<{ providers: ProviderInfo[] } & Record<string, unknown>>("provider.configure", { provider, enabled });
              providerCatalogRevisionRef.current += 1;
              setProviders(result.providers);
              setProviderCatalogLoaded(true);
              await refreshState(activeHost.id);
            } catch (caught) {
              setError(caught instanceof Error ? caught.message : "Provider setting could not be updated");
              throw caught;
            }
          }}
          onAdd={() => setHostSetupOpen(true)}
          onSelect={(hostId) => activateHost(hostId, { view: "settings" })}
          onRename={(hostId, displayName) => mutateHost(hostId, { displayName })}
          onForget={forget}
        />
      ) : view === "dashboard" ? (
        <div className="dashboard-scroll">
          <UnifiedDashboard
            hosts={hostRegistry.hosts}
            activeHostId={activeHost.id}
            snapshots={hostSnapshots}
            onOpenHost={(hostId) => activateHost(hostId, { view: "dashboard" })}
            onOpenSession={unifiedOpenSession}
            onReconnect={unifiedReconnect}
            onEdit={(hostId) => activateHost(hostId, { view: "settings" })}
            onForget={(hostId) => {
              const host = hostRegistryRef.current.hosts.find(({ id }) => id === hostId);
              if (host && window.confirm(`Forget “${host.displayName}”? Its browser-local token and preferences will be removed.`)) forget(hostId);
            }}
          />
          <div className="dashboard-discovery"><SessionSearchControls filters={searchFilters} repositories={repositoryOptions} loading={searchLoading} onChange={setSearchFilters} onSearchNow={() => setSearchRevision((value) => value + 1)} /></div>
          {discoveryActive ? <main className="dashboard-page search-page"><SessionSearchResults results={visibleSessions} query={searchFilters.query} loading={searchLoading} error={searchError} showProviderIdentity={shouldShowProviderIdentity(providers, providerCatalogLoaded)} onOpen={searchResultOpen} onPin={togglePin} onHide={toggleHidden} /></main> : <>
            {visibleSessions.some(({ pinned }) => pinned) && <section className="dashboard-pinned"><header><h2>Pinned</h2><span>Client-local</span></header><SessionSearchResults results={visibleSessions.filter(({ pinned }) => pinned)} query="" loading={false} error="" showProviderIdentity={shouldShowProviderIdentity(providers, providerCatalogLoaded)} onOpen={searchResultOpen} onPin={togglePin} onHide={toggleHidden} /></section>}
            <Dashboard
            hostId={activeHost.id}
            sessions={visibleSessions.map(({ session }) => session)}
            approvals={approvals}
            inputs={inputs}
            serviceStatus={serviceStatus}
            repositories={repositories}
            recentActivity={recentActivity.filter((entry) => !organization.hiddenIds.includes(providerSessionKey("codex", entry.sessionId)))}
            pairedClients={pairedClients}
            providers={providers}
            providerCatalogLoaded={providerCatalogLoaded}
            connection={connection}
            disabled={!connected}
            onOpen={dashboardOpen}
            onOpenApproval={dashboardOpenApproval}
            onOpenInput={dashboardOpenInput}
            onInterrupt={dashboardInterrupt}
            onRefresh={dashboardRefresh}
            onRevokeClient={async (pairedClient) => {
              try {
                await client.request("client.revoke", { clientId: pairedClient.id });
                setPairedClients((previous) => previous.filter((entry) => entry.id !== pairedClient.id));
                if (pairedClient.current) forget(activeHost.id);
              } catch (caught) {
                setError(caught instanceof Error ? caught.message : "Client token could not be revoked");
                throw caught;
              }
            }}
            onFetchDiagnostics={async () => {
              const result = await client.request<{ events: DiagnosticEvent[] } & Record<string, unknown>>("diagnostics.list");
              return result.events;
            }}
            onRestart={() => client.request<{ scheduled: boolean; timeoutSeconds?: number } & Record<string, unknown>>("service.restart")}
          /></>}
        </div>
      ) : (
        <main className={`workspace ${view === "detail" ? "show-detail" : "show-list"}`}>
          <SessionList
            collapsedRepositories={collapsedRepositoriesByHost.get(activeHost.id) ?? new Set()}
            onToggleRepository={(repositoryId) => {
              setCollapsedRepositoriesByHost((previous) => {
                const next = toggleCollapsedRepository(previous, activeHost.id, repositoryId);
                saveCollapsedRepositories(next.get(activeHost.id) ?? new Set(), activeHost.id);
                return next;
              });
            }}
            results={visibleSessions}
            groupByRepository={appearance.groupSessionsByRepository}
            repositories={repositories}
            repositoryRoot={serviceStatus?.repositoryRoot ?? ""}
            accountUsage={accountUsage}
            providers={providers}
            providerCatalogLoaded={providerCatalogLoaded}
            filters={searchFilters}
            repositoryOptions={repositoryOptions}
            searchLoading={searchLoading}
            searchError={searchError}
            selectedId={selectedId}
            selectedProvider={selectedProvider}
            disabled={!connected || !providerCatalogLoaded}
            onOpen={searchResultOpen}
            onRefresh={() => {
              const hostId = activeHostIdRef.current;
              if (hostId) void refreshState(hostId).catch((caught) => setError(String(caught)));
            }}
            onNew={async () => {
              setNewSessionOpen(true);
              try {
                const result = await client.request<
                  { repositories: RepositoryInfo[] } & Record<string, unknown>
                >("repository.list");
                setRepositories(result.repositories);
              } catch (caught) {
                setError(caught instanceof Error ? caught.message : "Repositories could not be loaded");
              }
            }}
            onFilters={setSearchFilters}
            onSearchNow={() => setSearchRevision((value) => value + 1)}
            onPin={togglePin}
            onHide={toggleHidden}
            onAction={async (action, session) => {
              if (!confirmSessionAction(action, session.title)) return;
              try {
                const request = sessionActionRequest(action, session);
                await client.request(request.type, request.payload);
                const provider = sessionProvider(session);
                const identityKey = providerSessionKey(provider, session.id);
                const remembered = loadRememberedSession(activeHostIdRef.current);
                if (remembered?.provider === provider && remembered.sessionId === session.id) {
                  clearRememberedSession(remembered.hostId);
                }
                setSessions((previous) => {
                  const next = previous.filter((item) => providerSessionKey(sessionProvider(item), item.id) !== identityKey);
                  sessionsRef.current = next;
                  return next;
                });
                setSearchResults((previous) => previous.filter(({ session: result }) => providerSessionKey(sessionProvider(result), result.id) !== identityKey));
                if (action === "delete") {
                  setOrganization((previous) => {
                    const next = {
                      pinnedIds: previous.pinnedIds.filter((id) => id !== identityKey),
                      hiddenIds: previous.hiddenIds.filter((id) => id !== identityKey),
                    };
                    if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
                    return next;
                  });
                }
                if (selectedIdRef.current === session.id && selectedProviderRef.current === provider) {
                  selectedIdRef.current = null;
                  selectedProviderRef.current = "codex";
                  setSelectedId(null);
                  setSelectedProvider("codex");
                  setCurrent(null);
                  showSessionList(true);
                }
              } catch (caught) {
                setError(caught instanceof Error ? caught.message : `${action} failed`);
              }
            }}
          />
          <section className="detail-pane">
            {current ? (
              <ConversationView
                key={`${activeHost.id}:${sessionProvider(current)}:${current.id}`}
                session={current}
                approvals={approvals.filter((approval) => approval.sessionId === current.id)}
                inputs={inputs.filter((pending) => pending.sessionId === current.id)}
                models={sessionProvider(current) === "claude-code" ? claudeModels : models}
                accessLevels={sessionProvider(current) === "claude-code" ? claudePermissionModes : accessLevels}
                connected={connected}
                showProviderIdentity={shouldShowProviderIdentity(providers, providerCatalogLoaded)}
                activityDetail={appearance.activityDetail}
                highlightItemId={highlightItemId}
                focusedApprovalId={focusedApprovalId}
                initialScrollTop={scrollPositions.current.get(sessionIdentityKey({ hostId: activeHost.id, provider: sessionProvider(current), sessionId: current.id }))}
                onScrollPosition={(scrollTop) => scrollPositions.current.set(sessionIdentityKey({ hostId: activeHost.id, provider: sessionProvider(current), sessionId: current.id }), scrollTop)}
                draft={messageDraft(messageDrafts, activeHost.id, sessionProvider(current), current.id)}
                onDraftChange={(text) => setMessageDrafts((previous) =>
                  updateMessageDraft(previous, activeHost.id, sessionProvider(current), current.id, text)
                )}
                onBack={() => showSessionList()}
                onRequest={requestAndReconcile}
                onError={setError}
              />
            ) : (
              <div className="empty-detail">
                <span className="brand-mark large">F</span>
                <h2>{busy ? "Loading session…" : "Select a session"}</h2>
                <p>Open an existing session or start a new one.</p>
              </div>
            )}
          </section>
        </main>
      )}

      {newSessionOpen && (
        <NewSessionDialog
          repositories={repositories}
          repositoryRoot={serviceStatus?.repositoryRoot ?? ""}
          models={models}
          accessLevels={accessLevels}
          providers={providers}
          providerCatalogLoaded={providerCatalogLoaded}
          claudeModels={claudeModels}
          claudePermissionModes={claudePermissionModes}
          onClose={() => setNewSessionOpen(false)}
          onCreate={async (settings) => {
            setBusy(true);
            try {
              const requestType = settings.provider === "claude-code" ? "provider.session.start" : "session.start";
              const payload = settings.provider === "claude-code"
                ? {
                  provider: settings.provider,
                  repositoryId: settings.repositoryId,
                  text: settings.text,
                  model: settings.model,
                  permissionMode: settings.permissionMode,
                }
                : {
                  repositoryId: settings.repositoryId,
                  model: settings.model,
                  reasoningEffort: settings.reasoningEffort,
                  accessLevel: settings.accessLevel,
                };
              const result = await client.request<
                { session: SessionSummary } & Record<string, unknown>
              >(requestType, payload);
              const created = { ...result.session, provider: settings.provider };
              setSessions((previous) => previous.some((session) => session.id === created.id && sessionProvider(session) === settings.provider)
                ? previous.map((session) => session.id === created.id && sessionProvider(session) === settings.provider ? created : session)
                : [created, ...previous]);
              selectedIdRef.current = result.session.id;
              selectedProviderRef.current = settings.provider;
              setSelectedId(result.session.id);
              setSelectedProvider(settings.provider);
              setCurrent(created);
              const hostId = activeHostIdRef.current;
              if (hostId) saveRememberedSession({ hostId, provider: settings.provider, sessionId: result.session.id });
              viewRef.current = "detail";
              setView("detail");
              updateRoute({ view: "detail", provider: settings.provider, sessionId: result.session.id });
              setNewSessionOpen(false);
            } catch (caught) {
              setError(caught instanceof Error ? caught.message : "Session could not be started");
            } finally {
              setBusy(false);
            }
          }}
        />
      )}
      {hostSetupOpen && (
        <div className="modal-backdrop">
          <SetupView
            error={error}
            busy={busy}
            onConnect={pairHost}
            onCancel={() => { setHostSetupOpen(false); setError(""); }}
          />
        </div>
      )}
    </div>
  );
}

export function SetupView({
  error,
  busy,
  onConnect,
  onCancel,
}: {
  error: string;
  busy: boolean;
  onConnect: (settings: PairingSettings, pairingKey: string) => Promise<void>;
  onCancel?: () => void;
}) {
  const [host, setHost] = useState(window.location.hostname || "");
  const [webPort, setWebPort] = useState(String(inferPagePort()));
  const [pairingKey, setPairingKey] = useState("");
  const [deviceName, setDeviceName] = useState("Web browser");
  const [displayName, setDisplayName] = useState("");
  return (
    <main className={onCancel ? "setup-page embedded" : "setup-page"}>
      <section className="setup-card">
        <div className="setup-heading">
          <span className="brand-mark large">F</span>
          <div><h1>Connect to Foreman</h1><p>Your local Codex companion.</p></div>
          {onCancel && <button className="setup-close" onClick={onCancel} aria-label="Close">×</button>}
        </div>
        {error && <div className="form-error" role="alert">{error}</div>}
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void onConnect(
              {
                displayName: displayName.trim() || suggestedHostDisplayName(host),
                host: host.trim(),
                tcpPort: 8765,
                webPort: Number(webPort),
                deviceName: deviceName.trim(),
              },
              pairingKey,
            );
          }}
        >
          <label>Host<input value={host} onChange={(event) => setHost(event.target.value)} placeholder="192.168.1.59" autoComplete="url" required /></label>
          <label>Web port<input value={webPort} onChange={(event) => setWebPort(event.target.value)} inputMode="numeric" min="1" max="65535" required /></label>
          <label>Host display name<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="Home server" autoComplete="off" /></label>
          <label>Pairing code<input value={pairingKey} onChange={(event) => setPairingKey(event.target.value)} inputMode="numeric" autoComplete="one-time-code" placeholder="123456" required /></label>
          <label>Device name<input value={deviceName} onChange={(event) => setDeviceName(event.target.value)} autoComplete="name" required /></label>
          <button className="primary full" disabled={busy}>{busy ? "Connecting…" : "Connect"}</button>
        </form>
        <p className="security-note">Use Foreman only on a trusted LAN or secure overlay. The persistent token is stored in this browser and is less protected than Android Keystore storage.</p>
      </section>
    </main>
  );
}

function ConnectionBadge({ state, detail }: { state: ConnectionState; detail: string }) {
  const label = state === "connected" ? "Connected" : state === "reconnecting" ? "Reconnecting…" : state === "connecting" ? "Connecting…" : "Disconnected";
  return <span className={`connection ${state}`} title={detail}><i />{label}</span>;
}

function HostSelector({ hosts, activeHostId, activeState, detail, onSelect, onAdd }: {
  hosts: StoredHost[];
  activeHostId: string;
  activeState: ConnectionState;
  detail: string;
  onSelect: (hostId: string) => void;
  onAdd: () => void;
}) {
  const status = (host: StoredHost) => host.id === activeHostId
    ? activeState === "connecting" ? "reconnecting" : activeState
    : host.lastKnownStatus;
  return <div className="host-selector">
    <ConnectionBadge state={activeState} detail={detail} />
    <select aria-label="Saved host" value={activeHostId} onChange={(event) => onSelect(event.target.value)}>
      {hosts.map((host) => <option key={host.id} value={host.id}>
        {host.displayName} · {status(host)}
      </option>)}
    </select>
    <button onClick={onAdd} aria-label="Add host">+</button>
  </div>;
}

export function SessionList({
  collapsedRepositories: controlledCollapsedRepositories,
  onToggleRepository,
  results,
  groupByRepository = true,
  repositories = [],
  repositoryRoot = "",
  accountUsage = null,
  providers = [],
  providerCatalogLoaded = true,
  filters,
  repositoryOptions,
  searchLoading,
  searchError,
  selectedId,
  selectedProvider,
  disabled,
  onOpen,
  onRefresh,
  onNew,
  onAction,
  onFilters,
  onSearchNow,
  onPin,
  onHide,
}: {
  collapsedRepositories?: ReadonlySet<string>;
  onToggleRepository?: (repositoryId: string) => void;
  results: VisibleSession[];
  groupByRepository?: boolean;
  repositories?: RepositoryInfo[];
  repositoryRoot?: string;
  accountUsage?: AccountUsage | null;
  providers?: ProviderInfo[];
  providerCatalogLoaded?: boolean;
  filters: SessionFilters;
  repositoryOptions: RepositoryFilterOption[];
  searchLoading: boolean;
  searchError: string;
  selectedId: string | null;
  selectedProvider: ProviderId;
  disabled: boolean;
  onOpen: (provider: ProviderId, id: string, itemId?: string | null) => void;
  onRefresh: () => void;
  onNew: () => void;
  onAction: (action: "archive" | "delete", session: SessionSummary) => void;
  onFilters: (filters: SessionFilters) => void;
  onSearchNow: () => void;
  onPin: (provider: ProviderId, id: string) => void;
  onHide: (provider: ProviderId, id: string) => void;
}) {
  const [localCollapsedRepositories, setLocalCollapsedRepositories] =
    useState<Set<string>>(() => new Set());
  const collapsedRepositories = controlledCollapsedRepositories ?? localCollapsedRepositories;
  const toggleRepository = onToggleRepository ?? ((repositoryId: string) => {
    setLocalCollapsedRepositories((previous) => {
      const next = new Set(previous);
      if (next.has(repositoryId)) next.delete(repositoryId);
      else next.add(repositoryId);
      return next;
    });
  });
  const sessions = results.map(({ session }) => session);
  const pinnedSessions = results.filter(({ pinned }) => pinned).map(({ session }) => session);
  const unpinnedSessions = results.filter(({ pinned }) => !pinned).map(({ session }) => session);
  const groups = {
    pinned: pinnedSessions,
    waiting: unpinnedSessions.filter((session) => session.attention || session.status === "waiting"),
    active: unpinnedSessions.filter((session) => !session.attention && session.status === "working"),
    recent: unpinnedSessions.filter((session) => !session.attention && session.status !== "waiting" && session.status !== "working"),
  };
  const repositoryGroups = repositorySessionGroups(results, repositories, repositoryRoot);
  const discoveryActive = activeFilterCount(filters) > 0;
  const showProviderIdentity = shouldShowProviderIdentity(providers, providerCatalogLoaded);
  return (
    <aside className="session-pane">
      <div className="pane-heading">
        <div><span className="eyebrow">Workspace</span><h1>Sessions</h1></div>
        <div className="heading-actions">
          <button className="icon-button" onClick={onRefresh} disabled={disabled} aria-label="Refresh sessions">↻</button>
          <button className="primary" onClick={onNew} disabled={disabled}>New</button>
        </div>
      </div>
      <SessionSearchControls filters={filters} repositories={repositoryOptions} loading={searchLoading} onChange={onFilters} onSearchNow={onSearchNow} />
      <div className="session-scroll">
        {discoveryActive ? <SessionSearchResults results={results} query={filters.query} loading={searchLoading} error={searchError} showProviderIdentity={showProviderIdentity} onOpen={onOpen} onPin={onPin} onHide={onHide} /> : <>
        {sessions.length === 0 && <div className="empty-list"><h3>No sessions yet</h3><p>Start one from a repository.</p></div>}
        {groupByRepository ? repositoryGroups.map((group) => (
          <section className="session-group repository-session-group" key={group.repository.id}>
            <button
              className="repository-group-toggle"
              aria-expanded={!collapsedRepositories.has(group.repository.id)}
              aria-label={`${collapsedRepositories.has(group.repository.id) ? "Expand" : "Collapse"} ${group.repository.label} (${group.sessions.length} sessions)`}
              onClick={() => toggleRepository(group.repository.id)}
            >
              <span>{group.repository.label}</span>
              <span>{group.sessions.length} {collapsedRepositories.has(group.repository.id) ? "›" : "⌄"}</span>
            </button>
            {!collapsedRepositories.has(group.repository.id) && group.sessions.map(({ session }) => renderSessionCard(session, {
              groupByRepository,
              repositoryGroupId: group.repository.id,
            }))}
          </section>
        )) : (["pinned", "waiting", "active", "recent"] as const).map((group) =>
          groups[group].length ? (
            <section className="session-group" key={group}>
              <h2>{group === "pinned" ? "Pinned" : group === "waiting" ? "Needs attention" : group === "active" ? "Active" : "Recent"}</h2>
              {groups[group].map((session) => renderSessionCard(session))}
            </section>
          ) : null,
        )}
        </>}
      </div>
      {accountUsage?.providers && <AccountUsageDock usage={accountUsage} providers={providers} providerCatalogLoaded={providerCatalogLoaded} />}
    </aside>
  );

  function renderSessionCard(
    session: SessionSummary,
    context: SessionCardRenderContext = { groupByRepository: false },
  ) {
    const provider = sessionProvider(session);
    const pinned = results.find((item) => item.session.id === session.id && sessionProvider(item.session) === provider)?.pinned;
    const showRepository = showSessionCardRepository(session, repositories, repositoryRoot, context);
    return <article
      key={`${provider}:${session.id}`}
      className={`session-card ${showRepository ? "" : "repository-metadata-suppressed"} ${selectedId === session.id && selectedProvider === provider ? "selected" : ""}`}
      onClick={() => onOpen(provider, session.id)}
    >
      <div className="session-title-row"><h3>{session.title}</h3>{showProviderIdentity && <ProviderBadge provider={provider} />}<StatusPill status={session.status} /></div>
      {showRepository && <p className="repository">{shortRepository(session.repository)}</p>}
      {provider === "claude-code" && session.source === "external" && <p className="session-limitation"><strong>Resumable</strong> · Not live-attached</p>}
      <div className="session-meta">
        <span>{formatActivity(session.lastActivity)}</span>
        <span className="card-actions">
          <button className={pinned ? "selected" : ""} onClick={(event) => { event.stopPropagation(); onPin(provider, session.id); }} aria-label={`${pinned ? "Unpin" : "Pin"} ${session.title}`}>{pinned ? "★" : "☆"}</button>
          <button onClick={(event) => { event.stopPropagation(); onHide(provider, session.id); }}>Hide</button>
          {provider === "codex" && <button onClick={(event) => { event.stopPropagation(); onAction("archive", session); }} disabled={session.status === "working" || session.status === "waiting"}>Archive</button>}
          {(provider === "codex" || session.capabilities?.includes("session.delete")) && <button className="danger-link" onClick={(event) => { event.stopPropagation(); onAction("delete", session); }} disabled={session.status === "working" || session.status === "waiting"}>Delete</button>}
        </span>
      </div>
    </article>;
  }
}

export function AccountUsageDock({ usage, providers: providerInfo, providerCatalogLoaded = true }: { usage: AccountUsage; providers: ProviderInfo[]; providerCatalogLoaded?: boolean }) {
  const [open, setOpen] = useState(false);
  const rootRef = usePopoverDismiss<HTMLDivElement>(open, setOpen);
  const providers = ([
    { id: "codex", label: "Codex" },
    { id: "claude-code", label: "Claude" },
  ] as const)
    .filter((provider) => providerInfo.length === 0
      ? usage.providers[provider.id] !== undefined
      : providerInfo.some((entry) => entry.id === provider.id && providerEnabled(entry)))
    .map((provider) => ({ ...provider, usage: usage.providers[provider.id] }));
  const availableWindows = providers.flatMap(({ usage: providerUsage }) => accountUsageWindows(providerUsage));
  const showProviderIdentity = shouldShowProviderIdentity(providerInfo, providerCatalogLoaded);
  if (!availableWindows.length && !providers.some(({ usage: providerUsage }) => providerUsage)) return null;
  const usedPercent = availableWindows.length
    ? Math.max(...availableWindows.map((window) => Math.max(0, Math.min(100, window.usedPercent))))
    : 0;
  const summary = providers.map(({ label, usage: providerUsage }) => `${showProviderIdentity ? `${label} ` : ""}${accountUsageRemaining(providerUsage)}`).join(", ");
  return <div className="account-usage-anchor" ref={rootRef}>
    <button className="account-usage-dock" type="button" aria-label={`Account usage, ${summary}`} aria-expanded={open} onClick={() => setOpen((value) => !value)}>
      <UsageRing percentUsed={usedPercent} />
      <span className="account-provider-summary">{providers.map(({ id, label, usage: providerUsage }) => <span key={id}>{showProviderIdentity && <b>{label}</b>}<strong>{accountUsageRemaining(providerUsage)}</strong></span>)}<small>Account usage</small></span>
    </button>
    {open && <aside className="account-usage-panel" aria-label="Account usage">
      <header><div>{showProviderIdentity && <span className="eyebrow">Across providers</span>}<strong>Account usage</strong></div><button type="button" onClick={() => setOpen(false)} aria-label="Close account usage">×</button></header>
      <div className="account-provider-list">{providers.map(({ id, label, usage: providerUsage }) => {
        const windows = accountUsageWindows(providerUsage);
        return <section className="account-provider-usage" key={id}>
          {(showProviderIdentity || providerUsage?.experimental) && <div className="account-provider-heading">{showProviderIdentity && <strong>{label}</strong>}{providerUsage?.experimental && <span>Experimental</span>}</div>}
          {windows.length ? <div className="account-limit-list">{windows.map((window, index) => {
            const remaining = Math.max(0, Math.round(100 - window.usedPercent));
            return <section key={`${window.windowDurationMins ?? "window"}-${index}`}>
              <div><strong>{rateLimitLabel(window.windowDurationMins, index)}</strong><span>{remaining}% left</span></div>
              <div className="context-meter" role="meter" aria-label={`${showProviderIdentity ? `${label} ` : ""}${rateLimitLabel(window.windowDurationMins, index)} used`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={window.usedPercent}><i style={{ width: `${Math.max(0, Math.min(100, window.usedPercent))}%` }} /></div>
              <small>{rateLimitResetLabel(window.resetsAt)}</small>
            </section>;
          })}</div> : <p>{providerUsage?.availabilityReason || `${showProviderIdentity ? `${label} usage` : "Usage"} is unavailable.`}</p>}
          {providerUsage?.observedAt && <small className="usage-observed">Last observed {new Date(providerUsage.observedAt * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</small>}
        </section>;
      })}</div>
    </aside>}
  </div>;
}

function accountUsageWindows(usage: AccountUsage["providers"][ProviderId]) {
  return [usage?.rateLimits?.primary, usage?.rateLimits?.secondary].filter(
    (window): window is NonNullable<typeof window> => !!window && Number.isFinite(window.usedPercent),
  );
}

function accountUsageRemaining(usage: AccountUsage["providers"][ProviderId]): string {
  const windows = accountUsageWindows(usage);
  if (!windows.length) return "unavailable";
  return `${Math.max(0, Math.round(100 - Math.max(...windows.map((window) => window.usedPercent))))}% left`;
}

function rateLimitLabel(durationMins: number | undefined, index: number): string {
  if (durationMins === 10_080) return "Weekly limit";
  if (durationMins && durationMins % 60 === 0) return `${durationMins / 60}-hour limit`;
  if (durationMins) return `${durationMins}-minute limit`;
  return index === 0 ? "Primary limit" : "Secondary limit";
}

function rateLimitResetLabel(resetsAt: number | undefined): string {
  if (!resetsAt) return "Reset time unavailable";
  return `Resets ${new Date(resetsAt * 1000).toLocaleString([], { weekday: "short", hour: "numeric", minute: "2-digit" })}`;
}

export function ConversationView({
  session,
  approvals,
  inputs = [],
  models,
  accessLevels,
  connected,
  showProviderIdentity = true,
  activityDetail = "focused",
  highlightItemId,
  focusedApprovalId,
  initialScrollTop,
  onScrollPosition,
  draft,
  onDraftChange,
  onBack,
  onRequest,
  onError,
}: {
  session: SessionSummary;
  approvals: ApprovalRequest[];
  inputs?: InputRequest[];
  models: ModelInfo[];
  accessLevels: AccessLevelInfo[];
  connected: boolean;
  showProviderIdentity?: boolean;
  activityDetail?: ActivityDetail;
  highlightItemId: string | null;
  focusedApprovalId: string | null;
  initialScrollTop?: number;
  onScrollPosition?: (scrollTop: number) => void;
  draft: string;
  onDraftChange: (text: string) => void;
  onBack: () => void;
  onRequest: <T extends Record<string, unknown>>(type: string, payload?: Record<string, unknown>) => Promise<T>;
  onError: (message: string) => void;
}) {
  const provider = sessionProvider(session);
  const initialRoute = useMemo(() => {
    if (provider === "codex") return routeForSession(session, models, accessLevels);
    const model = models.find((entry) => entry.id === session.model);
    const permission = accessLevels.find((entry) => entry.id === session.permissionMode);
    return {
      model: model?.id ?? session.model ?? "",
      reasoningEffort: "",
      accessLevel: permission?.id ?? session.permissionMode ?? "",
    };
  }, [accessLevels, models, provider, session]);
  const [model, setModel] = useState(initialRoute.model);
  const [effort, setEffort] = useState(initialRoute.reasoningEffort);
  const [access, setAccess] = useState(initialRoute.accessLevel);
  const latestSessionRef = useRef(session);
  latestSessionRef.current = session;
  const [images, setImages] = useState<ProcessedImage[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [updatingRoute, setUpdatingRoute] = useState(false);
  const [processing, setProcessing] = useState(false);
  const processingImages = useRef(false);
  const submissionGuard = useRef(createSubmissionGuard());
  const transcriptRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const openingScrollTop = useRef(initialScrollTop);
  const [jumpVisible, setJumpVisible] = useState(false);
  const [workspaceFile, setWorkspaceFile] = useState<WorkspaceFile | null>(null);
  const [openingWorkspaceFile, setOpeningWorkspaceFile] = useState<string | null>(null);
  const [sessionInfoOpen, setSessionInfoOpen] = useState(false);
  const sessionInfoRef = usePopoverDismiss<HTMLSpanElement>(sessionInfoOpen, setSessionInfoOpen);
  const workspaceFileRequest = useRef(0);
  const protectedItemIds = useMemo(() => new Set([
    ...(highlightItemId ? [highlightItemId] : []),
    ...approvals.flatMap(({ itemId }) => itemId ? [itemId] : []),
    ...inputs.flatMap(({ itemId }) => itemId ? [itemId] : []),
  ]), [approvals, highlightItemId, inputs]);
  const displayBlocks = useMemo(
    () => conversationBlocks(session.messages ?? [], activityDetail, protectedItemIds),
    [activityDetail, protectedItemIds, session.messages],
  );

  useEffect(() => {
    setModel(initialRoute.model);
    setEffort(initialRoute.reasoningEffort);
    setAccess(initialRoute.accessLevel);
  }, [initialRoute.accessLevel, initialRoute.model, initialRoute.reasoningEffort, session.id]);

  useEffect(() => {
    if (!highlightItemId) return;
    const frame = requestAnimationFrame(() => {
      document.getElementById(`message-${highlightItemId}`)?.scrollIntoView({ block: "center", behavior: "smooth" });
    });
    return () => cancelAnimationFrame(frame);
  }, [highlightItemId, session.messages?.length]);

  useEffect(() => {
    following.current = !highlightItemId && openingScrollTop.current === undefined;
    setJumpVisible(false);
    if (!highlightItemId) {
      requestAnimationFrame(() => transcriptRef.current?.scrollTo({
        top: openingScrollTop.current ?? transcriptRef.current.scrollHeight,
      }));
    }
  }, [highlightItemId, provider, session.id]);

  const transcriptKey = `${session.messages?.length ?? 0}:${session.messages?.at(-1)?.text?.length ?? 0}:${session.activityText?.length ?? 0}:${approvals.map(({ id, status }) => `${id}:${status}`).join(",")}:${inputs.map(({ id, status }) => `${id}:${status}`).join(",")}`;
  useEffect(() => {
    if (following.current) {
      const frame = requestAnimationFrame(() => transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight }));
      return () => cancelAnimationFrame(frame);
    }
    setJumpVisible(true);
  }, [transcriptKey]);

  const selectedModel = models.find((entry) => entry.id === model);
  const active = session.status === "working" && !!session.activeTurnId;
  const hasActiveTurn = ["working", "waiting", "stopping"].includes(session.status);
  const canSubmit = connected && !submitting && !updatingRoute && !processing && (provider === "codex" || !hasActiveTurn) && (!!draft.trim() || images.length > 0);
  const activityLabel = liveActivityLabel(session);
  const activityMessage = liveActivityMessage(session);
  const contextUsage = contextUsageView(session.tokenUsage);

  const openWorkspaceFile = async ({ path, line }: WorkspaceFileTarget) => {
    const request = ++workspaceFileRequest.current;
    setOpeningWorkspaceFile(path);
    try {
      const result = await onRequest<{ path: string; content: string }>("workspace.file.read", { path });
      if (workspaceFileRequest.current === request) setWorkspaceFile({ ...result, line });
    } catch (caught) {
      if (workspaceFileRequest.current === request) {
        onError(caught instanceof Error ? caught.message : "Workspace file could not be opened");
      }
    } finally {
      if (workspaceFileRequest.current === request) setOpeningWorkspaceFile(null);
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit || !submissionGuard.current.enter()) return;
    setSubmitting(true);
    try {
      const base = {
        sessionId: session.id,
        text: draft,
        images: images.map(({ mimeType, data }) => ({ mimeType, data })),
      };
      if (provider === "claude-code") {
        await onRequest(session.source === "external" ? "provider.session.resume" : "provider.turn.prompt", {
          provider,
          sessionId: session.id,
          repositoryId: session.repositoryId ?? ".",
          text: draft,
          model: model || "sonnet",
          permissionMode: access || "default",
        });
      } else if (active) {
        await onRequest("turn.steer", { ...base, turnId: session.activeTurnId });
      } else {
        await onRequest("turn.prompt", base);
      }
      onDraftChange("");
      setImages([]);
    } catch (caught) {
      onError(caught instanceof Error ? caught.message : "Message was not accepted");
    } finally {
      submissionGuard.current.leave();
      setSubmitting(false);
    }
  };

  const addImages = async (files: File[]) => {
    if (!files.length) return;
    if (processingImages.current) {
      onError("Wait for the current images to finish processing");
      return;
    }
    processingImages.current = true;
    setProcessing(true);
    try {
      const processed = await processImages(files, images);
      setImages((previous) => [...previous, ...processed]);
    } catch (caught) {
      onError(caught instanceof Error ? caught.message : "Images could not be processed");
    } finally {
      processingImages.current = false;
      setProcessing(false);
    }
  };

  const addFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    await addImages(files);
  };

  const pasteImages = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const files = clipboardImageFiles(event.clipboardData);
    if (!files.length) return;
    event.preventDefault();
    void addImages(files);
  };

  const updateAccess = async (value: string) => {
    if (hasActiveTurn) return;
    if (
      (value === "full" && !window.confirm("Enable Full access for the next turn? Codex can use the Internet and any file without asking first.")) ||
      (value === "bypassPermissions" && !window.confirm("Use Claude bypass-permissions mode for the next turn? This skips Claude Code permission checks."))
    ) return;
    const previous = access;
    const startingRevision = session.settingsRevision ?? 0;
    setAccess(value);
    setUpdatingRoute(true);
    try {
      const result = await onRequest<{ session?: SessionSummary }>(
        provider === "claude-code" ? "provider.session.settings" : "session.settings",
        provider === "claude-code"
          ? { provider, sessionId: session.id, repositoryId: session.repositoryId ?? ".", permissionMode: value }
          : { sessionId: session.id, accessLevel: value },
      );
      if (result.session) {
        setAccess(
          provider === "claude-code"
            ? result.session.permissionMode ?? value
            : result.session.accessLevel ?? value,
        );
      }
    } catch (caught) {
      const latest = latestSessionRef.current;
      setAccess(
        (latest.settingsRevision ?? 0) > startingRevision
          ? provider === "claude-code" ? latest.permissionMode ?? "" : latest.accessLevel ?? ""
          : previous,
      );
      onError(caught instanceof Error ? caught.message : "Access setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  const updateModel = async (value: string) => {
    if (hasActiveTurn) return;
    const previousModel = model;
    const previousEffort = effort;
    const startingRevision = session.settingsRevision ?? 0;
    const next = models.find((entry) => entry.id === value);
    const nextEffort = next?.defaultReasoningEffort ?? next?.reasoningEfforts[0] ?? "";
    setModel(value);
    setEffort(nextEffort);
    setUpdatingRoute(true);
    try {
      const result = await onRequest<{ session?: SessionSummary }>(
        provider === "claude-code" ? "provider.session.settings" : "session.settings",
        provider === "claude-code" ? {
          provider,
          sessionId: session.id,
          repositoryId: session.repositoryId ?? ".",
          model: value,
        } : {
          sessionId: session.id,
          model: value,
          ...(nextEffort ? { reasoningEffort: nextEffort } : {}),
        },
      );
      if (result.session) {
        setModel(result.session.model ?? value);
        if (provider === "codex") {
          setEffort(result.session.reasoningEffort ?? nextEffort);
        }
      }
    } catch (caught) {
      const latest = latestSessionRef.current;
      const newer = (latest.settingsRevision ?? 0) > startingRevision;
      setModel(newer ? latest.model ?? "" : previousModel);
      setEffort(newer ? latest.reasoningEffort ?? "" : previousEffort);
      onError(caught instanceof Error ? caught.message : "Model setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  const updateEffort = async (value: string) => {
    if (hasActiveTurn) return;
    const previous = effort;
    const startingRevision = session.settingsRevision ?? 0;
    setEffort(value);
    setUpdatingRoute(true);
    try {
      const result = await onRequest<{ session?: SessionSummary }>("session.settings", {
        sessionId: session.id,
        model,
        reasoningEffort: value,
      });
      if (result.session) setEffort(result.session.reasoningEffort ?? value);
    } catch (caught) {
      const latest = latestSessionRef.current;
      setEffort(
        (latest.settingsRevision ?? 0) > startingRevision
          ? latest.reasoningEffort ?? ""
          : previous,
      );
      onError(caught instanceof Error ? caught.message : "Reasoning setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  return (
    <div className="conversation">
      <header className="conversation-header">
        <button className="mobile-back" onClick={onBack}>‹ Sessions</button>
        <div className="conversation-title"><h1>{session.title}</h1><p>{showProviderIdentity && <ProviderBadge provider={provider} />} {shortRepository(session.repository)}</p></div>
        <StatusPill status={session.status} />
        {contextUsage && <span className="session-context-control" ref={sessionInfoRef}>
          <ContextUsageButton usage={contextUsage} open={sessionInfoOpen} onClick={() => setSessionInfoOpen((open) => !open)} />
          {sessionInfoOpen && <SessionInfoPanel session={session} usage={contextUsage} model={selectedModel?.displayName || model} effort={effort} access={accessLevels.find((level) => level.id === access)?.displayName || access} onClose={() => setSessionInfoOpen(false)} />}
        </span>}
      </header>
      <div
        className="transcript"
        ref={transcriptRef}
        onScroll={(event) => {
          const target = event.currentTarget;
          const atBottom = isNearBottom(target.scrollTop, target.clientHeight, target.scrollHeight);
          following.current = atBottom;
          onScrollPosition?.(target.scrollTop);
          if (atBottom) setJumpVisible(false);
        }}
      >
        {provider === "claude-code" && session.source === "external" && <div className="provider-limitation" role="note"><strong>Resumable · Not live-attached</strong><p>Open history here, then resume in Foreman to prompt, stream, or interrupt. Foreman cannot attach to the external running process.</p></div>}
        {provider === "claude-code" && session.status === "waiting" && <div className="provider-limitation warning" role="status"><strong>Permission required in Claude session.</strong><p>Foreman web approval support is not yet available.</p></div>}
        {!session.messages?.length && !approvals.length && !inputs.length && <div className="empty-conversation"><h2>Ready when you are</h2><p>{provider === "claude-code" ? "Send a prompt using the Claude model and permission mode below." : "Choose a route below and send the first prompt."}</p></div>}
        {displayBlocks.map((block) => {
          if (block.collapsedActivity) {
            return <CollapsedActivityGroup key={`activity-${block.items[0].id}`} items={block.items} onOpenWorkspaceFile={openWorkspaceFile} />;
          }
          const item = block.items[0];
          return <Fragment key={item.id}><ConversationItemView item={item} highlighted={item.id === highlightItemId} onOpenWorkspaceFile={openWorkspaceFile} />{approvals.filter((approval) => approval.itemId === item.id).map((approval) => <ApprovalCard key={approval.id} approval={approval} focused={focusedApprovalId === approval.id} connected={connected} onRespond={async (approvalId, decision) => { await onRequest("approval.respond", { approvalId, decision }); }} />)}{inputs.filter((input) => input.itemId === item.id).map((input) => <InputCard key={input.id} input={input} focused={focusedApprovalId === input.id} connected={connected} onRespond={async (inputId, response) => { await onRequest("input.respond", { inputId, response }); }} />)}</Fragment>;
        })}
        {approvals.filter((approval) => !approval.itemId || !session.messages?.some((item) => item.id === approval.itemId)).map((approval) => <ApprovalCard key={approval.id} approval={approval} focused={focusedApprovalId === approval.id} connected={connected} onRespond={async (approvalId, decision) => { await onRequest("approval.respond", { approvalId, decision }); }} />)}
        {inputs.filter((input) => !input.itemId || !session.messages?.some((item) => item.id === input.itemId)).map((input) => <InputCard key={input.id} input={input} focused={focusedApprovalId === input.id} connected={connected} onRespond={async (inputId, response) => { await onRequest("input.respond", { inputId, response }); }} />)}
        {(session.status === "working" || session.status === "waiting") && (
          <div className="live-activity">
            <span className="pulse" />
            <div>{activityMessage ? <><Markdown text={activityMessage} onOpenWorkspaceFile={openWorkspaceFile} /><small>{activityLabel}…</small></> : <strong>{session.status === "waiting" ? "Waiting for attention…" : `${activityLabel}…`}</strong>}</div>
          </div>
        )}
      </div>
      {jumpVisible && <button className="jump-latest" onClick={() => { following.current = true; setJumpVisible(false); transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight, behavior: "smooth" }); }}>Jump to latest ↓</button>}
      <form className="composer" onSubmit={submit}>
        <div className="route-row">
          <RouteSelect label={provider === "claude-code" ? "Permission" : "Access"} value={access} options={accessLevels.map((level) => ({ value: level.id, label: level.displayName, description: level.description, warning: provider === "claude-code" ? level.id === "bypassPermissions" : level.id === "full" }))} disabled={!connected || submitting || updatingRoute || hasActiveTurn} disabledReason={hasActiveTurn ? "Available when this turn finishes" : undefined} onChange={(value) => void updateAccess(value)} />
          <RouteSelect label="Model" value={model} options={models.map((entry) => ({ value: entry.id, label: entry.displayName, description: entry.description }))} disabled={!connected || submitting || updatingRoute || hasActiveTurn} disabledReason={hasActiveTurn ? "Available when this turn finishes" : undefined} onChange={(value) => void updateModel(value)} />
          {provider === "codex" && <RouteSelect label="Reasoning" value={effort} options={selectedModel?.reasoningEfforts.map((entry) => ({ value: entry, label: reasoningLabel(entry), description: reasoningDescription(entry) })) ?? []} disabled={!connected || submitting || updatingRoute || hasActiveTurn} disabledReason={hasActiveTurn ? "Available when this turn finishes" : undefined} onChange={(value) => void updateEffort(value)} />}
        </div>
        {hasActiveTurn && <p className="route-locked-note">{provider === "claude-code" ? "Model and permission are available when this turn finishes." : "Model, reasoning, and access are available when this turn finishes."}</p>}
        {images.length > 0 && <div className="attachment-row">{images.map((image, index) => <figure key={`${image.name}-${index}`}><img src={image.previewUrl} alt={image.name} /><button type="button" onClick={() => setImages((previous) => previous.filter((_, itemIndex) => itemIndex !== index))} aria-label={`Remove ${image.name}`}>×</button></figure>)}</div>}
        <div className="entry-row">
          {provider === "codex" && <label className="attach-button" title="Attach images">+<input type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(event) => void addFiles(event)} disabled={processing || submitting || images.length >= 4} /></label>}
          <textarea value={draft} onChange={(event) => onDraftChange(event.target.value)} onPaste={provider === "codex" ? pasteImages : undefined} placeholder={provider === "claude-code" ? hasActiveTurn ? "Claude is working…" : "Message Claude Code…" : active ? "Steer the active turn…" : "Message Codex…"} disabled={provider === "claude-code" && hasActiveTurn} rows={1} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} />
          {hasActiveTurn && <button type="button" className="interrupt" disabled={!connected || submitting || updatingRoute} onClick={() => void onRequest(provider === "claude-code" ? "provider.turn.interrupt" : "turn.interrupt", { ...(provider === "claude-code" ? { provider } : {}), sessionId: session.id, ...(provider === "codex" ? { turnId: session.activeTurnId } : {}) }).catch((caught) => onError(String(caught)))}>Stop</button>}
          <button className="send-button" disabled={!canSubmit}>{submitting ? "…" : provider === "claude-code" && session.source === "external" ? "Resume in Foreman" : active ? "Steer" : "Send"}</button>
        </div>
        {!connected && <p className="composer-note">Your draft is preserved while Foreman reconnects.</p>}
      </form>
      {openingWorkspaceFile && <div className="file-opening" role="status">Opening {openingWorkspaceFile}…</div>}
      {workspaceFile && <WorkspaceFileDialog file={workspaceFile} onOpenWorkspaceFile={openWorkspaceFile} onClose={() => setWorkspaceFile(null)} />}
    </div>
  );
}

export interface ContextUsageView {
  usedTokens: number;
  remainingTokens: number;
  contextWindow: number;
  percentUsed: number;
  percentRemaining: number;
}

export function contextUsageView(tokenUsage: SessionSummary["tokenUsage"]): ContextUsageView | null {
  const usedTokens = tokenUsage?.last?.totalTokens;
  const contextWindow = tokenUsage?.modelContextWindow;
  if (!Number.isFinite(usedTokens) || !Number.isFinite(contextWindow) || (usedTokens ?? -1) < 0 || (contextWindow ?? 0) <= 0) return null;
  const safeUsed = Math.max(0, usedTokens!);
  const safeWindow = contextWindow!;
  const remainingTokens = Math.max(0, safeWindow - safeUsed);
  const percentUsed = Math.min(100, Math.round((safeUsed / safeWindow) * 100));
  return {
    usedTokens: safeUsed,
    remainingTokens,
    contextWindow: safeWindow,
    percentUsed,
    percentRemaining: Math.max(0, 100 - percentUsed),
  };
}

export function formatTokenCount(value: number): string {
  if (value >= 1_000_000) {
    const millions = value / 1_000_000;
    return `${millions >= 10 ? Math.round(millions) : millions.toFixed(1).replace(/\.0$/, "")}m`;
  }
  if (value >= 1_000) {
    const thousands = value / 1_000;
    return `${thousands >= 100 ? Math.round(thousands) : thousands.toFixed(1).replace(/\.0$/, "")}k`;
  }
  return String(Math.round(value));
}

function UsageRing({ percentUsed }: { percentUsed: number }) {
  return <span className="context-ring" aria-hidden="true">
    <svg viewBox="0 0 36 36"><circle className="context-ring-track" cx="18" cy="18" r="15.5" /><circle className="context-ring-value" cx="18" cy="18" r="15.5" pathLength="100" strokeDasharray={`${percentUsed} 100`} /></svg>
  </span>;
}

function usePopoverDismiss<T extends HTMLElement>(open: boolean, setOpen: (open: boolean) => void) {
  const rootRef = useRef<T>(null);
  useEffect(() => {
    if (!open) return;
    const dismissOutside = (event: MouseEvent | FocusEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const dismissEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", dismissOutside);
    document.addEventListener("focusin", dismissOutside);
    document.addEventListener("keydown", dismissEscape);
    return () => {
      document.removeEventListener("mousedown", dismissOutside);
      document.removeEventListener("focusin", dismissOutside);
      document.removeEventListener("keydown", dismissEscape);
    };
  }, [open, setOpen]);
  return rootRef;
}

function ContextUsageButton({ usage, open, onClick }: { usage: ContextUsageView; open: boolean; onClick: () => void }) {
  return <button className="context-usage-button" type="button" aria-label={`Context usage, ${usage.percentRemaining}% left`} aria-expanded={open} onClick={onClick} title={`${formatTokenCount(usage.remainingTokens)} tokens left`}>
    <UsageRing percentUsed={usage.percentUsed} /><span>{usage.percentRemaining}%</span>
  </button>;
}

function SessionInfoPanel({ session, usage, model, effort, access, onClose }: { session: SessionSummary; usage: ContextUsageView; model: string; effort: string; access: string; onClose: () => void }) {
  const total = session.tokenUsage?.total;
  const last = session.tokenUsage?.last;
  const turnCount = new Set((session.messages ?? []).map(({ turnId }) => turnId).filter(Boolean)).size;
  const compactions = (session.messages ?? []).filter(({ kind }) => kind === "compaction");
  const latestCompaction = compactions.at(-1);
  return <aside className="session-info-panel" aria-label="Session info">
    <header><div><span className="eyebrow">Session info</span><strong>Context window</strong></div><button type="button" onClick={onClose} aria-label="Close session info">×</button></header>
    <div className="context-usage-summary"><span>{formatTokenCount(usage.usedTokens)} / {formatTokenCount(usage.contextWindow)} tokens</span><strong>{usage.percentRemaining}% left</strong></div>
    <div className="context-meter" role="meter" aria-label="Context used" aria-valuemin={0} aria-valuemax={100} aria-valuenow={usage.percentUsed}><i style={{ width: `${usage.percentUsed}%` }} /></div>
    <p>{formatTokenCount(usage.remainingTokens)} tokens remain. Conversation history normally compacts automatically before the window is exhausted.</p>
    <dl>
      <div><dt>Model</dt><dd>{model || session.model || "—"}</dd></div>
      {effort && <div><dt>Reasoning</dt><dd>{reasoningLabel(effort)}</dd></div>}
      {access && <div><dt>Access</dt><dd>{access}</dd></div>}
      <div><dt>Transcript</dt><dd>{session.messages?.length ?? 0} items{turnCount ? ` · ${turnCount} ${turnCount === 1 ? "turn" : "turns"}` : ""}</dd></div>
      <div><dt>Compactions</dt><dd>{compactions.length}</dd></div>
      {latestCompaction && (latestCompaction.preTokens !== undefined || latestCompaction.compactionTrigger) && <div><dt>Last compaction</dt><dd>{compactionDetail(latestCompaction)}</dd></div>}
      {total && <div><dt>Session tokens</dt><dd>{formatTokenCount(total.totalTokens)} total</dd></div>}
      {last?.cachedInputTokens !== undefined && <div><dt>Cached input</dt><dd>{formatTokenCount(last.cachedInputTokens)}</dd></div>}
      {last?.outputTokens !== undefined && <div><dt>Last output</dt><dd>{formatTokenCount(last.outputTokens)}</dd></div>}
    </dl>
  </aside>;
}

function compactionDetail(item: NonNullable<SessionSummary["messages"]>[number]): string {
  const trigger = item.compactionTrigger === "manual" ? "Manual" : item.compactionTrigger === "auto" ? "Automatic" : "Completed";
  if (item.preTokens !== undefined && item.postTokens !== undefined) {
    return `${trigger} · ${formatTokenCount(item.preTokens)} → ${formatTokenCount(item.postTokens)}`;
  }
  return trigger;
}

interface RouteOption {
  value: string;
  label: string;
  description?: string;
  warning?: boolean;
}

export function RouteSelect({
  label,
  value,
  options,
  disabled,
  disabledReason,
  onChange,
}: {
  label: string;
  value: string;
  options: RouteOption[];
  disabled: boolean;
  disabledReason?: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const selected = options.find((option) => option.value === value);
  const displayValue = (selected?.label ?? value) || "Server default";

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  useEffect(() => {
    if (!open) return;
    const closeOutside = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", closeOutside);
    const frame = requestAnimationFrame(() => {
      const selectedIndex = Math.max(0, options.findIndex((option) => option.value === value));
      optionRefs.current[selectedIndex]?.focus();
    });
    return () => {
      document.removeEventListener("mousedown", closeOutside);
      cancelAnimationFrame(frame);
    };
  }, [open, options, value]);

  const close = () => {
    setOpen(false);
    requestAnimationFrame(() => triggerRef.current?.focus());
  };

  return (
    <div className={`route-select ${open ? "open" : ""}`} ref={rootRef}>
      <span className="route-caption">{label}</span>
      <button
        ref={triggerRef}
        type="button"
        className={`route-trigger ${selected?.warning ? "warning" : ""}`}
        aria-label={`${label}: ${displayValue}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled || options.length === 0}
        title={disabled ? disabledReason : undefined}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <span>{displayValue}</span><i aria-hidden="true">⌄</i>
      </button>
      {open && (
        <div id={menuId} className="route-menu" role="listbox" aria-label={`${label} options`}>
          {options.map((option, index) => (
            <button
              key={option.value}
              ref={(node) => { optionRefs.current[index] = node; }}
              type="button"
              role="option"
              aria-selected={option.value === value}
              className={`route-option ${option.warning ? "warning" : ""}`}
              onClick={() => {
                onChange(option.value);
                close();
              }}
              onKeyDown={(event) => {
                if (event.key === "Escape") {
                  event.preventDefault();
                  close();
                } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
                  event.preventDefault();
                  const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 : (index + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
                  optionRefs.current[next]?.focus();
                }
              }}
            >
              <span><strong>{option.label}</strong>{option.description && <small>{option.description}</small>}</span>
              {option.value === value && <i aria-hidden="true">✓</i>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function ConversationItemView({ item, highlighted = false, onOpenWorkspaceFile }: { item: NonNullable<SessionSummary["messages"]>[number]; highlighted?: boolean; onOpenWorkspaceFile?: (target: WorkspaceFileTarget) => void }) {
  if (item.kind === "compaction") {
    return <article id={`message-${item.id}`} className={`tool-card compaction-card ${highlighted ? "search-highlight" : ""}`}><span>↻</span><div><strong>Context compacted</strong><p>{compactionDetail(item)}</p></div>{item.durationMs !== undefined && <small>{formatDuration(item.durationMs)}</small>}</article>;
  }
  if (item.kind === "command" || item.kind === "tool") {
    return <article id={`message-${item.id}`} className={`tool-card ${highlighted ? "search-highlight" : ""}`}><span>{item.kind === "command" ? "›_" : "◇"}</span><div><strong>{item.kind === "command" ? "Command" : "Tool"}</strong><p>{item.description || "Working"}</p></div><small>{item.status || "in progress"}{item.exitCode != null ? ` · exit ${item.exitCode}` : ""}</small></article>;
  }
  return (
    <article id={`message-${item.id}`} className={`message ${item.kind} ${highlighted ? "search-highlight" : ""}`}>
      <div className="message-label">{item.kind === "user" ? "You" : "Foreman"}</div>
      {item.kind === "assistant" ? <Markdown text={item.text ?? ""} onOpenWorkspaceFile={onOpenWorkspaceFile} /> : <LinkedUserText text={item.text ?? ""} />}
      {!!item.images?.length && <div className="message-images">{item.images.map((image, index) => <img key={index} src={`data:${image.mimeType};base64,${image.data}`} alt={`Attachment ${index + 1}`} />)}</div>}
      {!!item.imageCount && !item.images?.length && <span className="image-indicator">▧ {item.imageCount} image{item.imageCount === 1 ? "" : "s"}</span>}
    </article>
  );
}

function CollapsedActivityGroup({ items, onOpenWorkspaceFile }: { items: NonNullable<SessionSummary["messages"]>; onOpenWorkspaceFile?: (target: WorkspaceFileTarget) => void }) {
  const commands = items.filter(({ kind }) => kind === "command").length;
  const tools = items.length - commands;
  const breakdown = [
    commands ? `${commands} command${commands === 1 ? "" : "s"}` : "",
    tools ? `${tools} tool${tools === 1 ? "" : "s"}` : "",
  ].filter(Boolean).join(" · ");
  return <details className="collapsed-activity"><summary><span aria-hidden="true">◇</span><span><strong>{items.length} completed activity item{items.length === 1 ? "" : "s"}</strong><small>{breakdown}</small></span><i>Details</i></summary><div>{items.map((item) => <ConversationItemView key={item.id} item={item} onOpenWorkspaceFile={onOpenWorkspaceFile} />)}</div></details>;
}

export function LinkedUserText({ text }: { text: string }) {
  return <p className="user-text">{linkifyPlainText(text).map((segment, index) => segment.href
    ? <a key={`${segment.href}-${index}`} href={segment.href} target="_blank" rel="noreferrer noopener">{segment.text}</a>
    : segment.text)}</p>;
}

export function Markdown({ text, onOpenWorkspaceFile }: { text: string; onOpenWorkspaceFile?: (target: WorkspaceFileTarget) => void }) {
  return (
    <div className="markdown">
      {parseAssistantContent(text).map((segment, index) => segment.kind === "directive"
        ? <AppDirectiveCard key={`${segment.directive.name}-${index}`} directive={segment.directive} />
        : <MarkdownBody key={`markdown-${index}`} text={segment.text} onOpenWorkspaceFile={onOpenWorkspaceFile} />)}
    </div>
  );
}

function MarkdownBody({ text, onOpenWorkspaceFile }: { text: string; onOpenWorkspaceFile?: (target: WorkspaceFileTarget) => void }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
    a: ({ href, children }) => {
      const localFile = workspaceFileTarget(href);
      if (localFile && onOpenWorkspaceFile) {
        return <a href={href} onClick={(event) => { event.preventDefault(); onOpenWorkspaceFile(localFile); }}>{children}</a>;
      }
      const safe = safeLink(href);
      return safe ? <a href={safe} target="_blank" rel="noreferrer noopener">{children}</a> : <span>{children}</span>;
    },
    pre: CopyableCodeBlock,
    table: ({ children }) => <div className="markdown-table-wrap"><table>{children}</table></div>,
  }}>{text}</ReactMarkdown>;
}

export function workspaceFileTarget(href?: string): WorkspaceFileTarget | null {
  if (!href || !href.startsWith("/")) return null;
  let decoded: string;
  try {
    decoded = decodeURIComponent(href);
  } catch {
    return null;
  }
  if (decoded.includes("\0") || decoded.includes("?") || decoded.includes("#")) return null;
  const location = decoded.match(/^(.*):(\d+)(?::\d+)?$/);
  const path = location?.[1] ?? decoded;
  const line = location ? Number(location[2]) : undefined;
  if (!path.startsWith("/") || (line !== undefined && (!Number.isSafeInteger(line) || line < 1))) return null;
  return { path, ...(line === undefined ? {} : { line }) };
}

function WorkspaceFileDialog({ file, onOpenWorkspaceFile, onClose }: { file: WorkspaceFile; onOpenWorkspaceFile: (target: WorkspaceFileTarget) => void; onClose: () => void }) {
  const markdown = /\.(?:md|markdown)$/i.test(file.path);
  const [view, setView] = useState<"preview" | "source">(markdown && !file.line ? "preview" : "source");
  const selectedLine = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    setView(markdown && !file.line ? "preview" : "source");
  }, [file.line, file.path, markdown]);
  useEffect(() => {
    selectedLine.current?.scrollIntoView?.({ block: "center" });
  }, [file.line, file.path, view]);
  return (
    <div className="modal-backdrop workspace-file-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="workspace-file-dialog" role="dialog" aria-modal="true" aria-label={`Workspace file ${file.path}`}>
        <header>
          <code title={file.path}>{file.path}{file.line ? `:${file.line}` : ""}</code>
          {markdown && <div className="workspace-file-tabs" role="tablist" aria-label="File view">
            <button type="button" role="tab" aria-selected={view === "preview"} className={view === "preview" ? "selected" : ""} onClick={() => setView("preview")}>Preview</button>
            <button type="button" role="tab" aria-selected={view === "source"} className={view === "source" ? "selected" : ""} onClick={() => setView("source")}>Source</button>
          </div>}
          <button className="workspace-file-close" type="button" onClick={onClose} aria-label="Close workspace file">×</button>
        </header>
        {view === "preview"
          ? <div className="markdown workspace-file-preview"><MarkdownBody text={file.content} onOpenWorkspaceFile={onOpenWorkspaceFile} /></div>
          : <pre>{file.content.split("\n").map((content, index) => {
              const number = index + 1;
              return <span key={number} ref={number === file.line ? selectedLine : undefined} className={number === file.line ? "selected" : ""}><i aria-hidden="true">{number}</i>{content}{"\n"}</span>;
            })}</pre>}
      </section>
    </div>
  );
}

function CopyableCodeBlock({ children }: { children?: ReactNode }) {
  const [copyState, setCopyState] = useState<"idle" | "copying" | "copied" | "failed">("idle");
  const copyInFlight = useRef(false);
  const mounted = useRef(true);
  const resetTimer = useRef<number | null>(null);
  const code = reactNodeText(children).replace(/\n$/, "");

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    };
  }, []);

  const copy = async () => {
    if (copyInFlight.current) return;
    copyInFlight.current = true;
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    setCopyState("copying");
    let result: "copied" | "failed";
    try {
      await copyText(code);
      result = "copied";
    } catch {
      result = "failed";
    }
    copyInFlight.current = false;
    if (!mounted.current) return;
    setCopyState(result);
    resetTimer.current = window.setTimeout(() => setCopyState("idle"), 2_000);
  };

  const label = copyState === "copying" ? "Copying code" : copyState === "copied" ? "Code copied" : copyState === "failed" ? "Copy failed. Try again" : "Copy code";
  return (
    <div className="code-block">
      <button type="button" className={`copy-code ${copyState}`} aria-label={label} title={label} disabled={copyState === "copying"} onClick={() => void copy()}>
        <span className="copy-icon" aria-hidden="true" />
      </button>
      <pre>{children}</pre>
    </div>
  );
}

async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Local Foreman hosts may not have a secure context; fall back to a selected textarea.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  try {
    if (!document.execCommand?.("copy")) throw new Error("Clipboard unavailable");
  } finally {
    textarea.remove();
  }
}

function reactNodeText(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(reactNodeText).join("");
  if (isValidElement<{ children?: ReactNode }>(node)) return reactNodeText(node.props.children);
  return "";
}

function AppDirectiveCard({ directive }: { directive: AppDirective }) {
  const labels: Record<string, string> = {
    "created-thread": "Task created",
    "git-stage": "Changes staged",
    "git-commit": "Changes committed",
    "git-create-branch": "Branch created",
    "git-push": "Branch pushed",
    "git-create-pr": directive.attributes.isDraft === "true" ? "Draft PR opened" : "Pull request opened",
  };
  const detail = directive.attributes.branch || shortPath(directive.attributes.cwd);
  const url = directive.name === "git-create-pr" ? safeLink(directive.attributes.url) : null;
  const content = <><span aria-hidden="true">✓</span><div><strong>{labels[directive.name] ?? "Action completed"}</strong>{detail && <small>{detail}</small>}</div></>;
  return url
    ? <a className="app-directive" href={url} target="_blank" rel="noreferrer noopener">{content}<i aria-hidden="true">↗</i></a>
    : <div className="app-directive">{content}</div>;
}

function shortPath(path?: string): string | null {
  if (!path) return null;
  return path.replace(/\/+$/, "").split("/").filter(Boolean).at(-1) ?? path;
}

export interface NewSessionSettings {
  provider: ProviderId;
  repositoryId: string;
  text?: string;
  model?: string;
  reasoningEffort?: string;
  accessLevel?: string;
  permissionMode?: string;
}

export function NewSessionDialog({ repositories, repositoryRoot, providers = [{ id: "codex", displayName: "Codex", available: true, capabilities: [], limitations: [] }], providerCatalogLoaded = true, models, accessLevels, claudeModels = [], claudePermissionModes = [], onClose, onCreate }: { repositories: RepositoryInfo[]; repositoryRoot: string; providers?: ProviderInfo[]; providerCatalogLoaded?: boolean; models: ModelInfo[]; accessLevels: AccessLevelInfo[]; claudeModels?: ModelInfo[]; claudePermissionModes?: PermissionModeInfo[]; onClose: () => void; onCreate: (settings: NewSessionSettings) => Promise<void> }) {
  const [selected, setSelected] = useState(".");
  const enabledProviders = providers.filter(providerEnabled);
  const [providerChoice, setProviderChoice] = useState<ProviderId>(() =>
    enabledProviders.find(({ id }) => id === "codex")?.id ?? enabledProviders[0]?.id ?? "codex"
  );
  const soleProvider = soleEnabledProvider(providers, providerCatalogLoaded);
  const provider = soleProvider?.id ?? enabledProviders.find(({ id }) => id === providerChoice)?.id ?? enabledProviders[0]?.id ?? providerChoice;
  const showProviderIdentity = shouldShowProviderIdentity(providers, providerCatalogLoaded);
  const [prompt, setPrompt] = useState("");
  const defaultRoute = routeForSession(null, models, accessLevels);
  const [model, setModel] = useState(defaultRoute.model);
  const [effort, setEffort] = useState(defaultRoute.reasoningEffort);
  const [access, setAccess] = useState(defaultRoute.accessLevel);
  const [claudeModel, setClaudeModel] = useState(claudeModels[0]?.id ?? "sonnet");
  const [permissionMode, setPermissionMode] = useState("default");
  const hasRepositories = repositories.length > 0;
  const rootRepository = repositories.find((repository) => repository.id === ".");
  const selectableRepositories = repositories.filter((repository) => repository.id !== ".");
  const selectionAvailable = selected === "." || repositories.some((repository) => repository.id === selected);
  const location = selectionAvailable ? selected : ".";
  const selectedModel = models.find((entry) => entry.id === model);
  const selectedProviderInfo = enabledProviders.find((entry) => entry.id === provider);
  useEffect(() => {
    if (!selectionAvailable) setSelected(".");
  }, [selectionAvailable]);
  useEffect(() => {
    if (!enabledProviders.some(({ id }) => id === providerChoice) && enabledProviders[0]) {
      setProviderChoice(enabledProviders[0].id);
    }
  }, [enabledProviders, providerChoice]);
  const submit = () => onCreate({
    provider,
    repositoryId: location,
    ...(provider === "claude-code" ? {
      text: prompt,
      model: claudeModel || "sonnet",
      permissionMode,
    } : {
      ...(model ? { model } : {}),
      ...(effort ? { reasoningEffort: effort } : {}),
      ...(access ? { accessLevel: access } : {}),
    }),
  });
  const catalogEmpty = providerCatalogLoaded && enabledProviders.length === 0;
  const unavailable = !providerCatalogLoaded || catalogEmpty || selectedProviderInfo?.available !== true;
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><form className="modal" onSubmit={(event) => { event.preventDefault(); if (location && !unavailable && (provider === "codex" || prompt.trim())) void submit(); }}>
    <div className="modal-heading"><div>{showProviderIdentity && selectedProviderInfo && <span className="eyebrow">{selectedProviderInfo.displayName}</span>}<h2>New session</h2></div><button type="button" onClick={onClose} aria-label="Close">×</button></div>
    {providerCatalogLoaded && enabledProviders.length > 1 && <label>Provider<select value={provider} onChange={(event) => setProviderChoice(event.target.value as ProviderId)}>{enabledProviders.map((entry) => <option key={entry.id} value={entry.id}>{entry.displayName}{entry.available ? "" : " · unavailable"}</option>)}</select></label>}
    {!providerCatalogLoaded && <div className="new-session-empty" role="status"><strong>Loading providers…</strong><p>Foreman is checking the enabled providers for this host.</p></div>}
    {catalogEmpty && <div className="new-session-empty" role="status"><strong>No enabled provider is available.</strong><p>Open provider settings and enable at least one provider.</p></div>}
    {providerCatalogLoaded && !catalogEmpty && selectedProviderInfo?.available !== true && <div className="new-session-empty" role="status"><strong>{selectedProviderInfo?.displayName ?? providerDisplayName(provider)} is unavailable on this host.</strong><p>{providerUnavailableDescription(selectedProviderInfo?.unavailableReason, provider)}</p></div>}
    {hasRepositories ? <label>Workspace<select value={selected} onChange={(event) => setSelected(event.target.value)} required><option value=".">Workspace root · {rootRepository ? "repository" : "no repository"}</option>{selectableRepositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.path}{repository.dirty ? " · modified" : ""}</option>)}</select></label> : <div className="new-session-empty" role="status"><strong>No Git repositories yet</strong><p>Start in the configured workspace folder instead. You can initialize Git later if you need version control.</p>{repositoryRoot && <code title={repositoryRoot}>{repositoryRoot}</code>}</div>}
    {provider === "claude-code" && !unavailable && <label>Initial prompt<textarea value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="What should Claude work on?" required /></label>}
    <div className="new-session-settings">{provider === "codex" ? <>
      {accessLevels.length > 0 && <label>Access<select value={access} onChange={(event) => setAccess(event.target.value)}>{accessLevels.map((level) => <option key={level.id} value={level.id}>{level.displayName}</option>)}</select></label>}
      {models.length > 0 && <label>Model<select value={model} onChange={(event) => { const next = models.find((entry) => entry.id === event.target.value); setModel(event.target.value); setEffort(next?.defaultReasoningEffort ?? next?.reasoningEfforts[0] ?? ""); }}>{models.map((entry) => <option key={entry.id} value={entry.id}>{entry.displayName}</option>)}</select></label>}
      {selectedModel && selectedModel.reasoningEfforts.length > 0 && <label>Reasoning<select value={effort} onChange={(event) => setEffort(event.target.value)}>{selectedModel.reasoningEfforts.map((entry) => <option key={entry} value={entry}>{reasoningLabel(entry)}</option>)}</select></label>}
    </> : !unavailable && <>
      <label>Permission mode<select aria-label="Permission mode" value={permissionMode} onChange={(event) => setPermissionMode(event.target.value)}>{claudePermissionModes.map((mode) => <option key={mode.id} value={mode.id}>{mode.displayName}{mode.highRisk ? " · high risk" : ""}</option>)}</select><small>{claudePermissionModes.find((mode) => mode.id === permissionMode)?.description}</small></label>
      <label>Claude model<select aria-label="Claude model" value={claudeModel} onChange={(event) => setClaudeModel(event.target.value)}>{claudeModels.map((entry) => <option key={entry.id} value={entry.id}>{entry.displayName}</option>)}</select><small>Adapter-supported list; not dynamically discovered.</small></label>
    </>}</div>
    <div className="modal-actions"><button type="button" onClick={onClose}>Cancel</button><button className="primary" disabled={unavailable || (provider === "claude-code" && !prompt.trim())}>{provider === "claude-code" ? showProviderIdentity ? "Start Claude session" : "Start session" : hasRepositories ? "Create" : "Start in workspace"}</button></div>
  </form></div>;
}

function providerDisplayName(provider: ProviderId): string {
  return provider === "claude-code" ? "Claude Code" : "Codex";
}

function providerUnavailableDescription(reason?: ProviderInfo["unavailableReason"], provider: ProviderId = "claude-code"): string {
  const descriptions: Record<string, string> = {
    "cli-missing": "The Claude Code CLI is missing.",
    "node-missing": "Node.js 20 or newer is missing.",
    "sdk-missing": "The pinned Claude Agent SDK is missing.",
    "authentication-unavailable": "Claude authentication is unavailable on the host.",
    "adapter-unavailable": "The Claude adapter is unavailable.",
  };
  return reason ? descriptions[reason] ?? `${providerDisplayName(provider)} is unavailable.` : `${providerDisplayName(provider)} is unavailable.`;
}

function SettingsView({
  host,
  hosts,
  appearance,
  hello,
  serverVersion,
  connected,
  providers,
  notificationPreferences,
  notificationState,
  hostNotificationOverride,
  repositoryOptions,
  onAppearance,
  onNotificationPreferences,
  onHostNotificationOverride,
  onNotificationPermission,
  onNotificationTest,
  onProviderEnabled,
  onAdd,
  onSelect,
  onRename,
  onForget,
}: {
  host: StoredHost;
  hosts: StoredHost[];
  appearance: Appearance;
  hello: HelloPayload | null;
  serverVersion: string | null;
  connected: boolean;
  providers: ProviderInfo[];
  notificationPreferences: NotificationPreferences;
  notificationState: BrowserNotificationState;
  hostNotificationOverride: boolean;
  repositoryOptions: RepositoryFilterOption[];
  onAppearance: (appearance: Appearance) => void;
  onNotificationPreferences: (preferences: NotificationPreferences) => void;
  onHostNotificationOverride: (enabled: boolean) => void;
  onNotificationPermission: () => Promise<void>;
  onNotificationTest: () => Promise<NotificationDeliveryMethod>;
  onProviderEnabled: (provider: ProviderId, enabled: boolean) => Promise<void>;
  onAdd: () => void;
  onSelect: (hostId: string) => void;
  onRename: (hostId: string, displayName: string) => void;
  onForget: (hostId: string) => void;
}) {
  const [testingNotification, setTestingNotification] = useState(false);
  const [notificationTestResult, setNotificationTestResult] = useState("");
  const permissionUnavailable = ["insecure", "unsupported", "denied"].includes(notificationState);
  const update = <K extends keyof NotificationPreferences>(key: K, value: NotificationPreferences[K]) =>
    onNotificationPreferences({ ...notificationPreferences, [key]: value });
  const eventToggles: Array<[keyof NotificationPreferences, string]> = [
    ["notifyApprovals", "Approvals and input"],
    ["notifyFailures", "Failures"],
    ["notifyCompletions", "Completions"],
    ["notifyInterruptions", "Interruptions"],
    ["notifyLongRunning", "Long-running turns"],
  ];
  const overrideKeys: Array<[keyof RepositoryNotificationOverride, string]> = [
    ["notifyApprovals", "Approvals/input"],
    ["notifyFailures", "Failures"],
    ["notifyCompletions", "Completions"],
    ["notifyInterruptions", "Interruptions"],
    ["notifyLongRunning", "Long-running"],
  ];
  return <main className="settings-page">
    <header><span className="eyebrow">Preferences</span><h1>Settings</h1></header>
    <section className="settings-card"><h2>Saved hosts</h2><div className="saved-hosts">{hosts.map((saved) => <div className={`saved-host ${saved.id === host.id ? "active" : ""}`} key={saved.id}><button className="saved-host-main" onClick={() => onSelect(saved.id)}><strong>{saved.displayName}</strong><small>{saved.host}:{saved.webPort} · {saved.id === host.id ? "active" : saved.lastKnownStatus}</small></button><button onClick={() => { const name = window.prompt("Host display name", saved.displayName)?.trim(); if (name) onRename(saved.id, name); }}>Rename</button><button className="danger-link" onClick={() => { if (window.confirm(`Forget “${saved.displayName}”? Its browser-local token and preferences will be removed.`)) onForget(saved.id); }}>Forget</button></div>)}</div><button className="secondary add-host" onClick={onAdd}>Add host</button></section>
    <ProviderSettings providers={providers} onProviderEnabled={onProviderEnabled} />
    <section className="settings-card"><h2>Appearance</h2><label>Theme<select value={appearance.theme} onChange={(event) => onAppearance({ ...appearance, theme: event.target.value as Appearance["theme"] })}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select></label><label>Activity detail<select value={appearance.activityDetail} onChange={(event) => onAppearance({ ...appearance, activityDetail: event.target.value as ActivityDetail })}><option value="focused">Focused</option><option value="full">Full</option></select><small>Focused groups routine completed commands and tools. Failures, live work, approvals, and input stay visible.</small></label><label className="check-row"><input type="checkbox" checked={appearance.groupSessionsByRepository} onChange={(event) => onAppearance({ ...appearance, groupSessionsByRepository: event.target.checked })} /><span><strong>Group sessions by repository</strong><small>Keep each project together and show its active sessions first.</small></span></label><div><span className="field-label">Accent</span><div className="accent-grid">{ACCENTS.map((accent) => <button key={accent} className={`accent-swatch ${appearance.accent === accent ? "selected" : ""}`} data-color={accent} onClick={() => onAppearance({ ...appearance, accent })}><i />{titleCase(accent)}</button>)}</div></div></section>
    <section className="settings-card notification-preferences">
      <h2>Notifications</h2>
      <div className="notification-setting"><div><strong>Browser permission: {notificationState}</strong><p>{notificationStateDescription(notificationState, notificationState === "granted")}</p><p>Alerts are evaluated locally. Foreman must stay open in a tab; browsers cannot run this monitor after the site is fully closed.</p>{notificationTestResult && <p className="notification-test-result" role="status">{notificationTestResult}</p>}</div><div className="notification-actions"><button className="secondary" disabled={permissionUnavailable || notificationState === "granted"} onClick={() => void onNotificationPermission()}>{notificationState === "granted" ? "Allowed" : notificationState === "denied" ? "Blocked" : "Allow"}</button>{notificationState === "granted" && <button className="secondary" disabled={testingNotification} onClick={() => { setTestingNotification(true); setNotificationTestResult(""); void onNotificationTest().then((method) => setNotificationTestResult(`Browser accepted the test via ${method === "page" ? "the page" : "the service worker"}. If no system alert appeared, check OS notification settings and Do Not Disturb.`)).catch((caught) => setNotificationTestResult(caught instanceof Error ? `Test failed: ${caught.message}` : "Test failed: the browser rejected the notification.")).finally(() => setTestingNotification(false)); }}>{testingNotification ? "Sending…" : "Send test"}</button>}</div></div>
      <label className="check-row"><input type="checkbox" checked={hostNotificationOverride} onChange={(event) => onHostNotificationOverride(event.target.checked)} /><span><strong>Override for {host.displayName}</strong><small>{hostNotificationOverride ? "This host uses its own local settings." : "This host inherits the global browser defaults."}</small></span></label>
      <div className="notification-toggle-grid">{eventToggles.map(([key, label]) => <label className="check-row single-line" key={key}><input type="checkbox" checked={notificationPreferences[key] as boolean} onChange={(event) => update(key, event.target.checked)} /><span>{label}</span></label>)}</div>
      <label>Long-running threshold (minutes)<input type="number" min="1" max="1440" value={notificationPreferences.longRunningMinutes} disabled={!notificationPreferences.notifyLongRunning} onChange={(event) => update("longRunningMinutes", Math.max(1, Math.min(1440, Number(event.target.value) || 1)))} /></label>
      <label className="check-row single-line"><input type="checkbox" checked={notificationPreferences.quietHoursEnabled} onChange={(event) => update("quietHoursEnabled", event.target.checked)} /><span>Quiet hours</span></label>
      {notificationPreferences.quietHoursEnabled && <div className="quiet-hours"><label>Start<input type="time" value={notificationPreferences.quietStart} onChange={(event) => update("quietStart", event.target.value)} /></label><label>End<input type="time" value={notificationPreferences.quietEnd} onChange={(event) => update("quietEnd", event.target.value)} /></label></div>}
      <label className="check-row"><input type="checkbox" checked={notificationPreferences.criticalBypassQuietHours} onChange={(event) => update("criticalBypassQuietHours", event.target.checked)} /><span><strong>Allow critical alerts during quiet hours</strong><small>Only approval/input and failure alerts bypass quiet hours.</small></span></label>
      <div className="repository-overrides"><h3>Repository and workspace overrides</h3><p className="muted">Each event inherits the settings above until explicitly set to on or off. Identities are canonical workspace paths and stay in this browser.</p>{repositoryOptions.length === 0 && <p className="muted">No known repositories or workspaces yet.</p>}{repositoryOptions.map((repository) => <details key={repository.id}><summary>{repository.label}</summary><small title={repository.id}>{repository.id}</small><div className="override-grid">{overrideKeys.map(([key, label]) => { const value = notificationPreferences.repositoryOverrides[repository.id]?.[key]; return <label key={key}>{label}<select value={value === undefined ? "inherit" : String(value)} onChange={(event) => onNotificationPreferences(setRepositoryOverride(notificationPreferences, repository.id, { [key]: event.target.value === "inherit" ? undefined : event.target.value === "true" }))}><option value="inherit">Inherit</option><option value="true">On</option><option value="false">Off</option></select></label>; })}</div></details>)}</div>
    </section>
    <section className="settings-card"><h2>Active connection</h2><dl><div><dt>Host</dt><dd>{host.host}:{host.webPort}</dd></div><div><dt>Local host ID</dt><dd>{host.id}</dd></div>{providers.map((provider) => <div key={provider.id}><dt>{provider.displayName}</dt><dd>{!providerEnabled(provider) ? "Disabled" : provider.available ? "Available" : "Unavailable"}</dd></div>)}{providers.some((provider) => provider.id === "codex" && providerEnabled(provider)) && <div><dt>Codex runtime</dt><dd>{hello?.codexRuntime === "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE" ? "Shared Desktop runtime attached" : hello?.codexConnected ? "Foreman-managed runtime" : "Unavailable"}</dd></div>}</dl><p className="muted">Each persistent device token stays in browser-local storage and is never placed in the URL. Browser storage is less protected than Android Keystore.</p></section>
    <AboutSection serverVersion={serverVersion} connected={connected} />
  </main>;
}

export function ProviderSettings({ providers, onProviderEnabled }: {
  providers: ProviderInfo[];
  onProviderEnabled: (provider: ProviderId, enabled: boolean) => Promise<void>;
}) {
  const [pending, setPending] = useState<ProviderId | null>(null);
  const enabledCount = providers.filter(providerEnabled).length;
  return <section className="settings-card provider-settings">
    <h2>Providers</h2>
    <p className="muted">Choose which installed CLIs Foreman uses on this host. At least one provider must remain enabled.</p>
    <div className="provider-setting-list">{providers.map((provider) => {
      const enabled = providerEnabled(provider);
      const lastEnabled = enabled && enabledCount === 1;
      const version = provider.version ?? provider.cliVersion;
      const state = !enabled ? "Disabled" : provider.available ? "Available" : "Unavailable";
      return <label className="check-row" key={provider.id}>
        <input
          type="checkbox"
          checked={enabled}
          disabled={pending !== null || lastEnabled}
          onChange={(event) => {
            const next = event.target.checked;
            setPending(provider.id);
            void onProviderEnabled(provider.id, next).finally(() => setPending(null));
          }}
        />
        <span><strong>{provider.displayName}</strong><small>{state}{version ? ` · ${version}` : ""}{lastEnabled ? " · at least one required" : ""}</small></span>
      </label>;
    })}</div>
  </section>;
}

function StatusPill({ status }: { status: string }) {
  return <span className={`status-pill ${status}`}>{status === "working" ? "Active" : status === "waiting" ? "Attention" : titleCase(status)}</span>;
}

function ProviderBadge({ provider }: { provider: ProviderId }) {
  return <span className={`provider-badge ${provider}`}>{provider === "claude-code" ? "Claude Code" : "Codex"}</span>;
}

function safeLink(href?: string): string | null {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.href);
    return ["http:", "https:", "mailto:"].includes(url.protocol) ? href : null;
  } catch {
    return null;
  }
}

function setupError(caught: unknown): string {
  const message = caught instanceof Error ? caught.message : "Cannot connect to Foreman";
  if (/pairing key is invalid or expired/i.test(message)) return "Pairing code is invalid or expired. Run foreman pair again.";
  if (/unauthorized|token/i.test(message)) return "Authentication failed. Pair this browser again.";
  if (/incompatible/i.test(message)) return "This browser and Foreman service use incompatible protocols.";
  if (/fallback/i.test(message)) return "Foreman is using the Foreman-managed Codex runtime.";
  return message;
}

function shortRepository(path: string): string {
  if (!path) return "No repository";
  const parts = path.replace(/\/$/, "").split("/");
  return parts.at(-1) || path;
}

function titleCase(value: string): string {
  return value ? value[0].toUpperCase() + value.slice(1).replaceAll("-", " ") : "";
}

export default App;
