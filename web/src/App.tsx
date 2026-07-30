import {
  Fragment,
  type ChangeEvent,
  type ClipboardEvent,
  type FormEvent,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import ReactMarkdown from "react-markdown";
import { ApprovalCard, approvalAttentionLabel } from "./ApprovalCard";
import { InputCard, inputAttentionLabel } from "./InputCard";
import { Dashboard } from "./Dashboard";
import { messageDraft, updateMessageDraft } from "./drafts";
import { UnifiedDashboard } from "./UnifiedDashboard";
import { UnifiedHostConnections } from "./unified-client";
import { forgetHostSnapshot, loadHostSnapshots, saveHostSnapshots } from "./unified-storage";
import { mergeHostSnapshot, projectHostSnapshot, type HostOverviewSnapshot, type UnifiedAttentionItem } from "./unified";
import { SessionSearchControls, SessionSearchResults } from "./SessionDiscovery";
import { recordRecentActivity, type RecentActivityEntry } from "./dashboard";
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
  showTurnNotification,
  TurnNotificationMonitor,
  type BrowserNotificationState,
} from "./notifications";
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
  routeForSession,
  reconcileSessionSummaries,
  type AccessLevelInfo,
  type ApprovalEventPayload,
  type ApprovalRequest,
  type HelloPayload,
  type InputEventPayload,
  type InputRequest,
  type ModelInfo,
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
  loadHostNotificationOverride,
  loadHostRegistry,
  loadNotificationPreferences,
  loadSessionSearch,
  loadSessionOrganization,
  saveAppearance,
  clearHostNotificationOverride,
  saveHostRegistry,
  saveNotificationPreferences,
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
  sessionFiltersSearch,
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

export function appShellClassName(view: View): string {
  return view === "settings" ? "app-shell settings-shell" : "app-shell";
}
type PairingSettings = Omit<NewStoredHost, "deviceToken"> & { deviceName: string };

function App() {
  const initialRoute = useRef(parseWebRoute(window.location.pathname)).current;
  const initialRegistry = useRef(loadHostRegistry()).current;
  const requestedHostId = hostIdFromUrl();
  const initialHostId = initialRegistry.hosts.some(({ id }) => id === requestedHostId)
    ? requestedHostId
    : initialRegistry.activeHostId;
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
  const [pairedClients, setPairedClients] = useState<PairedClient[]>([]);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [current, setCurrent] = useState<SessionSummary | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const [highlightItemId, setHighlightItemId] = useState<string | null>(null);
  const [focusedApprovalId, setFocusedApprovalId] = useState<string | null>(null);
  const [approvals, setApprovals] = useState<ApprovalRequest[]>([]);
  const [inputs, setInputs] = useState<InputRequest[]>([]);
  const selectedIdRef = useRef<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [accessLevels, setAccessLevels] = useState<AccessLevelInfo[]>([]);
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
  const notificationPreferencesRef = useRef(notificationPreferences);
  const searchFiltersRef = useRef(initialFilters);
  const lastImmediateSearch = useRef(0);
  const lastBackendSearchKey = useRef("");
  const searchGeneration = useRef(0);
  const sessionsRef = useRef<SessionSummary[]>([]);
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
  const openSessionRef = useRef<(id: string, updateHistory?: boolean) => void>(() => undefined);
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

  useEffect(() => applyAppearance(appearance), [appearance]);
  useEffect(() => {
    notificationPreferencesRef.current = notificationPreferences;
    notificationMonitor.current.configure(notificationPreferences, (notification) => {
      if (document.visibilityState !== "visible" || !document.hasFocus()) {
        void showTurnNotification(notification).catch(() => undefined);
      }
    }, (tag) => { void clearTurnNotification(tag).catch(() => undefined); });
  }, [notificationPreferences]);
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

  const queueDashboardEvent = useCallback((sessionId: string, event: SessionEvent) => {
    const pending = pendingDashboardEvents.current;
    const events = pending.get(sessionId) ?? [];
    pending.set(sessionId, [...events.slice(-49), event]);
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
    if (message.type === "service.event") {
      setServiceStatus({ ...(message.payload as unknown as ServiceStatus), receivedAt: Date.now() });
      void clientRef.current?.request<{ clients: PairedClient[] } & Record<string, unknown>>("client.list")
        .then((result) => setPairedClients(result.clients))
        .catch(() => undefined);
      return;
    }
    if (["approval.requested", "approval.updated", "approval.resolved"].includes(message.type)) {
      const approval = (message.payload as unknown as ApprovalEventPayload).approval;
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
        lastActivity: Math.floor(Date.now() / 1000),
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
        if (notification && (document.visibilityState !== "visible" || !document.hasFocus())) {
          void showTurnNotification(notification).catch(() => undefined);
        }
      }
      if (feedSession && (message.type === "approval.requested" || message.type === "approval.resolved")) {
        setRecentActivity((previous) => recordRecentActivity(previous, feedSession, {
          kind: "activity",
          label: message.type === "approval.requested" ? "Approval requested" : "Approval resolved",
          observedAt: Math.floor(Date.now() / 1000),
        }));
      }
      if (resolved) window.setTimeout(() => {
        setApprovals((previous) => previous.filter((item) => item.id !== approval.id));
        setFocusedApprovalId((current) => current === approval.id ? null : current);
      }, 5_000);
      return;
    }
    if (["input.requested", "input.updated", "input.resolved"].includes(message.type)) {
      const pending = (message.payload as unknown as InputEventPayload).input;
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
        lastActivity: Math.floor(Date.now() / 1000),
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
        if (notification && (document.visibilityState !== "visible" || !document.hasFocus())) {
          void showTurnNotification(notification).catch(() => undefined);
        }
      }
      if (resolved) window.setTimeout(() => setInputs((previous) => previous.filter(({ id }) => id !== pending.id)), 5_000);
      return;
    }
    if (message.type !== "session.event") return;
    const payload = message.payload as unknown as SessionEventPayload;
    if (!payload.sessionId || !payload.event) return;
    const feedSession = payload.event.session ?? sessionsRef.current.find(
      (session) => session.id === payload.sessionId,
    );
    setRecentActivity((previous) => recordRecentActivity(previous, feedSession, payload.event));
    if (payload.event.kind !== "lifecycle" && payload.event.observedAt) {
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
        setSessions((previous) => {
          const next = previous.filter((session) => session.id !== payload.sessionId);
          sessionsRef.current = next;
          return next;
        });
        setSearchResults((previous) => previous.filter(({ session }) => session.id !== payload.sessionId));
        setOrganization((previous) => {
          const next = {
            pinnedIds: previous.pinnedIds.filter((id) => id !== payload.sessionId),
            hiddenIds: previous.hiddenIds.filter((id) => id !== payload.sessionId),
          };
          if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
          return next;
        });
      } else if (payload.event.session) {
        setSessions((previous) => {
          const next = previous.some((session) => session.id === payload.sessionId)
            ? previous.map((session) => session.id === payload.sessionId ? payload.event.session! : session)
            : [payload.event.session!, ...previous];
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
        ({ id }) => id === payload.sessionId,
      );
      const repositoryId = repositoryIdentity(
        observedSession?.repository ?? "",
        repositoriesRef.current,
        repositoryRootRef.current,
      ).id;
      const notification = notificationMonitor.current.observe(
        {
          hostId: activeHostIdRef.current ?? "",
          sessionId: payload.sessionId,
          repositoryId,
          status: payload.event.status,
          turnId: payload.event.turnId ?? observedSession?.activeTurnId,
          activeTurnStartedAt: payload.event.startedAt ?? observedSession?.activeTurnStartedAt,
          waitType: payload.event.waitType ?? observedSession?.waitType,
        },
      );
      if (
        notification &&
        (document.visibilityState !== "visible" || !document.hasFocus())
      ) {
        void showTurnNotification(notification).catch(() => undefined);
      }
      const active = ["working", "waiting"].includes(payload.event.status);
      if (active && !dashboardSubscriptions.current.has(payload.sessionId)) {
        dashboardSubscriptions.current.add(payload.sessionId);
        void clientRef.current?.request("session.subscribe", { sessionId: payload.sessionId })
          .catch(() => dashboardSubscriptions.current.delete(payload.sessionId));
      } else if (!active && selectedIdRef.current !== payload.sessionId) {
        dashboardSubscriptions.current.delete(payload.sessionId);
        void clientRef.current?.request("session.unsubscribe", { sessionId: payload.sessionId })
          .catch(() => undefined);
      }
      if (!sessionsRef.current.some((session) => session.id === payload.sessionId)) {
        void clientRef.current?.request<{ sessions: SessionSummary[] } & Record<string, unknown>>(
          "session.list",
        ).then((result) => {
          setSessions((previous) => {
            const next = reconcileSessionSummaries(previous, result.sessions);
            sessionsRef.current = next;
            return next;
          });
        }).catch(() => undefined);
      }
    }
    if (["activity", "assistant.delta"].includes(payload.event.kind)) {
      queueDashboardEvent(payload.sessionId, payload.event);
    } else {
      setSessions((previous) => {
        const next = previous.map((session) =>
          session.id === payload.sessionId
            ? applySessionSummaryEvent(session, payload.event)
            : session,
        );
        sessionsRef.current = next;
        return next;
      });
    }
    if (viewRef.current === "detail") {
      setCurrent((session) =>
        session?.id === payload.sessionId ? applySessionEvent(session, payload.event) : session,
      );
    }
  }, [queueDashboardEvent]);

  const clearHostProjections = useCallback(() => {
    searchGeneration.current += 1;
    if (dashboardFrame.current !== null) cancelAnimationFrame(dashboardFrame.current);
    dashboardFrame.current = null;
    pendingDashboardEvents.current.clear();
    dashboardSubscriptions.current.clear();
    notificationMonitor.current.dispose();
    notificationMonitor.current = new TurnNotificationMonitor();
    notificationMonitor.current.configure(notificationPreferencesRef.current, (notification) => {
      if (document.visibilityState !== "visible" || !document.hasFocus()) {
        void showTurnNotification(notification).catch(() => undefined);
      }
    }, (tag) => { void clearTurnNotification(tag).catch(() => undefined); });
    sessionsRef.current = [];
    currentRef.current = null;
    selectedIdRef.current = null;
    setSessions([]);
    setCurrent(null);
    setSelectedId(null);
    setHighlightItemId(null);
    setFocusedApprovalId(null);
    setApprovals([]);
    setInputs([]);
    setModels([]);
    setAccessLevels([]);
    setRepositories([]);
    setServiceStatus(null);
    setPairedClients([]);
    setRecentActivity([]);
    setSearchResults([]);
    setSearchLoading(false);
    setSearchError("");
    setHello(null);
    setBusy(false);
    setNewSessionOpen(false);
    setError("");
  }, []);

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

  const refreshState = useCallback(
    async (hostId: string, reconnected = false) => {
      const [approvalResult, inputResult, sessionResult, modelResult, accessResult, statusResult, repositoryResult, clientResult] = await Promise.all([
        client.request<{ approvals: ApprovalRequest[] } & Record<string, unknown>>("approval.list"),
        client.request<{ inputs: InputRequest[] } & Record<string, unknown>>("input.list")
          .catch(() => ({ inputs: [] })),
        client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("session.list"),
        client.request<{ models: ModelInfo[] } & Record<string, unknown>>("model.list"),
        client.request<{ levels: AccessLevelInfo[] } & Record<string, unknown>>("access.list"),
        client.request<ServiceStatus & Record<string, unknown>>("service.status"),
        client.request<{ repositories: RepositoryInfo[] } & Record<string, unknown>>("repository.list"),
        client.request<{ clients: PairedClient[] } & Record<string, unknown>>("client.list"),
      ]);
      if (activeHostIdRef.current !== hostId) return;
      setApprovals(approvalResult.approvals);
      setInputs(inputResult.inputs);
      const reconciled = reconcileSessionSummaries(sessionsRef.current, sessionResult.sessions);
      sessionsRef.current = reconciled;
      notificationMonitor.current.seed(reconciled.map((session) => ({
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
      const validIds = new Set(reconciled.map(({ id }) => id));
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
      setServiceStatus({ ...statusResult, receivedAt: Date.now() });
      setRepositories(repositoryResult.repositories);
      setPairedClients(clientResult.clients);
      if (reconnected) dashboardSubscriptions.current.clear();
      const wanted = new Set(
        reconciled
          .filter((session) => ["working", "waiting"].includes(session.status))
          .map((session) => session.id),
      );
      const stale = [...dashboardSubscriptions.current].filter((id) => !wanted.has(id));
      await Promise.all([
        ...[...wanted].map((sessionId) =>
          client.request("session.subscribe", { sessionId }).then(() => {
            dashboardSubscriptions.current.add(sessionId);
          }).catch(() => undefined),
        ),
        ...stale.map((sessionId) =>
          client.request("session.unsubscribe", { sessionId }).then(() => {
            dashboardSubscriptions.current.delete(sessionId);
          }).catch(() => undefined),
        ),
      ]);
      const reopenId = selectedIdRef.current;
      if (reopenId && viewRef.current === "detail") {
        try {
          const result = await client.request<
            { session: SessionSummary } & Record<string, unknown>
          >("session.read", { sessionId: reopenId });
          setCurrent(result.session);
          await client.request("session.subscribe", { sessionId: reopenId });
        } catch {
          selectedIdRef.current = null;
          setSelectedId(null);
          setCurrent(null);
          setView("dashboard");
          updateRoute({ view: "dashboard" }, true);
        }
      }
    },
    [client, updateRoute],
  );

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
    async (id: string, updateHistory = true, matchedItemId: string | null = null, approvalId: string | null = null) => {
      setError("");
      setBusy(true);
      selectedIdRef.current = id;
      setSelectedId(id);
      setHighlightItemId(matchedItemId);
      setFocusedApprovalId(approvalId);
      viewRef.current = "detail";
      setView("detail");
      if (updateHistory) updateRoute({ view: "detail", sessionId: id });
      try {
        const result = await client.request<
          { session: SessionSummary } & Record<string, unknown>
        >("session.read", { sessionId: id });
        setCurrent(result.session);
        await client.request("session.subscribe", { sessionId: id });
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : "Session could not be loaded");
      } finally {
        setBusy(false);
      }
    },
    [client, updateRoute],
  );
  openSessionRef.current = (id, updateHistory = true) => { void openSession(id, updateHistory); };

  const closeSelectedSession = useCallback(() => {
    const sessionId = selectedIdRef.current;
    if (sessionId && !dashboardSubscriptions.current.has(sessionId)) {
      void client.request("session.unsubscribe", { sessionId }).catch(() => undefined);
    }
    selectedIdRef.current = null;
    setSelectedId(null);
    setCurrent(null);
  }, [client]);

  const restoreView = useCallback((route: WebRoute, openConnectedDetail: boolean) => {
    viewRef.current = route.view;
    setView(route.view);
    if (route.view !== "detail") {
      selectedIdRef.current = null;
      setSelectedId(null);
      setCurrent(null);
      return;
    }
    selectedIdRef.current = route.sessionId;
    setSelectedId(route.sessionId);
    if (openConnectedDetail && currentRef.current?.id !== route.sessionId) {
      openSessionRef.current(route.sessionId, false);
    }
  }, []);

  const activateHost = useCallback((hostId: string, route: WebRoute = { view: "dashboard" }, replace = false, filtersSearch?: string) => {
    const registry = hostRegistryRef.current;
    if (!registry.hosts.some((host) => host.id === hostId)) return;
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
    }
    const filters = parseSessionFilters(filtersSearch ?? loadSessionSearch(hostId));
    searchFiltersRef.current = filters;
    setSearchFilters(filters);
    restoreView(route, !switching && connectedRef.current);
    const search = withHostInSearch(sessionFiltersSearch(filters), hostId);
    const path = webRoutePath(route);
    window.history[replace ? "replaceState" : "pushState"](null, "", `${path}${search}`);
  }, [clearHostProjections, client, persistRegistry, restoreView]);

  notificationOpenRef.current = (hostId, sessionId) => {
    activateHost(hostId, { view: "detail", sessionId });
  };

  useEffect(() => {
    const restoreRoute = () => {
      const route = parseWebRoute(window.location.pathname);
      const hostId = hostIdFromUrl();
      if (hostId) activateHost(hostId, route, true, window.location.search);
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

  const showSessions = (replace = false) => {
    viewRef.current = "sessions";
    setView("sessions");
    closeSelectedSession();
    updateRoute({ view: "sessions" }, replace);
  };

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
    restoreView({ view: "dashboard" }, false);
    const search = next.activeHostId ? withHostInSearch("", next.activeHostId) : "";
    window.history.replaceState(null, "", `/${search}`);
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
      restoreView({ view: "dashboard" }, false);
      window.history.pushState(null, "", `/${withHostInSearch("", saved.id)}`);
    } catch (caught) {
      setError(setupError(caught));
    } finally {
      pairingClient.disconnect();
      setBusy(false);
    }
  };

  const dashboardOpen = useCallback((id: string) => {
    void openSession(id);
  }, [openSession]);
  const dashboardOpenApproval = useCallback((approval: ApprovalRequest) => {
    void openSession(approval.sessionId, true, null, approval.id);
  }, [openSession]);
  const dashboardOpenInput = useCallback((input: InputRequest) => {
    void openSession(input.sessionId, true, null, input.id);
  }, [openSession]);
  const searchResultOpen = useCallback((id: string, itemId?: string | null) => {
    void openSession(id, true, itemId ?? null);
  }, [openSession]);
  const dashboardInterrupt = useCallback((session: SessionSummary) => {
    if (!session.activeTurnId) return;
    void client.request("turn.interrupt", {
      sessionId: session.id,
      turnId: session.activeTurnId,
    }).catch((caught) => setError(caught instanceof Error ? caught.message : "Interrupt failed"));
  }, [client]);
  const dashboardRefresh = useCallback(() => {
    const hostId = activeHostIdRef.current;
    if (hostId) void refreshState(hostId).catch((caught) => setError(String(caught)));
  }, [refreshState]);
  const unifiedOpenSession = useCallback((item: UnifiedAttentionItem | { hostId: string; sessionId: string }) => {
    const wasActive = item.hostId === activeHostIdRef.current;
    activateHost(item.hostId, { view: "detail", sessionId: item.sessionId });
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
  const togglePin = useCallback((id: string) => {
    setOrganization((previous) => {
      const pinnedIds = previous.pinnedIds.includes(id)
        ? previous.pinnedIds.filter((value) => value !== id)
        : [...previous.pinnedIds, id];
      const next = { ...previous, pinnedIds };
      if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
      return next;
    });
  }, []);
  const toggleHidden = useCallback((id: string) => {
    setOrganization((previous) => {
      const hiddenIds = previous.hiddenIds.includes(id)
        ? previous.hiddenIds.filter((value) => value !== id)
        : [...previous.hiddenIds, id];
      const next = { ...previous, hiddenIds };
      if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
      return next;
    });
  }, []);

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
          onSelect={(hostId) => activateHost(hostId)}
          onAdd={() => setHostSetupOpen(true)}
        />
        <nav>
          <button className={view === "dashboard" ? "active" : ""} onClick={() => showDashboard()}>
            Dashboard
          </button>
          <button className={view === "sessions" || view === "detail" ? "active" : ""} onClick={() => showSessions()}>
            Sessions
          </button>
          <button className={view === "settings" ? "active" : ""} onClick={showSettings}>
            Settings
          </button>
        </nav>
      </header>

      {hello?.codexRuntime === "SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE" && (
        <div className="runtime-banner" role="status">
          Fallback Codex runtime active. Live Desktop co-presence is unavailable.
        </div>
      )}
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
          {discoveryActive ? <main className="dashboard-page search-page"><SessionSearchResults results={visibleSessions} query={searchFilters.query} loading={searchLoading} error={searchError} onOpen={searchResultOpen} onPin={togglePin} onHide={toggleHidden} /></main> : <>
            {visibleSessions.some(({ pinned }) => pinned) && <section className="dashboard-pinned"><header><h2>Pinned</h2><span>Client-local</span></header><SessionSearchResults results={visibleSessions.filter(({ pinned }) => pinned)} query="" loading={false} error="" onOpen={searchResultOpen} onPin={togglePin} onHide={toggleHidden} /></section>}
            <Dashboard
            hostId={activeHost.id}
            sessions={visibleSessions.map(({ session }) => session)}
            approvals={approvals}
            inputs={inputs}
            serviceStatus={serviceStatus}
            repositories={repositories}
            recentActivity={recentActivity.filter((entry) => !organization.hiddenIds.includes(entry.sessionId))}
            pairedClients={pairedClients}
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
            results={visibleSessions}
            filters={searchFilters}
            repositoryOptions={repositoryOptions}
            searchLoading={searchLoading}
            searchError={searchError}
            selectedId={selectedId}
            disabled={!connected}
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
                await client.request(`session.${action}`, {
                  sessionId: session.id,
                  ...(action === "delete" ? { confirm: true } : {}),
                });
                setSessions((previous) => previous.filter((item) => item.id !== session.id));
                setSearchResults((previous) => previous.filter(({ session: result }) => result.id !== session.id));
                if (action === "delete") {
                  setOrganization((previous) => {
                    const next = {
                      pinnedIds: previous.pinnedIds.filter((id) => id !== session.id),
                      hiddenIds: previous.hiddenIds.filter((id) => id !== session.id),
                    };
                    if (activeHostIdRef.current) saveSessionOrganization(next, activeHostIdRef.current);
                    return next;
                  });
                }
                if (selectedIdRef.current === session.id) {
                  selectedIdRef.current = null;
                  setSelectedId(null);
                  setCurrent(null);
                  showSessions(true);
                }
              } catch (caught) {
                setError(caught instanceof Error ? caught.message : `${action} failed`);
              }
            }}
          />
          <section className="detail-pane">
            {current ? (
              <ConversationView
                key={`${activeHost.id}:${current.id}`}
                session={current}
                approvals={approvals.filter((approval) => approval.sessionId === current.id)}
                inputs={inputs.filter((pending) => pending.sessionId === current.id)}
                models={models}
                accessLevels={accessLevels}
                connected={connected}
                highlightItemId={highlightItemId}
                focusedApprovalId={focusedApprovalId}
                draft={messageDraft(messageDrafts, activeHost.id, current.id)}
                onDraftChange={(text) => setMessageDrafts((previous) =>
                  updateMessageDraft(previous, activeHost.id, current.id, text)
                )}
                onBack={() => showSessions()}
                onRequest={(type, payload) => client.request(type, payload)}
                onError={setError}
              />
            ) : (
              <div className="empty-detail">
                <span className="brand-mark large">F</span>
                <h2>{busy ? "Loading session…" : "Select a session"}</h2>
                <p>Open an existing Codex session or start a new one.</p>
              </div>
            )}
          </section>
        </main>
      )}

      {newSessionOpen && (
        <NewSessionDialog
          repositories={repositories}
          onClose={() => setNewSessionOpen(false)}
          onCreate={async (repositoryId) => {
            setBusy(true);
            try {
              const result = await client.request<
                { session: SessionSummary } & Record<string, unknown>
              >("session.start", { repositoryId });
              setSessions((previous) => previous.some((session) => session.id === result.session.id)
                ? previous.map((session) => session.id === result.session.id ? result.session : session)
                : [result.session, ...previous]);
              selectedIdRef.current = result.session.id;
              setSelectedId(result.session.id);
              setCurrent(result.session);
              viewRef.current = "detail";
              setView("detail");
              updateRoute({ view: "detail", sessionId: result.session.id });
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

function SessionList({
  results,
  filters,
  repositoryOptions,
  searchLoading,
  searchError,
  selectedId,
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
  results: VisibleSession[];
  filters: SessionFilters;
  repositoryOptions: RepositoryFilterOption[];
  searchLoading: boolean;
  searchError: string;
  selectedId: string | null;
  disabled: boolean;
  onOpen: (id: string, itemId?: string | null) => void;
  onRefresh: () => void;
  onNew: () => void;
  onAction: (action: "archive" | "delete", session: SessionSummary) => void;
  onFilters: (filters: SessionFilters) => void;
  onSearchNow: () => void;
  onPin: (id: string) => void;
  onHide: (id: string) => void;
}) {
  const sessions = results.map(({ session }) => session);
  const pinnedSessions = results.filter(({ pinned }) => pinned).map(({ session }) => session);
  const unpinnedSessions = results.filter(({ pinned }) => !pinned).map(({ session }) => session);
  const groups = {
    pinned: pinnedSessions,
    waiting: unpinnedSessions.filter((session) => session.attention || session.status === "waiting"),
    active: unpinnedSessions.filter((session) => !session.attention && session.status === "working"),
    recent: unpinnedSessions.filter((session) => !session.attention && session.status !== "waiting" && session.status !== "working"),
  };
  const discoveryActive = activeFilterCount(filters) > 0;
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
        {discoveryActive ? <SessionSearchResults results={results} query={filters.query} loading={searchLoading} error={searchError} onOpen={onOpen} onPin={onPin} onHide={onHide} /> : <>
        {sessions.length === 0 && <div className="empty-list"><h3>No sessions yet</h3><p>Start one from a repository.</p></div>}
        {(["pinned", "waiting", "active", "recent"] as const).map((group) =>
          groups[group].length ? (
            <section className="session-group" key={group}>
              <h2>{group === "pinned" ? "Pinned" : group === "waiting" ? "Needs attention" : group === "active" ? "Active" : "Recent"}</h2>
              {groups[group].map((session) => (
                <article
                  key={session.id}
                  className={`session-card ${selectedId === session.id ? "selected" : ""}`}
                  onClick={() => onOpen(session.id)}
                >
                  <div className="session-title-row"><h3>{session.title}</h3><StatusPill status={session.status} /></div>
                  <p className="repository">{shortRepository(session.repository)}</p>
                  <div className="session-meta">
                    <span>{formatActivity(session.lastActivity)}</span>
                    <span className="card-actions">
                      <button className={results.find((item) => item.session.id === session.id)?.pinned ? "selected" : ""} onClick={(event) => { event.stopPropagation(); onPin(session.id); }} aria-label={`${results.find((item) => item.session.id === session.id)?.pinned ? "Unpin" : "Pin"} ${session.title}`}>{results.find((item) => item.session.id === session.id)?.pinned ? "★" : "☆"}</button>
                      <button onClick={(event) => { event.stopPropagation(); onHide(session.id); }}>Hide</button>
                      <button onClick={(event) => { event.stopPropagation(); onAction("archive", session); }} disabled={session.status === "working" || session.status === "waiting"}>Archive</button>
                      <button className="danger-link" onClick={(event) => { event.stopPropagation(); onAction("delete", session); }} disabled={session.status === "working" || session.status === "waiting"}>Delete</button>
                    </span>
                  </div>
                </article>
              ))}
            </section>
          ) : null,
        )}
        </>}
      </div>
    </aside>
  );
}

export function ConversationView({
  session,
  approvals,
  inputs = [],
  models,
  accessLevels,
  connected,
  highlightItemId,
  focusedApprovalId,
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
  highlightItemId: string | null;
  focusedApprovalId: string | null;
  draft: string;
  onDraftChange: (text: string) => void;
  onBack: () => void;
  onRequest: <T extends Record<string, unknown>>(type: string, payload?: Record<string, unknown>) => Promise<T>;
  onError: (message: string) => void;
}) {
  const initialRoute = useMemo(() => routeForSession(session, models, accessLevels), [session, models, accessLevels]);
  const [model, setModel] = useState(initialRoute.model);
  const [effort, setEffort] = useState(initialRoute.reasoningEffort);
  const [access, setAccess] = useState(initialRoute.accessLevel);
  const [images, setImages] = useState<ProcessedImage[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [updatingRoute, setUpdatingRoute] = useState(false);
  const [processing, setProcessing] = useState(false);
  const processingImages = useRef(false);
  const submissionGuard = useRef(createSubmissionGuard());
  const transcriptRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const [jumpVisible, setJumpVisible] = useState(false);

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
    following.current = !highlightItemId;
    setJumpVisible(false);
    if (!highlightItemId) {
      requestAnimationFrame(() => transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight }));
    }
  }, [highlightItemId, session.id]);

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
  const hasActiveTurn = (session.status === "working" || session.status === "waiting") && !!session.activeTurnId;
  const canSubmit = connected && !submitting && !updatingRoute && !processing && (!!draft.trim() || images.length > 0);
  const activityLabel = liveActivityLabel(session);
  const activityMessage = liveActivityMessage(session);

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
      if (active) {
        await onRequest("turn.steer", { ...base, turnId: session.activeTurnId });
      } else {
        await onRequest("turn.prompt", {
          ...base,
          ...(model ? { model } : {}),
          ...(effort ? { reasoningEffort: effort } : {}),
          ...(access ? { accessLevel: access } : {}),
        });
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
    const previous = access;
    setAccess(value);
    setUpdatingRoute(true);
    try {
      await onRequest("session.settings", { sessionId: session.id, accessLevel: value });
    } catch (caught) {
      setAccess(previous);
      onError(caught instanceof Error ? caught.message : "Access setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  const updateModel = async (value: string) => {
    const previousModel = model;
    const previousEffort = effort;
    const next = models.find((entry) => entry.id === value);
    const nextEffort = next?.defaultReasoningEffort ?? next?.reasoningEfforts[0] ?? "";
    setModel(value);
    setEffort(nextEffort);
    setUpdatingRoute(true);
    try {
      await onRequest("session.settings", {
        sessionId: session.id,
        model: value,
        ...(nextEffort ? { reasoningEffort: nextEffort } : {}),
      });
    } catch (caught) {
      setModel(previousModel);
      setEffort(previousEffort);
      onError(caught instanceof Error ? caught.message : "Model setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  const updateEffort = async (value: string) => {
    const previous = effort;
    setEffort(value);
    setUpdatingRoute(true);
    try {
      await onRequest("session.settings", {
        sessionId: session.id,
        model,
        reasoningEffort: value,
      });
    } catch (caught) {
      setEffort(previous);
      onError(caught instanceof Error ? caught.message : "Reasoning setting was not updated");
    } finally {
      setUpdatingRoute(false);
    }
  };

  return (
    <div className="conversation">
      <header className="conversation-header">
        <button className="mobile-back" onClick={onBack}>‹ Sessions</button>
        <div><h1>{session.title}</h1><p>{shortRepository(session.repository)}</p></div>
        <StatusPill status={session.status} />
      </header>
      <div
        className="transcript"
        ref={transcriptRef}
        onScroll={(event) => {
          const target = event.currentTarget;
          const atBottom = isNearBottom(target.scrollTop, target.clientHeight, target.scrollHeight);
          following.current = atBottom;
          if (atBottom) setJumpVisible(false);
        }}
      >
        {!session.messages?.length && !approvals.length && !inputs.length && <div className="empty-conversation"><h2>Ready when you are</h2><p>Choose a route below and send the first prompt.</p></div>}
        {session.messages?.map((item) => <Fragment key={item.id}><ConversationItemView item={item} highlighted={item.id === highlightItemId} />{approvals.filter((approval) => approval.itemId === item.id).map((approval) => <ApprovalCard key={approval.id} approval={approval} focused={focusedApprovalId === approval.id} connected={connected} onRespond={async (approvalId, decision) => { await onRequest("approval.respond", { approvalId, decision }); }} />)}{inputs.filter((input) => input.itemId === item.id).map((input) => <InputCard key={input.id} input={input} focused={focusedApprovalId === input.id} connected={connected} onRespond={async (inputId, response) => { await onRequest("input.respond", { inputId, response }); }} />)}</Fragment>)}
        {approvals.filter((approval) => !approval.itemId || !session.messages?.some((item) => item.id === approval.itemId)).map((approval) => <ApprovalCard key={approval.id} approval={approval} focused={focusedApprovalId === approval.id} connected={connected} onRespond={async (approvalId, decision) => { await onRequest("approval.respond", { approvalId, decision }); }} />)}
        {inputs.filter((input) => !input.itemId || !session.messages?.some((item) => item.id === input.itemId)).map((input) => <InputCard key={input.id} input={input} focused={focusedApprovalId === input.id} connected={connected} onRespond={async (inputId, response) => { await onRequest("input.respond", { inputId, response }); }} />)}
        {(session.status === "working" || session.status === "waiting") && (
          <div className="live-activity">
            <span className="pulse" />
            <div>{activityMessage ? <><Markdown text={activityMessage} /><small>{activityLabel}…</small></> : <strong>{session.status === "waiting" ? "Waiting for attention…" : `${activityLabel}…`}</strong>}</div>
          </div>
        )}
      </div>
      {jumpVisible && <button className="jump-latest" onClick={() => { following.current = true; setJumpVisible(false); transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight, behavior: "smooth" }); }}>Jump to latest ↓</button>}
      <form className="composer" onSubmit={submit}>
        <div className="route-row">
          <RouteSelect label="Access" value={access} options={accessLevels.map((level) => ({ value: level.id, label: level.displayName, description: level.description, warning: level.id === "full" }))} disabled={!connected || submitting || updatingRoute} onChange={(value) => void updateAccess(value)} />
          <RouteSelect label="Model" value={model} options={models.map((entry) => ({ value: entry.id, label: entry.displayName, description: entry.description }))} disabled={!connected || submitting || updatingRoute} onChange={(value) => void updateModel(value)} />
          <RouteSelect label="Reasoning" value={effort} options={selectedModel?.reasoningEfforts.map((entry) => ({ value: entry, label: reasoningLabel(entry), description: reasoningDescription(entry) })) ?? []} disabled={!connected || submitting || updatingRoute} onChange={(value) => void updateEffort(value)} />
        </div>
        {images.length > 0 && <div className="attachment-row">{images.map((image, index) => <figure key={`${image.name}-${index}`}><img src={image.previewUrl} alt={image.name} /><button type="button" onClick={() => setImages((previous) => previous.filter((_, itemIndex) => itemIndex !== index))} aria-label={`Remove ${image.name}`}>×</button></figure>)}</div>}
        <div className="entry-row">
          <label className="attach-button" title="Attach images">+<input type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(event) => void addFiles(event)} disabled={processing || submitting || images.length >= 4} /></label>
          <textarea value={draft} onChange={(event) => onDraftChange(event.target.value)} onPaste={pasteImages} placeholder={active ? "Steer the active turn…" : "Message Codex…"} rows={1} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} />
          {hasActiveTurn && <button type="button" className="interrupt" disabled={!connected || submitting || updatingRoute} onClick={() => void onRequest("turn.interrupt", { sessionId: session.id, turnId: session.activeTurnId }).catch((caught) => onError(String(caught)))}>Stop</button>}
          <button className="send-button" disabled={!canSubmit}>{submitting ? "…" : active ? "Steer" : "Send"}</button>
        </div>
        {!connected && <p className="composer-note">Your draft is preserved while Foreman reconnects.</p>}
      </form>
    </div>
  );
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
  onChange,
}: {
  label: string;
  value: string;
  options: RouteOption[];
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const selected = options.find((option) => option.value === value);

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
        aria-label={`${label}: ${selected?.label ?? value}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled || options.length === 0}
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <span>{selected?.label ?? value}</span><i aria-hidden="true">⌄</i>
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

function ConversationItemView({ item, highlighted = false }: { item: NonNullable<SessionSummary["messages"]>[number]; highlighted?: boolean }) {
  if (item.kind === "command" || item.kind === "tool") {
    return <article id={`message-${item.id}`} className={`tool-card ${highlighted ? "search-highlight" : ""}`}><span>{item.kind === "command" ? "›_" : "◇"}</span><div><strong>{item.kind === "command" ? "Command" : "Tool"}</strong><p>{item.description || "Working"}</p></div><small>{item.status || "in progress"}{item.exitCode != null ? ` · exit ${item.exitCode}` : ""}</small></article>;
  }
  return (
    <article id={`message-${item.id}`} className={`message ${item.kind} ${highlighted ? "search-highlight" : ""}`}>
      <div className="message-label">{item.kind === "user" ? "You" : "Foreman"}</div>
      {item.kind === "assistant" ? <Markdown text={item.text ?? ""} /> : <LinkedUserText text={item.text ?? ""} />}
      {!!item.images?.length && <div className="message-images">{item.images.map((image, index) => <img key={index} src={`data:${image.mimeType};base64,${image.data}`} alt={`Attachment ${index + 1}`} />)}</div>}
      {!!item.imageCount && !item.images?.length && <span className="image-indicator">▧ {item.imageCount} image{item.imageCount === 1 ? "" : "s"}</span>}
    </article>
  );
}

export function LinkedUserText({ text }: { text: string }) {
  return <p className="user-text">{linkifyPlainText(text).map((segment, index) => segment.href
    ? <a key={`${segment.href}-${index}`} href={segment.href} target="_blank" rel="noreferrer noopener">{segment.text}</a>
    : segment.text)}</p>;
}

function Markdown({ text }: { text: string }) {
  return (
    <div className="markdown">
      {parseAssistantContent(text).map((segment, index) => segment.kind === "directive"
        ? <AppDirectiveCard key={`${segment.directive.name}-${index}`} directive={segment.directive} />
        : <ReactMarkdown
            key={`markdown-${index}`}
            components={{
              a: ({ href, children }) => {
                const safe = safeLink(href);
                return safe ? <a href={safe} target="_blank" rel="noreferrer noopener">{children}</a> : <span>{children}</span>;
              },
            }}
          >{segment.text}</ReactMarkdown>)}
    </div>
  );
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

function NewSessionDialog({ repositories, onClose, onCreate }: { repositories: RepositoryInfo[]; onClose: () => void; onCreate: (id: string) => Promise<void> }) {
  const [selected, setSelected] = useState("");
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><form className="modal" onSubmit={(event) => { event.preventDefault(); if (selected) void onCreate(selected); }}><div className="modal-heading"><div><span className="eyebrow">Codex</span><h2>New session</h2></div><button type="button" onClick={onClose} aria-label="Close">×</button></div><label>Repository<select value={selected} onChange={(event) => setSelected(event.target.value)} required><option value="">Choose a repository…</option>{repositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.path}{repository.dirty ? " · modified" : ""}</option>)}</select></label>{repositories.length === 0 && <p className="muted">No Git repositories were found under Foreman’s configured root.</p>}<div className="modal-actions"><button type="button" onClick={onClose}>Cancel</button><button className="primary" disabled={!selected}>Create</button></div></form></div>;
}

function SettingsView({
  host,
  hosts,
  appearance,
  hello,
  notificationPreferences,
  notificationState,
  hostNotificationOverride,
  repositoryOptions,
  onAppearance,
  onNotificationPreferences,
  onHostNotificationOverride,
  onNotificationPermission,
  onAdd,
  onSelect,
  onRename,
  onForget,
}: {
  host: StoredHost;
  hosts: StoredHost[];
  appearance: Appearance;
  hello: HelloPayload | null;
  notificationPreferences: NotificationPreferences;
  notificationState: BrowserNotificationState;
  hostNotificationOverride: boolean;
  repositoryOptions: RepositoryFilterOption[];
  onAppearance: (appearance: Appearance) => void;
  onNotificationPreferences: (preferences: NotificationPreferences) => void;
  onHostNotificationOverride: (enabled: boolean) => void;
  onNotificationPermission: () => Promise<void>;
  onAdd: () => void;
  onSelect: (hostId: string) => void;
  onRename: (hostId: string, displayName: string) => void;
  onForget: (hostId: string) => void;
}) {
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
    <section className="settings-card"><h2>Appearance</h2><label>Theme<select value={appearance.theme} onChange={(event) => onAppearance({ ...appearance, theme: event.target.value as Appearance["theme"] })}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select></label><div><span className="field-label">Accent</span><div className="accent-grid">{ACCENTS.map((accent) => <button key={accent} className={`accent-swatch ${appearance.accent === accent ? "selected" : ""}`} data-color={accent} onClick={() => onAppearance({ ...appearance, accent })}><i />{titleCase(accent)}</button>)}</div></div></section>
    <section className="settings-card notification-preferences">
      <h2>Notifications</h2>
      <div className="notification-setting"><div><strong>Browser permission: {notificationState}</strong><p>{notificationStateDescription(notificationState, notificationState === "granted")}</p><p>Alerts are evaluated locally. Foreman must stay open in a tab; browsers cannot run this monitor after the site is fully closed.</p></div><button className="secondary" disabled={permissionUnavailable || notificationState === "granted"} onClick={() => void onNotificationPermission()}>{notificationState === "granted" ? "Allowed" : notificationState === "denied" ? "Blocked" : "Allow"}</button></div>
      <label className="check-row"><input type="checkbox" checked={hostNotificationOverride} onChange={(event) => onHostNotificationOverride(event.target.checked)} /><span><strong>Override for {host.displayName}</strong><small>{hostNotificationOverride ? "This host uses its own local settings." : "This host inherits the global browser defaults."}</small></span></label>
      <div className="notification-toggle-grid">{eventToggles.map(([key, label]) => <label className="check-row single-line" key={key}><input type="checkbox" checked={notificationPreferences[key] as boolean} onChange={(event) => update(key, event.target.checked)} /><span>{label}</span></label>)}</div>
      <label>Long-running threshold (minutes)<input type="number" min="1" max="1440" value={notificationPreferences.longRunningMinutes} disabled={!notificationPreferences.notifyLongRunning} onChange={(event) => update("longRunningMinutes", Math.max(1, Math.min(1440, Number(event.target.value) || 1)))} /></label>
      <label className="check-row single-line"><input type="checkbox" checked={notificationPreferences.quietHoursEnabled} onChange={(event) => update("quietHoursEnabled", event.target.checked)} /><span>Quiet hours</span></label>
      {notificationPreferences.quietHoursEnabled && <div className="quiet-hours"><label>Start<input type="time" value={notificationPreferences.quietStart} onChange={(event) => update("quietStart", event.target.value)} /></label><label>End<input type="time" value={notificationPreferences.quietEnd} onChange={(event) => update("quietEnd", event.target.value)} /></label></div>}
      <label className="check-row"><input type="checkbox" checked={notificationPreferences.criticalBypassQuietHours} onChange={(event) => update("criticalBypassQuietHours", event.target.checked)} /><span><strong>Allow critical alerts during quiet hours</strong><small>Only approval/input and failure alerts bypass quiet hours.</small></span></label>
      <div className="repository-overrides"><h3>Repository and workspace overrides</h3><p className="muted">Each event inherits the settings above until explicitly set to on or off. Identities are canonical workspace paths and stay in this browser.</p>{repositoryOptions.length === 0 && <p className="muted">No known repositories or workspaces yet.</p>}{repositoryOptions.map((repository) => <details key={repository.id}><summary>{repository.label}</summary><small title={repository.id}>{repository.id}</small><div className="override-grid">{overrideKeys.map(([key, label]) => { const value = notificationPreferences.repositoryOverrides[repository.id]?.[key]; return <label key={key}>{label}<select value={value === undefined ? "inherit" : String(value)} onChange={(event) => onNotificationPreferences(setRepositoryOverride(notificationPreferences, repository.id, { [key]: event.target.value === "inherit" ? undefined : event.target.value === "true" }))}><option value="inherit">Inherit</option><option value="true">On</option><option value="false">Off</option></select></label>; })}</div></details>)}</div>
    </section>
    <section className="settings-card"><h2>Active connection</h2><dl><div><dt>Host</dt><dd>{host.host}:{host.webPort}</dd></div><div><dt>Local host ID</dt><dd>{host.id}</dd></div><div><dt>Codex</dt><dd>{hello?.codexConnected ? "Connected" : "Unavailable"}</dd></div><div><dt>Runtime</dt><dd>{hello?.codexRuntime === "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE" ? "Shared Desktop runtime attached" : "Fallback runtime"}</dd></div></dl><p className="muted">Each persistent device token stays in browser-local storage and is never placed in the URL. Browser storage is less protected than Android Keystore.</p></section>
  </main>;
}

function StatusPill({ status }: { status: string }) {
  return <span className={`status-pill ${status}`}>{status === "working" ? "Active" : status === "waiting" ? "Attention" : titleCase(status)}</span>;
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
  if (/fallback/i.test(message)) return "Foreman is using its fallback Codex runtime.";
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
