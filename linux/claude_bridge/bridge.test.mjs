import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";

import {
  ClaudeBridge,
  detectClaudeCode,
  MAX_EVENT_TEXT_BYTES,
  MAX_MESSAGE_BYTES,
  normalizedEvents,
  normalizedHistory,
  parseClaudeVersion,
} from "./bridge.mjs";
import * as fakeSdk from "./test_fake_sdk.mjs";

const BRIDGE = fileURLToPath(new URL("./bridge.mjs", import.meta.url));
const FAKE_SDK = pathToFileURL(fileURLToPath(new URL("./test_fake_sdk.mjs", import.meta.url))).href;

function waitFor(predicate, timeout = 3_000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeout;
    const timer = setInterval(() => {
      const value = predicate();
      if (value) {
        clearInterval(timer);
        resolve(value);
      } else if (Date.now() >= deadline) {
        clearInterval(timer);
        reject(new Error("timed out waiting for bridge output"));
      }
    }, 10);
  });
}

async function processBridge() {
  const directory = await mkdtemp(join(tmpdir(), "foreman-claude-bridge-"));
  const child = spawn(process.execPath, [BRIDGE, "--state", join(directory, "state.json")], {
    env: {
      ...process.env,
      FOREMAN_CLAUDE_SDK_MODULE: FAKE_SDK,
      FOREMAN_CLAUDE_EXECUTABLE: process.execPath,
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const messages = [];
  let buffer = "";
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    buffer += chunk;
    while (buffer.includes("\n")) {
      const index = buffer.indexOf("\n");
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line) messages.push(JSON.parse(line));
    }
  });
  return { child, messages, directory };
}

function send(child, value) {
  child.stdin.write(`${JSON.stringify(value)}\n`);
}

test("parses installed Claude versions", () => {
  assert.equal(parseClaudeVersion("2.1.220 (Claude Code)"), "2.1.220");
  assert.equal(parseClaudeVersion("unexpected"), null);
});

test("missing native Claude is an explicit non-fatal unavailable state", async () => {
  const status = await detectClaudeCode({
    env: { PATH: "", FOREMAN_CLAUDE_SDK_MODULE: FAKE_SDK },
    moduleSpecifier: FAKE_SDK,
  });
  assert.equal(status.installed, false);
  assert.equal(status.available, false);
  assert.match(status.limitation, /native claude executable/i);
});

test("normalization bounds visible text and discards raw tool output", () => {
  const delta = normalizedEvents({
    type: "stream_event",
    event: { type: "content_block_delta", delta: { type: "text_delta", text: "x".repeat(MAX_EVENT_TEXT_BYTES * 2) } },
  }, "session-1")[0];
  assert.ok(Buffer.byteLength(delta.text) <= MAX_EVENT_TEXT_BYTES);
  const tool = normalizedEvents({
    type: "user",
    message: { content: [{ type: "tool_result", tool_use_id: "tool", content: "SECRET" }] },
  }, "session-1")[0];
  assert.equal(tool.summary, "Tool completed");
  assert.equal(JSON.stringify(tool).includes("SECRET"), false);
});

test("history projection keeps visible messages and safe tool cards only", async () => {
  const history = normalizedHistory(await fakeSdk.getSessionMessages("session-1"));
  assert.deepEqual(history.map((item) => item.kind), ["user", "assistant", "tool"]);
  assert.equal(history[2].status, "completed");
  assert.match(history[2].description, /output hidden/);
  assert.equal(JSON.stringify(history).includes("SECRET"), false);
});

test("start, model, permission callback, discovery, interrupt, and minimal mapping", async () => {
  const directory = await mkdtemp(join(tmpdir(), "foreman-claude-unit-"));
  const statePath = join(directory, "state.json");
  const events = [];
  const bridge = new ClaudeBridge({
    statePath,
    send: (message) => messages.push(message),
    env: {
      ...process.env,
      FOREMAN_CLAUDE_EXECUTABLE: process.execPath,
      FOREMAN_CLAUDE_SDK_MODULE: FAKE_SDK,
    },
    sdkLoader: async () => fakeSdk,
  });
  const messages = [];
  const started = await bridge.start({ cwd: directory, prompt: "approval Bash", model: "sonnet", permissionMode: "default" });
  await waitFor(() => messages.find((message) => message.event?.kind === "permission.requested"));
  bridge.approve({ requestId: "approval-request", decision: "deny" });
  await waitFor(() => messages.find((message) => message.event?.kind === "query.completed"));
  assert.equal(started.sessionId.startsWith("managed-session-"), true);
  assert.equal(messages.find((message) => message.event?.kind === "query.started").event.model, "sonnet");
  assert.equal(JSON.stringify(messages).includes("sensitive output"), false);
  const state = JSON.parse(await readFile(statePath, "utf8"));
  assert.deepEqual(Object.keys(state.sessions[0]), ["sessionId", "cwd"]);

  const discovered = await bridge.discover({ cwd: directory });
  assert.equal(discovered[0].classification, "resumable");
  assert.equal(discovered[0].liveAttachSupported, false);
  assert.equal("summary" in discovered[0], false);
  const read = await bridge.read({ cwd: directory, sessionId: "external-session" });
  assert.equal(read.title, "External Claude work");
  assert.deepEqual(read.messages.map((item) => item.kind), ["user", "assistant", "tool"]);
  assert.equal(JSON.stringify(read).includes("SECRET"), false);
  assert.throws(() => bridge.attachExternal(), /not supported/);

  const sleeping = await bridge.start({ cwd: directory, prompt: "sleep", permissionMode: "dontAsk" });
  await waitFor(() => messages.find((message) => message.event?.sessionId === sleeping.sessionId && message.event?.kind === "tool"));
  await bridge.interrupt({ sessionId: sleeping.sessionId });
  await waitFor(() => messages.find((message) => message.event?.sessionId === sleeping.sessionId && message.event?.kind === "query.interrupted"));
  await bridge.shutdown();
});

test("stdio protocol handles handshake, malformed and oversized input, and clean shutdown", async () => {
  const { child, messages } = await processBridge();
  send(child, { id: "hello", method: "handshake", params: { protocol: 1 } });
  await waitFor(() => messages.find((message) => message.id === "hello"));
  assert.equal(messages.find((message) => message.id === "hello").result.protocol, 1);

  child.stdin.write("not-json\n");
  await waitFor(() => messages.find((message) => message.type === "error" && message.id === null));

  child.stdin.write(`${"x".repeat(MAX_MESSAGE_BYTES + 1)}\n`);
  await waitFor(() => messages.filter((message) => message.error?.code === "message_too_large").length > 0);

  send(child, { id: "stop", method: "shutdown", params: {} });
  await waitFor(() => messages.find((message) => message.id === "stop"));
  const exitCode = child.exitCode ?? await new Promise((resolve) => child.once("exit", resolve));
  assert.equal(exitCode, 0);
});
