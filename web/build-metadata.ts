import { readFileSync } from "node:fs";
import { join } from "node:path";

export interface ForemanBuildMetadata {
  version: string;
  commit: string;
  releaseBuild: boolean;
}

export function parseReleaseProperties(contents: string): Record<string, string> {
  const values: Record<string, string> = {};
  contents.split(/\r?\n/).forEach((rawLine, index) => {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) return;
    const separator = line.indexOf("=");
    if (separator <= 0 || !line.slice(separator + 1).trim()) {
      throw new Error(`release.properties:${index + 1}: expected key=value`);
    }
    values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  });
  return values;
}

export function loadForemanBuildMetadata(
  repositoryRoot: string,
  environment: NodeJS.ProcessEnv = process.env,
): ForemanBuildMetadata {
  const release = parseReleaseProperties(
    readFileSync(join(repositoryRoot, "release.properties"), "utf8"),
  );
  const version = release.foremanVersion;
  if (!version) throw new Error("release.properties: missing foremanVersion");
  if (release.releaseBuild !== "true" && release.releaseBuild !== "false") {
    throw new Error("release.properties: releaseBuild must be true or false");
  }
  const commit = environment.FOREMAN_BUILD_COMMIT?.trim() || "unknown";
  return { version, commit, releaseBuild: release.releaseBuild === "true" };
}
