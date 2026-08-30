import type { Appearance } from "./storage";

const THEME_CHROME_COLORS: Record<Appearance["themeId"], { light: string; dark: string }> = {
  foreman: { light: "#6b3fb5", dark: "#1d1926" },
  harbor: { light: "#006b75", dark: "#142226" },
  grove: { light: "#356a3f", dark: "#19231a" },
  ember: { light: "#8a3d61", dark: "#25191e" },
};

export function resolvedTheme(
  colorMode: Appearance["colorMode"],
  prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches,
): "light" | "dark" {
  return colorMode === "system" ? (prefersDark ? "dark" : "light") : colorMode;
}

export function applyAppearance(appearance: Appearance): () => void {
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const apply = () => {
    const mode = resolvedTheme(appearance.colorMode, media.matches);
    document.documentElement.dataset.colorMode = mode;
    document.documentElement.dataset.foremanTheme = appearance.themeId;
    document.documentElement.style.colorScheme = mode;
    document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
      ?.setAttribute("content", THEME_CHROME_COLORS[appearance.themeId][mode]);
  };
  apply();
  media.addEventListener("change", apply);
  return () => media.removeEventListener("change", apply);
}
