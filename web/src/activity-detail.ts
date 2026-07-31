import type { ConversationItem } from "./protocol";

export type ActivityDetail = "focused" | "full";

export interface ConversationBlock {
  items: ConversationItem[];
  collapsedActivity: boolean;
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

function isRoutineCompletedActivity(item: ConversationItem): boolean {
  const completed = ["completed", "complete", "succeeded", "success", "done"];
  return (item.kind === "command" || item.kind === "tool") &&
    completed.includes((item.status ?? "").toLowerCase()) &&
    (item.exitCode == null || item.exitCode === 0);
}
