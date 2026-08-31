import type { ComponentReleaseUpdates, ForemanRelease, ReleaseUpdateSnapshot } from "./protocol";

const RELEASE_NOTES_PREFIX = "https://github.com/mkaltner/foreman/releases/tag/";
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

interface ParsedSemVer {
  core: [bigint, bigint, bigint];
  prerelease: string[];
}

export type UpdateStatusKind =
  | "up-to-date"
  | "update-available"
  | "checking"
  | "unavailable"
  | "development"
  | "prerelease"
  | "newer-than-latest"
  | "artifact-unavailable";

export interface ComponentUpdateStatus {
  kind: UpdateStatusKind;
  label: string;
  detail?: string;
  release?: ForemanRelease;
}

export function parseSemVer(value: string): ParsedSemVer | null {
  const match = SEMVER.exec(value);
  if (!match) return null;
  const core = [BigInt(match[1]), BigInt(match[2]), BigInt(match[3])] as [bigint, bigint, bigint];
  return {
    core,
    prerelease: match[4]?.split(".") ?? [],
  };
}

export function compareSemVer(left: ParsedSemVer, right: ParsedSemVer): number {
  for (let index = 0; index < 3; index += 1) {
    if (left.core[index] !== right.core[index]) return left.core[index] < right.core[index] ? -1 : 1;
  }
  if (left.prerelease.length === 0 || right.prerelease.length === 0) {
    if (left.prerelease.length === right.prerelease.length) return 0;
    return left.prerelease.length === 0 ? 1 : -1;
  }
  const length = Math.max(left.prerelease.length, right.prerelease.length);
  for (let index = 0; index < length; index += 1) {
    const a = left.prerelease[index];
    const b = right.prerelease[index];
    if (a === undefined || b === undefined) return a === undefined ? -1 : 1;
    if (a === b) continue;
    const aNumeric = /^\d+$/.test(a);
    const bNumeric = /^\d+$/.test(b);
    if (aNumeric && bNumeric) return BigInt(a) < BigInt(b) ? -1 : 1;
    if (aNumeric !== bNumeric) return aNumeric ? -1 : 1;
    return a < b ? -1 : 1;
  }
  return 0;
}

export function componentUpdateStatus(
  installedVersion: string | null,
  releaseBuild: boolean | null,
  discovery: ReleaseUpdateSnapshot | null,
  component: ComponentReleaseUpdates | null,
  artifactLabel: string,
): ComponentUpdateStatus {
  if (releaseBuild === false) {
    return { kind: "development", label: "Development or source-checkout build", detail: "Stable update comparisons are not applied to this build." };
  }
  const installed = installedVersion ? parseSemVer(installedVersion) : null;
  if (!installed) {
    return { kind: "unavailable", label: "Check unavailable", detail: "The installed version is unknown or malformed." };
  }
  if (installed.prerelease.length > 0) {
    return { kind: "prerelease", label: "Prerelease build", detail: "Prereleases are not treated as ordinary stable updates." };
  }
  if (!discovery || !component) {
    return { kind: "unavailable", label: "Check unavailable", detail: "No validated release information is cached." };
  }
  if (!discovery.observedAt && discovery.refreshStatus === "checking") {
    return { kind: "checking", label: "Checking…" };
  }
  const supported = component.supportedRelease;
  const newest = component.newestRelease;
  const supportedVersion = supported ? parseSemVer(supported.version) : null;
  const newestVersion = newest ? parseSemVer(newest.version) : null;
  if (!newest || !newestVersion) {
    return { kind: "unavailable", label: "Check unavailable", detail: "No valid stable release was found." };
  }
  if (supported && supportedVersion && compareSemVer(installed, supportedVersion) < 0) {
    return {
      kind: "update-available",
      label: `Update available · ${supported.version}`,
      detail: `A complete stable ${artifactLabel} release is available.`,
      release: supported,
    };
  }
  if (!newest.artifactAvailable && compareSemVer(installed, newestVersion) <= 0) {
    return {
      kind: "artifact-unavailable",
      label: `Release ${newest.version} exists, but its ${artifactLabel} artifact or checksum is unavailable`,
      detail: supported ? `${supported.version} is the newest complete supported release.` : "No complete supported release is available.",
      release: newest,
    };
  }
  if (compareSemVer(installed, newestVersion) > 0) {
    return {
      kind: "newer-than-latest",
      label: "Installed version is newer than the latest published stable release",
      detail: `Latest published: ${newest.version}. No downgrade is recommended.`,
    };
  }
  return {
    kind: "up-to-date",
    label: "Up to date",
    detail: supported ? `Latest supported: ${supported.version}.` : undefined,
  };
}

function validRelease(value: unknown, supported: boolean): ForemanRelease | null {
  if (!value || typeof value !== "object") return null;
  const release = value as Partial<ForemanRelease>;
  if (
    typeof release.version !== "string" || release.version.length > 80 || !parseSemVer(release.version) ||
    release.tag !== `v${release.version}` ||
    typeof release.title !== "string" || !release.title || release.title.length > 160 ||
    typeof release.publishedAt !== "string" || !Number.isFinite(Date.parse(release.publishedAt)) ||
    release.releaseNotesUrl !== `${RELEASE_NOTES_PREFIX}${release.tag}` ||
    typeof release.artifactAvailable !== "boolean" || (supported && !release.artifactAvailable)
  ) return null;
  return release as ForemanRelease;
}

export function normalizeReleaseUpdates(value: unknown): ReleaseUpdateSnapshot | null {
  if (!value || typeof value !== "object") return null;
  const snapshot = value as Partial<ReleaseUpdateSnapshot>;
  if (
    snapshot.observedAt !== null &&
    (typeof snapshot.observedAt !== "string" || !Number.isFinite(Date.parse(snapshot.observedAt)))
  ) return null;
  if (typeof snapshot.stale !== "boolean" || !["idle", "checking", "unavailable"].includes(snapshot.refreshStatus ?? "")) return null;
  if (!snapshot.components || typeof snapshot.components !== "object") return null;
  const normalizeComponent = (input: unknown): ComponentReleaseUpdates | null => {
    if (!input || typeof input !== "object") return null;
    const candidate = input as Partial<ComponentReleaseUpdates>;
    const supportedRelease = candidate.supportedRelease === null ? null : validRelease(candidate.supportedRelease, true);
    const newestRelease = candidate.newestRelease === null ? null : validRelease(candidate.newestRelease, false);
    if (candidate.supportedRelease !== null && !supportedRelease) return null;
    if (candidate.newestRelease !== null && !newestRelease) return null;
    return { supportedRelease, newestRelease };
  };
  const server = normalizeComponent(snapshot.components.server);
  const android = normalizeComponent(snapshot.components.android);
  if (!server || !android) return null;
  return {
    observedAt: snapshot.observedAt ?? null,
    stale: snapshot.stale,
    refreshStatus: snapshot.refreshStatus as ReleaseUpdateSnapshot["refreshStatus"],
    components: { server, android },
    ...(typeof snapshot.unavailableReason === "string" && snapshot.unavailableReason.length <= 80
      ? { unavailableReason: snapshot.unavailableReason }
      : {}),
  };
}
