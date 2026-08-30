import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import {
  AboutSection,
  FOREMAN_LICENSE_URL,
  FOREMAN_RELEASES_URL,
  FOREMAN_REPOSITORY_URL,
  FOREMAN_THIRD_PARTY_NOTICES_URL,
  WEB_CLIENT_VERSION,
} from "./about";

describe("AboutSection", () => {
  it("keeps client build information visible while disconnected", () => {
    render(<AboutSection serverVersion={null} connected={false} />);

    expect(screen.getByText("Unavailable while disconnected")).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^${WEB_CLIENT_VERSION.replaceAll(".", "\\.")}`))).toBeInTheDocument();
  });

  it("clearly labels differing server and web client versions", () => {
    render(<AboutSection serverVersion="0.9.0" connected />);

    expect(screen.getByText("Server").nextElementSibling).toHaveTextContent("0.9.0");
    expect(screen.getByText("Web client").nextElementSibling).toHaveTextContent(WEB_CLIENT_VERSION);
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
