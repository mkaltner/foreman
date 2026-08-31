import { fireEvent, render, screen } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { ConversationView } from "./App";
import type { AccessLevelInfo, ApprovalRequest, ConversationItem, InputRequest, ModelInfo, SessionSummary } from "./protocol";

const renderCounts = vi.hoisted(() => ({
  markdown: vi.fn(),
  userText: vi.fn(),
  activityKind: vi.fn(),
}));

vi.mock("react-markdown", () => ({
  default: ({ children }: { children: ReactNode }) => {
    renderCounts.markdown();
    return <>{children}</>;
  },
}));

vi.mock("./ui", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./ui")>();
  return {
    ...actual,
    linkifyPlainText: (text: string) => {
      renderCounts.userText();
      return actual.linkifyPlainText(text);
    },
  };
});

const onRequest = vi.fn().mockResolvedValue({});
const noOp = () => undefined;
const noApprovals: ApprovalRequest[] = [];
const noInputs: InputRequest[] = [];
const noModels: ModelInfo[] = [];
const noAccessLevels: AccessLevelInfo[] = [];

function DraftHarness({ session }: { session: SessionSummary }) {
  const [draft, setDraft] = useState("");
  return <ConversationView
    session={session}
    approvals={noApprovals}
    inputs={noInputs}
    models={noModels}
    accessLevels={noAccessLevels}
    connected
    highlightItemId={null}
    focusedApprovalId={null}
    draft={draft}
    onDraftChange={setDraft}
    onBack={noOp}
    onRequest={onRequest}
    onError={noOp}
  />;
}

describe("conversation render boundaries", () => {
  it("does not rerender a long unchanged transcript while the draft changes", () => {
    const conversationMessages: NonNullable<SessionSummary["messages"]> = Array.from(
      { length: 80 },
      (_, index) => index % 2 === 0
        ? { id: `assistant-${index}`, kind: "assistant", text: `Assistant response ${index}` }
        : { id: `user-${index}`, kind: "user", text: `User prompt ${index}` },
    );
    const activityMessages: ConversationItem[] = Array.from({ length: 20 }, (_, index) => {
      const kind = index % 2 === 0 ? "command" as const : "tool" as const;
      return {
        id: `activity-${index}`,
        get kind() {
          renderCounts.activityKind();
          return kind;
        },
        description: `Completed activity ${index}`,
        status: "completed",
      };
    });
    const messages = [...conversationMessages, ...activityMessages];
    const session: SessionSummary = {
      id: "long-transcript",
      repository: "/projects/foreman",
      title: "Long transcript",
      status: "idle",
      messages,
    };
    const view = render(<DraftHarness session={session} />);
    const textbox = screen.getByRole("textbox");

    expect(renderCounts.markdown).toHaveBeenCalledTimes(40);
    expect(renderCounts.userText).toHaveBeenCalledTimes(40);
    const initialActivityKindReads = renderCounts.activityKind.mock.calls.length;
    expect(initialActivityKindReads).toBeGreaterThan(0);

    for (let index = 1; index <= 30; index += 1) {
      fireEvent.change(textbox, { target: { value: "x".repeat(index) } });
    }

    expect(textbox).toHaveValue("x".repeat(30));
    expect(renderCounts.markdown).toHaveBeenCalledTimes(40);
    expect(renderCounts.userText).toHaveBeenCalledTimes(40);
    expect(renderCounts.activityKind).toHaveBeenCalledTimes(initialActivityKindReads);

    const appended: SessionSummary = {
      ...session,
      messages: [...messages, { id: "fresh-answer", kind: "assistant", text: "Fresh transcript answer" }],
    };
    view.rerender(<DraftHarness session={appended} />);

    expect(screen.getByText("Fresh transcript answer")).toBeInTheDocument();
    expect(renderCounts.markdown).toHaveBeenCalledTimes(41);
    expect(renderCounts.userText).toHaveBeenCalledTimes(40);

    const updated: SessionSummary = {
      ...appended,
      messages: appended.messages?.map((item) => item.id === "user-1"
        ? { ...item, text: "Updated user transcript item" }
        : item),
    };
    view.rerender(<DraftHarness session={updated} />);

    expect(screen.getByText("Updated user transcript item")).toBeInTheDocument();
    expect(renderCounts.markdown).toHaveBeenCalledTimes(41);
    expect(renderCounts.userText).toHaveBeenCalledTimes(41);
  });
});
