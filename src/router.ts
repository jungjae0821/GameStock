import { useSyncExternalStore } from "react";

export type Route =
  | { name: "home" }
  | { name: "market"; ticker: string | null }
  | { name: "etf"; ticker: string | null }
  | { name: "heatmap" }
  | { name: "derivatives"; tab: DerivativesTab }
  | { name: "trade"; ticker: string | null }
  | { name: "news"; id: number | null }
  | { name: "ranking" }
  | { name: "apiDocs" }
  | { name: "mypage" }
  | { name: "settings" }
  | { name: "charge" }
  | { name: "loan" }
  | { name: "billboard" }
  | { name: "titles" }
  | { name: "attendance" }
  | { name: "quant"; id: number | null }
  | { name: "admin"; section: AdminSection }
  | { name: "login"; method: LoginMethod }
  | { name: "notFound" };

/** 로그인해야 열리는 화면. 로그아웃 상태에서는 본문이 로그인 안내로 바뀐다. */
export const PRIVATE_ROUTES = new Set<Route["name"]>(["mypage", "settings", "titles", "attendance", "charge", "loan", "billboard", "admin", "quant"]);

export type DerivativesTab = "futures" | "options";
const DERIVATIVE_TABS: readonly DerivativesTab[] = ["futures", "options"];

export type AdminSection = "users" | "orders" | "notices" | "billboard" | "promos" | "audit";
const ADMIN_SECTIONS: readonly AdminSection[] = ["users", "orders", "notices", "billboard", "promos", "audit"];

export type LoginMethod = "choose" | "google" | "email" | "register" | "reset";

const listeners = new Set<() => void>();

function parse(pathname: string): Route {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] === "login") {
    const method = parts[1];
    return { name: "login", method: method === "google" || method === "email" || method === "register" || method === "reset" ? method : "choose" };
  }
  if (parts[0] === "market" && parts[1]?.toLowerCase() === "etf") {
    return parts.length <= 3 ? { name: "etf", ticker: parts[2]?.toUpperCase() ?? null } : { name: "notFound" };
  }
  if (parts[0] === "market") return { name: "market", ticker: parts[1] ? parts[1].toUpperCase() : null };
  if (parts[0] === "derivatives") {
    if (parts[1] === "etf") return parts.length <= 3 ? { name: "etf", ticker: parts[2]?.toUpperCase() ?? null } : { name: "notFound" };
    const tab = DERIVATIVE_TABS.find((name) => name === parts[1]) ?? "futures";
    return { name: "derivatives", tab };
  }
  if (parts[0] === "trade") return { name: "trade", ticker: parts[1] ? parts[1].toUpperCase() : null };
  if (parts[0] === "news") {
    const id = Number(parts[1]);
    return { name: "news", id: parts[1] && Number.isFinite(id) ? id : null };
  }
  if (parts[0] === "heatmap") return parts.length === 1 ? { name: "heatmap" } : { name: "notFound" };
  if (parts[0] === "ranking") return { name: "ranking" };
  if (parts[0] === "api-docs") return parts.length === 1 ? { name: "apiDocs" } : { name: "notFound" };
  if (parts[0] === "mypage") return { name: "mypage" };
  if (parts[0] === "settings") return { name: "settings" };
  if (parts[0] === "charge") return { name: "charge" };
  if (parts[0] === "loan") return { name: "loan" };
  if (parts[0] === "billboard") return parts.length === 1 ? { name: "billboard" } : { name: "notFound" };
  if (parts[0] === "titles") return { name: "titles" };
  if (parts[0] === "attendance") return { name: "attendance" };
  if (parts[0] === "quant") {
    if (parts.length === 1) return { name: "quant", id: null };
    const id = Number(parts[1]);
    return parts.length === 2 && Number.isSafeInteger(id) && id > 0 ? { name: "quant", id } : { name: "notFound" };
  }
  if (parts[0] === "admin") {
    const section = ADMIN_SECTIONS.find((name) => name === parts[1]) ?? "users";
    return { name: "admin", section };
  }
  return parts.length === 0 ? { name: "home" } : { name: "notFound" };
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

export const API_DOCS_PATH = "/api-docs";
export const HEATMAP_PATH = "/heatmap";
export const MARKET_PATH = (ticker: string | null): string => (ticker ? `/market/${ticker}` : "/market");
export const ETF_PATH = (ticker: string | null = null): string => (ticker ? `/market/etf/${ticker}` : "/market/etf");
export const NEWS_PATH = (id: number | null): string => (id === null ? "/news" : `/news/${id}`);
export const QUANT_PATH = (id: number | null): string => (id === null ? "/quant" : `/quant/${id}`);
export const TRADE_PATH = (ticker: string | null): string => (ticker ? `/trade/${ticker}` : "/trade");
