let sequence = 0;

export async function listSessions({ dir }) {
  return [
    {
      sessionId: "external-session",
      cwd: dir,
      createdAt: 100,
      lastModified: 200,
      summary: "not projected",
      firstPrompt: "not projected",
    },
  ];
}

export function query({ prompt, options }) {
  let interrupted = false;
  let releaseSleep;
  const sleep = new Promise((resolve) => {
    releaseSleep = resolve;
  });
  const iterator = (async function* () {
    let text = "";
    for await (const input of prompt) {
      text = input.message.content;
      break;
    }
    const sessionId = options.resume || `managed-session-${++sequence}`;
    yield {
      type: "system",
      subtype: "init",
      session_id: sessionId,
      claude_code_version: "2.1.220",
      model: options.model || "default-test-model",
      permissionMode: options.permissionMode,
    };
    if (text.includes("approval")) {
      let denied = options.permissionMode === "dontAsk";
      if (!denied) {
        const decision = await options.canUseTool(
          "Bash",
          { command: "touch SHOULD_NOT_EXIST" },
          {
            requestId: "approval-request",
            toolUseID: "approval-tool",
            title: "Run a bounded command",
            description: "Test approval callback",
          },
        );
        denied = decision.behavior === "deny";
      }
      if (denied) {
        yield {
          type: "system",
          subtype: "permission_denied",
          session_id: sessionId,
          tool_name: "Bash",
          tool_use_id: "approval-tool",
          decision_reason: "harness",
          message: "denied",
        };
      }
    }
    const tool = text.includes("sleep") || text.includes("Bash") ? "Bash" : "Read";
    yield {
      type: "assistant",
      session_id: sessionId,
      message: {
        content: [
          { type: "text", text: "completed text is not projected" },
          { type: "tool_use", id: "tool-1", name: tool, input: { secret: "not projected" } },
        ],
      },
    };
    if (text.includes("sleep")) await sleep;
    if (!interrupted) {
      yield {
        type: "stream_event",
        session_id: sessionId,
        event: { type: "content_block_delta", delta: { type: "text_delta", text: "hello" } },
      };
      yield {
        type: "user",
        session_id: sessionId,
        message: {
          content: [{ type: "tool_result", tool_use_id: "tool-1", content: "sensitive output" }],
        },
      };
    }
    yield {
      type: "result",
      subtype: interrupted ? "error_during_execution" : "success",
      session_id: sessionId,
      is_error: interrupted,
      result: "full result is not projected",
    };
  })();
  iterator.interrupt = async () => {
    interrupted = true;
    releaseSleep();
    return { still_queued: [] };
  };
  iterator.close = () => releaseSleep();
  return iterator;
}
