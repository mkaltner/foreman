import {
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
import { Dashboard } from "./Dashboard";
import {
  ForemanWebClient,
  inferPagePort,
  parseEndpoint,
  type ConnectionState,
} from "./client";
import { clipboardImageFiles, processImages, type ProcessedImage } from "./images";
import {
  browserNotificationState,
  notificationStateDescription,
  requestBrowserNotifications,
  showTurnNotification,
  TurnNotificationMonitor,
  type BrowserNotificationState,
} from "./notifications";
import {
  applySessionEvent,
  applySessionSummaryEventBatch,
  applySessionSummaryEvent,
  groupSessions,
  liveActivityLabel,
  liveActivityMessage,
  routeForSession,
  reconcileSessionSummaries,
  type AccessLevelInfo,
  type HelloPayload,
  type ModelInfo,
  type RepositoryInfo,
  type ServiceStatus,
  type SessionEvent,
  type SessionEventPayload,
  type SessionSummary,
  type WireMessage,
} from "./protocol";
import {
  ACCENTS,
  forgetHost,
  loadAppearance,
  loadHost,
  loadNotificationsEnabled,
  saveAppearance,
  saveHost,
  saveNotificationsEnabled,
  type Appearance,
  type StoredHost,
} from "./storage";
import { applyAppearance } from "./theme";
import {
  confirmSessionAction,
  createSubmissionGuard,
  formatActivity,
  isNearBottom,
  parseAssistantContent,
  parseWebRoute,
  reasoningDescription,
  reasoningLabel,
  webRoutePath,
  type AppDirective,
  type WebRoute,
} from "./ui";

type View = "dashboard" | "sessions" | "detail" | "settings";

function App() {
  const initialRoute = useRef(parseWebRoute(window.location.pathname)).current;
  const [storedHost, setStoredHost] = useState<StoredHost | null>(() => loadHost());
  const [appearance, setAppearance] = useState<Appearance>(() => loadAppearance());
  const [connection, setConnection] = useState<ConnectionState>("disconnected");
  const [connectionDetail, setConnectionDetail] = useState("");
  const [hello, setHello] = useState<HelloPayload | null>(null);
  const [serviceStatus, setServiceStatus] = useState<ServiceStatus | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [current, setCurrent] = useState<SessionSummary | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const selectedIdRef = useRef<string | null>(
    initialRoute.view === "detail" ? initialRoute.sessionId : null,
  );
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [accessLevels, setAccessLevels] = useState<AccessLevelInfo[]>([]);
  const [repositories, setRepositories] = useState<RepositoryInfo[]>([]);
  const [view, setView] = useState<View>(initialRoute.view);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [newSessionOpen, setNewSessionOpen] = useState(false);
  const [notificationsEnabled, setNotificationsEnabled] = useState(loadNotificationsEnabled);
  const notificationsEnabledRef = useRef(notificationsEnabled);
  const sessionsRef = useRef<SessionSummary[]>([]);
  const currentRef = useRef<SessionSummary | null>(null);
  const connectedRef = useRef(false);
  const viewRef = useRef<View>(initialRoute.view);
  const clientRef = useRef<ForemanWebClient | null>(null);
  const dashboardSubscriptions = useRef(new Set<string>());
  const pendingDashboardEvents = useRef(new Map<string, SessionEvent[]>());
  const dashboardFrame = useRef<number | null>(null);
  const notificationMonitor = useRef(new TurnNotificationMonitor());
  const openSessionRef = useRef<(id: string, updateHistory?: boolean) => void>(() => undefined);

  const updateRoute = useCallback((route: WebRoute, replace = false) => {
    const path = webRoutePath(route);
    if (window.location.pathname === path) return;
    window.history[replace ? "replaceState" : "pushState"](null, "", path);
  }, []);

  useEffect(() => applyAppearance(appearance), [appearance]);
  useEffect(() => { notificationsEnabledRef.current = notificationsEnabled; }, [notificationsEnabled]);
  useEffect(() => { sessionsRef.current = sessions; }, [sessions]);
  useEffect(() => { currentRef.current = current; }, [current]);
  useEffect(() => { connectedRef.current = connection === "connected"; }, [connection]);
  useEffect(() => { viewRef.current = view; }, [view]);
  useEffect(() => { updateRoute(initialRoute, true); }, [initialRoute, updateRoute]);

  useEffect(() => {
    if (window.isSecureContext && "serviceWorker" in navigator) {
      void navigator.serviceWorker.register("/sw.js").catch(() => undefined);
    }
    const openFromNotification = (event: Event) => {
      const sessionId = (event as CustomEvent<{ sessionId?: string }>).detail?.sessionId;
      if (sessionId) openSessionRef.current(sessionId);
    };
    const serviceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === "notification.open" && typeof event.data.sessionId === "string") {
        openSessionRef.current(event.data.sessionId);
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
    if (message.type !== "session.event") return;
    const payload = message.payload as unknown as SessionEventPayload;
    if (!payload.sessionId || !payload.event) return;
    if (payload.event.kind === "lifecycle") {
      if (payload.event.action === "removed") {
        setSessions((previous) => {
          const next = previous.filter((session) => session.id !== payload.sessionId);
          sessionsRef.current = next;
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
      }
      return;
    }
    if (payload.event.kind === "status" && payload.event.status) {
      const title = sessionsRef.current.find(({ id }) => id === payload.sessionId)?.title ?? "Foreman session";
      const notification = notificationMonitor.current.observe(
        payload.sessionId,
        title,
        payload.event.status,
      );
      if (
        notification &&
        notificationsEnabledRef.current &&
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

  const client = useMemo(
    () =>
      new ForemanWebClient({
        onEvent,
        onState: (state, detail) => {
          setConnection(state);
          setConnectionDetail(detail ?? "");
        },
        onHello: setHello,
      }),
    [onEvent],
  );
  clientRef.current = client;

  const refreshState = useCallback(
    async (reconnected = false) => {
      const [sessionResult, modelResult, accessResult, statusResult] = await Promise.all([
        client.request<{ sessions: SessionSummary[] } & Record<string, unknown>>("session.list"),
        client.request<{ models: ModelInfo[] } & Record<string, unknown>>("model.list"),
        client.request<{ levels: AccessLevelInfo[] } & Record<string, unknown>>("access.list"),
        client.request<ServiceStatus & Record<string, unknown>>("service.status"),
      ]);
      const reconciled = reconcileSessionSummaries(sessionsRef.current, sessionResult.sessions);
      sessionsRef.current = reconciled;
      notificationMonitor.current.seed(reconciled);
      setSessions(reconciled);
      setModels(modelResult.models.filter((model) => model.visible));
      setAccessLevels(accessResult.levels);
      setServiceStatus(statusResult);
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
        const endpoint = parseEndpoint(host.host, host.port);
        await client.start(endpoint, host.deviceToken, refreshState);
      } catch (caught) {
        const message = caught instanceof Error ? caught.message : "Cannot connect to Foreman";
        setError(message);
        if (/token|authenticate|unauthorized|incompatible/i.test(message)) client.disconnect();
      }
    },
    [client, refreshState],
  );

  useEffect(() => {
    if (storedHost) void connectHost(storedHost);
    return () => client.disconnect();
  }, [client, connectHost, storedHost]);

  const openSession = useCallback(
    async (id: string, updateHistory = true) => {
      setError("");
      setBusy(true);
      selectedIdRef.current = id;
      setSelectedId(id);
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

  useEffect(() => {
    const restoreRoute = () => {
      const route = parseWebRoute(window.location.pathname);
      viewRef.current = route.view;
      if (route.view === "dashboard") {
        closeSelectedSession();
        setView("dashboard");
        return;
      }
      if (route.view === "settings") {
        closeSelectedSession();
        setView("settings");
        return;
      }
      if (route.view === "sessions") {
        closeSelectedSession();
        setView("sessions");
        return;
      }
      selectedIdRef.current = route.sessionId;
      setSelectedId(route.sessionId);
      setView("detail");
      if (currentRef.current?.id !== route.sessionId && connectedRef.current) {
        openSessionRef.current(route.sessionId, false);
      }
    };
    window.addEventListener("popstate", restoreRoute);
    return () => window.removeEventListener("popstate", restoreRoute);
  }, [closeSelectedSession]);

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
    saveAppearance(next);
  };

  const forget = () => {
    client.disconnect();
    forgetHost();
    setStoredHost(null);
    setSessions([]);
    setCurrent(null);
    selectedIdRef.current = null;
    setSelectedId(null);
    setHello(null);
    setServiceStatus(null);
    dashboardSubscriptions.current.clear();
    setError("");
    showDashboard(true);
  };

  const dashboardOpen = useCallback((id: string) => {
    void openSession(id);
  }, [openSession]);
  const dashboardInterrupt = useCallback((session: SessionSummary) => {
    if (!session.activeTurnId) return;
    void client.request("turn.interrupt", {
      sessionId: session.id,
      turnId: session.activeTurnId,
    }).catch((caught) => setError(caught instanceof Error ? caught.message : "Interrupt failed"));
  }, [client]);
  const dashboardRefresh = useCallback(() => {
    void refreshState().catch((caught) => setError(String(caught)));
  }, [refreshState]);

  if (!storedHost) {
    return (
      <SetupView
        error={error}
        busy={busy}
        onConnect={async (settings, pairingKey) => {
          setBusy(true);
          setError("");
          try {
            const endpoint = parseEndpoint(settings.host, settings.port);
            const token = await client.pair(endpoint, pairingKey, settings.deviceName);
            const saved = { ...settings, deviceToken: token };
            saveHost(saved);
            setStoredHost(saved);
          } catch (caught) {
            setError(setupError(caught));
          } finally {
            setBusy(false);
          }
        }}
      />
    );
  }

  const connected = connection === "connected";
  return (
    <div className="app-shell">
      <header className="topbar">
        <button className="brand" onClick={() => showDashboard()} aria-label="Dashboard">
          <span className="brand-mark">F</span>
          <span>Foreman</span>
        </button>
        <ConnectionBadge state={connection} detail={connectionDetail} />
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
          host={storedHost}
          appearance={appearance}
          hello={hello}
          onAppearance={updateAppearance}
          notificationsEnabled={notificationsEnabled}
          notificationState={browserNotificationState()}
          onNotifications={async (enabled) => {
            if (enabled && !await requestBrowserNotifications()) {
              setError(notificationStateDescription(browserNotificationState(), false));
              return;
            }
            saveNotificationsEnabled(enabled);
            setNotificationsEnabled(enabled);
          }}
          onForget={forget}
        />
      ) : view === "dashboard" ? (
        <Dashboard
          sessions={sessions}
          serviceStatus={serviceStatus}
          connection={connection}
          disabled={!connected}
          onOpen={dashboardOpen}
          onInterrupt={dashboardInterrupt}
          onRefresh={dashboardRefresh}
        />
      ) : (
        <main className={`workspace ${view === "detail" ? "show-detail" : "show-list"}`}>
          <SessionList
            sessions={sessions}
            selectedId={selectedId}
            disabled={!connected}
            onOpen={(id) => void openSession(id)}
            onRefresh={() => void refreshState().catch((caught) => setError(String(caught)))}
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
            onAction={async (action, session) => {
              if (!confirmSessionAction(action, session.title)) return;
              try {
                await client.request(`session.${action}`, {
                  sessionId: session.id,
                  ...(action === "delete" ? { confirm: true } : {}),
                });
                setSessions((previous) => previous.filter((item) => item.id !== session.id));
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
                session={current}
                models={models}
                accessLevels={accessLevels}
                connected={connected}
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
    </div>
  );
}

export function SetupView({
  error,
  busy,
  onConnect,
}: {
  error: string;
  busy: boolean;
  onConnect: (settings: Omit<StoredHost, "deviceToken">, pairingKey: string) => Promise<void>;
}) {
  const [host, setHost] = useState(window.location.hostname || "");
  const [pairingKey, setPairingKey] = useState("");
  const [deviceName, setDeviceName] = useState("Web browser");
  return (
    <main className="setup-page">
      <section className="setup-card">
        <div className="setup-heading">
          <span className="brand-mark large">F</span>
          <div><h1>Connect to Foreman</h1><p>Your local Codex companion.</p></div>
        </div>
        {error && <div className="form-error" role="alert">{error}</div>}
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void onConnect(
              { host: host.trim(), port: inferPagePort(), deviceName: deviceName.trim() },
              pairingKey,
            );
          }}
        >
          <label>Host<input value={host} onChange={(event) => setHost(event.target.value)} placeholder="192.168.1.59" autoComplete="url" required /></label>
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

function SessionList({
  sessions,
  selectedId,
  disabled,
  onOpen,
  onRefresh,
  onNew,
  onAction,
}: {
  sessions: SessionSummary[];
  selectedId: string | null;
  disabled: boolean;
  onOpen: (id: string) => void;
  onRefresh: () => void;
  onNew: () => void;
  onAction: (action: "archive" | "delete", session: SessionSummary) => void;
}) {
  const groups = groupSessions(sessions);
  return (
    <aside className="session-pane">
      <div className="pane-heading">
        <div><span className="eyebrow">Workspace</span><h1>Sessions</h1></div>
        <div className="heading-actions">
          <button className="icon-button" onClick={onRefresh} disabled={disabled} aria-label="Refresh sessions">↻</button>
          <button className="primary" onClick={onNew} disabled={disabled}>New</button>
        </div>
      </div>
      <div className="session-scroll">
        {sessions.length === 0 && <div className="empty-list"><h3>No sessions yet</h3><p>Start one from a repository.</p></div>}
        {(["waiting", "active", "recent"] as const).map((group) =>
          groups[group].length ? (
            <section className="session-group" key={group}>
              <h2>{group === "waiting" ? "Needs attention" : group === "active" ? "Active" : "Recent"}</h2>
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
                      <button onClick={(event) => { event.stopPropagation(); onAction("archive", session); }} disabled={session.status === "working" || session.status === "waiting"}>Archive</button>
                      <button className="danger-link" onClick={(event) => { event.stopPropagation(); onAction("delete", session); }} disabled={session.status === "working" || session.status === "waiting"}>Delete</button>
                    </span>
                  </div>
                </article>
              ))}
            </section>
          ) : null,
        )}
      </div>
    </aside>
  );
}

function ConversationView({
  session,
  models,
  accessLevels,
  connected,
  onBack,
  onRequest,
  onError,
}: {
  session: SessionSummary;
  models: ModelInfo[];
  accessLevels: AccessLevelInfo[];
  connected: boolean;
  onBack: () => void;
  onRequest: <T extends Record<string, unknown>>(type: string, payload?: Record<string, unknown>) => Promise<T>;
  onError: (message: string) => void;
}) {
  const initialRoute = useMemo(() => routeForSession(session, models, accessLevels), [session, models, accessLevels]);
  const [model, setModel] = useState(initialRoute.model);
  const [effort, setEffort] = useState(initialRoute.reasoningEffort);
  const [access, setAccess] = useState(initialRoute.accessLevel);
  const [text, setText] = useState("");
  const [images, setImages] = useState<ProcessedImage[]>([]);
  const [submitting, setSubmitting] = useState(false);
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
    following.current = true;
    setJumpVisible(false);
    requestAnimationFrame(() => transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight }));
  }, [session.id]);

  const transcriptKey = `${session.messages?.length ?? 0}:${session.messages?.at(-1)?.text?.length ?? 0}:${session.activityText?.length ?? 0}`;
  useEffect(() => {
    if (following.current) {
      const frame = requestAnimationFrame(() => transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight }));
      return () => cancelAnimationFrame(frame);
    }
    setJumpVisible(true);
  }, [transcriptKey]);

  const selectedModel = models.find((entry) => entry.id === model);
  const active = session.status === "working" && !!session.activeTurnId;
  const canSubmit = connected && !submitting && !processing && (!!text.trim() || images.length > 0);
  const activityLabel = liveActivityLabel(session);
  const activityMessage = liveActivityMessage(session);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit || !submissionGuard.current.enter()) return;
    setSubmitting(true);
    try {
      const base = {
        sessionId: session.id,
        text,
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
      setText("");
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
        {!session.messages?.length && <div className="empty-conversation"><h2>Ready when you are</h2><p>Choose a route below and send the first prompt.</p></div>}
        {session.messages?.map((item) => <ConversationItemView key={item.id} item={item} />)}
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
          <RouteSelect label="Access" value={access} options={accessLevels.map((level) => ({ value: level.id, label: level.displayName, description: level.description, warning: level.id === "full" }))} disabled={active || submitting} onChange={setAccess} />
          <RouteSelect label="Model" value={model} options={models.map((entry) => ({ value: entry.id, label: entry.displayName, description: entry.description }))} disabled={active || submitting} onChange={(value) => { const next = models.find((entry) => entry.id === value); setModel(value); setEffort(next?.defaultReasoningEffort ?? next?.reasoningEfforts[0] ?? ""); }} />
          <RouteSelect label="Reasoning" value={effort} options={selectedModel?.reasoningEfforts.map((entry) => ({ value: entry, label: reasoningLabel(entry), description: reasoningDescription(entry) })) ?? []} disabled={active || submitting} onChange={setEffort} />
        </div>
        {images.length > 0 && <div className="attachment-row">{images.map((image, index) => <figure key={`${image.name}-${index}`}><img src={image.previewUrl} alt={image.name} /><button type="button" onClick={() => setImages((previous) => previous.filter((_, itemIndex) => itemIndex !== index))} aria-label={`Remove ${image.name}`}>×</button></figure>)}</div>}
        <div className="entry-row">
          <label className="attach-button" title="Attach images">+<input type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(event) => void addFiles(event)} disabled={processing || submitting || images.length >= 4} /></label>
          <textarea value={text} onChange={(event) => setText(event.target.value)} onPaste={pasteImages} placeholder={active ? "Steer the active turn…" : "Message Codex…"} rows={1} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} />
          {(session.status === "working" || session.status === "waiting") && session.activeTurnId && <button type="button" className="interrupt" disabled={!connected || submitting} onClick={() => void onRequest("turn.interrupt", { sessionId: session.id, turnId: session.activeTurnId }).catch((caught) => onError(String(caught)))}>Stop</button>}
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

function ConversationItemView({ item }: { item: NonNullable<SessionSummary["messages"]>[number] }) {
  if (item.kind === "command" || item.kind === "tool") {
    return <article className="tool-card"><span>{item.kind === "command" ? "›_" : "◇"}</span><div><strong>{item.kind === "command" ? "Command" : "Tool"}</strong><p>{item.description || "Working"}</p></div><small>{item.status || "in progress"}{item.exitCode != null ? ` · exit ${item.exitCode}` : ""}</small></article>;
  }
  return (
    <article className={`message ${item.kind}`}>
      <div className="message-label">{item.kind === "user" ? "You" : "Foreman"}</div>
      {item.kind === "assistant" ? <Markdown text={item.text ?? ""} /> : <p className="user-text">{item.text}</p>}
      {!!item.images?.length && <div className="message-images">{item.images.map((image, index) => <img key={index} src={`data:${image.mimeType};base64,${image.data}`} alt={`Attachment ${index + 1}`} />)}</div>}
      {!!item.imageCount && !item.images?.length && <span className="image-indicator">▧ {item.imageCount} image{item.imageCount === 1 ? "" : "s"}</span>}
    </article>
  );
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

function SettingsView({ host, appearance, hello, notificationsEnabled, notificationState, onAppearance, onNotifications, onForget }: { host: StoredHost; appearance: Appearance; hello: HelloPayload | null; notificationsEnabled: boolean; notificationState: BrowserNotificationState; onAppearance: (appearance: Appearance) => void; onNotifications: (enabled: boolean) => Promise<void>; onForget: () => void }) {
  const notificationUnavailable = ["insecure", "unsupported", "denied"].includes(notificationState);
  const notificationsActive = notificationsEnabled && notificationState === "granted";
  return <main className="settings-page"><header><span className="eyebrow">Preferences</span><h1>Settings</h1></header><section className="settings-card"><h2>Appearance</h2><label>Theme<select value={appearance.theme} onChange={(event) => onAppearance({ ...appearance, theme: event.target.value as Appearance["theme"] })}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select></label><div><span className="field-label">Accent</span><div className="accent-grid">{ACCENTS.map((accent) => <button key={accent} className={`accent-swatch ${appearance.accent === accent ? "selected" : ""}`} data-color={accent} onClick={() => onAppearance({ ...appearance, accent })}><i />{titleCase(accent)}</button>)}</div></div></section><section className="settings-card"><h2>Notifications</h2><div className="notification-setting"><div><strong>Turn results</strong><p>{notificationStateDescription(notificationState, notificationsActive)}</p></div><button className="secondary" disabled={notificationUnavailable} onClick={() => void onNotifications(!notificationsActive)}>{notificationsActive ? "Disable" : notificationState === "denied" ? "Blocked" : "Enable"}</button></div></section><section className="settings-card"><h2>Connection</h2><dl><div><dt>Host</dt><dd>{host.host}:{host.port}</dd></div><div><dt>Device</dt><dd>{host.deviceName}</dd></div><div><dt>Codex</dt><dd>{hello?.codexConnected ? "Connected" : "Unavailable"}</dd></div><div><dt>Runtime</dt><dd>{hello?.codexRuntime === "SHARED_DESKTOP_LIVE_STATUS_AVAILABLE" ? "Shared Desktop runtime attached" : "Fallback runtime"}</dd></div></dl><p className="muted">The persistent device token is stored in localStorage. Browser storage is less protected than Android Keystore.</p><button className="danger" onClick={() => { if (window.confirm("Disconnect and forget this Foreman host?")) onForget(); }}>Disconnect and forget host</button></section></main>;
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
