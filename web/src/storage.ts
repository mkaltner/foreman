export type ThemeMode = "system" | "light" | "dark";
export type AccentColor = "purple" | "blue" | "teal" | "green" | "orange" | "red" | "pink";

export interface StoredHost {
  host: string;
  port: number;
  deviceName: string;
  deviceToken: string;
}

export interface Appearance {
  theme: ThemeMode;
  accent: AccentColor;
}

const HOST_KEY = "foreman.host.v1";
const APPEARANCE_KEY = "foreman.appearance.v1";
const NOTIFICATIONS_KEY = "foreman.notifications.v1";
const DASHBOARD_KEY = "foreman.dashboard.v1";
export const DEFAULT_APPEARANCE: Appearance = { theme: "system", accent: "purple" };
export const ACCENTS: AccentColor[] = [
  "purple",
  "blue",
  "teal",
  "green",
  "orange",
  "red",
  "pink",
];

export function loadHost(storage: Storage = localStorage): StoredHost | null {
  try {
    const parsed = JSON.parse(storage.getItem(HOST_KEY) ?? "null") as Partial<StoredHost> | null;
    if (
      !parsed ||
      typeof parsed.host !== "string" ||
      typeof parsed.port !== "number" ||
      typeof parsed.deviceName !== "string" ||
      typeof parsed.deviceToken !== "string"
    ) {
      return null;
    }
    return parsed as StoredHost;
  } catch {
    return null;
  }
}

export function saveHost(host: StoredHost, storage: Storage = localStorage): void {
  storage.setItem(HOST_KEY, JSON.stringify(host));
}

export function forgetHost(storage: Storage = localStorage): void {
  storage.removeItem(HOST_KEY);
}

export function loadAppearance(storage: Storage = localStorage): Appearance {
  try {
    const parsed = JSON.parse(storage.getItem(APPEARANCE_KEY) ?? "null") as Partial<Appearance> | null;
    return {
      theme:
        parsed?.theme === "light" || parsed?.theme === "dark" || parsed?.theme === "system"
          ? parsed.theme
          : DEFAULT_APPEARANCE.theme,
      accent: ACCENTS.includes(parsed?.accent as AccentColor)
        ? (parsed?.accent as AccentColor)
        : DEFAULT_APPEARANCE.accent,
    };
  } catch {
    return DEFAULT_APPEARANCE;
  }
}

export function saveAppearance(
  appearance: Appearance,
  storage: Storage = localStorage,
): void {
  storage.setItem(APPEARANCE_KEY, JSON.stringify(appearance));
}

export function loadNotificationsEnabled(storage: Storage = localStorage): boolean {
  return storage.getItem(NOTIFICATIONS_KEY) === "true";
}

export function saveNotificationsEnabled(enabled: boolean, storage: Storage = localStorage): void {
  storage.setItem(NOTIFICATIONS_KEY, String(enabled));
}

export interface DashboardPreferences {
  filter: "all" | "active" | "waiting" | "failed" | "recent";
  repository: string;
  dismissedFailures: string[];
}

export function loadDashboardPreferences(storage: Storage = localStorage): DashboardPreferences {
  try {
    const parsed = JSON.parse(storage.getItem(DASHBOARD_KEY) ?? "null") as Partial<DashboardPreferences> | null;
    const filters = ["all", "active", "waiting", "failed", "recent"];
    return {
      filter: filters.includes(parsed?.filter ?? "")
        ? parsed!.filter as DashboardPreferences["filter"]
        : "all",
      repository: typeof parsed?.repository === "string" ? parsed.repository : "",
      dismissedFailures: Array.isArray(parsed?.dismissedFailures)
        ? parsed.dismissedFailures.filter((id): id is string => typeof id === "string").slice(-100)
        : [],
    };
  } catch {
    return { filter: "all", repository: "", dismissedFailures: [] };
  }
}

export function saveDashboardPreferences(
  preferences: DashboardPreferences,
  storage: Storage = localStorage,
): void {
  storage.setItem(DASHBOARD_KEY, JSON.stringify({
    ...preferences,
    dismissedFailures: preferences.dismissedFailures.slice(-100),
  }));
}
