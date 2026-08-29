import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  shouldNotify,
  type NotificationEvent,
  type NotificationPreferences,
} from "./notification-preferences";

export interface TurnNotification {
  hostId: string;
  sessionId: string;
  title: string;
  body: string;
  tag: string;
  event: NotificationEvent;
  approvalId?: string;
}

export interface TurnObservation {
  hostId: string;
  sessionId: string;
  repositoryId: string;
  status: string;
  turnId?: string | null;
  activeTurnStartedAt?: number | null;
  waitType?: "approval" | "input" | null;
}

export interface TurnNotificationDecision {
  notification: TurnNotification | null;
  clearTag?: string;
}

export type BrowserNotificationState =
  | "unsupported"
  | "insecure"
  | NotificationPermission;

const OUTCOMES: Record<string, { event: NotificationEvent; title: string; detail: string }> = {
  completed: {
    event: "completion",
    title: "Foreman turn completed",
    detail: "A monitored turn finished successfully.",
  },
  idle: {
    event: "completion",
    title: "Foreman turn completed",
    detail: "A monitored turn is no longer active.",
  },
  failed: {
    event: "failure",
    title: "Foreman turn failed",
    detail: "A monitored turn failed. Open Foreman for details.",
  },
  interrupted: {
    event: "interruption",
    title: "Foreman turn interrupted",
    detail: "A monitored turn was interrupted.",
  },
};

interface ActiveTurn {
  observation: TurnObservation;
  turnKey: string;
  startedAt: number | null;
  longRunningNotified: boolean;
  timer: ReturnType<typeof setTimeout> | null;
}

type LongRunningEmitter = (notification: TurnNotification) => void;

export class TurnNotificationMonitor {
  private active = new Map<string, ActiveTurn>();
  private approvals = new Set<string>();
  private attentionTurns = new Set<string>();
  private preferences = DEFAULT_NOTIFICATION_PREFERENCES;
  private emitLongRunning: LongRunningEmitter = () => undefined;

  constructor(
    private readonly now: () => number = () => Date.now(),
    private readonly schedule: (callback: () => void, delay: number) => ReturnType<typeof setTimeout> =
      (callback, delay) => setTimeout(callback, delay),
    private readonly cancelSchedule: (timer: ReturnType<typeof setTimeout>) => void =
      (timer) => clearTimeout(timer),
  ) {}

  configure(
    preferences: NotificationPreferences,
    emitLongRunning: LongRunningEmitter,
  ): void {
    this.preferences = preferences;
    this.emitLongRunning = emitLongRunning;
    for (const active of this.active.values()) this.scheduleLongRunning(active);
  }

  seed(observations: TurnObservation[]): void {
    for (const observation of observations) {
      if (observation.status === "working") this.trackWorking(observation);
    }
  }

  observe(observation: TurnObservation): TurnNotification | null {
    return this.observeDecision(observation).notification;
  }

  observeDecision(observation: TurnObservation): TurnNotificationDecision {
    if (observation.status === "working") {
      this.trackWorking(observation);
      return { notification: null };
    }
    const active = this.active.get(observation.sessionId);
    if (!active) return { notification: null };
    if (observation.status === "waiting") {
      this.clearTimer(active);
      const attentionKey = active.turnKey;
      if (this.attentionTurns.has(attentionKey)) return { notification: null };
      this.attentionTurns.add(attentionKey);
      if (!shouldNotify("approval", this.preferences, observation.repositoryId, new Date(this.now()))) {
        return { notification: null };
      }
      return { notification: attentionNotification(observation, attentionKey) };
    }
    const outcome = OUTCOMES[observation.status];
    if (!outcome) return { notification: null };
    this.clearTimer(active);
    this.active.delete(observation.sessionId);
    this.attentionTurns.delete(active.turnKey);
    const terminalTag = `foreman-turn-${observation.hostId}-${observation.sessionId}`;
    if (!shouldNotify(outcome.event, this.preferences, active.observation.repositoryId, new Date(this.now()))) {
      return { notification: null, clearTag: terminalTag };
    }
    return {
      clearTag: terminalTag,
      notification: {
        hostId: observation.hostId,
        sessionId: observation.sessionId,
        title: outcome.title,
        body: outcome.detail,
        tag: terminalTag,
        event: outcome.event,
      },
    };
  }

  observeApproval(
    hostId: string,
    sessionId: string,
    approvalId: string,
    repositoryId: string,
  ): TurnNotification | null {
    const key = `${hostId}:${approvalId}`;
    if (this.approvals.has(key)) return null;
    this.approvals.add(key);
    const active = this.active.get(sessionId);
    if (active) this.attentionTurns.add(active.turnKey);
    if (!shouldNotify("approval", this.preferences, repositoryId, new Date(this.now()))) return null;
    return {
      hostId,
      sessionId,
      approvalId,
      title: "Foreman needs your attention",
      body: "A monitored session needs approval or input.",
      tag: approvalTag(hostId, approvalId),
      event: "approval",
    };
  }

  resolveApproval(hostId: string, approvalId: string): string {
    this.approvals.delete(`${hostId}:${approvalId}`);
    return approvalTag(hostId, approvalId);
  }

  dispose(): void {
    for (const active of this.active.values()) this.clearTimer(active);
    this.active.clear();
    this.approvals.clear();
    this.attentionTurns.clear();
  }

  private trackWorking(observation: TurnObservation): void {
    const startedAt = timestampMillis(observation.activeTurnStartedAt);
    const turnKey = `${observation.hostId}:${observation.sessionId}:${observation.turnId ?? startedAt ?? "active"}`;
    const previous = this.active.get(observation.sessionId);
    if (previous?.turnKey === turnKey) {
      previous.observation = observation;
      if (previous.startedAt === null && startedAt !== null) previous.startedAt = startedAt;
      this.scheduleLongRunning(previous);
      return;
    }
    if (previous) this.clearTimer(previous);
    const active: ActiveTurn = {
      observation,
      turnKey,
      startedAt,
      longRunningNotified: false,
      timer: null,
    };
    this.active.set(observation.sessionId, active);
    this.scheduleLongRunning(active);
  }

  private scheduleLongRunning(active: ActiveTurn): void {
    this.clearTimer(active);
    if (active.longRunningNotified || active.startedAt === null) return;
    const effective = this.preferences.repositoryOverrides[active.observation.repositoryId];
    const enabled = effective?.notifyLongRunning ?? this.preferences.notifyLongRunning;
    if (!enabled) return;
    const threshold = active.startedAt + this.preferences.longRunningMinutes * 60_000;
    active.timer = this.schedule(() => {
      active.timer = null;
      if (this.active.get(active.observation.sessionId)?.turnKey !== active.turnKey) return;
      active.longRunningNotified = true;
      if (!shouldNotify(
        "longRunning",
        this.preferences,
        active.observation.repositoryId,
        new Date(this.now()),
      )) return;
      this.emitLongRunning({
        hostId: active.observation.hostId,
        sessionId: active.observation.sessionId,
        title: "Foreman turn is still running",
        body: "A monitored turn passed your long-running threshold.",
        tag: `foreman-turn-${active.observation.hostId}-${active.observation.sessionId}`,
        event: "longRunning",
      });
    }, Math.max(0, threshold - this.now()));
  }

  private clearTimer(active: ActiveTurn): void {
    if (active.timer !== null) this.cancelSchedule(active.timer);
    active.timer = null;
  }
}

export function browserNotificationState(): BrowserNotificationState {
  if (!window.isSecureContext) return "insecure";
  if (!("Notification" in window)) return "unsupported";
  return Notification.permission;
}

export async function requestBrowserNotifications(): Promise<boolean> {
  const state = browserNotificationState();
  if (state === "insecure" || state === "unsupported" || state === "denied") return false;
  return state === "granted" || await Notification.requestPermission() === "granted";
}

const displayedNotifications = new Map<string, Notification>();

export type NotificationDeliveryMethod = "page" | "service-worker";

async function displayBrowserNotification(
  title: string,
  options: NotificationOptions,
  onClick: () => void,
): Promise<NotificationDeliveryMethod> {
  const tag = options.tag ?? title;
  try {
    displayedNotifications.get(tag)?.close();
    const displayed = new Notification(title, options);
    displayedNotifications.set(tag, displayed);
    displayed.onclick = () => {
      onClick();
      displayed.close();
      displayedNotifications.delete(tag);
    };
    return "page";
  } catch (pageError) {
    const registration = await navigator.serviceWorker?.getRegistration();
    if (registration && typeof registration.showNotification === "function") {
      await registration.showNotification(title, options);
      return "service-worker";
    }
    if (pageError instanceof Error) throw pageError;
    throw new Error("The browser rejected both notification delivery methods.");
  }
}

export async function showTurnNotification(notification: TurnNotification): Promise<void> {
  if (browserNotificationState() !== "granted") return;
  const options: NotificationOptions = {
    body: notification.body,
    tag: notification.tag,
    data: { hostId: notification.hostId, sessionId: notification.sessionId },
  };
  await displayBrowserNotification(notification.title, options, () => {
    window.focus();
    window.dispatchEvent(new CustomEvent("foreman.notification.open", {
      detail: { hostId: notification.hostId, sessionId: notification.sessionId },
    }));
  });
}

export async function showBrowserTestNotification(): Promise<NotificationDeliveryMethod> {
  if (browserNotificationState() !== "granted") {
    throw new Error("Browser notification permission is not granted.");
  }
  const title = "Foreman notifications are working";
  const tag = "foreman-notification-test";
  const options: NotificationOptions = {
    body: "This test bypasses session-event and background-tab checks.",
    tag,
    requireInteraction: true,
  };
  return displayBrowserNotification(title, options, () => {
    window.focus();
  });
}

export async function clearTurnNotification(tag: string): Promise<void> {
  displayedNotifications.get(tag)?.close();
  displayedNotifications.delete(tag);
  const registration = await navigator.serviceWorker?.getRegistration();
  if (!registration) return;
  const notifications = await registration.getNotifications({ tag });
  notifications.forEach((notification) => notification.close());
}

export function notificationStateDescription(
  state: BrowserNotificationState,
  enabled: boolean,
): string {
  if (state === "insecure") return "Browser notifications require HTTPS or localhost.";
  if (state === "unsupported") return "This browser does not support notifications.";
  if (state === "denied") return "Notifications are blocked in this browser’s site settings.";
  if (enabled && state === "granted") {
    return "Allowed. Alerts work while Foreman remains open, including in a background tab.";
  }
  return "Permission has not been granted. Browsers cannot alert after Foreman is fully closed.";
}

function attentionNotification(observation: TurnObservation, turnKey: string): TurnNotification {
  return {
    hostId: observation.hostId,
    sessionId: observation.sessionId,
    title: "Foreman needs your attention",
    body: "A monitored session needs approval or input.",
    tag: `foreman-attention-${turnKey}`,
    event: "approval",
  };
}

function approvalTag(hostId: string, approvalId: string): string {
  return `foreman-approval-${hostId}-${approvalId}`;
}

function timestampMillis(value: number | null | undefined): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return null;
  return value < 10_000_000_000 ? value * 1000 : value;
}
