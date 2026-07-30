import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { InputCard } from "./InputCard";
import type { InputRequest } from "./protocol";

const supported: InputRequest = {
  id: "inp-safe",
  sessionId: "thread-safe",
  turnId: "turn-safe",
  source: "mcp",
  title: "MCP input requested",
  message: "Provide bounded values",
  serverName: "example-mcp",
  supported: true,
  canDecline: true,
  canCancel: true,
  createdAt: 1_720_000_000,
  status: "pending",
  fields: [
    { id: "one", type: "singleChoice", label: "One", description: "Choose one", required: true, options: [{ value: "a", label: "Alpha" }, { value: "b", label: "Beta" }] },
    { id: "many", type: "multipleChoice", label: "Many", required: true, minSelections: 1, maxSelections: 2, options: [{ value: "x", label: "X" }, { value: "y", label: "Y" }] },
    { id: "short", type: "shortText", label: "Short", required: true, minLength: 2, maxLength: 10 },
    { id: "long", type: "longText", label: "Long", required: true, minLength: 1, maxLength: 1000 },
    { id: "enabled", type: "boolean", label: "Enabled", required: true },
  ],
};

describe("structured input card", () => {
  it("renders and submits every normalized field without leaking schema details", async () => {
    const respond = vi.fn().mockResolvedValue(undefined);
    render(<InputCard input={supported} connected onRespond={respond} />);
    expect(screen.getByText("Requested by example-mcp")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Beta"));
    fireEvent.click(screen.getByLabelText("X"));
    fireEvent.change(screen.getByLabelText("Short"), { target: { value: "ok" } });
    fireEvent.change(screen.getByLabelText("Long"), { target: { value: "long details" } });
    fireEvent.click(screen.getByLabelText("Yes"));
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    await waitFor(() => expect(respond).toHaveBeenCalledWith("inp-safe", {
      action: "accept",
      values: { one: "b", many: ["x"], short: "ok", long: "long details", enabled: true },
    }));
    expect(screen.getByText("Submitting response…")).toBeInTheDocument();
  });

  it("enforces selection bounds before sending", () => {
    const respond = vi.fn();
    render(<InputCard input={supported} connected onRespond={respond} />);
    fireEvent.click(screen.getByLabelText("Alpha"));
    fireEvent.change(screen.getByLabelText("Short"), { target: { value: "ok" } });
    fireEvent.change(screen.getByLabelText("Long"), { target: { value: "details" } });
    fireEvent.click(screen.getByLabelText("No"));
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Many has an invalid number of selections");
    expect(respond).not.toHaveBeenCalled();
  });

  it("shows unsupported schemas honestly and offers only valid MCP exits", async () => {
    const respond = vi.fn().mockResolvedValue(undefined);
    render(<InputCard input={{ ...supported, supported: false, fields: [], unsupportedMessage: "Numeric schemas are unsupported." }} connected onRespond={respond} />);
    expect(screen.getByText(/Numeric schemas are unsupported/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Decline" }));
    await waitFor(() => expect(respond).toHaveBeenCalledWith("inp-safe", { action: "decline" }));
  });

  it("renders a zero-field MCP elicitation as an Allow confirmation", async () => {
    const respond = vi.fn().mockResolvedValue(undefined);
    render(<InputCard input={{ ...supported, title: "Confirmation requested", message: "Allow GitHub to create a pull request?", fields: [] }} connected onRespond={respond} />);

    expect(screen.getByText("Allow GitHub to create a pull request?")).toBeInTheDocument();
    const allow = screen.getByRole("button", { name: "Allow" });
    expect(allow.parentElement).toHaveClass("confirmation-actions");
    expect(allow.parentElement).toContainElement(screen.getByRole("button", { name: "Decline" }));
    expect(allow.parentElement).toContainElement(screen.getByRole("button", { name: "Cancel" }));
    fireEvent.click(allow);
    await waitFor(() => expect(respond).toHaveBeenCalledWith("inp-safe", {
      action: "accept",
      values: {},
    }));
  });

  it("does not invent decline or cancel for Codex tool questions", () => {
    render(<InputCard input={{ ...supported, source: "codex", canDecline: false, canCancel: false }} connected onRespond={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Decline" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
  });
});
