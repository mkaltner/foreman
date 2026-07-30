import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HostOperations, diagnosticsText, restartPhaseAfterConnection } from "./HostOperations";
import type { DiagnosticEvent } from "./protocol";

const events: DiagnosticEvent[] = [{
  timestamp: "2026-07-30T12:00:00+00:00",
  severity: "warning",
  category: "request.failed",
  message: "Request category failed",
  requestCategory: "service",
}];

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("sanitized host operations", () => {
  it("refreshes and copies the bounded diagnostic projection", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    render(<HostOperations connection="connected" disabled={false} remoteRestartEnabled={false} fetchDiagnostics={vi.fn().mockResolvedValue(events)} scheduleRestart={vi.fn()} />);

    fireEvent.click(screen.getByText(/Diagnostics/));
    await waitFor(() => expect(screen.getByText("request.failed")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(diagnosticsText(events)));
    expect(screen.getByText("Remote restart is disabled on this host.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restart Foreman" })).toBeDisabled();
  });

  it("reports success only after disconnect and automatic reconnect", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const scheduleRestart = vi.fn().mockResolvedValue({ scheduled: true, timeoutSeconds: 45 });
    const props = { disabled: false, remoteRestartEnabled: true, fetchDiagnostics: vi.fn().mockResolvedValue([]), scheduleRestart };
    const view = render(<HostOperations {...props} connection="connected" />);

    fireEvent.click(screen.getByRole("button", { name: "Restart Foreman" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("waiting for Foreman to stop"));
    expect(screen.getByRole("status")).not.toHaveTextContent("complete");

    view.rerender(<HostOperations {...props} connection="reconnecting" />);
    expect(screen.getByRole("status")).toHaveTextContent("reconnecting");
    view.rerender(<HostOperations {...props} connection="connected" />);
    expect(screen.getByRole("status")).toHaveTextContent("Restart complete");
  });

  it("moves a scheduled restart to timeout without claiming success", async () => {
    vi.useFakeTimers();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<HostOperations connection="connected" disabled={false} remoteRestartEnabled fetchDiagnostics={vi.fn().mockResolvedValue([])} scheduleRestart={vi.fn().mockResolvedValue({ scheduled: true, timeoutSeconds: 1 })} />);
    fireEvent.click(screen.getByRole("button", { name: "Restart Foreman" }));
    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("waiting for Foreman to stop"));
    act(() => vi.advanceTimersByTime(1_001));
    expect(screen.getByRole("status")).toHaveTextContent("timed out");
    expect(screen.getByRole("status")).not.toHaveTextContent("complete");
  });

  it("disables restart while sessions need uninterrupted attention", () => {
    const scheduleRestart = vi.fn();
    render(<HostOperations connection="connected" disabled={false} remoteRestartEnabled restartBlocked fetchDiagnostics={vi.fn().mockResolvedValue([])} scheduleRestart={scheduleRestart} />);

    expect(screen.getByRole("button", { name: "Restart Foreman" })).toBeDisabled();
    expect(screen.getByText("Restart is unavailable while sessions are active or waiting for attention.")).toBeInTheDocument();
    expect(scheduleRestart).not.toHaveBeenCalled();
  });

  it("requires a disconnected phase before success", () => {
    expect(restartPhaseAfterConnection("scheduled", "connected")).toBe("scheduled");
    expect(restartPhaseAfterConnection("scheduled", "reconnecting")).toBe("reconnecting");
    expect(restartPhaseAfterConnection("reconnecting", "connected")).toBe("succeeded");
  });
});
