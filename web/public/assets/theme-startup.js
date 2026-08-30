(() => {
  const root = document.documentElement;
  const fallback = { colorMode: "system", themeId: "foreman" };
  try {
    const registry = JSON.parse(localStorage.getItem("foreman.hosts.v2") || "null");
    const requestedHost = new URLSearchParams(location.search).get("host");
    const hostId = registry?.hosts?.some((host) => host.id === requestedHost)
      ? requestedHost
      : registry?.activeHostId;
    const suffix = hostId ? `.${hostId}` : "";
    const current = JSON.parse(localStorage.getItem(`foreman.appearance.v2${suffix}`) || "null");
    const legacy = JSON.parse(localStorage.getItem(`foreman.appearance.v1${suffix}`) || "null");
    const legacyThemes = { purple: "foreman", blue: "harbor", teal: "harbor", green: "grove", orange: "ember", red: "ember", pink: "ember" };
    const colorMode = current?.version === 2 && ["system", "light", "dark"].includes(current?.colorMode)
      ? current.colorMode
      : ["system", "light", "dark"].includes(legacy?.theme) ? legacy.theme : fallback.colorMode;
    const themeId = current?.version === 2 && ["foreman", "harbor", "grove", "ember"].includes(current?.themeId)
      ? current.themeId
      : legacyThemes[legacy?.accent] || fallback.themeId;
    const resolved = colorMode === "system"
      ? matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
      : colorMode;
    root.dataset.colorMode = resolved;
    root.dataset.foremanTheme = themeId;
    root.style.colorScheme = resolved;
    const chromeColors = {
      foreman: { light: "#6b3fb5", dark: "#1d1926" },
      harbor: { light: "#006b75", dark: "#142226" },
      grove: { light: "#356a3f", dark: "#19231a" },
      ember: { light: "#8a3d61", dark: "#25191e" },
    };
    document.querySelector('meta[name="theme-color"]').content = chromeColors[themeId][resolved];
  } catch {
    const resolved = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    root.dataset.colorMode = resolved;
    root.dataset.foremanTheme = fallback.themeId;
    root.style.colorScheme = resolved;
    document.querySelector('meta[name="theme-color"]').content = resolved === "dark" ? "#1d1926" : "#6b3fb5";
  }
})();
