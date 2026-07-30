import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { LinkedUserText, RouteSelect, SetupView } from "./App";
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
