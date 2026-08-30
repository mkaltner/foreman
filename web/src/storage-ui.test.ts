import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  addStoredHost,
  createStoredHost,
  forgetStoredHost,
  hostIdFromUrl,
  clearHostNotificationOverride,
  clearRememberedSession,
  loadAppearance,
  loadDashboardPreferences,
  loadHostRegistry,
  loadNotificationsEnabled,
  loadHostNotificationOverride,
  loadNotificationPreferences,
  loadSessionOrganization,
  loadRememberedSession,
  loadCollapsedRepositories,
  saveAppearance,
  saveDashboardPreferences,
  saveHostRegistry,
  saveNotificationsEnabled,
  saveNotificationPreferences,
  saveSessionOrganization,
  saveRememberedSession,
  saveCollapsedRepositories,
  suggestedHostDisplayName,
  updateStoredHost,
  withHostInSearch,
} from "./storage";
import {
  confirmSessionAction,
  createSubmissionGuard,
  isNearBottom,
  linkifyPlainText,
  parseAssistantContent,
  parseWebRoute,
  reasoningDescription,
  reasoningLabel,
  webRoutePath,
} from "./ui";

describe("storage, appearance, and interaction helpers", () => {
  beforeEach(() => localStorage.clear());

  it("stores isolated hosts and forgets only the selected host", () => {
    const home = createStoredHost({ displayName: "Home", host: "home.local", webPort: 8766, deviceToken: "fmt_home" });
    const work = createStoredHost({ displayName: "Work", host: "work.local", webPort: 9766, deviceToken: "fmt_work" });
    let registry = addStoredHost({ hosts: [], activeHostId: null }, home);
    registry = addStoredHost(registry, work);
    saveHostRegistry(registry);
    saveCollapsedRepositories(new Set(["/projects/work"]), work.id);
    expect(loadHostRegistry().hosts.map(({ displayName }) => displayName)).toEqual(["Home", "Work"]);
    expect(localStorage.getItem("foreman.hosts.v2")).not.toContain("pairingKey");
    registry = forgetStoredHost(registry, work.id);
    saveHostRegistry(registry);
    expect(loadHostRegistry().hosts).toHaveLength(1);
    expect(loadHostRegistry().hosts[0].deviceToken).toBe("fmt_home");
    expect(loadCollapsedRepositories(work.id)).toEqual(new Set());
  });

  it("stores bounded provider-aware last sessions per host and removes them when forgotten", () => {
    const home = createStoredHost({ displayName: "Home", host: "home.local", webPort: 8766, deviceToken: "fmt_home" });
    const work = createStoredHost({ displayName: "Work", host: "work.local", webPort: 9766, deviceToken: "fmt_work" });
    let registry = addStoredHost({ hosts: [], activeHostId: null }, home);
    registry = addStoredHost(registry, work);
    saveRememberedSession({ hostId: home.id, provider: "codex", sessionId: "home-thread" });
    saveRememberedSession({ hostId: work.id, provider: "claude-code", sessionId: "work-thread" });

    expect(loadRememberedSession(home.id)?.sessionId).toBe("home-thread");
    expect(loadRememberedSession(work.id)).toMatchObject({ provider: "claude-code", sessionId: "work-thread" });
    registry = forgetStoredHost(registry, home.id);
    expect(loadRememberedSession(home.id)).toBeNull();
    expect(loadRememberedSession(work.id)?.sessionId).toBe("work-thread");

    clearRememberedSession(work.id);
    expect(loadRememberedSession(work.id)).toBeNull();
    saveRememberedSession({ hostId: work.id, provider: "codex", sessionId: "x".repeat(1001) });
    expect(loadRememberedSession(work.id)).toBeNull();
  });

  it("does not resurrect a forgotten host when a stale socket update arrives", () => {
    const host = createStoredHost({ displayName: "Home", host: "home.local", webPort: 8766, deviceToken: "fmt_home" });
    const paired = addStoredHost({ hosts: [], activeHostId: null }, host);
    const forgotten = forgetStoredHost(paired, host.id);
    saveHostRegistry(forgotten);

    const afterStaleDisconnect = updateStoredHost(forgotten, host.id, {
      lastKnownStatus: "disconnected",
    });
    expect(afterStaleDisconnect).toBe(forgotten);
    saveHostRegistry(afterStaleDisconnect);
    expect(loadHostRegistry()).toEqual({ hosts: [], activeHostId: null });
    expect(localStorage.getItem("foreman.hosts.v2")).not.toContain("fmt_home");
  });

  it("migrates the prior single-host record and its local preferences", () => {
    localStorage.setItem("foreman.host.v1", JSON.stringify({ host: "old.local", port: 8766, deviceName: "Browser", deviceToken: "fmt_old" }));
    localStorage.setItem("foreman.notifications.v1", "true");
    const registry = loadHostRegistry();
    expect(registry.hosts).toHaveLength(1);
    expect(registry.hosts[0]).toMatchObject({ host: "old.local", webPort: 8766, isDefault: true });
    expect(loadNotificationsEnabled(registry.hosts[0].id)).toBe(true);
    expect(localStorage.getItem("foreman.host.v1")).toBeNull();
  });

  it("separates the legacy browser device name from the host display name", () => {
    localStorage.setItem("foreman.host.v1", JSON.stringify({ host: "localhost", port: 8766, deviceName: "Web browser", deviceToken: "fmt_old" }));
    expect(loadHostRegistry().hosts[0].displayName).toBe("Local Foreman");

    const migrated = createStoredHost({ displayName: "Web browser", host: "workstation.local", webPort: 8766, deviceToken: "fmt_migrated" }, true);
    saveHostRegistry({ hosts: [migrated], activeHostId: migrated.id });
    expect(loadHostRegistry().hosts[0].displayName).toBe("workstation.local");
    expect(localStorage.getItem("foreman.hosts.v2")).toContain('"displayName":"workstation.local"');
  });

  it("suggests a local label or the endpoint hostname for new hosts", () => {
    expect(suggestedHostDisplayName("localhost")).toBe("Local Foreman");
    expect(suggestedHostDisplayName("http://127.0.0.1")).toBe("Local Foreman");
    expect(suggestedHostDisplayName("workstation.local")).toBe("workstation.local");
  });

  it("persists color mode and curated theme with safe defaults", () => {
    expect(loadAppearance()).toEqual({ colorMode: "system", themeId: "foreman", activityDetail: "focused", groupSessionsByRepository: true });
    saveAppearance({ colorMode: "dark", themeId: "harbor", activityDetail: "full", groupSessionsByRepository: false });
    expect(loadAppearance()).toEqual({ colorMode: "dark", themeId: "harbor", activityDetail: "full", groupSessionsByRepository: false });
    expect(localStorage.getItem("foreman.appearance.v2")).toContain('"version":2');
    expect(localStorage.getItem("foreman.appearance.v2")).not.toContain("accent");

    localStorage.setItem("foreman.appearance.v2", '{"version":2,"colorMode":"light","themeId":"chartreuse","activityDetail":"full","groupSessionsByRepository":false}');
    expect(loadAppearance()).toEqual({ colorMode: "light", themeId: "foreman", activityDetail: "full", groupSessionsByRepository: false });
    localStorage.setItem("foreman.appearance.v2", "not-json");
    expect(loadAppearance()).toEqual({ colorMode: "system", themeId: "foreman", activityDetail: "focused", groupSessionsByRepository: true });
  });

  it("migrates every legacy accent deterministically without losing unrelated appearance settings", () => {
    const migrations = {
      purple: "foreman",
      blue: "harbor",
      teal: "harbor",
      green: "grove",
      orange: "ember",
      red: "ember",
      pink: "ember",
    } as const;
    for (const [accent, themeId] of Object.entries(migrations)) {
      localStorage.clear();
      localStorage.setItem("foreman.appearance.v1.host-a", JSON.stringify({
        theme: "dark",
        accent,
        activityDetail: "full",
        groupSessionsByRepository: false,
      }));
      expect(loadAppearance("host-a")).toEqual({
        colorMode: "dark",
        themeId,
        activityDetail: "full",
        groupSessionsByRepository: false,
      });
      expect(localStorage.getItem("foreman.appearance.v1.host-a")).toBeNull();
      expect(localStorage.getItem("foreman.appearance.v2.host-a")).toContain(`"themeId":"${themeId}"`);
    }

    localStorage.clear();
    localStorage.setItem("foreman.appearance.v1.host-a", '{"theme":4,"accent":[],"activityDetail":{},"groupSessionsByRepository":"bad"}');
    expect(loadAppearance("host-a")).toEqual({ colorMode: "system", themeId: "foreman", activityDetail: "focused", groupSessionsByRepository: true });
  });

  it("keeps appearance isolated across host switches and removes it with a forgotten host", () => {
    saveAppearance({ colorMode: "light", themeId: "grove", activityDetail: "focused", groupSessionsByRepository: true }, "home");
    saveAppearance({ colorMode: "dark", themeId: "ember", activityDetail: "full", groupSessionsByRepository: false }, "work");
    expect(loadAppearance("home").themeId).toBe("grove");
    expect(loadAppearance("work").themeId).toBe("ember");

    const home = createStoredHost({ displayName: "Home", host: "home.local", webPort: 8766, deviceToken: "fmt_home" });
    const registry = { hosts: [{ ...home, id: "home" }], activeHostId: "home" };
    forgetStoredHost(registry, "home");
    expect(localStorage.getItem("foreman.appearance.v2.home")).toBeNull();
    expect(loadAppearance("work").themeId).toBe("ember");
  });

  it("persists bounded browser-local dashboard presentation choices", () => {
    expect(loadDashboardPreferences()).toEqual({
      filter: "all",
      repository: "",
      dismissedFailures: [],
    });
    saveDashboardPreferences({
      filter: "failed",
      repository: "/projects/foreman",
      dismissedFailures: ["failed-1"],
    });
    expect(loadDashboardPreferences()).toEqual({
      filter: "failed",
      repository: "/projects/foreman",
      dismissedFailures: ["failed-1"],
    });
  });

  it("persists browser-local pins and hidden sessions", () => {
    saveSessionOrganization({ pinnedIds: ["one", "one", "two"], hiddenIds: ["noise"] });
    expect(loadSessionOrganization()).toEqual({ pinnedIds: ["one", "two"], hiddenIds: ["noise"] });
    expect(localStorage.getItem("foreman.session-organization.v1")).not.toContain("transcript");
  });

  it("namespaces local settings by stable host ID", () => {
    saveSessionOrganization({ pinnedIds: ["home-session"], hiddenIds: [] }, "home");
    saveSessionOrganization({ pinnedIds: ["work-session"], hiddenIds: [] }, "work");
    expect(loadSessionOrganization("home").pinnedIds).toEqual(["home-session"]);
    expect(loadSessionOrganization("work").pinnedIds).toEqual(["work-session"]);
  });

  it("restores collapsed repositories after a tab is reopened and keeps hosts isolated", () => {
    saveCollapsedRepositories(new Set(["/projects/foreman"]), "home");
    saveCollapsedRepositories(new Set(["/projects/android"]), "work");

    expect(loadCollapsedRepositories("home")).toEqual(new Set(["/projects/foreman"]));
    expect(loadCollapsedRepositories("work")).toEqual(new Set(["/projects/android"]));
  });

  it("persists the browser notification preference disabled by default", () => {
    expect(loadNotificationsEnabled()).toBe(false);
    saveNotificationsEnabled(true);
    expect(loadNotificationsEnabled()).toBe(true);
    saveNotificationsEnabled(false);
    expect(loadNotificationsEnabled()).toBe(false);
  });

  it("persists global notification defaults and optional per-host overrides locally", () => {
    const global = { ...loadNotificationPreferences(), notifyInterruptions: true };
    saveNotificationPreferences(global);
    expect(loadNotificationPreferences("home").notifyInterruptions).toBe(true);
    const home = { ...global, notifyCompletions: false };
    saveNotificationPreferences(home, "home");
    expect(loadHostNotificationOverride("home")?.notifyCompletions).toBe(false);
    expect(loadNotificationPreferences("home").notifyCompletions).toBe(false);
    expect(loadNotificationPreferences("work").notifyCompletions).toBe(true);
    saveNotificationPreferences({ ...global, notifyApprovals: false });
    expect(loadNotificationPreferences("home").notifyApprovals).toBe(false);
    expect(loadNotificationPreferences("home").notifyCompletions).toBe(false);
    clearHostNotificationOverride("home");
    expect(loadNotificationPreferences("home")).toEqual({ ...global, notifyApprovals: false });
  });

  it("prevents duplicate submissions until the accepted request finishes", () => {
    const guard = createSubmissionGuard();
    expect(guard.enter()).toBe(true);
    expect(guard.enter()).toBe(false);
    guard.leave();
    expect(guard.enter()).toBe(true);
  });

  it("linkifies only safe bare web URLs and preserves surrounding punctuation", () => {
    expect(linkifyPlainText("Open https://example.com/docs, then javascript:alert(1)."))
      .toEqual([
        { text: "Open " },
        { text: "https://example.com/docs", href: "https://example.com/docs" },
        { text: "," },
        { text: " then javascript:alert(1)." },
      ]);
    expect(linkifyPlainText("See https://en.wikipedia.org/wiki/Foreman_(software)."))
      .toEqual([
        { text: "See " },
        {
          text: "https://en.wikipedia.org/wiki/Foreman_(software)",
          href: "https://en.wikipedia.org/wiki/Foreman_(software)",
        },
        { text: "." },
      ]);
  });

  it("tracks bottom proximity and requires confirmation for archive and permanent delete", () => {
    expect(isNearBottom(900, 100, 1050)).toBe(true);
    expect(isNearBottom(500, 100, 1050)).toBe(false);
    const confirm = vi.fn((_message: string) => true);
    expect(confirmSessionAction("archive", "Alpha", confirm)).toBe(true);
    expect(confirmSessionAction("delete", "Alpha", confirm)).toBe(true);
    expect(confirm.mock.calls[1][0]).toContain("cannot be undone");
  });

  it("uses Android-style reasoning labels and warns about Ultra usage", () => {
    expect(reasoningLabel("low")).toBe("Light");
    expect(reasoningLabel("xhigh")).toBe("Extra High");
    expect(reasoningDescription("high")).toBeUndefined();
    expect(reasoningDescription("ultra")).toBe("Consumes usage limits faster");
  });

  it("separates app directives from assistant Markdown", () => {
    const content = parseAssistantContent([
      "Validation passed.",
      "",
      '::git-commit{cwd="/home/mkaltner/projects/foreman"}',
      '::git-push{cwd="/home/mkaltner/projects/foreman" branch="codex/web"}',
    ].join("\n"));

    expect(content).toEqual([
      { kind: "markdown", text: "Validation passed.\n" },
      { kind: "directive", directive: { name: "git-commit", attributes: { cwd: "/home/mkaltner/projects/foreman" } } },
      { kind: "directive", directive: { name: "git-push", attributes: { cwd: "/home/mkaltner/projects/foreman", branch: "codex/web" } } },
    ]);
  });

  it("preserves unsupported directives as ordinary Markdown", () => {
    expect(parseAssistantContent('::unknown{value="safe"}')).toEqual([
      { kind: "markdown", text: '::unknown{value="safe"}' },
    ]);
  });

  it("uses sessions as the default and round-trips all browser routes", () => {
    expect(parseWebRoute("/")).toEqual({ view: "sessions" });
    expect(parseWebRoute("/hosts")).toEqual({ view: "dashboard" });
    expect(parseWebRoute("/dashboard")).toEqual({ view: "dashboard" });
    expect(parseWebRoute("/settings")).toEqual({ view: "settings" });
    expect(parseWebRoute("/sessions/thread%2Fone")).toEqual({ view: "detail", provider: "codex", sessionId: "thread/one" });
    expect(parseWebRoute("/sessions/claude-code/thread%2Fone")).toEqual({ view: "detail", provider: "claude-code", sessionId: "thread/one" });
    expect(parseWebRoute("/sessions")).toEqual({ view: "sessions" });
    expect(parseWebRoute("/not-a-route")).toEqual({ view: "sessions" });
    expect(webRoutePath({ view: "dashboard" })).toBe("/dashboard");
    expect(webRoutePath({ view: "sessions" })).toBe("/sessions");
    expect(webRoutePath({ view: "detail", provider: "claude-code", sessionId: "thread/one" }))
      .toBe("/sessions/claude-code/thread%2Fone");
    const deepLink = `${webRoutePath({ view: "detail", provider: "codex", sessionId: "same" })}${withHostInSearch("", "host-work")}`;
    expect(deepLink).toBe("/sessions/codex/same?host=host-work");
    expect(parseWebRoute(new URL(deepLink, "https://foreman.local").pathname))
      .toEqual({ view: "detail", provider: "codex", sessionId: "same" });
    expect(hostIdFromUrl(new URL(deepLink, "https://foreman.local").search)).toBe("host-work");
  });
});
