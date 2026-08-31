import { fireEvent, render, screen } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { ConversationView } from "./App";
import type { SessionSummary } from "./protocol";

const renderCounts = vi.hoisted(() => ({
  markdown: vi.fn(),
  userText: vi.fn(),
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

function DraftHarness({ session }: { session: SessionSummary }) {
  const [draft, setDraft] = useState("");
  return <ConversationView
    session={session}
    approvals={[]}
    models={[]}
    accessLevels={[]}
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
    const messages: NonNullable<SessionSummary["messages"]> = Array.from(
      { length: 100 },
      (_, index) => index % 2 === 0
        ? { id: `assistant-${index}`, kind: "assistant", text: `Assistant response ${index}` }
        : { id: `user-${index}`, kind: "user", text: `User prompt ${index}` },
    );
    const session: SessionSummary = {
      id: "long-transcript",
      repository: "/projects/foreman",
      title: "Long transcript",
      status: "idle",
      messages,
    };
    const view = render(<DraftHarness session={session} />);
    const textbox = screen.getByRole("textbox");

    expect(renderCounts.markdown).toHaveBeenCalledTimes(50);
    expect(renderCounts.userText).toHaveBeenCalledTimes(50);

    for (let index = 1; index <= 30; index += 1) {
      fireEvent.change(textbox, { target: { value: "x".repeat(index) } });
    }

    expect(textbox).toHaveValue("x".repeat(30));
    expect(renderCounts.markdown).toHaveBeenCalledTimes(50);
    expect(renderCounts.userText).toHaveBeenCalledTimes(50);

    const appended: SessionSummary = {
      ...session,
      messages: [...messages, { id: "fresh-answer", kind: "assistant", text: "Fresh transcript answer" }],
    };
    view.rerender(<DraftHarness session={appended} />);

    expect(screen.getByText("Fresh transcript answer")).toBeInTheDocument();
    expect(renderCounts.markdown).toHaveBeenCalledTimes(51);
    expect(renderCounts.userText).toHaveBeenCalledTimes(50);

    const updated: SessionSummary = {
      ...appended,
      messages: appended.messages?.map((item) => item.id === "user-1"
        ? { ...item, text: "Updated user transcript item" }
        : item),
    };
    view.rerender(<DraftHarness session={updated} />);

    expect(screen.getByText("Updated user transcript item")).toBeInTheDocument();
    expect(renderCounts.markdown).toHaveBeenCalledTimes(51);
    expect(renderCounts.userText).toHaveBeenCalledTimes(51);
  });
});
