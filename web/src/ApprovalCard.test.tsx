import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { APPROVAL_CARD_MAX_WIDTH, ApprovalCard } from "./ApprovalCard";
import type { ApprovalRequest } from "./protocol";

const base: ApprovalRequest = {
  id: "apr-safe",
  sessionId: "thread-safe",
  turnId: "turn-safe",
  itemId: "item-safe",
  type: "command",
  title: "Approval required",
  createdAt: 1_720_000_000,
  status: "pending",
  command: "printf '<safe>'",
  cwd: "/workspace/example",
  reason: "Run a safe local check",
  availableDecisions: [
    { type: "accept", label: "Allow once", optionId: "decision-0" },
    { type: "decline", label: "Decline", optionId: "decision-1" },
  ],
};

describe("approval card", () => {
  it("aligns its focus treatment with the conversation column", () => {
    const { container } = render(<ApprovalCard approval={base} focused connected onRespond={vi.fn()} />);
    expect(APPROVAL_CARD_MAX_WIDTH).toBe(760);
    expect(container.querySelector(".approval-card")).toHaveStyle({ maxWidth: "760px" });
    expect(container.querySelector(".approval-card")).toHaveClass("approval-focused");
  });

  it("renders command fields safely and only advertised decisions", async () => {
    const respond = vi.fn().mockResolvedValue(undefined);
    render(<ApprovalCard approval={base} connected onRespond={respond} />);
    expect(screen.getByText("Run a safe local check")).toBeInTheDocument();
    expect(screen.getByText("printf '<safe>'")).toBeInTheDocument();
    expect(screen.getByText("/workspace/example")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Allow once" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Decline" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /session/i })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Allow once" }));
    await waitFor(() => expect(respond).toHaveBeenCalledWith("apr-safe", { type: "accept", optionId: "decision-0" }));
    expect(screen.getByText("Submitting decision…")).toBeInTheDocument();
  });

  it("prevents duplicate submission and renders structured amendment choices", async () => {
    let finish!: () => void;
    const respond = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    render(<ApprovalCard approval={{ ...base, availableDecisions: [{ type: "acceptWithExecpolicyAmendment", label: "Allow matching commands", optionId: "decision-0", amendment: ["git", "status"] }] }} connected onRespond={respond} />);
    const button = screen.getByRole("button", { name: "Allow matching commands" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(respond).toHaveBeenCalledTimes(1);
    expect(button).toBeDisabled();
    finish();
  });

  it("renders concise file changes", () => {
    render(<ApprovalCard approval={{ ...base, type: "fileChange", title: "File changes require approval", command: undefined, cwd: undefined, fileCount: 1, fileChanges: [{ path: "/workspace/example/README.md", kind: "update", summary: { addedLines: 2, removedLines: 1 } }] }} connected onRespond={vi.fn()} />);
    expect(screen.getByText("File changes require approval")).toBeInTheDocument();
    expect(screen.getByText("/workspace/example/README.md")).toBeInTheDocument();
    expect(screen.getByText("+2 −1")).toBeInTheDocument();
  });

  it("grants only selected permission subsets with explicit scope", async () => {
    const respond = vi.fn().mockResolvedValue(undefined);
    render(<ApprovalCard approval={{ ...base, type: "permission", title: "Permissions requested", availableDecisions: [{ type: "grant", label: "Grant selected", scopes: ["turn", "session"] }, { type: "deny", label: "Deny all" }], availableScopes: ["turn", "session"], requestedPermissions: { fileSystem: { write: ["/workspace/one", "/workspace/two"] }, network: { enabled: true } } }} connected onRespond={respond} />);
    const grant = screen.getByRole("button", { name: "Grant selected" });
    expect(grant).toBeDisabled();
    expect(screen.getAllByRole("checkbox").every((input) => !(input as HTMLInputElement).checked)).toBe(true);
    fireEvent.click(screen.getByLabelText("Write: /workspace/one"));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "session" } });
    expect(grant).toBeEnabled();
    fireEvent.click(grant);
    await waitFor(() => expect(respond).toHaveBeenCalledWith("apr-safe", {
      type: "grant",
      scope: "session",
      permissions: { fileSystem: { write: ["/workspace/one"] } },
    }));
  });

  it("shows unsupported and resolved-elsewhere states honestly", () => {
    const { rerender } = render(<ApprovalCard approval={{ ...base, type: "unsupportedInput", title: "User input required", unsupportedMessage: "This request type is not yet supported in Foreman.", availableDecisions: [] }} connected onRespond={vi.fn()} />);
    expect(screen.getByText(/Open another compatible Codex client/)).toBeInTheDocument();
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    rerender(<ApprovalCard approval={{ ...base, status: "resolved", resolution: "resolvedElsewhere" }} connected onRespond={vi.fn()} />);
    expect(screen.getByText("Already resolved in another client.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Allow once" })).toBeDisabled();
  });
});
