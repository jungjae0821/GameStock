import { useSyncExternalStore } from "react";

export type Route =
  | { name: "home" }
  | { name: "market"; ticker: string | null }
  | { name: "news" };

const listeners = new Set<() => void>();

function parse(pathname: string): Route {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] === "market") return { name: "market", ticker: parts[1] ? parts[1].toUpperCase() : null };
  if (parts[0] === "news") return { name: "news" };
  return { name: "home" };
}

let cachedPath = typeof window === "undefined" ? "/" : window.location.pathname;
let cachedRoute = parse(cachedPath);

function getRoute(): Route {
  if (window.location.pathname !== cachedPath) {
    cachedPath = window.location.pathname;
    cachedRoute = parse(cachedPath);
  }
  return cachedRoute;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onPop = () => listener();
  window.addEventListener("popstate", onPop);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("popstate", onPop);
  };
}

export function useRoute(): Route {
  return useSyncExternalStore(subscribe, getRoute, getRoute);
}

export function navigate(to: string): void {
  if (to === window.location.pathname + window.location.search) return;
  window.history.pushState(null, "", to);
  for (const listener of listeners) listener();
}

export const MARKET_PATH = (ticker: string | null): string => (ticker ? `/market/${ticker}` : "/market");
