import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
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
  const completeRelease = {
    version: "1.0.0",
    tag: "v1.0.0",
    title: "Foreman 1.0.0",
    publishedAt: "2026-08-29T04:47:19Z",
    releaseNotesUrl: "https://github.com/mkaltner/foreman/releases/tag/v1.0.0",
    artifactAvailable: true,
  };
  const releaseUpdates = {
    observedAt: "2026-08-30T00:00:00Z",
    stale: false,
    refreshStatus: "idle" as const,
    components: {
      server: { supportedRelease: completeRelease, newestRelease: completeRelease },
      android: { supportedRelease: completeRelease, newestRelease: completeRelease },
    },
  };
  it("keeps client build information visible while disconnected", () => {
    render(<AboutSection serverVersion={null} connected={false} />);

    expect(screen.getByText("Last connected server")).toBeInTheDocument();
    expect(screen.getByText("Unavailable")).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^${WEB_CLIENT_VERSION.replaceAll(".", "\\.")}`))).toBeInTheDocument();
  });

  it("labels a retained server version as last connected after disconnect", () => {
    render(<AboutSection serverVersion="1.0.1" connected={false} />);

    expect(screen.getByText("Last connected server").nextElementSibling).toHaveTextContent("1.0.1");
  });

  it("clearly labels differing server and web client versions", () => {
    render(<AboutSection serverVersion="0.9.0" connected />);

    expect(screen.getByText("Installed server").nextElementSibling).toHaveTextContent("0.9.0");
    expect(screen.getByText("Build").nextElementSibling).toHaveTextContent(WEB_CLIENT_VERSION);
  });

  it("distinguishes development builds from official release builds", () => {
    expect(clientBuildDescription("1.0.2", "abc123def456", false)).toBe(
      "1.0.2 (development build) · abc123def456",
    );
    expect(clientBuildDescription("1.0.2", "abc123def456", true)).toBe(
      "1.0.2 · abc123def456",
    );
  });

  it("links an available server update only to official release notes", () => {
    render(
      <AboutSection
        serverVersion="0.9.0"
        serverReleaseBuild
        connected
        releaseUpdates={releaseUpdates}
      />,
    );

    expect(screen.getByText("Update available · 1.0.0")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Release notes for v1.0.0" })).toHaveAttribute(
      "href",
      completeRelease.releaseNotesUrl,
    );
    expect(screen.getByRole("link", { name: "Release notes for v1.0.0" })).toHaveAttribute(
      "rel",
      "noreferrer noopener",
    );
  });

  it("marks cached offline release information stale without blocking About", () => {
    render(
      <AboutSection
        serverVersion="1.0.0"
        serverReleaseBuild
        connected={false}
        releaseUpdates={{ ...releaseUpdates, stale: true, refreshStatus: "unavailable" }}
        onCheckAgain={vi.fn(async () => undefined)}
      />,
    );

    expect(screen.getByText(/Cached release information from/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Check again" })).toBeDisabled();
    expect(screen.getByText("Foreman server and bundled web")).toBeInTheDocument();
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
