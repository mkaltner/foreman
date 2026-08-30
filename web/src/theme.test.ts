/// <reference types="node" />
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { CURATED_THEMES, DEFAULT_APPEARANCE, type Appearance } from "./storage";
import { applyAppearance, resolvedTheme } from "./theme";

describe("curated Foreman themes", () => {
  it("uses the same stable IDs and names as Android", () => {
    expect(CURATED_THEMES.map(({ id, name }) => ({ id, name }))).toEqual([
      { id: "foreman", name: "Foreman" },
      { id: "harbor", name: "Harbor" },
      { id: "grove", name: "Grove" },
      { id: "ember", name: "Ember" },
    ]);
  });

  it("applies every named theme in light and dark modes", () => {
    for (const { id } of CURATED_THEMES) {
      for (const colorMode of ["light", "dark"] as const) {
        const appearance: Appearance = { ...DEFAULT_APPEARANCE, colorMode, themeId: id };
        const cleanup = applyAppearance(appearance);
        expect(document.documentElement.dataset.foremanTheme).toBe(id);
        expect(document.documentElement.dataset.colorMode).toBe(colorMode);
        expect(document.documentElement.style.colorScheme).toBe(colorMode);
        cleanup();
      }
    }
  });

  it("follows a live OS color-scheme change in System mode", () => {
    let listener: (() => void) | undefined;
    const media = {
      matches: false,
      addEventListener: vi.fn((_event: string, next: () => void) => { listener = next; }),
      removeEventListener: vi.fn(),
    };
    vi.spyOn(window, "matchMedia").mockReturnValue(media as unknown as MediaQueryList);
    const cleanup = applyAppearance(DEFAULT_APPEARANCE);
    expect(document.documentElement.dataset.colorMode).toBe("light");
    media.matches = true;
    listener?.();
    expect(document.documentElement.dataset.colorMode).toBe("dark");
    cleanup();
    expect(media.removeEventListener).toHaveBeenCalled();
    expect(resolvedTheme("system", true)).toBe("dark");
  });

  it("keeps text, controls, failures, and full access above deterministic contrast floors", () => {
    const css = readFileSync(join(process.cwd(), "src/styles.css"), "utf8");
    [
      "--app-background", "--surface-primary", "--surface-alternate", "--surface-raised",
      "--border-default", "--divider", "--text-primary", "--text-muted", "--accent-primary",
      "--accent-emphasis", "--accent-container", "--link", "--focus-indicator", "--selection",
      "--disabled-surface", "--card-surface", "--grouped-header-surface", "--usage-track",
      "--usage-fill", "--context-track", "--context-fill", "--navigation-surface",
      "--dialog-surface", "--popover-surface", "--success", "--working", "--attention",
      "--warning", "--failure", "--full-access",
    ].forEach((token) => expect(css, `${token} is defined`).toContain(`${token}:`));
    const rule = (selector: string) => {
      const start = css.indexOf(selector);
      const open = css.indexOf("{", start);
      const close = css.indexOf("}", open);
      expect(start, `${selector} exists`).toBeGreaterThanOrEqual(0);
      return tokens(css.slice(open + 1, close));
    };
    const baseLight = rule(":root");
    const baseDark = rule(":root[data-color-mode=dark]");
    for (const { id } of CURATED_THEMES) {
      for (const dark of [false, true]) {
        const override = id === "foreman" ? {} : rule(
          dark
            ? `:root[data-color-mode=dark][data-foreman-theme=${id}]`
            : `:root[data-foreman-theme=${id}]`,
        );
        const palette = { ...baseLight, ...(dark ? baseDark : {}), ...override };
        expect(contrast(palette["--text-primary"], palette["--app-background"]), `${id} ${dark ? "dark" : "light"} text`).toBeGreaterThanOrEqual(7);
        expect(contrast(palette["--on-accent"], palette["--accent-primary"]), `${id} ${dark ? "dark" : "light"} accent`).toBeGreaterThanOrEqual(4.5);
        expect(contrast(palette["--failure"], palette["--failure-container"]), `${id} ${dark ? "dark" : "light"} failure`).toBeGreaterThanOrEqual(4.5);
        expect(contrast(palette["--full-access"], palette["--full-access-container"]), `${id} ${dark ? "dark" : "light"} full access`).toBeGreaterThanOrEqual(4.5);
        expect(palette["--full-access"]).not.toBe(palette["--working"]);
      }
    }
  });
});

function tokens(body: string): Record<string, string> {
  return Object.fromEntries([...body.matchAll(/(--[a-z-]+):\s*(#[0-9a-f]{3,8})/gi)].map(([, key, value]) => [key, value]));
}

function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((left, right) => right - left);
  return (lighter + 0.05) / (darker + 0.05);
}

function luminance(hex: string): number {
  const normalized = hex.length === 4 ? hex.slice(1).split("").map((value) => value + value).join("") : hex.slice(1, 7);
  const channels = normalized.match(/.{2}/g)!.map((value) => Number.parseInt(value, 16) / 255).map((value) =>
    value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}
