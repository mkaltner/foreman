export type NotificationEvent =
  | "approval"
  | "failure"
  | "completion"
  | "interruption"
  | "longRunning";

export interface RepositoryNotificationOverride {
  notifyApprovals?: boolean;
  notifyFailures?: boolean;
  notifyCompletions?: boolean;
  notifyInterruptions?: boolean;
  notifyLongRunning?: boolean;
}

export interface NotificationPreferences {
  notifyApprovals: boolean;
  notifyFailures: boolean;
  notifyCompletions: boolean;
  notifyInterruptions: boolean;
  notifyLongRunning: boolean;
  longRunningMinutes: number;
  quietHoursEnabled: boolean;
  quietStart: string;
  quietEnd: string;
  criticalBypassQuietHours: boolean;
  repositoryOverrides: Record<string, RepositoryNotificationOverride>;
}

export const DEFAULT_NOTIFICATION_PREFERENCES: NotificationPreferences = {
  notifyApprovals: true,
  notifyFailures: true,
  notifyCompletions: true,
  notifyInterruptions: false,
  notifyLongRunning: false,
  longRunningMinutes: 15,
  quietHoursEnabled: false,
  quietStart: "22:00",
  quietEnd: "07:00",
  criticalBypassQuietHours: false,
  repositoryOverrides: {},
};

const EVENT_KEYS: Record<NotificationEvent, keyof RepositoryNotificationOverride> = {
  approval: "notifyApprovals",
  failure: "notifyFailures",
  completion: "notifyCompletions",
  interruption: "notifyInterruptions",
  longRunning: "notifyLongRunning",
};

export function normalizeNotificationPreferences(value: unknown): NotificationPreferences {
  const candidate = value && typeof value === "object"
    ? value as Partial<NotificationPreferences>
    : {};
  const repositories = candidate.repositoryOverrides && typeof candidate.repositoryOverrides === "object"
    ? Object.fromEntries(Object.entries(candidate.repositoryOverrides).flatMap(([identity, override]) => {
      if (!identity || !override || typeof override !== "object") return [];
      const normalized = normalizeOverride(override as RepositoryNotificationOverride);
      return Object.keys(normalized).length ? [[identity, normalized]] : [];
    }).slice(-250))
    : {};
  return {
    notifyApprovals: booleanOr(candidate.notifyApprovals, true),
    notifyFailures: booleanOr(candidate.notifyFailures, true),
    notifyCompletions: booleanOr(candidate.notifyCompletions, true),
    notifyInterruptions: booleanOr(candidate.notifyInterruptions, false),
    notifyLongRunning: booleanOr(candidate.notifyLongRunning, false),
    longRunningMinutes: integerBetween(candidate.longRunningMinutes, 1, 1440, 15),
    quietHoursEnabled: booleanOr(candidate.quietHoursEnabled, false),
    quietStart: validTime(candidate.quietStart) ? candidate.quietStart : "22:00",
    quietEnd: validTime(candidate.quietEnd) ? candidate.quietEnd : "07:00",
    criticalBypassQuietHours: booleanOr(candidate.criticalBypassQuietHours, false),
    repositoryOverrides: repositories,
  };
}

export function effectiveNotificationPreferences(
  preferences: NotificationPreferences,
  repositoryIdentity: string,
): NotificationPreferences {
  const override = preferences.repositoryOverrides[repositoryIdentity] ?? {};
  return { ...preferences, ...override };
}

export function shouldNotify(
  event: NotificationEvent,
  preferences: NotificationPreferences,
  repositoryIdentity: string,
  at = new Date(),
): boolean {
  const effective = effectiveNotificationPreferences(preferences, repositoryIdentity);
  if (!effective[EVENT_KEYS[event]]) return false;
  if (!isQuietTime(effective, at)) return true;
  return effective.criticalBypassQuietHours && (event === "approval" || event === "failure");
}

export function isQuietTime(preferences: NotificationPreferences, at = new Date()): boolean {
  if (!preferences.quietHoursEnabled) return false;
  const start = timeMinutes(preferences.quietStart);
  const end = timeMinutes(preferences.quietEnd);
  if (start === null || end === null || start === end) return false;
  const current = at.getHours() * 60 + at.getMinutes();
  return start < end
    ? current >= start && current < end
    : current >= start || current < end;
}

export function setRepositoryOverride(
  preferences: NotificationPreferences,
  repositoryIdentity: string,
  update: RepositoryNotificationOverride,
): NotificationPreferences {
  const override = normalizeOverride({
    ...preferences.repositoryOverrides[repositoryIdentity],
    ...update,
  });
  const repositoryOverrides = { ...preferences.repositoryOverrides };
  if (Object.keys(override).length) repositoryOverrides[repositoryIdentity] = override;
  else delete repositoryOverrides[repositoryIdentity];
  return { ...preferences, repositoryOverrides };
}

function normalizeOverride(value: RepositoryNotificationOverride): RepositoryNotificationOverride {
  return Object.fromEntries(Object.entries(value).filter(([key, enabled]) =>
    Object.values(EVENT_KEYS).includes(key as keyof RepositoryNotificationOverride) && typeof enabled === "boolean"
  )) as RepositoryNotificationOverride;
}

function booleanOr(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function integerBetween(value: unknown, minimum: number, maximum: number, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value)
    ? Math.min(maximum, Math.max(minimum, value))
    : fallback;
}

function validTime(value: unknown): value is string {
  return typeof value === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
}

function timeMinutes(value: string): number | null {
  if (!validTime(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  return hours * 60 + minutes;
}
