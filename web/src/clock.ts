import { useSyncExternalStore } from "react";

type Listener = () => void;

let current = Date.now();
let interval: number | null = null;
const listeners = new Set<Listener>();

function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  if (interval === null) {
    interval = window.setInterval(() => {
      current = Date.now();
      listeners.forEach((notify) => notify());
    }, 1000);
  }
  return () => {
    listeners.delete(listener);
    if (!listeners.size && interval !== null) {
      window.clearInterval(interval);
      interval = null;
    }
  };
}

function snapshot(): number {
  return current;
}

export function useSharedClock(): number {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
