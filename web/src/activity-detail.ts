import type { ConversationItem } from "./protocol";

export type ActivityDetail = "focused" | "full";

export interface ConversationBlock {
  items: ConversationItem[];
  collapsedActivity: boolean;
}

export interface ActivitySummary {
  commands: number;
  tools: number;
  nonZero: number;
}

export function conversationBlocks(
  messages: ConversationItem[],
  activityDetail: ActivityDetail,
  protectedItemIds: ReadonlySet<string> = new Set(),
): ConversationBlock[] {
  if (activityDetail === "full") {
    return messages.map((item) => ({ items: [item], collapsedActivity: false }));
  }
  const result: ConversationBlock[] = [];
  let collapsed: ConversationItem[] = [];
  const flushCollapsed = () => {
    if (!collapsed.length) return;
    result.push({ items: collapsed, collapsedActivity: true });
    collapsed = [];
  };
  messages.forEach((item) => {
    if (!protectedItemIds.has(item.id) && isRoutineCompletedActivity(item)) {
      collapsed.push(item);
    } else {
      flushCollapsed();
      result.push({ items: [item], collapsedActivity: false });
    }
  });
  flushCollapsed();
  return result;
}

export function activitySummary(items: ConversationItem[]): ActivitySummary {
  return {
    commands: items.filter(({ kind }) => kind === "command").length,
    tools: items.filter(({ kind }) => kind === "tool").length,
    nonZero: items.filter(({ exitCode }) => exitCode != null && exitCode !== 0).length,
  };
}

export function formatActivitySummary(items: ConversationItem[]): string {
  const { commands, tools, nonZero } = activitySummary(items);
  return [
    commands ? `${commands} command${commands === 1 ? "" : "s"}` : "",
    tools ? `${tools} tool${tools === 1 ? "" : "s"}` : "",
    nonZero ? `${nonZero} non-zero` : "",
  ].filter(Boolean).join(" · ");
}

export function formatActivityOutcome(item: ConversationItem): string {
  const rawStatus = item.status?.trim();
  const status = rawStatus === "inProgress"
    ? "In progress"
    : rawStatus === "executionError"
      ? "Execution error"
      : rawStatus === "permissionBlocked"
        ? "Permission blocked"
        : rawStatus
          ? `${rawStatus[0].toUpperCase()}${rawStatus.slice(1)}`
          : "In progress";
  return item.exitCode == null ? status : `${status} · Exited ${item.exitCode}`;
}

export function activityStatusTone(item: ConversationItem): "active" | "attention" | "neutral" {
  const status = (item.status ?? "").toLowerCase().replaceAll(/[_\s-]/g, "");
  if (["inprogress", "running", "started", "pending"].includes(status)) return "active";
  if ([
    "blocked", "denied", "permissionblocked", "executionerror", "error", "failed",
    "interrupted", "cancelled", "canceled",
  ].includes(status)) return "attention";
  return "neutral";
}

function isRoutineCompletedActivity(item: ConversationItem): boolean {
  const completed = ["completed", "complete", "succeeded", "success", "done"];
  const status = (item.status ?? "").toLowerCase();
  return (item.kind === "command" || item.kind === "tool") &&
    (completed.includes(status) ||
      (item.kind === "command" && status === "failed" && item.exitCode != null));
}
