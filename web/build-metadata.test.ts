import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { loadForemanBuildMetadata, parseReleaseProperties } from "./build-metadata";

const repositoryRoot = resolve(process.cwd(), "..");

describe("Foreman build metadata", () => {
  it("derives the web version from the shared release manifest", () => {
    const release = parseReleaseProperties(readFileSync(`${repositoryRoot}/release.properties`, "utf8"));
    expect(loadForemanBuildMetadata(repositoryRoot, { FOREMAN_BUILD_COMMIT: "abc123def456" })).toEqual({
      version: release.foremanVersion,
      commit: "abc123def456",
      releaseBuild: release.releaseBuild === "true",
    });
  });

  it("rejects malformed release metadata", () => {
    expect(() => parseReleaseProperties("foremanVersion\n")).toThrow("expected key=value");
  });
});
