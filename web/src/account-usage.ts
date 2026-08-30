import type { AccountUsage, ProviderAccountUsage, ProviderId, RateLimitSnapshot, RateLimitWindow } from "./protocol";

const MAX_WINDOWS = 16;
const MAX_DURATION_MINS = 525_600;
const MAX_TIMESTAMP = 253_402_300_799;
const MAX_TEXT = 100;

function text(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  return value.trim().slice(0, MAX_TEXT) || undefined;
}

function timestamp(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? Math.min(Math.trunc(value), MAX_TIMESTAMP)
    : undefined;
}

function normalizeWindow(value: unknown, fallbackId: string): RateLimitWindow | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  if (typeof raw.usedPercent !== "number" || !Number.isFinite(raw.usedPercent)) return null;
  const duration = typeof raw.windowDurationMins === "number" && Number.isFinite(raw.windowDurationMins) && raw.windowDurationMins > 0
    ? Math.min(Math.trunc(raw.windowDurationMins), MAX_DURATION_MINS)
    : undefined;
  return {
    id: text(raw.id) ?? fallbackId,
    ...(text(raw.label) ? { label: text(raw.label) } : {}),
    usedPercent: Math.round(Math.max(0, Math.min(100, raw.usedPercent)) * 10) / 10,
    ...(duration ? { windowDurationMins: duration } : {}),
    ...(timestamp(raw.resetsAt) !== undefined ? { resetsAt: timestamp(raw.resetsAt) } : {}),
  };
}

export function normalizeAccountUsage(value: unknown): AccountUsage | null {
  if (!value || typeof value !== "object") return null;
  const rawProviders = (value as { providers?: unknown }).providers;
  if (!rawProviders || typeof rawProviders !== "object") return null;
  const providers: AccountUsage["providers"] = {};
  for (const provider of ["codex", "claude-code"] as ProviderId[]) {
    const rawUsage = (rawProviders as Record<string, unknown>)[provider];
    if (!rawUsage || typeof rawUsage !== "object") continue;
    const usage = rawUsage as Record<string, unknown>;
    const rawLimits = usage.rateLimits && typeof usage.rateLimits === "object"
      ? usage.rateLimits as Record<string, unknown>
      : null;
    const rawWindows = Array.isArray(rawLimits?.windows) && rawLimits.windows.length
      ? rawLimits.windows.slice(0, MAX_WINDOWS)
      : [rawLimits?.primary, rawLimits?.secondary];
    const windows: RateLimitWindow[] = [];
    const seen = new Set<string>();
    rawWindows.forEach((window, index) => {
      const projected = normalizeWindow(window, index === 0 ? "primary" : index === 1 ? "secondary" : `window-${index + 1}`);
      if (!projected || seen.has(projected.id!)) return;
      seen.add(projected.id!);
      windows.push(projected);
    });
    let rateLimits: RateLimitSnapshot | undefined;
    if (windows.length) {
      const primary = windows.find((window) => window.id === "primary") ?? windows[0];
      const secondary = windows.find((window) => window.id === "secondary") ?? windows[1] ?? null;
      rateLimits = {
        windows,
        primary,
        secondary,
        ...Object.fromEntries(["limitId", "limitName", "planType", "rateLimitReachedType"]
          .map((key) => [key, text(rawLimits?.[key])])
          .filter((entry) => entry[1] !== undefined)),
      };
    }
    const observedAt = timestamp(usage.observedAt);
    const availabilityReason = text(usage.availabilityReason);
    providers[provider] = {
      available: usage.available === true && windows.length > 0,
      ...(rateLimits ? { rateLimits } : {}),
      ...(usage.experimental === true ? { experimental: true } : {}),
      ...(observedAt !== undefined ? { observedAt } : {}),
      ...(availabilityReason ? { availabilityReason } : {}),
    };
  }
  return { providers };
}

export function accountUsageWindows(usage: ProviderAccountUsage | undefined): RateLimitWindow[] {
  return usage?.rateLimits?.windows?.length
    ? usage.rateLimits.windows.slice(0, MAX_WINDOWS)
    : [usage?.rateLimits?.primary, usage?.rateLimits?.secondary].filter(
      (window): window is RateLimitWindow => !!window && Number.isFinite(window.usedPercent),
    );
}

export function mostConstrainedWindow(windows: RateLimitWindow[]): RateLimitWindow | undefined {
  return windows.reduce<RateLimitWindow | undefined>(
    (most, window) => !most || window.usedPercent > most.usedPercent ? window : most,
    undefined,
  );
}

export function accountUsageRemaining(usage: ProviderAccountUsage | undefined): string {
  const constrained = mostConstrainedWindow(accountUsageWindows(usage));
  return constrained ? `${Math.max(0, Math.round(100 - constrained.usedPercent))}% left` : "unavailable";
}

export function rateLimitLabel(window: RateLimitWindow, index: number, count: number): string {
  if (window.label) return window.label;
  const durationMins = window.windowDurationMins;
  if (durationMins === 10_080) return "Weekly limit";
  if (durationMins && durationMins % 60 === 0) return `${durationMins / 60}-hour limit`;
  if (durationMins) return `${durationMins}-minute limit`;
  return count === 1 ? "Usage limit" : `Usage limit ${index + 1}`;
}

export function compactRateLimitLabel(window: RateLimitWindow): string {
  return rateLimitLabel(window, 0, 1).replace(/ limit$/i, "");
}

export function rateLimitResetLabel(resetsAt: number | undefined): string {
  if (resetsAt === undefined) return "Reset time unavailable";
  const date = new Date(resetsAt * 1000);
  if (!Number.isFinite(date.valueOf())) return "Reset time unavailable";
  return `Resets ${date.toLocaleString([], { weekday: "short", hour: "numeric", minute: "2-digit" })}`;
}
