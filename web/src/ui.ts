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

export interface PlainTextLinkSegment {
  text: string;
  href?: string;
}

const BARE_WEB_URL = /\bhttps?:\/\/[^\s<>"']+/gi;

export function linkifyPlainText(text: string): PlainTextLinkSegment[] {
  const segments: PlainTextLinkSegment[] = [];
  let cursor = 0;
  for (const match of text.matchAll(BARE_WEB_URL)) {
    const index = match.index;
    if (index > cursor) segments.push({ text: text.slice(cursor, index) });
    const raw = match[0];
    const href = trimTrailingUrlPunctuation(raw);
    if (isSafeWebUrl(href)) {
      segments.push({ text: href, href });
      if (href.length < raw.length) segments.push({ text: raw.slice(href.length) });
    } else {
      segments.push({ text: raw });
    }
    cursor = index + raw.length;
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor) });
  return segments.length ? segments : [{ text }];
}

function trimTrailingUrlPunctuation(raw: string): string {
  let value = raw.replace(/[.,;:!?]+$/, "");
  const unbalanced = (open: string, close: string) =>
    [...value].filter((character) => character === close).length >
      [...value].filter((character) => character === open).length;
  while (
    (value.endsWith(")") && unbalanced("(", ")")) ||
    (value.endsWith("]") && unbalanced("[", "]")) ||
    (value.endsWith("}") && unbalanced("{", "}"))
  ) value = value.slice(0, -1);
  return value;
}

function isSafeWebUrl(raw: string): boolean {
  try {
    const url = new URL(raw);
    return ["http:", "https:"].includes(url.protocol) && !!url.hostname;
  } catch {
    return false;
  }
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

export function reasoningLabel(effort: string): string {
  const labels: Record<string, string> = {
    low: "Light",
    medium: "Medium",
    high: "High",
    xhigh: "Extra High",
    max: "Max",
    ultra: "Ultra",
  };
  return labels[effort.toLowerCase()] ??
    (effort ? effort[0].toUpperCase() + effort.slice(1).replaceAll("-", " ") : "");
}

export function reasoningDescription(effort: string): string | undefined {
  return effort.toLowerCase() === "ultra" ? "Consumes usage limits faster" : undefined;
}

export type WebRoute =
  | { view: "overview" }
  | { view: "dashboard" }
  | { view: "sessions" }
  | { view: "settings" }
  | { view: "detail"; sessionId: string };

export function parseWebRoute(pathname: string): WebRoute {
  const normalized = pathname.replace(/\/+$/, "") || "/";
  if (normalized === "/hosts") return { view: "overview" };
  if (normalized === "/" || normalized === "/dashboard") return { view: "dashboard" };
  if (normalized === "/settings") return { view: "settings" };
  if (normalized === "/sessions") return { view: "sessions" };
  const match = /^\/sessions\/([^/]+)$/.exec(normalized);
  if (match) {
    try {
      const sessionId = decodeURIComponent(match[1]);
      if (sessionId) return { view: "detail", sessionId };
    } catch {
      // Treat malformed URL escapes as the session list.
    }
  }
  return { view: "dashboard" };
}

export function webRoutePath(route: WebRoute): string {
  if (route.view === "overview") return "/hosts";
  if (route.view === "dashboard") return "/";
  if (route.view === "settings") return "/settings";
  if (route.view === "detail") return `/sessions/${encodeURIComponent(route.sessionId)}`;
  return "/sessions";
}

export interface AppDirective {
  name: string;
  attributes: Record<string, string>;
}

export type AssistantContentSegment =
  | { kind: "markdown"; text: string }
  | { kind: "directive"; directive: AppDirective };

const DISPLAYED_DIRECTIVES = new Set([
  "created-thread",
  "git-stage",
  "git-commit",
  "git-create-branch",
  "git-push",
  "git-create-pr",
]);

export function parseAssistantContent(text: string): AssistantContentSegment[] {
  const segments: AssistantContentSegment[] = [];
  let markdown: string[] = [];
  const flushMarkdown = () => {
    if (!markdown.length) return;
    segments.push({ kind: "markdown", text: markdown.join("\n") });
    markdown = [];
  };

  for (const line of text.split("\n")) {
    const directives = parseDirectiveLine(line);
    if (!directives?.length || directives.some(({ name }) => !DISPLAYED_DIRECTIVES.has(name))) {
      markdown.push(line);
      continue;
    }
    flushMarkdown();
    segments.push(...directives.map((directive) => ({ kind: "directive" as const, directive })));
  }
  flushMarkdown();
  return segments;
}

function parseDirectiveLine(line: string): AppDirective[] | null {
  const directives: AppDirective[] = [];
  let cursor = 0;
  while (cursor < line.length && /\s/.test(line[cursor])) cursor += 1;
  if (!line.startsWith("::", cursor)) return null;

  while (cursor < line.length) {
    if (!line.startsWith("::", cursor)) return null;
    cursor += 2;
    const nameStart = cursor;
    while (cursor < line.length && /[a-z0-9-]/i.test(line[cursor])) cursor += 1;
    const name = line.slice(nameStart, cursor);
    if (!name || line[cursor] !== "{") return null;
    cursor += 1;

    const bodyStart = cursor;
    let quoted = false;
    let escaped = false;
    while (cursor < line.length) {
      const character = line[cursor];
      if (escaped) escaped = false;
      else if (character === "\\" && quoted) escaped = true;
      else if (character === "\"") quoted = !quoted;
      else if (character === "}" && !quoted) break;
      cursor += 1;
    }
    if (cursor >= line.length) return null;
    directives.push({ name, attributes: parseDirectiveAttributes(line.slice(bodyStart, cursor)) });
    cursor += 1;
    while (cursor < line.length && /\s/.test(line[cursor])) cursor += 1;
  }
  return directives;
}

function parseDirectiveAttributes(source: string): Record<string, string> {
  const attributes: Record<string, string> = {};
  const pattern = /([A-Za-z][\w-]*)=(?:"((?:\\.|[^"\\])*)"|([^\s]+))/g;
  for (const match of source.matchAll(pattern)) {
    attributes[match[1]] = match[2] == null
      ? match[3]
      : match[2].replace(/\\(["\\])/g, "$1");
  }
  return attributes;
}
