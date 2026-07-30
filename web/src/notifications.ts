export interface TurnNotification {
  hostId: string;
  sessionId: string;
  title: string;
  body: string;
}

export type BrowserNotificationState =
  | "unsupported"
  | "insecure"
  | NotificationPermission;

const OUTCOMES: Record<string, { title: string; detail: string }> = {
  waiting: {
    title: "Foreman needs your attention",
    detail: "This session is waiting for you.",
  },
  completed: {
    title: "Foreman turn completed",
    detail: "This turn finished successfully.",
  },
  failed: {
    title: "Foreman turn failed",
    detail: "This turn failed.",
  },
  interrupted: {
    title: "Foreman turn interrupted",
    detail: "This turn was interrupted.",
  },
  idle: {
    title: "Foreman turn is no longer active",
    detail: "Open the session for details.",
  },
};

export class TurnNotificationMonitor {
  private active = new Set<string>();

  seed(sessions: Array<{ id: string; status: string }>): void {
    for (const session of sessions) {
      if (session.status === "working") this.active.add(session.id);
    }
  }

  observe(hostId: string, sessionId: string, sessionTitle: string, status: string): TurnNotification | null {
    if (status === "working") {
      this.active.add(sessionId);
      return null;
    }
    if (!this.active.has(sessionId)) return null;
    const outcome = OUTCOMES[status];
    if (!outcome) return null;
    this.active.delete(sessionId);
    return {
      hostId,
      sessionId,
      title: outcome.title,
      body: sessionTitle ? `${sessionTitle} — ${outcome.detail}` : outcome.detail,
    };
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

export async function showTurnNotification(notification: TurnNotification): Promise<void> {
  if (browserNotificationState() !== "granted") return;
  const options: NotificationOptions = {
    body: notification.body,
    tag: `foreman-turn-${notification.hostId}-${notification.sessionId}`,
    data: { hostId: notification.hostId, sessionId: notification.sessionId },
  };
  const registration = await navigator.serviceWorker?.getRegistration();
  if (registration) {
    await registration.showNotification(notification.title, options);
    return;
  }
  const displayed = new Notification(notification.title, options);
  displayed.onclick = () => {
    window.focus();
    window.dispatchEvent(new CustomEvent("foreman.notification.open", {
      detail: { hostId: notification.hostId, sessionId: notification.sessionId },
    }));
    displayed.close();
  };
}

export function notificationStateDescription(
  state: BrowserNotificationState,
  enabled: boolean,
): string {
  if (state === "insecure") return "Browser notifications require HTTPS or localhost.";
  if (state === "unsupported") return "This browser does not support notifications.";
  if (state === "denied") return "Notifications are blocked in this browser’s site settings.";
  if (enabled && state === "granted") {
    return "Notify when a turn finishes, fails, or needs attention while this tab is in the background.";
  }
  return "Enable alerts for turn results while Foreman remains open in a background tab.";
}
