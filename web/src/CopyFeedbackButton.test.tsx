import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { COPY_FEEDBACK_DURATION_MS, CopyFeedbackButton } from "./CopyFeedbackButton";

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
const originalExecCommand = Object.getOwnPropertyDescriptor(document, "execCommand");

function setClipboard(value: { writeText: (text: string) => Promise<void> } | undefined) {
  Object.defineProperty(navigator, "clipboard", { configurable: true, value });
}

function setExecCommand(value: ((command: string) => boolean) | undefined) {
  Object.defineProperty(document, "execCommand", { configurable: true, value });
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  if (originalClipboard) Object.defineProperty(navigator, "clipboard", originalClipboard);
  else Reflect.deleteProperty(navigator, "clipboard");
  if (originalExecCommand) Object.defineProperty(document, "execCommand", originalExecCommand);
  else Reflect.deleteProperty(document, "execCommand");
});

describe("CopyFeedbackButton", () => {
  it("shows an accessible checkmark for 1.5 seconds after a successful copy", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    render(<CopyFeedbackButton text="exact payload" />);

    const copy = screen.getByRole("button", { name: "Copy" });
    expect(copy).toHaveTextContent("Copy");
    await act(async () => fireEvent.click(copy));

    expect(writeText).toHaveBeenCalledWith("exact payload");
    expect(screen.getByRole("button", { name: "Copied" })).toHaveTextContent("✓");
    expect(screen.getByRole("status")).toHaveTextContent("Copied");
    act(() => vi.advanceTimersByTime(COPY_FEEDBACK_DURATION_MS - 1));
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole("button", { name: "Copy" })).toHaveTextContent("Copy");
  });

  it("shows bounded failure feedback without ever showing a success checkmark", async () => {
    vi.useFakeTimers();
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error("denied")) });
    setExecCommand(vi.fn().mockReturnValue(false));
    render(<CopyFeedbackButton text="payload" />);

    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copy" })));

    const failed = screen.getByRole("button", { name: "Copy failed" });
    expect(failed).toHaveTextContent("×");
    expect(failed).not.toHaveTextContent("✓");
    expect(screen.getByRole("alert")).toHaveTextContent("Copy failed");
    act(() => vi.advanceTimersByTime(COPY_FEEDBACK_DURATION_MS));
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("restarts the full success timer after a repeated copy", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    render(<CopyFeedbackButton text="payload" />);

    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copy" })));
    act(() => vi.advanceTimersByTime(1_000));
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copied" })));
    expect(writeText).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(COPY_FEEDBACK_DURATION_MS - 1));
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
  });

  it("suppresses overlapping clipboard attempts and exposes Copying", async () => {
    let finishCopy: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finishCopy = resolve; });
    const writeText = vi.fn().mockReturnValue(pending);
    setClipboard({ writeText });
    render(<CopyFeedbackButton text="payload" />);

    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    const copying = screen.getByRole("button", { name: "Copying" });
    expect(copying).toBeDisabled();
    expect(copying).toHaveAttribute("aria-busy", "true");
    fireEvent.click(copying);
    expect(writeText).toHaveBeenCalledTimes(1);

    await act(async () => finishCopy?.());
    expect(screen.getByRole("button", { name: "Copied" })).toBeEnabled();
  });

  it("cancels its reset timer when unmounted", async () => {
    vi.useFakeTimers();
    setClipboard({ writeText: vi.fn().mockResolvedValue(undefined) });
    const view = render(<CopyFeedbackButton text="payload" />);
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copy" })));
    expect(vi.getTimerCount()).toBe(1);

    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("uses the selected-text fallback when the Clipboard API is unavailable", async () => {
    const execCommand = vi.fn().mockImplementation((command: string) => {
      expect(document.querySelector("textarea")).toHaveValue("fallback payload");
      return command === "copy";
    });
    setClipboard(undefined);
    setExecCommand(execCommand);
    render(<CopyFeedbackButton text="fallback payload" variant="icon" />);

    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copy" })));

    expect(execCommand).toHaveBeenCalledWith("copy");
    expect(screen.getByRole("button", { name: "Copied" })).toHaveTextContent("✓");
    expect(document.querySelector("textarea")).not.toBeInTheDocument();
  });

  it("keeps multiple copy controls independent", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    render(<><CopyFeedbackButton text="first" /><CopyFeedbackButton text="second" /></>);

    await act(async () => fireEvent.click(screen.getAllByRole("button", { name: "Copy" })[0]));
    expect(screen.getAllByRole("button", { name: "Copied" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Copy" })).toHaveLength(1);
    act(() => vi.advanceTimersByTime(500));
    await act(async () => fireEvent.click(screen.getByRole("button", { name: "Copy" })));
    expect(screen.getAllByRole("button", { name: "Copied" })).toHaveLength(2);

    act(() => vi.advanceTimersByTime(1_000));
    expect(screen.getAllByRole("button", { name: "Copy" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Copied" })).toHaveLength(1);
    expect(writeText.mock.calls).toEqual([["first"], ["second"]]);
  });
});
