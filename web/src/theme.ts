import type { Appearance } from "./storage";

export function resolvedTheme(
  theme: Appearance["theme"],
  prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches,
): "light" | "dark" {
  return theme === "system" ? (prefersDark ? "dark" : "light") : theme;
}

export function applyAppearance(appearance: Appearance): () => void {
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const apply = () => {
    document.documentElement.dataset.theme = resolvedTheme(appearance.theme, media.matches);
    document.documentElement.dataset.accent = appearance.accent;
    document.documentElement.style.colorScheme = resolvedTheme(appearance.theme, media.matches);
  };
  apply();
  media.addEventListener("change", apply);
  return () => media.removeEventListener("change", apply);
}
