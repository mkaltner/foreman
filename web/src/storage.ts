export type ThemeMode = "system" | "light" | "dark";
export type AccentColor = "purple" | "blue" | "teal" | "green" | "orange" | "red" | "pink";
export type StoredHostStatus = "connected" | "reconnecting" | "disconnected";

export interface StoredHost {
  id: string;
  displayName: string;
  host: string;
  tcpPort: number;
  webPort: number;
  deviceToken: string;
  pairedAt: number;
  lastConnectedAt: number | null;
  lastKnownStatus: StoredHostStatus;
  runtimeMode: string | null;
  isDefault: boolean;
}

export interface HostRegistry {
  hosts: StoredHost[];
  activeHostId: string | null;
}

export interface NewStoredHost {
  displayName: string;
  host: string;
  tcpPort?: number;
  webPort: number;
  deviceToken: string;
}

interface LegacyStoredHost {
  host: string;
  port: number;
  deviceName: string;
  deviceToken: string;
}

export interface Appearance {
  theme: ThemeMode;
  accent: AccentColor;
}

const LEGACY_HOST_KEY = "foreman.host.v1";
const HOSTS_KEY = "foreman.hosts.v2";
const APPEARANCE_KEY = "foreman.appearance.v1";
const NOTIFICATIONS_KEY = "foreman.notifications.v1";
const DASHBOARD_KEY = "foreman.dashboard.v1";
const SESSION_ORGANIZATION_KEY = "foreman.session-organization.v1";
const SESSION_SEARCH_KEY = "foreman.session-search.v1";
const HOST_SCOPED_KEYS = [
  APPEARANCE_KEY,
  NOTIFICATIONS_KEY,
  DASHBOARD_KEY,
  SESSION_ORGANIZATION_KEY,
  SESSION_SEARCH_KEY,
];
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

export function loadHostRegistry(storage: Storage = localStorage): HostRegistry {
  const stored = parseRegistry(storage.getItem(HOSTS_KEY));
  if (stored) return normalizeRegistry(stored);

  const legacy = parseLegacyHost(storage.getItem(LEGACY_HOST_KEY));
  if (!legacy) return { hosts: [], activeHostId: null };
  const migrated = createStoredHost({
    displayName: legacy.deviceName || legacy.host,
    host: legacy.host,
    webPort: legacy.port,
    deviceToken: legacy.deviceToken,
  }, true);
  const registry = { hosts: [migrated], activeHostId: migrated.id };
  saveHostRegistry(registry, storage);
  migrateLegacyPreferences(migrated.id, storage);
  storage.removeItem(LEGACY_HOST_KEY);
  return registry;
}

export function saveHostRegistry(registry: HostRegistry, storage: Storage = localStorage): void {
  storage.setItem(HOSTS_KEY, JSON.stringify(normalizeRegistry(registry)));
}

export function createStoredHost(input: NewStoredHost, isDefault = false): StoredHost {
  return {
    id: localHostId(),
    displayName: input.displayName.trim() || input.host.trim(),
    host: input.host.trim(),
    tcpPort: input.tcpPort ?? 8765,
    webPort: input.webPort,
    deviceToken: input.deviceToken,
    pairedAt: Date.now(),
    lastConnectedAt: null,
    lastKnownStatus: "disconnected",
    runtimeMode: null,
    isDefault,
  };
}

export function addStoredHost(registry: HostRegistry, host: StoredHost): HostRegistry {
  const first = registry.hosts.length === 0;
  const added = { ...host, isDefault: first || host.isDefault };
  return normalizeRegistry({
    hosts: [...registry.hosts.filter(({ id }) => id !== added.id), added],
    activeHostId: added.id,
  });
}

export function updateStoredHost(
  registry: HostRegistry,
  hostId: string,
  update: Partial<Omit<StoredHost, "id" | "deviceToken" | "pairedAt">>,
): HostRegistry {
  return normalizeRegistry({
    ...registry,
    hosts: registry.hosts.map((host) => host.id === hostId ? { ...host, ...update } : host),
  });
}

export function selectStoredHost(registry: HostRegistry, hostId: string): HostRegistry {
  return registry.hosts.some(({ id }) => id === hostId)
    ? { ...registry, activeHostId: hostId }
    : registry;
}

export function forgetStoredHost(
  registry: HostRegistry,
  hostId: string,
  storage: Storage = localStorage,
): HostRegistry {
  HOST_SCOPED_KEYS.forEach((key) => storage.removeItem(scopedKey(key, hostId)));
  const remaining = registry.hosts.filter(({ id }) => id !== hostId);
  const nextActive = registry.activeHostId === hostId
    ? remaining.find(({ isDefault }) => isDefault)?.id ?? remaining[0]?.id ?? null
    : registry.activeHostId;
  return normalizeRegistry({ hosts: remaining, activeHostId: nextActive });
}

export function hostIdFromUrl(search = window.location.search): string | null {
  return new URLSearchParams(search).get("host");
}

export function withHostInSearch(search: string, hostId: string | null): string {
  const params = new URLSearchParams(search);
  if (hostId) params.set("host", hostId);
  else params.delete("host");
  const value = params.toString();
  return value ? `?${value}` : "";
}

export function loadAppearance(hostId?: string | null, storage: Storage = localStorage): Appearance {
  try {
    const parsed = JSON.parse(storage.getItem(scopedKey(APPEARANCE_KEY, hostId)) ?? "null") as Partial<Appearance> | null;
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
  hostId?: string | null,
  storage: Storage = localStorage,
): void {
  storage.setItem(scopedKey(APPEARANCE_KEY, hostId), JSON.stringify(appearance));
}

export function loadNotificationsEnabled(hostId?: string | null, storage: Storage = localStorage): boolean {
  return storage.getItem(scopedKey(NOTIFICATIONS_KEY, hostId)) === "true";
}

export function saveNotificationsEnabled(
  enabled: boolean,
  hostId?: string | null,
  storage: Storage = localStorage,
): void {
  storage.setItem(scopedKey(NOTIFICATIONS_KEY, hostId), String(enabled));
}

export interface DashboardPreferences {
  filter: "all" | "active" | "waiting" | "failed" | "recent";
  repository: string;
  dismissedFailures: string[];
}

export function loadDashboardPreferences(hostId?: string | null, storage: Storage = localStorage): DashboardPreferences {
  try {
    const parsed = JSON.parse(storage.getItem(scopedKey(DASHBOARD_KEY, hostId)) ?? "null") as Partial<DashboardPreferences> | null;
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
  hostId?: string | null,
  storage: Storage = localStorage,
): void {
  storage.setItem(scopedKey(DASHBOARD_KEY, hostId), JSON.stringify({
    ...preferences,
    dismissedFailures: preferences.dismissedFailures.slice(-100),
  }));
}

export interface SessionOrganization {
  pinnedIds: string[];
  hiddenIds: string[];
}

export function loadSessionOrganization(hostId?: string | null, storage: Storage = localStorage): SessionOrganization {
  try {
    const parsed = JSON.parse(storage.getItem(scopedKey(SESSION_ORGANIZATION_KEY, hostId)) ?? "null") as Partial<SessionOrganization> | null;
    return {
      pinnedIds: stringIds(parsed?.pinnedIds),
      hiddenIds: stringIds(parsed?.hiddenIds),
    };
  } catch {
    return { pinnedIds: [], hiddenIds: [] };
  }
}

export function saveSessionOrganization(
  organization: SessionOrganization,
  hostId?: string | null,
  storage: Storage = localStorage,
): void {
  storage.setItem(scopedKey(SESSION_ORGANIZATION_KEY, hostId), JSON.stringify({
    pinnedIds: [...new Set(organization.pinnedIds)].slice(-1000),
    hiddenIds: [...new Set(organization.hiddenIds)].slice(-1000),
  }));
}

export function loadSessionSearch(hostId: string, storage: Storage = localStorage): string {
  return storage.getItem(scopedKey(SESSION_SEARCH_KEY, hostId)) ?? "";
}

export function saveSessionSearch(hostId: string, search: string, storage: Storage = localStorage): void {
  storage.setItem(scopedKey(SESSION_SEARCH_KEY, hostId), search.slice(0, 4000));
}

function parseRegistry(raw: string | null): HostRegistry | null {
  try {
    const parsed = JSON.parse(raw ?? "null") as Partial<HostRegistry> | null;
    if (!parsed || !Array.isArray(parsed.hosts)) return null;
    const hosts = parsed.hosts.filter(isStoredHost);
    return { hosts, activeHostId: typeof parsed.activeHostId === "string" ? parsed.activeHostId : null };
  } catch {
    return null;
  }
}

function parseLegacyHost(raw: string | null): LegacyStoredHost | null {
  try {
    const parsed = JSON.parse(raw ?? "null") as Partial<LegacyStoredHost> | null;
    return parsed && typeof parsed.host === "string" && typeof parsed.port === "number" &&
      typeof parsed.deviceToken === "string"
      ? { ...parsed, deviceName: typeof parsed.deviceName === "string" ? parsed.deviceName : parsed.host } as LegacyStoredHost
      : null;
  } catch {
    return null;
  }
}

function isStoredHost(value: unknown): value is StoredHost {
  const host = value as Partial<StoredHost> | null;
  return !!host && typeof host.id === "string" && typeof host.displayName === "string" &&
    typeof host.host === "string" && validPort(host.tcpPort) && validPort(host.webPort) &&
    typeof host.deviceToken === "string" && typeof host.pairedAt === "number" &&
    (host.lastConnectedAt === null || typeof host.lastConnectedAt === "number") &&
    ["connected", "reconnecting", "disconnected"].includes(host.lastKnownStatus ?? "") &&
    (host.runtimeMode === null || typeof host.runtimeMode === "string") && typeof host.isDefault === "boolean";
}

function normalizeRegistry(registry: HostRegistry): HostRegistry {
  const hosts = registry.hosts.filter(isStoredHost);
  const defaultId = hosts.find(({ isDefault }) => isDefault)?.id ?? hosts[0]?.id;
  const normalized = hosts.map((host) => ({ ...host, isDefault: host.id === defaultId }));
  const activeHostId = normalized.some(({ id }) => id === registry.activeHostId)
    ? registry.activeHostId
    : defaultId ?? null;
  return { hosts: normalized, activeHostId };
}

function migrateLegacyPreferences(hostId: string, storage: Storage): void {
  HOST_SCOPED_KEYS.forEach((key) => {
    const legacy = storage.getItem(key);
    const scoped = scopedKey(key, hostId);
    if (legacy !== null && storage.getItem(scoped) === null) storage.setItem(scoped, legacy);
  });
}

function scopedKey(key: string, hostId?: string | null): string {
  return hostId ? `${key}.${hostId}` : key;
}

function localHostId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `host-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function validPort(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 65535;
}

function stringIds(value: unknown): string[] {
  return Array.isArray(value)
    ? [...new Set(value.filter((id): id is string => typeof id === "string" && id.length <= 100))].slice(-1000)
    : [];
}
