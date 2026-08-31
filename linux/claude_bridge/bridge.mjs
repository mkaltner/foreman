#!/usr/bin/env node

import { execFile as execFileCallback } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import {
  access,
  chmod,
  mkdir,
  readFile,
  realpath,
  rename,
  stat,
  writeFile,
} from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

export const PROTOCOL_VERSION = 1;
export const MAX_MESSAGE_BYTES = 256 * 1024;
export const MAX_EVENT_TEXT_BYTES = 16 * 1024;
export const MAX_HISTORY_ITEMS = 500;
export const MAX_HISTORY_BYTES = 192 * 1024;
export const SUPPORTED_MODELS = Object.freeze([
  { id: "sonnet", displayName: "Sonnet", description: "Adapter-supported Claude Sonnet alias" },
  { id: "haiku", displayName: "Haiku", description: "Adapter-supported Claude Haiku alias" },
]);
export const PERMISSION_MODES = Object.freeze([
  "default",
  "dontAsk",
  "acceptEdits",
  "plan",
  "auto",
  "bypassPermissions",
]);

const PERMISSION_MODE_SET = new Set(PERMISSION_MODES);
const TOOLS = ["Read", "Glob", "Grep", "Bash", "Edit", "Write"];
const execFile = promisify(execFileCallback);
const require = createRequire(import.meta.url);

function boundedText(value, maximum = 512) {
  if (typeof value !== "string") return undefined;
  const cleaned = value.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "");
  const bytes = Buffer.from(cleaned);
  if (bytes.length <= maximum) return cleaned;
  return `${bytes.subarray(0, Math.max(0, maximum - 3)).toString("utf8").replace(/\uFFFD$/u, "")}…`;
}

function safeError(error) {
  const message = boundedText(error instanceof Error ? error.message : String(error), 400);
  return (message || "Claude Code operation failed")
    .replace(/(?:sk-ant-|Bearer\s+)[A-Za-z0-9._-]+/gi, "[credential]")
    .replace(/(token|api[_-]?key|authorization)\s*[=:]\s*\S+/gi, "$1=[redacted]");
}

export function parseClaudeVersion(output) {
  return String(output ?? "").match(/\d+\.\d+\.\d+(?:[-+][\w.-]+)?/)?.[0] ?? null;
}

async function executableOnPath(name, env = process.env) {
  if (name.includes("/")) {
    try {
      const path = resolve(name);
      await access(path, fsConstants.X_OK);
      return path;
    } catch {
      return null;
    }
  }
  for (const directory of (env.PATH ?? "").split(":")) {
    if (!directory) continue;
    const candidate = join(directory, name);
    try {
      await access(candidate, fsConstants.X_OK);
      return candidate;
    } catch {
      // Continue searching PATH.
    }
  }
  return null;
}

async function sdkMetadata(moduleSpecifier) {
  try {
    if (moduleSpecifier !== "@anthropic-ai/claude-agent-sdk") {
      return { version: "test", moduleSpecifier };
    }
    const entry = require.resolve(moduleSpecifier);
    const metadata = JSON.parse(await readFile(join(dirname(entry), "package.json"), "utf8"));
    return {
      version: metadata.version ?? null,
      claudeCodeVersion: metadata.claudeCodeVersion ?? null,
      moduleSpecifier,
    };
  } catch (error) {
    return { version: null, moduleSpecifier, error: safeError(error) };
  }
}

export async function detectClaudeCode({
  env = process.env,
  runVersion = execFile,
  moduleSpecifier = env.FOREMAN_CLAUDE_SDK_MODULE || "@anthropic-ai/claude-agent-sdk",
} = {}) {
  const executable = await executableOnPath(env.FOREMAN_CLAUDE_EXECUTABLE || "claude", env);
  let cliVersion = null;
  let cliError = null;
  if (executable) {
    try {
      const result = await runVersion(executable, ["--version"], { timeout: 10_000 });
      cliVersion = parseClaudeVersion(result.stdout || result.stderr);
      if (!cliVersion) cliError = "Unrecognized Claude Code version output";
    } catch (error) {
      cliError = safeError(error);
    }
  }
  const sdk = await sdkMetadata(moduleSpecifier);
  const installed = Boolean(executable && cliVersion);
  const nodeSupported = Number(process.versions.node.split(".")[0]) >= 20;
  const available = process.platform === "linux" && nodeSupported && installed && Boolean(sdk.version);
  return {
    provider: "claude-code",
    installed,
    cliVersion,
    sdkVersion: sdk.version,
    nodeVersion: process.version,
    available,
    executable,
    permissionModes: [...PERMISSION_MODES],
    models: SUPPORTED_MODELS.map((model) => ({ ...model })),
    modelSelection: true,
    limitation: available
      ? "External sessions are discoverable and resumable, but Foreman cannot live-attach, stream, approve, or interrupt an external Claude process."
      : process.platform !== "linux"
        ? "The Foreman Claude Code adapter is Linux-only."
        : !nodeSupported
          ? "Node.js 20 or newer is required for optional Claude Code support."
        : !installed
          ? "The native claude executable is unavailable."
          : sdk.error || "The Claude Agent SDK is unavailable.",
    capabilities: {
      discover: available,
      start: available,
      resume: available,
      stream: available,
      delete: available,
      interruptManaged: available,
      liveAttachExternal: false,
      approveExternal: false,
      interruptExternal: false,
      remoteControl: false,
    },
    ...(cliError ? { cliError } : {}),
  };
}

class MappingStore {
  constructor(path) {
    this.path = resolve(path);
    this.writes = Promise.resolve();
  }

  async read() {
    try {
      const parsed = JSON.parse(await readFile(this.path, "utf8"));
      if (parsed.version !== 1 || !Array.isArray(parsed.sessions)) {
        throw new Error("Unsupported Claude session mapping format");
      }
      return {
        version: 1,
        sessions: parsed.sessions.filter(
          (item) => item && typeof item.sessionId === "string" && typeof item.cwd === "string",
        ).map((item) => ({ sessionId: item.sessionId, cwd: item.cwd })),
      };
    } catch (error) {
      if (error?.code === "ENOENT") return { version: 1, sessions: [] };
      throw error;
    }
  }

  async remember(sessionId, cwd) {
    this.writes = this.writes.then(async () => {
      const state = await this.read();
      const sessions = state.sessions.filter((item) => item.sessionId !== sessionId);
      sessions.push({ sessionId, cwd });
      await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${process.pid}.tmp`;
      await writeFile(temporary, `${JSON.stringify({ version: 1, sessions }, null, 2)}\n`, {
        mode: 0o600,
      });
      await chmod(temporary, 0o600);
      await rename(temporary, this.path);
    });
    return this.writes;
  }

  async forget(sessionId, cwd) {
    this.writes = this.writes.then(async () => {
      const state = await this.read();
      const sessions = state.sessions.filter(
        (item) => item.sessionId !== sessionId || item.cwd !== cwd,
      );
      await mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
      const temporary = `${this.path}.${process.pid}.tmp`;
      await writeFile(temporary, `${JSON.stringify({ version: 1, sessions }, null, 2)}\n`, {
        mode: 0o600,
      });
      await chmod(temporary, 0o600);
      await rename(temporary, this.path);
    });
    return this.writes;
  }
}

function sanitizedValue(value, depth = 0) {
  if (depth > 2) return "[nested]";
  if (typeof value === "string") return boundedText(value, 500);
  if (typeof value === "number" || typeof value === "boolean" || value == null) return value;
  if (Array.isArray(value)) return value.slice(0, 4).map((item) => sanitizedValue(item, depth + 1));
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).slice(0, 8).map(([key, item]) => [boundedText(key, 80), sanitizedValue(item, depth + 1)]),
    );
  }
  return "[unsupported]";
}

function opaque(value, name) {
  if (typeof value !== "string" || !value || value.length > 160 || !/^[A-Za-z0-9._:-]+$/.test(value)) {
    throw new Error(`${name} is invalid`);
  }
  return value;
}

function boundedCount(value, maximum = 1_000_000_000_000) {
  return Number.isFinite(value) && value >= 0 ? Math.min(Math.trunc(value), maximum) : undefined;
}

function boundedPercent(value) {
  return Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value * 10) / 10)) : undefined;
}

function resetTimestamp(value) {
  if (typeof value !== "string") return undefined;
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) ? Math.max(0, Math.trunc(milliseconds / 1000)) : undefined;
}

function claudeRateLimits(raw) {
  if (!raw || typeof raw !== "object") return null;
  const project = (window, duration) => {
    const usedPercent = boundedPercent(window?.utilization);
    if (usedPercent === undefined) return null;
    const resetsAt = resetTimestamp(window?.resets_at);
    return {
      usedPercent,
      windowDurationMins: duration,
      ...(resetsAt !== undefined ? { resetsAt } : {}),
    };
  };
  const primary = project(raw.five_hour, 300);
  const secondary = project(raw.seven_day, 10_080);
  return primary || secondary ? { primary, secondary } : null;
}

async function within(promise, milliseconds = 5_000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("Claude usage request timed out")), milliseconds);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function checkedDirectory(value) {
  if (typeof value !== "string" || !value || Buffer.byteLength(value) > 4096) {
    throw new Error("cwd is invalid");
  }
  const directory = await realpath(resolve(value));
  if (!(await stat(directory)).isDirectory()) throw new Error("cwd is not a directory");
  return directory;
}

function checkedPrompt(value) {
  if (typeof value !== "string" || !value.trim()) throw new Error("prompt is required");
  if (Buffer.byteLength(value) > 100_000) throw new Error("prompt is too large");
  return value;
}

function oneMessage(prompt) {
  let finish;
  let finished = false;
  const done = new Promise((resolveDone) => {
    finish = () => {
      if (finished) return;
      finished = true;
      resolveDone();
    };
  });
  const stream = (async function* () {
    yield {
      type: "user",
      message: { role: "user", content: prompt },
      parent_tool_use_id: null,
    };
    await done;
  })();
  return { stream, finish };
}

function deferred() {
  let resolvePromise;
  let rejectPromise;
  const promise = new Promise((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  return { promise, resolve: resolvePromise, reject: rejectPromise };
}

export function normalizedEvents(message, sessionId) {
  const events = [];
  if (message?.type === "stream_event") {
    const delta = message.event?.delta;
    if (message.event?.type === "content_block_delta" && delta?.type === "text_delta") {
      events.push({
        kind: "assistant.delta",
        sessionId,
        text: boundedText(delta.text, MAX_EVENT_TEXT_BYTES) ?? "",
      });
    }
  } else if (message?.type === "assistant") {
    for (const block of message.message?.content ?? []) {
      if (block?.type === "tool_use") {
        events.push({
          kind: "tool",
          sessionId,
          name: boundedText(block.name, 120) || "Tool",
          status: "started",
          toolUseId: boundedText(block.id, 160),
        });
      }
    }
    events.push({ kind: "assistant.completed", sessionId });
  } else if (message?.type === "user" && Array.isArray(message.message?.content)) {
    for (const block of message.message.content) {
      if (block?.type === "tool_result") {
        events.push({
          kind: "tool",
          sessionId,
          status: block.is_error ? "failed" : "completed",
          toolUseId: boundedText(block.tool_use_id, 160),
          summary: block.is_error ? "Tool failed" : "Tool completed",
        });
      }
    }
  } else if (message?.type === "system" && message.subtype === "permission_denied") {
    events.push({
      kind: "permission.denied",
      sessionId,
      name: boundedText(message.tool_name, 120) || "Tool",
      toolUseId: boundedText(message.tool_use_id, 160),
      reason: boundedText(message.decision_reason || message.message, 300) || "Permission denied",
    });
  } else if (message?.type === "system" && message.subtype === "compact_boundary") {
    const metadata = message.compact_metadata ?? {};
    events.push({
      kind: "compaction",
      sessionId,
      trigger: metadata.trigger === "manual" ? "manual" : "auto",
      preTokens: Number.isFinite(metadata.pre_tokens) ? Math.max(0, metadata.pre_tokens) : undefined,
      postTokens: Number.isFinite(metadata.post_tokens) ? Math.max(0, metadata.post_tokens) : undefined,
      durationMs: Number.isFinite(metadata.duration_ms) ? Math.max(0, metadata.duration_ms) : undefined,
    });
  }
  return events;
}

function safeToolSummary(name, status) {
  const normalized = String(name ?? "").toLowerCase();
  const action = normalized === "read" ? "Reading a file"
    : normalized === "bash" ? "Running a command (output hidden)"
      : normalized === "edit" || normalized === "write" ? "Editing a file"
        : normalized === "grep" || normalized === "glob" ? "Searching files"
          : `Using ${boundedText(name, 80) || "a tool"}`;
  if (status === "failed") return `${action} failed`;
  if (status === "denied") return `${action} was denied`;
  if (status === "completed") return `${action} completed`;
  return action;
}

function messageBody(value) {
  return value && typeof value === "object" ? value : {};
}

function textContent(content) {
  if (typeof content === "string") return boundedText(content, MAX_EVENT_TEXT_BYTES) || "";
  if (!Array.isArray(content)) return "";
  return boundedText(
    content.filter((block) => block?.type === "text" && typeof block.text === "string")
      .map((block) => block.text).join("\n"),
    MAX_EVENT_TEXT_BYTES,
  ) || "";
}

export function normalizedHistory(messages) {
  const items = [];
  const tools = new Map();
  const append = (item) => {
    if (items.length < MAX_HISTORY_ITEMS) items.push(item);
  };
  for (const entry of Array.isArray(messages) ? messages : []) {
    if (!entry || typeof entry !== "object") continue;
    const body = messageBody(entry.message);
    const uuid = boundedText(entry.uuid, 160) || `message-${items.length + 1}`;
    const content = body.content;
    if (entry.type === "user") {
      const text = textContent(content);
      if (text) append({ id: uuid, kind: "user", text });
      if (!Array.isArray(content)) continue;
      for (const block of content) {
        if (block?.type !== "tool_result") continue;
        const toolUseId = boundedText(block.tool_use_id, 160);
        const existing = toolUseId ? tools.get(toolUseId) : undefined;
        if (!existing) continue;
        existing.status = block.is_error ? "failed" : "completed";
        existing.description = safeToolSummary(existing.toolName, existing.status);
      }
      continue;
    }
    if (entry.type === "assistant") {
      const text = textContent(content);
      if (text) append({ id: uuid, kind: "assistant", text });
      if (!Array.isArray(content)) continue;
      for (const block of content) {
        if (block?.type !== "tool_use") continue;
        const toolUseId = boundedText(block.id, 160) || `${uuid}-tool-${items.length}`;
        const name = boundedText(block.name, 120) || "Tool";
        const item = {
          id: `tool-${toolUseId}`,
          kind: "tool",
          description: safeToolSummary(name, "running"),
          status: "running",
          toolName: name,
        };
        append(item);
        tools.set(toolUseId, item);
      }
      continue;
    }
    if (entry.type === "system" && (body.subtype === "compact_boundary" || entry.subtype === "compact_boundary")) {
      const metadata = body.compact_metadata ?? entry.compact_metadata ?? {};
      append({
        id: `compaction-${uuid}`,
        kind: "compaction",
        description: "Context compacted",
        compactionTrigger: metadata.trigger === "manual" ? "manual" : "auto",
        preTokens: Number.isFinite(metadata.pre_tokens) ? Math.max(0, metadata.pre_tokens) : undefined,
        postTokens: Number.isFinite(metadata.post_tokens) ? Math.max(0, metadata.post_tokens) : undefined,
        durationMs: Number.isFinite(metadata.duration_ms) ? Math.max(0, metadata.duration_ms) : undefined,
      });
      continue;
    }
    if (entry.type === "system" && (body.subtype === "permission_denied" || body.type === "permission_denied")) {
      const name = boundedText(body.tool_name, 120) || "Tool";
      append({
        id: `permission-${uuid}`,
        kind: "tool",
        description: safeToolSummary(name, "denied"),
        status: "denied",
        toolName: name,
      });
    }
  }
  const projected = items.map(({ toolName: _toolName, ...item }) => item);
  const selected = [];
  let encodedBytes = 2;
  for (let index = projected.length - 1; index >= 0; index -= 1) {
    const item = projected[index];
    const itemBytes = Buffer.byteLength(JSON.stringify(item));
    const separatorBytes = selected.length ? 1 : 0;
    if (encodedBytes + separatorBytes + itemBytes > MAX_HISTORY_BYTES) continue;
    selected.unshift(item);
    encodedBytes += separatorBytes + itemBytes;
  }
  return selected;
}

export class ClaudeBridge {
  constructor({ statePath, send, env = process.env, sdkLoader } = {}) {
    if (!statePath) throw new Error("statePath is required");
    this.mapping = new MappingStore(statePath);
    this.send = send || (() => {});
    this.env = env;
    this.sdkLoader = sdkLoader || (() => import(env.FOREMAN_CLAUDE_SDK_MODULE || "@anthropic-ai/claude-agent-sdk"));
    this.sdk = null;
    this.runs = new Map();
    this.activeSessions = new Map();
    this.pendingApprovals = new Map();
    this.nextRunId = 1;
    this.stopping = false;
  }

  async loadSdk() {
    this.sdk ||= await this.sdkLoader();
    return this.sdk;
  }

  emit(event) {
    this.send({ type: "event", event: { provider: "claude-code", ...event } });
  }

  async status() {
    return detectClaudeCode({ env: this.env });
  }

  async discover(params) {
    const cwd = await checkedDirectory(params?.cwd);
    const status = await this.status();
    if (!status.available) throw new Error(status.limitation);
    const { listSessions } = await this.loadSdk();
    const [discovered, state] = await Promise.all([
      listSessions({ dir: cwd, includeWorktrees: false, includeProgrammatic: true }),
      this.mapping.read(),
    ]);
    const managed = new Set(state.sessions.map((item) => item.sessionId));
    const sessions = new Map();
    for (const item of discovered) {
      const sessionId = opaque(item.sessionId, "sessionId");
      const active = this.activeSessions.has(sessionId);
      const sessionCwd = typeof item.cwd === "string" ? await checkedDirectory(item.cwd) : cwd;
      if (sessionCwd !== cwd) continue;
      const run = this.activeSessions.get(sessionId);
      const projected = {
        provider: "claude-code",
        sessionId,
        cwd: sessionCwd,
        title: boundedText(item.summary || item.customTitle || item.firstPrompt, 300) || "Claude Code session",
        classification: managed.has(sessionId) ? "managed" : "resumable",
        active,
        createdAt: Number.isFinite(item.createdAt) ? item.createdAt : undefined,
        lastSeenAt: Number.isFinite(item.lastModified) ? item.lastModified : undefined,
        model: run?.model,
        permissionMode: run?.permissionMode,
        liveAttachSupported: false,
      };
      const key = `${sessionId}\u0000${sessionCwd}`;
      const previous = sessions.get(key);
      if (!previous || (projected.lastSeenAt ?? 0) > (previous.lastSeenAt ?? 0)) sessions.set(key, projected);
    }
    return [...sessions.values()].sort((left, right) => {
      const rank = (item) => item.classification === "managed" && item.active ? 0
        : item.classification === "managed" ? 1 : 2;
      return rank(left) - rank(right) || (right.lastSeenAt ?? 0) - (left.lastSeenAt ?? 0);
    });
  }

  async read(params) {
    const cwd = await checkedDirectory(params?.cwd);
    const sessionId = opaque(params?.sessionId, "sessionId");
    const status = await this.status();
    if (!status.available) throw new Error(status.limitation);
    const { getSessionInfo, getSessionMessages } = await this.loadSdk();
    const info = await getSessionInfo(sessionId, { dir: cwd });
    if (!info || opaque(info.sessionId, "sessionId") !== sessionId) throw new Error("Claude session was not found");
    const infoCwd = typeof info.cwd === "string" ? await checkedDirectory(info.cwd) : cwd;
    if (infoCwd !== cwd) throw new Error("Claude session working directory does not match");
    const messages = await getSessionMessages(sessionId, {
      dir: cwd,
      limit: MAX_HISTORY_ITEMS,
      includeSystemMessages: true,
    });
    const run = this.activeSessions.get(sessionId);
    return {
      provider: "claude-code",
      sessionId,
      cwd,
      title: boundedText(info.summary || info.customTitle || info.firstPrompt, 300) || "Claude Code session",
      createdAt: Number.isFinite(info.createdAt) ? info.createdAt : undefined,
      lastSeenAt: Number.isFinite(info.lastModified) ? info.lastModified : undefined,
      active: Boolean(run),
      model: run?.model,
      permissionMode: run?.permissionMode,
      messages: normalizedHistory(messages),
    };
  }

  async delete(params) {
    const cwd = await checkedDirectory(params?.cwd);
    const sessionId = opaque(params?.sessionId, "sessionId");
    if (
      this.activeSessions.has(sessionId)
      || [...this.runs.values()].some((run) => run.sessionId === sessionId || run.resume === sessionId)
    ) {
      throw new Error("Session is active; interrupt it before deletion");
    }
    const status = await this.status();
    if (!status.available) throw new Error(status.limitation);
    const { deleteSession, getSessionInfo } = await this.loadSdk();
    if (typeof deleteSession !== "function") throw new Error("Claude session deletion is unavailable");
    const info = await getSessionInfo(sessionId, { dir: cwd });
    if (!info || opaque(info.sessionId, "sessionId") !== sessionId) throw new Error("Claude session was not found");
    const infoCwd = typeof info.cwd === "string" ? await checkedDirectory(info.cwd) : cwd;
    if (infoCwd !== cwd) throw new Error("Claude session working directory does not match");
    await deleteSession(sessionId, { dir: cwd });
    await this.mapping.forget(sessionId, cwd);
    return { sessionId, deleted: true };
  }

  async start(params) {
    return this.startRun(params, null);
  }

  async resume(params) {
    return this.startRun(params, opaque(params?.sessionId, "sessionId"));
  }

  async startRun(params, resume) {
    if (this.stopping) throw new Error("Claude bridge is stopping");
    const cwd = await checkedDirectory(params?.cwd);
    const prompt = checkedPrompt(params?.prompt);
    const permissionMode = params?.permissionMode ?? "default";
    if (!PERMISSION_MODE_SET.has(permissionMode)) throw new Error("Unsupported permission mode");
    const model = params?.model == null ? undefined : opaque(params.model, "model");
    if (resume && this.activeSessions.has(resume)) throw new Error("Session already has an active Foreman query");
    const status = await this.status();
    if (!status.available) throw new Error(status.limitation);
    const sdk = await this.loadSdk();
    const runId = `run-${this.nextRunId++}`;
    const run = {
      runId,
      cwd,
      prompt,
      model,
      permissionMode,
      sessionId: resume,
      resume,
      ready: deferred(),
      done: deferred(),
      interruptRequested: false,
      input: null,
      query: null,
      terminal: false,
      terminalEvent: null,
      accountUsageObserved: false,
      clientRequestId: params?.clientRequestId,
    };
    this.runs.set(runId, run);
    if (resume) this.activeSessions.set(resume, run);
    void this.executeRun(run, sdk, status.executable);
    return run.ready.promise;
  }

  async executeRun(run, sdk, executable) {
    const input = oneMessage(run.prompt);
    run.input = input;
    const options = {
      cwd: run.cwd,
      allowedTools: [],
      tools: TOOLS,
      includePartialMessages: true,
      persistSession: true,
      permissionMode: run.permissionMode,
      env: { ...this.env, CLAUDE_AGENT_SDK_CLIENT_APP: "foreman/1.0.4" },
      canUseTool: (name, toolInput, context) => this.requestApproval(run, name, toolInput, context),
    };
    if (run.model) options.model = run.model;
    if (run.resume) options.resume = run.resume;
    if (executable) options.pathToClaudeCodeExecutable = executable;
    if (run.permissionMode === "bypassPermissions") options.allowDangerouslySkipPermissions = true;
    try {
      run.query = sdk.query({ prompt: input.stream, options });
      for await (const message of run.query) {
        if (message?.type === "system" && message.subtype === "init") {
          const sessionId = opaque(message.session_id, "sessionId");
          if (run.sessionId && run.sessionId !== sessionId) throw new Error("Claude resumed an unexpected session");
          if (!run.sessionId && this.activeSessions.has(sessionId)) throw new Error("Session already has an active Foreman query");
          run.sessionId = sessionId;
          this.activeSessions.set(sessionId, run);
          await this.mapping.remember(sessionId, run.cwd);
          const started = {
            kind: "query.started",
            runId: run.runId,
            sessionId,
            cwd: run.cwd,
            model: boundedText(message.model, 120),
            permissionMode: run.permissionMode,
          };
          this.emit(started);
          run.ready.resolve({ runId: run.runId, sessionId });
          await this.emitUsage(run);
          continue;
        }
        for (const event of normalizedEvents(message, run.sessionId)) this.emit({ runId: run.runId, ...event });
        if (message?.type === "result") {
          await this.emitUsage(run);
          input.finish();
          run.terminal = true;
          if (run.interruptRequested) {
            run.terminalEvent = { kind: "query.interrupted", runId: run.runId, sessionId: run.sessionId };
          } else if (message.is_error || message.subtype !== "success") {
            run.terminalEvent = {
              kind: "query.failed",
              runId: run.runId,
              sessionId: run.sessionId,
              message: "Claude Code query failed",
            };
          } else {
            run.terminalEvent = { kind: "query.completed", runId: run.runId, sessionId: run.sessionId };
          }
        }
      }
      if (!run.sessionId) throw new Error("Claude Code did not start a session");
      if (!run.terminal) {
        run.terminal = true;
        run.terminalEvent = {
          kind: run.interruptRequested ? "query.interrupted" : "query.failed",
          runId: run.runId,
          sessionId: run.sessionId,
          ...(run.interruptRequested ? {} : { message: "Claude Code query ended without a result" }),
        };
      }
    } catch (error) {
      if (!run.sessionId) run.ready.reject(new Error(safeError(error)));
      if (!run.terminal) {
        run.terminal = true;
        run.terminalEvent = {
          kind: run.interruptRequested ? "query.interrupted" : "query.failed",
          runId: run.runId,
          sessionId: run.sessionId,
          ...(run.interruptRequested ? {} : { message: safeError(error) }),
        };
      }
    } finally {
      input.finish();
      this.clearApprovals(run, "Query ended before an approval decision");
      this.runs.delete(run.runId);
      if (run.sessionId && this.activeSessions.get(run.sessionId) === run) this.activeSessions.delete(run.sessionId);
      try {
        run.query?.close?.();
      } catch {
        // The SDK query is already closed.
      }
      if (run.terminalEvent) this.emit(run.terminalEvent);
      run.done.resolve();
    }
  }

  async emitUsage(run) {
    if (!run.query || !run.sessionId) return;
    const contextRequest = typeof run.query.getContextUsage === "function"
      ? within(Promise.resolve().then(() => run.query.getContextUsage()))
      : Promise.reject(new Error("Claude context usage is unavailable"));
    const accountRequest = !run.accountUsageObserved && typeof run.query.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET === "function"
      ? within(Promise.resolve().then(() => run.query.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET()))
      : Promise.reject(new Error("Claude account usage is unavailable"));
    const [contextResult, accountResult] = await Promise.allSettled([contextRequest, accountRequest]);
    const event = { kind: "usage", runId: run.runId, sessionId: run.sessionId };
    if (contextResult.status === "fulfilled") {
      const totalTokens = boundedCount(contextResult.value?.totalTokens);
      const modelContextWindow = boundedCount(contextResult.value?.maxTokens);
      if (totalTokens !== undefined && modelContextWindow) {
        event.tokenUsage = {
          last: { totalTokens },
          modelContextWindow,
        };
      }
    }
    if (accountResult.status === "fulfilled") {
      run.accountUsageObserved = true;
      const rateLimits = accountResult.value?.rate_limits_available
        ? claudeRateLimits(accountResult.value.rate_limits)
        : null;
      event.accountUsage = {
        available: Boolean(rateLimits),
        ...(rateLimits ? { rateLimits } : {}),
        experimental: true,
        observedAt: Math.trunc(Date.now() / 1000),
        ...(!rateLimits ? { availabilityReason: "Claude plan limits are unavailable for this account." } : {}),
      };
    }
    if (event.tokenUsage || event.accountUsage) this.emit(event);
  }

  requestApproval(run, name, input, context) {
    const requestId = opaque(context.requestId, "requestId");
    const decision = deferred();
    this.pendingApprovals.set(requestId, {
      run,
      input,
      name,
      toolUseId: context.toolUseID,
      resolve: decision.resolve,
    });
    this.emit({
      kind: "permission.requested",
      runId: run.runId,
      sessionId: run.sessionId,
      requestId,
      toolUseId: boundedText(context.toolUseID, 160),
      name: boundedText(name, 120) || "Tool",
      title: boundedText(context.title, 300),
      description: boundedText(context.description, 500),
      input: sanitizedValue(input),
    });
    return decision.promise;
  }

  approve(params) {
    const requestId = opaque(params?.requestId, "requestId");
    const pending = this.pendingApprovals.get(requestId);
    if (!pending) throw new Error("Approval is no longer pending");
    if (params?.decision !== "allow" && params?.decision !== "deny") throw new Error("decision must be allow or deny");
    this.pendingApprovals.delete(requestId);
    const result = params.decision === "allow"
      ? { behavior: "allow", updatedInput: pending.input }
      : { behavior: "deny", message: "Denied by Foreman test harness" };
    pending.resolve(result);
    this.emit({
      kind: params.decision === "allow" ? "permission.allowed" : "permission.denied",
      runId: pending.run.runId,
      sessionId: pending.run.sessionId,
      requestId,
      name: boundedText(pending.name, 120) || "Tool",
    });
    return { requestId, decision: params.decision };
  }

  clearApprovals(run, message) {
    for (const [requestId, pending] of this.pendingApprovals) {
      if (pending.run !== run) continue;
      this.pendingApprovals.delete(requestId);
      pending.resolve({ behavior: "deny", message });
    }
  }

  async interrupt(params) {
    const sessionId = opaque(params?.sessionId, "sessionId");
    const run = this.activeSessions.get(sessionId);
    if (!run) throw new Error("Session is not active in the Foreman Claude adapter");
    run.interruptRequested = true;
    await run.query.interrupt();
    return { sessionId, interrupted: true };
  }

  async cancelRequest(params) {
    const requestId = opaque(params?.requestId, "requestId");
    const run = [...this.runs.values()].find((item) => item.clientRequestId === requestId);
    if (!run) return { requestId, cancelled: false };
    run.interruptRequested = true;
    this.clearApprovals(run, "Claude query request timed out");
    await run.query?.interrupt?.();
    await run.done.promise;
    return { requestId, cancelled: true };
  }

  attachExternal() {
    throw new Error("Live attachment to an external Claude process is not supported");
  }

  async shutdown() {
    this.stopping = true;
    const runs = [...this.runs.values()];
    for (const run of runs) {
      run.interruptRequested = true;
      this.clearApprovals(run, "Claude adapter is shutting down");
    }
    await Promise.allSettled(runs.map((run) => run.query?.interrupt?.()));
    for (const run of runs) {
      run.input?.finish();
      try {
        run.query?.close?.();
      } catch {
        // The SDK query is already closed.
      }
    }
    this.runs.clear();
    this.activeSessions.clear();
    this.pendingApprovals.clear();
    return { stopped: true };
  }
}

function bridgeArguments(argv) {
  let statePath = null;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--state" && argv[index + 1]) statePath = argv[++index];
    else throw new Error("Expected --state PATH");
  }
  if (!statePath) throw new Error("--state is required");
  return { statePath };
}

function writeProtocol(message) {
  let encoded = `${JSON.stringify(message)}\n`;
  if (Buffer.byteLength(encoded) > MAX_MESSAGE_BYTES) {
    encoded = `${JSON.stringify({ type: "error", id: message.id ?? null, error: { code: "message_too_large", message: "Bridge output exceeded the message limit" } })}\n`;
  }
  process.stdout.write(encoded);
}

async function serve() {
  const { statePath } = bridgeArguments(process.argv.slice(2));
  const bridge = new ClaudeBridge({ statePath, send: writeProtocol });
  let buffer = Buffer.alloc(0);
  let oversized = false;

  async function handle(line) {
    let request;
    try {
      request = JSON.parse(line.toString("utf8"));
      if (!request || typeof request !== "object" || typeof request.id !== "string" || typeof request.method !== "string") {
        throw new Error("Request must include string id and method");
      }
      let result;
      if (request.method === "handshake") {
        if (request.params?.protocol !== PROTOCOL_VERSION) throw new Error("Unsupported bridge protocol");
        result = { protocol: PROTOCOL_VERSION, maxMessageBytes: MAX_MESSAGE_BYTES, pid: process.pid };
      } else if (request.method === "status") result = await bridge.status();
      else if (request.method === "discover") result = await bridge.discover(request.params);
      else if (request.method === "read") result = await bridge.read(request.params);
      else if (request.method === "start") result = await bridge.start({ ...request.params, clientRequestId: request.id });
      else if (request.method === "resume") result = await bridge.resume({ ...request.params, clientRequestId: request.id });
      else if (request.method === "delete") result = await bridge.delete(request.params);
      else if (request.method === "interrupt") result = await bridge.interrupt(request.params);
      else if (request.method === "cancelRequest") result = await bridge.cancelRequest(request.params);
      else if (request.method === "approval") result = bridge.approve(request.params);
      else if (request.method === "attachExternal") result = bridge.attachExternal(request.params);
      else if (request.method === "shutdown") result = await bridge.shutdown();
      else throw new Error("Unknown bridge method");
      writeProtocol({ type: "response", id: request.id, result });
      if (request.method === "shutdown") setImmediate(() => process.exit(0));
    } catch (error) {
      writeProtocol({
        type: "error",
        id: typeof request?.id === "string" ? request.id : null,
        error: { code: "bridge_error", message: safeError(error) },
      });
    }
  }

  process.stdin.on("data", (chunk) => {
    if (oversized) {
      const newline = chunk.indexOf(10);
      if (newline < 0) return;
      oversized = false;
      chunk = chunk.subarray(newline + 1);
    }
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const newline = buffer.indexOf(10);
      if (newline < 0) break;
      const line = buffer.subarray(0, newline);
      buffer = buffer.subarray(newline + 1);
      if (line.length > MAX_MESSAGE_BYTES) {
        writeProtocol({ type: "error", id: null, error: { code: "message_too_large", message: "Bridge input exceeded the message limit" } });
      } else if (line.length) {
        void handle(line);
      }
    }
    if (buffer.length > MAX_MESSAGE_BYTES) {
      buffer = Buffer.alloc(0);
      oversized = true;
      writeProtocol({ type: "error", id: null, error: { code: "message_too_large", message: "Bridge input exceeded the message limit" } });
    }
  });
  process.stdin.on("end", () => void bridge.shutdown().finally(() => process.exit(0)));
  const terminate = () => void bridge.shutdown().finally(() => process.exit(0));
  process.once("SIGTERM", terminate);
  process.once("SIGINT", terminate);
}

const invokedDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  serve().catch((error) => {
    process.stderr.write(`${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
