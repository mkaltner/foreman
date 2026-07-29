export function isNearBottom(
  scrollTop: number,
  clientHeight: number,
  scrollHeight: number,
  threshold = 72,
): boolean {
  return scrollHeight - scrollTop - clientHeight <= threshold;
}

export function createSubmissionGuard() {
  let active = false;
  return {
    enter(): boolean {
      if (active) return false;
      active = true;
      return true;
    },
    leave(): void {
      active = false;
    },
  };
}

export function confirmSessionAction(
  action: "archive" | "delete",
  title: string,
  confirm: (message: string) => boolean = window.confirm,
): boolean {
  const message =
    action === "delete"
      ? `Permanently delete “${title}”? This cannot be undone.`
      : `Archive “${title}”?`;
  return confirm(message);
}

export function formatActivity(timestamp?: number | null): string {
  if (!timestamp) return "No recent activity";
  const milliseconds = timestamp < 10_000_000_000 ? timestamp * 1000 : timestamp;
  const elapsed = Date.now() - milliseconds;
  if (elapsed < 60_000) return "Just now";
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)}m ago`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)}h ago`;
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(
    new Date(milliseconds),
  );
}
