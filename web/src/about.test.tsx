import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  AboutSection,
  FOREMAN_LICENSE_URL,
  FOREMAN_RELEASES_URL,
  FOREMAN_REPOSITORY_URL,
  FOREMAN_THIRD_PARTY_NOTICES_URL,
  WEB_CLIENT_VERSION,
  clientBuildDescription,
} from "./about";

describe("AboutSection", () => {
  it("keeps client build information visible while disconnected", () => {
    render(<AboutSection serverVersion={null} connected={false} />);

    expect(screen.getByText("Unavailable while disconnected")).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^${WEB_CLIENT_VERSION.replaceAll(".", "\\.")}`))).toBeInTheDocument();
  });

  it("does not present a retained server version as current after disconnect", () => {
    render(<AboutSection serverVersion="1.0.1" connected={false} />);

    expect(screen.getByText("Server").nextElementSibling).toHaveTextContent(
      "Unavailable while disconnected",
    );
    expect(screen.queryByText("1.0.1")).not.toBeInTheDocument();
  });

  it("clearly labels differing server and web client versions", () => {
    render(<AboutSection serverVersion="0.9.0" connected />);

    expect(screen.getByText("Server").nextElementSibling).toHaveTextContent("0.9.0");
    expect(screen.getByText("Web client").nextElementSibling).toHaveTextContent(WEB_CLIENT_VERSION);
  });

  it("distinguishes development builds from official release builds", () => {
    expect(clientBuildDescription("1.0.2", "abc123def456", false)).toBe(
      "1.0.2 (development build) · abc123def456",
    );
    expect(clientBuildDescription("1.0.2", "abc123def456", true)).toBe(
      "1.0.2 · abc123def456",
    );
  });

  it.each([
    ["GitHub repository", FOREMAN_REPOSITORY_URL],
    ["Current releases", FOREMAN_RELEASES_URL],
    ["License", FOREMAN_LICENSE_URL],
    ["Third-party notices", FOREMAN_THIRD_PARTY_NOTICES_URL],
  ])("uses the expected %s target", (name, href) => {
    render(<AboutSection serverVersion={null} connected={false} />);
    expect(screen.getByRole("link", { name })).toHaveAttribute("href", href);
  });
});
