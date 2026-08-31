import type {
  ServerUpdateBlocker,
  ServerUpdateCheck,
  ServerUpdateOperation,
  ServerUpdatePhase,
} from "./protocol";
import { parseSemVer } from "./update-status";

const phases = new Set<ServerUpdatePhase>([
  "downloading", "verifying", "staging", "activationScheduled",
  "activating", "restarting", "healthChecking", "rollingBack", "succeeded",
  "rolledBack", "recoveryRequired", "blocked", "failed", "interrupted",
]);
const blockerCategories = new Set(["workingSession", "waitingSession", "pendingApproval", "pendingInput"]);
const officialSource = "https://github.com/mkaltner/foreman/releases";
const notesPrefix = "https://github.com/mkaltner/foreman/releases/tag/v";
const officialSourceLabel = "Official Foreman GitHub releases";
const stableVersion = /^\d+\.\d+\.\d+$/;
const operationId = /^fmu_[A-Za-z0-9_-]{16,80}$/;

export const terminalUpdatePhases = new Set<ServerUpdatePhase>([
  "succeeded", "rolledBack", "recoveryRequired", "blocked", "failed", "interrupted",
]);

function text(value: unknown, maximum: number): string | null {
  return typeof value === "string" && value.length > 0 && value.length <= maximum ? value : null;
}

export function normalizeServerUpdateOperation(value: unknown): ServerUpdateOperation | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Partial<ServerUpdateOperation>;
  const id = text(raw.id, 100);
  const phase = phases.has(raw.phase as ServerUpdatePhase) ? raw.phase as ServerUpdatePhase : null;
  const currentVersion = text(raw.currentVersion, 80);
  const targetVersion = text(raw.targetVersion, 80);
  const source = text(raw.source, 100);
  const sourceUrl = raw.sourceUrl === officialSource ? raw.sourceUrl : null;
  const releaseNotesUrl = typeof raw.releaseNotesUrl === "string" && raw.releaseNotesUrl.startsWith(notesPrefix) && raw.releaseNotesUrl.length <= 240
    ? raw.releaseNotesUrl
    : null;
  const progress = typeof raw.progress === "number" && raw.progress >= 0 && raw.progress <= 100 ? raw.progress : null;
  const createdAt = text(raw.createdAt, 40);
  const updatedAt = text(raw.updatedAt, 40);
  if (
    !id || !operationId.test(id) || !phase
    || !currentVersion || !parseSemVer(currentVersion)
    || !targetVersion || !stableVersion.test(targetVersion)
    || source !== officialSourceLabel || !sourceUrl || !releaseNotesUrl
    || releaseNotesUrl !== `${notesPrefix}${targetVersion}`
    || progress === null || !createdAt || !updatedAt
    || (raw.completedAt !== undefined && !text(raw.completedAt, 40))
    || (raw.resultCode !== undefined && !text(raw.resultCode, 80))
    || (raw.message !== undefined && !text(raw.message, 500))
    || (raw.recoveryCommand !== undefined && raw.recoveryCommand !== "foreman update --recover")
  ) return null;
  return {
    id, phase, currentVersion, targetVersion, source, sourceUrl, releaseNotesUrl,
    progress, createdAt, updatedAt,
    ...(text(raw.completedAt, 40) ? { completedAt: raw.completedAt } : {}),
    ...(text(raw.resultCode, 80) ? { resultCode: raw.resultCode } : {}),
    ...(text(raw.message, 500) ? { message: raw.message } : {}),
    ...(raw.recoveryCommand === "foreman update --recover" ? { recoveryCommand: raw.recoveryCommand } : {}),
  };
}

export function normalizeServerUpdateCheck(value: unknown): ServerUpdateCheck | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Partial<ServerUpdateCheck>;
  if (
    !text(raw.currentVersion, 80)
    || !parseSemVer(raw.currentVersion as string)
    || typeof raw.releaseBuild !== "boolean"
    || raw.source !== officialSourceLabel
    || raw.sourceUrl !== officialSource
    || typeof raw.updateAvailable !== "boolean"
    || !Array.isArray(raw.blockers)
  ) return null;
  const blockers = raw.blockers.filter((item): item is ServerUpdateBlocker =>
    !!item && blockerCategories.has(item.category) && Number.isInteger(item.count) && item.count > 0 && item.count <= 10_000,
  );
  if (blockers.length !== raw.blockers.length) return null;
  const target = raw.target && typeof raw.target === "object" ? raw.target : null;
  if (raw.updateAvailable && !target) return null;
  if (target && (
    !text(target.version, 80)
    || !stableVersion.test(target.version)
    || target.tag !== `v${target.version}`
    || !text(target.title, 160)
    || !text(target.publishedAt, 40)
    || typeof target.releaseNotesUrl !== "string"
    || !target.releaseNotesUrl.startsWith(notesPrefix)
    || target.releaseNotesUrl !== `${notesPrefix}${target.version}`
    || typeof target.artifactAvailable !== "boolean"
    || (raw.updateAvailable && !target.artifactAvailable)
  )) return null;
  const operation = raw.operation === null || raw.operation === undefined
    ? null
    : normalizeServerUpdateOperation(raw.operation);
  if (raw.operation && !operation) return null;
  return {
    currentVersion: raw.currentVersion!, releaseBuild: raw.releaseBuild,
    source: raw.source!, sourceUrl: raw.sourceUrl, updateAvailable: raw.updateAvailable,
    target: target as ServerUpdateCheck["target"], blockers, operation,
  };
}

export function serverUpdatePhaseLabel(phase: ServerUpdatePhase): string {
  return ({
    downloading: "Downloading", verifying: "Verifying signature",
    staging: "Staging", activationScheduled: "Activation scheduled", activating: "Activating",
    restarting: "Restarting Foreman", healthChecking: "Health checking",
    rollingBack: "Rolling back", succeeded: "Update complete", rolledBack: "Previous version restored",
    recoveryRequired: "Recovery required", blocked: "Blocked by active work",
    failed: "Update failed", interrupted: "Update interrupted",
  } as Record<ServerUpdatePhase, string>)[phase];
}

const operationKey = "foreman.server-update-operation.v1";

export function loadServerUpdateOperationId(hostId: string, storage: Storage = localStorage): string | null {
  const value = storage.getItem(`${operationKey}.${hostId}`);
  return value && operationId.test(value) ? value : null;
}

export function saveServerUpdateOperationId(hostId: string, operationId: string, storage: Storage = localStorage): void {
  if (/^fmu_[A-Za-z0-9_-]{16,80}$/.test(operationId)) storage.setItem(`${operationKey}.${hostId}`, operationId);
}

export function forgetServerUpdateOperationId(hostId: string, storage: Storage = localStorage): void {
  storage.removeItem(`${operationKey}.${hostId}`);
}
