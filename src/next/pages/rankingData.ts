import { useEffect, useRef, useState } from "react";
import { apiFetch } from "../../lib/api";

/* 랭킹 화면(NxRankingPage)이 쓰는 데이터 계층: 응답 타입, 로컬 캐시, 5초 주기 갱신 훅. */

export type RankingEntry = {
  rank: number;
  nickname: string;
  profileImageUrl?: string | null;
  totalAsset: number;
  changePercent: number;
  /** 사용자가 칭호 페이지에서 장착한 칭호. 없으면 null. */
  equippedTitle?: string | null;
  equippedTitleWeekly?: boolean;
};

export type WeeklyRankingEntry = Omit<RankingEntry, "changePercent"> & { weeklyReturnPercent: number };

/** 이번 주(월~일, 한국 시간) 수익률 순위. 월요일 0시 칭호 지급과 같은 기준으로 계산된다. */
export type WeeklyRanking = {
  weekStart: string;
  weekEnd: string;
  /** 다음 칭호 지급 시각(ISO). */
  awardAt: string;
  participants: number;
  entries: WeeklyRankingEntry[];
  /** 로그인한 사용자가 이번 주 집계에 들어 있으면 그 행. 표에 없는 순위여도 내려온다. */
  me: WeeklyRankingEntry | null;
};

export type RankingTab = "total" | "weekly" | "season" | "fandom";

export const RANKING_CACHE_KEY = "gamestock-ranking-cache";
export const WEEKLY_RANKING_CACHE_KEY = "gamestock-weekly-ranking-cache";
export const RANKING_TAB_KEY = "gamestock-ranking-tab";

function readCache<T>(key: string, valid: (value: unknown) => boolean): T | null {
  try {
    const value = JSON.parse(window.localStorage.getItem(key) ?? "null");
    return valid(value) ? (value as T) : null;
  } catch {
    return null;
  }
}

function writeCache(key: string, value: unknown) {
  try { window.localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage may be disabled */ }
}

export const readRankingCache = () => readCache<RankingEntry[]>(RANKING_CACHE_KEY, Array.isArray) ?? [];
export const readWeeklyCache = () =>
  readCache<WeeklyRanking>(WEEKLY_RANKING_CACHE_KEY, (value) => Array.isArray((value as WeeklyRanking | null)?.entries));

export function readTab(): RankingTab {
  try {
    const saved = window.localStorage.getItem(RANKING_TAB_KEY);
    return saved === "weekly" || saved === "season" || saved === "fandom" ? saved : "total";
  } catch { return "total"; }
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
/** "2026-10-12" → "10.12(월)". 날짜만 다루므로 시간대 영향이 없게 UTC로 해석한다. */
export function formatWeekDay(isoDate: string) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  return `${date.getUTCMonth() + 1}.${date.getUTCDate()}(${WEEKDAYS[date.getUTCDay()]})`;
}

export function formatCountdown(targetIso: string, now: number) {
  const remaining = Math.max(0, new Date(targetIso).getTime() - now);
  const minutes = Math.floor(remaining / 60_000);
  const days = Math.floor(minutes / 1440);
  const hours = String(Math.floor((minutes % 1440) / 60)).padStart(2, "0");
  return `${days}일 ${hours}시간 ${String(minutes % 60).padStart(2, "0")}분`;
}

/**
 * 5초마다 다시 불러오는 랭킹 데이터. 지난번 값이 있으면 그것부터 보여 주고 뒤에서 갱신하므로
 * 로딩 문구는 처음 방문할 때만 보인다. 선택된 탭의 컴포넌트만 마운트되므로 숨은 탭은 요청하지 않는다.
 */
export function usePolledRanking<T>(path: string, cacheKey: string, initial: T | null) {
  const [data, setData] = useState<T | null>(initial);
  const [loading, setLoading] = useState(initial === null);
  const [error, setError] = useState("");
  const hasData = useRef(initial !== null);

  useEffect(() => {
    let active = true;
    // 요청 중 표시는 effect마다 따로 둔다. ref로 공유하면 StrictMode가 effect를 다시 실행할 때
    // 정리된 쪽의 요청이 남아 새 effect의 첫 요청이 건너뛰어지고, 다음 주기(5초)까지 표가 비어 있다.
    let inFlight = false;

    const load = async () => {
      // 주기 요청이 느린 네트워크에서 겹치지 않도록 한 번에 하나만 보낸다.
      if (!active || inFlight) return;
      inFlight = true;
      try {
        const next = await apiFetch<T>(path);
        if (!active) return;
        // 기존 행을 유지한 채 데이터만 교체하므로 갱신 때 표가 깜빡이지 않는다.
        setData(next);
        writeCache(cacheKey, next);
        hasData.current = true;
        setError("");
      } catch {
        if (active && !hasData.current) {
          setError("랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      } finally {
        inFlight = false;
        if (active) setLoading(false);
      }
    };

    void load();
    const refreshId = window.setInterval(() => void load(), 5000);
    return () => {
      active = false;
      window.clearInterval(refreshId);
    };
  }, [path, cacheKey]);

  return { data, loading, error };
}
