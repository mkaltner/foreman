import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { appShellClassName, ConversationView, LinkedUserText, RouteSelect, SetupView } from "./App";
import type { SessionSummary } from "./protocol";
import { inferPagePort } from "./client";

describe("Foreman setup", () => {
  it("captures a display name and explicit web port for each host", () => {
    const onConnect = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<SetupView error="" busy={false} onConnect={onConnect} />);

    expect(screen.getByLabelText("Web port")).toHaveValue(String(inferPagePort()));
    expect([...container.querySelectorAll("form label")].map((label) => label.firstChild?.textContent))
      .toEqual(["Host", "Web port", "Host display name", "Pairing code", "Device name"]);
    fireEvent.change(screen.getByLabelText("Host"), {
      target: { value: "foreman.local" },
    });
    fireEvent.change(screen.getByLabelText("Pairing code"), {
      target: { value: "123456" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Connect" }));

    expect(onConnect).toHaveBeenCalledWith(
      {
        displayName: "foreman.local",
        host: "foreman.local",
        tcpPort: 8765,
        webPort: inferPagePort(),
        deviceName: "Web browser",
      },
      "123456",
    );
  });
});

describe("page scrolling", () => {
  it("uses a content-height shell only for settings", () => {
    expect(appShellClassName("settings")).toBe("app-shell settings-shell");
    expect(appShellClassName("dashboard")).toBe("app-shell");
    expect(appShellClassName("sessions")).toBe("app-shell");
    expect(appShellClassName("detail")).toBe("app-shell");
  });
});

describe("user message links", () => {
  it("opens safe bare links without interpreting other message text as markup", () => {
    render(<LinkedUserText text={'Review <b>literal</b> at https://example.com/pr/11.'} />);
    expect(screen.getByText("<b>literal</b>", { exact: false })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "https://example.com/pr/11" });
    expect(link).toHaveAttribute("href", "https://example.com/pr/11");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer noopener");
  });
});

describe("route selector", () => {
  it("renders a themed option menu and changes the selected route", () => {
    const onChange = vi.fn();
    render(
      <RouteSelect
        label="Access"
        value="full"
        options={[
          { value: "ask", label: "Ask for approval", description: "Always ask first" },
          { value: "full", label: "Full access", description: "Unrestricted", warning: true },
        ]}
        disabled={false}
        onChange={onChange}
      />,
    );

    const trigger = screen.getByRole("button", { name: "Access: Full access" });
    expect(trigger).toHaveClass("warning");
    expect(screen.getByText("Access")).not.toHaveClass("warning");
    fireEvent.click(trigger);
    expect(screen.getByRole("listbox", { name: "Access options" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: /Ask for approval/ }));
    expect(onChange).toHaveBeenCalledWith("ask");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
});

describe("conversation drafts", () => {
  const session = (id: string): SessionSummary => ({
    id,
    repository: "/projects/foreman",
    title: `Session ${id}`,
    status: "idle",
    messages: [],
  });

  const renderConversation = (selected: SessionSummary, draft: string, onDraftChange = vi.fn()) => (
    <ConversationView
      session={selected}
      approvals={[]}
      models={[]}
      accessLevels={[]}
      connected
      highlightItemId={null}
      focusedApprovalId={null}
      draft={draft}
      onDraftChange={onDraftChange}
      onBack={vi.fn()}
      onRequest={vi.fn().mockResolvedValue({})}
      onError={vi.fn()}
    />
  );

  it("restores the controlled draft after tab and session changes", () => {
    const firstChange = vi.fn();
    const view = render(renderConversation(session("one"), "First session draft", firstChange));
    expect(screen.getByRole("textbox")).toHaveValue("First session draft");

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "First session edited" } });
    expect(firstChange).toHaveBeenCalledWith("First session edited");

    view.rerender(renderConversation(session("two"), "Second session draft"));
    expect(screen.getByRole("textbox")).toHaveValue("Second session draft");

    view.unmount();
    render(renderConversation(session("one"), "First session edited"));
    expect(screen.getByRole("textbox")).toHaveValue("First session edited");
  });
});
