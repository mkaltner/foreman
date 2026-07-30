import type { HostOverviewSnapshot } from "./unified";

const KEY = "foreman.unified-overview.v1";

export function loadHostSnapshots(storage: Storage = localStorage): Map<string, HostOverviewSnapshot> {
  try {
    const value = JSON.parse(storage.getItem(KEY) ?? "{}") as Record<string, HostOverviewSnapshot>;
    return new Map(Object.entries(value).filter(([id, snapshot]) => id === snapshot?.hostId));
  } catch {
    return new Map();
  }
}

export function saveHostSnapshots(snapshots: Map<string, HostOverviewSnapshot>, storage: Storage = localStorage): void {
  storage.setItem(KEY, JSON.stringify(Object.fromEntries(snapshots)));
}

export function forgetHostSnapshot(hostId: string, storage: Storage = localStorage): void {
  const snapshots = loadHostSnapshots(storage);
  snapshots.delete(hostId);
  saveHostSnapshots(snapshots, storage);
}
