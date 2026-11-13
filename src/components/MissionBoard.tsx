import { onAuthStateChanged, type User } from "firebase/auth";
import { useCallback, useEffect, useRef, useState } from "react";
import { announceMissionReward } from "./MissionRewardToast";
import { firebaseAuth } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { useMarketApi } from "../market/MarketProvider";
import type { DailyMissionStatus } from "../market/types";
import { navigate } from "../router";

const MISSION_IDS = [
  "market", "news", "watch", "ranking", "portfolio", "home", "settings",
  "trade", "career", "titles",
  "stock-gov", "stock-uma", "stock-ba", "stock-gi", "stock-sr",
  "stock-zzz", "stock-crk", "stock-vr", "stock-ow", "stock-pw",
] as const;
const DAILY_MISSION_COUNT = 3;
const MISSION_REWARD_CASH = 10_000;
type MissionId = (typeof MISSION_IDS)[number];
type MissionProgress = { userId: string; status: DailyMissionStatus; expiresAt: number };

const labels: Record<MissionId, { title: string; description: string; action: string; href: string }> = {
  market: { title: "시장 둘러보기", description: "시세표에서 오늘 움직이는 종목을 찾아보세요.", action: "시장 보기", href: "/market" },
  news: { title: "뉴스 읽기", description: "가격이 왜 움직였는지 뉴스에서 확인해보세요.", action: "뉴스 보기", href: "/news" },
  watch: { title: "관심종목 등록", description: "마음에 드는 종목 하나를 관심 목록에 담아보세요. 담으면 보상이 자동으로 지급됩니다.", action: "종목 고르기", href: "/market" },
  ranking: { title: "투자 랭킹 확인", description: "트레이더 랭킹과 부자 랭킹에서 내 위치를 확인해보세요.", action: "랭킹 보기", href: "/ranking" },
  portfolio: { title: "내 계좌 확인", description: "보유 종목과 평가손익을 한눈에 확인해보세요.", action: "내 계좌 보기", href: "/mypage" },
  home: { title: "오늘의 시세 요약", description: "홈에서 인기 종목과 오늘의 소식을 확인해보세요.", action: "홈 보기", href: "/" },
  settings: { title: "내 투자 환경 확인", description: "프로필과 투자 환경을 확인해보세요.", action: "설정 보기", href: "/settings" },
  trade: { title: "주문 화면 살펴보기", description: "호가와 주문창을 보며 매매 방법을 익혀보세요.", action: "주문 보기", href: "/trade" },
  career: { title: "내 커리어 확인", description: "지금 직급과 다음 승진 조건을 확인해보세요.", action: "커리어 보기", href: "/career" },
  titles: { title: "칭호 둘러보기", description: "획득한 칭호와 다음 칭호 조건을 확인해보세요.", action: "칭호 보기", href: "/titles" },
  "stock-gov": stockMission("승리의 여신: 니케", "GOV"),
  "stock-uma": stockMission("우마무스메 프리티더비", "UMA"),
  "stock-ba": stockMission("블루 아카이브", "BA"),
  "stock-gi": stockMission("원신", "GI"),
  "stock-sr": stockMission("붕괴: 스타레일", "SR"),
  "stock-zzz": stockMission("젠레스 존 제로", "ZZZ"),
  "stock-crk": stockMission("쿠키런: 킹덤", "CRK"),
  "stock-vr": stockMission("발로란트", "VR"),
  "stock-ow": stockMission("오버워치", "OW"),
  "stock-pw": stockMission("팰월드", "PW"),
};

function stockMission(name: string, code: string) {
  return { title: `${name} 종목 분석`, description: `${name}의 차트와 시세 흐름을 확인해보세요.`, action: "종목 보기", href: `/market/${code}` };
}

/** 미션 ID의 화면 이름. 관리자 화면의 보상 복구에서도 같은 이름을 쓴다. */
export const missionTitle = (id: string) => (labels as Record<string, { title: string }>)[id]?.title ?? id;
export const missionIds: readonly string[] = MISSION_IDS;

const missionIdSet = new Set<string>(MISSION_IDS);
const previewMissionIds = MISSION_IDS.slice(0, DAILY_MISSION_COUNT);

function normalizeMissionIds(ids: string[] | undefined): MissionId[] {
  if (!ids) return [];
  return [...new Set(ids)].filter((id): id is MissionId => missionIdSet.has(id));
}

/** 오늘의 미션 상태와 보상 요청. 기존 화면과 새 화면이 같은 규칙을 쓰도록 화면과 분리했다. */
export function useDailyMissions() {
  const api = useMarketApi();
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [progress, setProgress] = useState<MissionProgress | null>(null);
  const progressRef = useRef<MissionProgress | null>(null);
  const pendingRef = useRef(new Set<MissionId>());
  const [pending, setPending] = useState(new Set<MissionId>());
  const [error, setError] = useState<string | null>(null);
  const sessionRef = useRef(0);
  const refreshRef = useRef<() => Promise<void>>(async () => undefined);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(firebaseAuth, (current) => {
      sessionRef.current += 1;
      progressRef.current = null;
      pendingRef.current.clear();
      setProgress(null);
      setPending(new Set());
      setError(null);
      setUser(current);
    });
    return () => {
      sessionRef.current += 1;
      unsubscribe();
    };
  }, []);

  const applyStatus = useCallback((userId: string, status: DailyMissionStatus) => {
    if (firebaseAuth.currentUser?.uid !== userId) return;
    const previous = progressRef.current;
    if (previous?.userId === userId && previous.status.missionDate > status.missionDate) return;
    const sameDay = previous?.userId === userId && previous.status.missionDate === status.missionDate;
    // A slower status request must not undo a reward just confirmed by another request.
    const merged = sameDay ? {
      ...status,
      completedMissionIds: [...new Set([...previous.status.completedMissionIds, ...status.completedMissionIds])],
    } : status;
    const remaining = Date.parse(status.resetsAt) - Date.parse(status.serverTime);
    const next = { userId, status: merged, expiresAt: Date.now() + Math.max(0, remaining) };
    // Refreshes cannot push this day's midnight deadline further into the future.
    if (sameDay) next.expiresAt = Math.min(previous.expiresAt, next.expiresAt);
    progressRef.current = next;
    setProgress(next);
  }, []);

  useEffect(() => {
    if (!user) return;
    const userId = user.uid;
    const controller = new AbortController();
    let disposed = false;
    let refreshing = false;
    const refresh = async () => {
      if (disposed || refreshing) return;
      refreshing = true;
      try {
        const status = await apiFetch<DailyMissionStatus>("/api/missions", { signal: controller.signal });
        if (!disposed) {
          applyStatus(userId, status);
          setError(null);
        }
      } catch {
        if (!disposed) setError("미션 상태를 불러오지 못했어요. 잠시 후 다시 확인합니다.");
      } finally {
        refreshing = false;
      }
    };
    const resume = () => {
      if (document.visibilityState !== "visible") return;
      if (progressRef.current && progressRef.current.expiresAt <= Date.now()) {
        progressRef.current = null;
        setProgress(null);
      }
      void refresh();
    };
    refreshRef.current = refresh;
    void refresh();
    const poll = window.setInterval(() => void refresh(), 30_000);
    window.addEventListener("focus", resume);
    document.addEventListener("visibilitychange", resume);
    return () => {
      disposed = true;
      controller.abort();
      refreshRef.current = async () => undefined;
      window.clearInterval(poll);
      window.removeEventListener("focus", resume);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [user?.uid, applyStatus]);

  useEffect(() => {
    if (!progress) return;
    const timer = window.setTimeout(() => {
      progressRef.current = null;
      setProgress(null);
      void refreshRef.current();
    }, Math.max(0, progress.expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [progress]);

  const ready = Boolean(user && progress?.userId === user.uid && progress.expiresAt > Date.now());
  const assignedMissionIds = ready ? normalizeMissionIds(progress?.status.availableMissionIds) : [];
  const visibleMissionIds = assignedMissionIds.length > 0 ? assignedMissionIds : previewMissionIds;
  const completed = ready ? new Set(progress?.status.completedMissionIds ?? []) : new Set<string>();
  const count = visibleMissionIds.filter((id) => completed.has(id)).length;

  const complete = async (id: MissionId, href: string) => {
    if (!user || !ready || completed.has(id) || pendingRef.current.has(id)) return;
    const session = sessionRef.current;
    pendingRef.current.add(id);
    setPending(new Set(pendingRef.current));
    setError(null);
    try {
      const result = await api.claimMissionReward(id);
      if (sessionRef.current !== session || firebaseAuth.currentUser?.uid !== user.uid) return;
      applyStatus(user.uid, result.missions);
      if (result.awarded) announceMissionReward(result.rewardCash);
      navigate(href);
    } catch (error) {
      // 관심종목을 아직 담지 않았으면 서버가 거절한다. 종목을 고르러 이동하고,
      // 등록에 성공하면 MarketProvider가 보상을 자동으로 요청한다.
      if (id === "watch" && sessionRef.current === session) {
        navigate(href);
        return;
      }
      if (sessionRef.current === session) {
        setError(error instanceof Error ? error.message : "미션 보상 지급에 실패했습니다.");
      }
    } finally {
      if (sessionRef.current === session) {
        pendingRef.current.delete(id);
        setPending(new Set(pendingRef.current));
      }
    }
  };

  return { user, ready, error, pending, completed, count, visibleMissionIds, complete };
}

export const MISSION_REWARD = MISSION_REWARD_CASH;
export const missionLabel = (id: string) => (labels as Record<string, { title: string; description: string; action: string; href: string }>)[id];

