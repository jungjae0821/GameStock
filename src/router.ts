import { useSyncExternalStore } from "react";

export type Route =
  | { name: "home" }
  | { name: "market"; ticker: string | null }
  | { name: "trade"; ticker: string | null }
  | { name: "news" }
  | { name: "ranking" }
  | { name: "mypage" }
  | { name: "login"; method: LoginMethod };

export type LoginMethod = "choose" | "google" | "email" | "register" | "reset";

const listeners = new Set<() => void>();

function parse(pathname: string): Route {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] === "login") {
    const method = parts[1];
    return { name: "login", method: method === "google" || method === "email" || method === "register" || method === "reset" ? method : "choose" };
  }
  if (parts[0] === "market") return { name: "market", ticker: parts[1] ? parts[1].toUpperCase() : null };
  if (parts[0] === "trade") return { name: "trade", ticker: parts[1] ? parts[1].toUpperCase() : null };
  if (parts[0] === "news") return { name: "news" };
  if (parts[0] === "ranking") return { name: "ranking" };
  if (parts[0] === "mypage") return { name: "mypage" };
  return { name: "home" };
}

let cachedPath = typeof window === "undefined" ? "/" : window.location.pathname + window.location.search;
let cachedRoute = parse(typeof window === "undefined" ? "/" : window.location.pathname);

function getRoute(): Route {
  const path = window.location.pathname + window.location.search;
  if (path !== cachedPath) {
    cachedPath = path;
    cachedRoute = parse(window.location.pathname);
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

export function navigate(to: string, options: { replace?: boolean } = {}): void {
  if (document.documentElement.dataset.appShell === "mobile") {
    const target = new URL(to, window.location.origin);
    target.searchParams.set("app-shell", "1");
    to = target.pathname + target.search;
  }
  if (to === window.location.pathname + window.location.search) return;
  if (options.replace) window.history.replaceState(null, "", to);
  else window.history.pushState(null, "", to);
  for (const listener of listeners) listener();
}

export const MARKET_PATH = (ticker: string | null): string => (ticker ? `/market/${ticker}` : "/market");
export const TRADE_PATH = (ticker: string | null): string => (ticker ? `/trade/${ticker}` : "/trade");
