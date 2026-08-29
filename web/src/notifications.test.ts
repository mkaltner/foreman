import { afterEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_NOTIFICATION_PREFERENCES,
  effectiveNotificationPreferences,
  isQuietTime,
  setRepositoryOverride,
  shouldNotify,
} from "./notification-preferences";
import {
  browserNotificationState,
  notificationStateDescription,
  showBrowserTestNotification,
  TurnNotificationMonitor,
  type TurnObservation,
} from "./notifications";

const working = (overrides: Partial<TurnObservation> = {}): TurnObservation => ({
  hostId: "home",
  sessionId: "one",
  repositoryId: "/workspace/foreman",
  status: "working",
  turnId: "turn-1",
  activeTurnStartedAt: Date.now(),
  ...overrides,
});

describe("notification preferences", () => {
  it("uses safe defaults for every event toggle", () => {
    expect(DEFAULT_NOTIFICATION_PREFERENCES).toMatchObject({
      notifyApprovals: true,
      notifyFailures: true,
      notifyCompletions: true,
      notifyInterruptions: false,
      notifyLongRunning: false,
      quietHoursEnabled: false,
    });
    expect(shouldNotify("approval", DEFAULT_NOTIFICATION_PREFERENCES, "/repo")).toBe(true);
    expect(shouldNotify("failure", DEFAULT_NOTIFICATION_PREFERENCES, "/repo")).toBe(true);
    expect(shouldNotify("completion", DEFAULT_NOTIFICATION_PREFERENCES, "/repo")).toBe(true);
    expect(shouldNotify("interruption", DEFAULT_NOTIFICATION_PREFERENCES, "/repo")).toBe(false);
    expect(shouldNotify("longRunning", DEFAULT_NOTIFICATION_PREFERENCES, "/repo")).toBe(false);
  });

  it("honors every toggle independently", () => {
    const disabled = {
      ...DEFAULT_NOTIFICATION_PREFERENCES,
      notifyApprovals: false,
      notifyFailures: false,
      notifyCompletions: false,
      notifyInterruptions: true,
      notifyLongRunning: true,
    };
    expect(shouldNotify("approval", disabled, "/repo")).toBe(false);
    expect(shouldNotify("failure", disabled, "/repo")).toBe(false);
    expect(shouldNotify("completion", disabled, "/repo")).toBe(false);
    expect(shouldNotify("interruption", disabled, "/repo")).toBe(true);
    expect(shouldNotify("longRunning", disabled, "/repo")).toBe(true);
  });

  it("supports overnight quiet hours and a critical-only bypass", () => {
    const preferences = {
      ...DEFAULT_NOTIFICATION_PREFERENCES,
      quietHoursEnabled: true,
      quietStart: "22:00",
      quietEnd: "07:00",
      notifyInterruptions: true,
    };
    expect(isQuietTime(preferences, new Date(2026, 6, 30, 23, 0))).toBe(true);
    expect(isQuietTime(preferences, new Date(2026, 6, 31, 6, 59))).toBe(true);
    expect(isQuietTime(preferences, new Date(2026, 6, 31, 7, 0))).toBe(false);
    expect(shouldNotify("failure", preferences, "/repo", new Date(2026, 6, 30, 23, 0))).toBe(false);
    const bypass = { ...preferences, criticalBypassQuietHours: true };
    expect(shouldNotify("approval", bypass, "/repo", new Date(2026, 6, 30, 23, 0))).toBe(true);
    expect(shouldNotify("failure", bypass, "/repo", new Date(2026, 6, 30, 23, 0))).toBe(true);
    expect(shouldNotify("interruption", bypass, "/repo", new Date(2026, 6, 30, 23, 0))).toBe(false);
  });

  it("inherits global values until a canonical repository event is overridden", () => {
    const inherited = effectiveNotificationPreferences(DEFAULT_NOTIFICATION_PREFERENCES, "/repo");
    expect(inherited.notifyCompletions).toBe(true);
    const overridden = setRepositoryOverride(DEFAULT_NOTIFICATION_PREFERENCES, "/repo", {
      notifyCompletions: false,
      notifyInterruptions: true,
    });
    expect(shouldNotify("completion", overridden, "/repo")).toBe(false);
    expect(shouldNotify("interruption", overridden, "/repo")).toBe(true);
    expect(shouldNotify("completion", overridden, "/other")).toBe(true);
  });
});

describe("web turn notification lifecycle", () => {
  afterEach(() => vi.useRealTimers());

  it("emits one long-running alert per actual turn start", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-30T12:00:00Z"));
    const emitted = vi.fn();
    const monitor = new TurnNotificationMonitor();
    monitor.configure({
      ...DEFAULT_NOTIFICATION_PREFERENCES,
      notifyLongRunning: true,
      longRunningMinutes: 5,
    }, emitted);
    monitor.observe(working());
    vi.advanceTimersByTime(5 * 60_000);
    expect(emitted).toHaveBeenCalledTimes(1);
    monitor.observe(working());
    vi.advanceTimersByTime(30 * 60_000);
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it("cancels a pending long-running timer without racing the terminal notification", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-30T12:00:00Z"));
    const emitted = vi.fn();
    const monitor = new TurnNotificationMonitor();
    monitor.configure({ ...DEFAULT_NOTIFICATION_PREFERENCES, notifyLongRunning: true }, emitted);
    monitor.observe(working());
    const completed = monitor.observe(working({ status: "completed" }));
    expect(completed?.event).toBe("completion");
    vi.advanceTimersByTime(60 * 60_000);
    expect(emitted).not.toHaveBeenCalled();
    expect(monitor.observe(working({ status: "completed" }))).toBeNull();
  });

  it("sends an explicit test through the active service worker", async () => {
    const secure = Object.getOwnPropertyDescriptor(window, "isSecureContext");
    const browserNotification = Object.getOwnPropertyDescriptor(window, "Notification");
    const serviceWorker = Object.getOwnPropertyDescriptor(navigator, "serviceWorker");
    const showNotification = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
    Object.defineProperty(window, "Notification", {
      configurable: true,
      value: { permission: "granted" },
    });
    Object.defineProperty(navigator, "serviceWorker", {
      configurable: true,
      value: { getRegistration: vi.fn().mockResolvedValue({ showNotification }) },
    });
    try {
      await showBrowserTestNotification();
      expect(showNotification).toHaveBeenCalledWith(
        "Foreman notifications are working",
        expect.objectContaining({ tag: "foreman-notification-test" }),
      );
    } finally {
      if (secure) Object.defineProperty(window, "isSecureContext", secure);
      else Reflect.deleteProperty(window, "isSecureContext");
      if (browserNotification) Object.defineProperty(window, "Notification", browserNotification);
      else Reflect.deleteProperty(window, "Notification");
      if (serviceWorker) Object.defineProperty(navigator, "serviceWorker", serviceWorker);
      else Reflect.deleteProperty(navigator, "serviceWorker");
    }
  });

  it("deduplicates approval alerts and removes their lifecycle state on resolution", () => {
    const monitor = new TurnNotificationMonitor();
    monitor.configure(DEFAULT_NOTIFICATION_PREFERENCES, () => undefined);
    expect(monitor.observeApproval("home", "one", "approval-1", "/repo")?.event).toBe("approval");
    expect(monitor.observeApproval("home", "one", "approval-1", "/repo")).toBeNull();
    expect(monitor.resolveApproval("home", "approval-1")).toBe("foreman-approval-home-approval-1");
    expect(monitor.observeApproval("home", "one", "approval-1", "/repo")?.event).toBe("approval");
  });

  it("uses privacy-safe text and explains denied browser permission", () => {
    const monitor = new TurnNotificationMonitor();
    monitor.configure(DEFAULT_NOTIFICATION_PREFERENCES, () => undefined);
    monitor.observe(working());
    const notification = monitor.observe(working({ status: "failed" }));
    expect(notification?.body).not.toContain("/workspace");
    expect(notification?.body).not.toContain("foreman");
    expect(notification?.body).not.toContain("command");
    expect(notificationStateDescription("denied", false)).toContain("blocked");
  });

  it("uses the same generic private notification for structured input", () => {
    const monitor = new TurnNotificationMonitor();
    monitor.configure(DEFAULT_NOTIFICATION_PREFERENCES, () => undefined);
    const notification = monitor.observeApproval("home", "one", "inp-secret", "/repo");
    expect(notification).toMatchObject({ title: "Foreman needs your attention", body: "A monitored session needs approval or input." });
    expect(notification?.body).not.toContain("secret");
  });

  it("reports a denied browser permission as blocked", () => {
    const secure = Object.getOwnPropertyDescriptor(window, "isSecureContext");
    const browserNotification = Object.getOwnPropertyDescriptor(window, "Notification");
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
    Object.defineProperty(window, "Notification", {
      configurable: true,
      value: { permission: "denied" },
    });
    expect(browserNotificationState()).toBe("denied");
    if (secure) Object.defineProperty(window, "isSecureContext", secure);
    else Reflect.deleteProperty(window, "isSecureContext");
    if (browserNotification) Object.defineProperty(window, "Notification", browserNotification);
    else Reflect.deleteProperty(window, "Notification");
  });
});
