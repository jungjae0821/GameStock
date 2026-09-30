import { onAuthStateChanged, type User } from "firebase/auth";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "./Link";
import { Panel } from "./Panel";
import { announceMissionReward } from "./MissionRewardToast";
import { firebaseAuth } from "../lib/firebase";
import { apiFetch } from "../lib/api";
import { useMarketApi } from "../market/MarketProvider";
import type { DailyMissionStatus } from "../market/types";
import { navigate } from "../router";

const MISSION_IDS = ["market", "news", "watch"] as const;
type MissionId = (typeof MISSION_IDS)[number];
type CompletedMissions = Record<MissionId, boolean>;
type MissionProgress = { userId: string; status: DailyMissionStatus; expiresAt: number };

const labels: Record<MissionId, { title: string; description: string; action: string; href: string }> = {
  market: { title: "시장 둘러보기", description: "시세표에서 오늘 움직이는 종목을 찾아봐.", action: "시장 보기", href: "/market" },
  news: { title: "뉴스 읽기", description: "가격이 왜 움직였는지 뉴스에서 확인해봐.", action: "뉴스 보기", href: "/news" },
  watch: { title: "관심종목 등록", description: "마음에 드는 종목 하나를 관심 목록에 담아봐.", action: "종목 고르기", href: "/market" },
};

function emptyCompleted(): CompletedMissions {
  return { market: false, news: false, watch: false };
}

export function MissionBoard() {
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

  const ready = user && progress?.userId === user.uid && progress.expiresAt > Date.now();
  const done: CompletedMissions = ready
    ? Object.fromEntries(MISSION_IDS.map((id) => [id, progress.status.completedMissionIds.includes(id)])) as CompletedMissions
    : emptyCompleted();
  const count = Object.values(done).filter(Boolean).length;

  const complete = async (id: MissionId, href: string) => {
    if (!user || !ready || done[id] || pendingRef.current.has(id)) return;
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

  return (
    <Panel id="mission-board" title="오늘의 투자 미션" meta={user && !ready ? (error ? "확인 필요" : "확인 중") : `${count}/${MISSION_IDS.length} 완료`}>
      <p className="mission-reset-note">한국시간 매일 00:00 초기화</p>
      {error && <p className="mission-reset-note" role="status">{error}</p>}
      <div className="mission-list">
        {MISSION_IDS.map((id) => {
          const mission = labels[id];
          return (
            <article className={`mission-row${done[id] ? " is-done" : ""}`} key={id}>
              <span className="mission-check" aria-hidden="true">{done[id] ? "✓" : "○"}</span>
              <div className="mission-copy">
                <h3>{mission.title}</h3>
                <p>{mission.description}</p>
              </div>
              {done[id] ? <span className="mission-status">완료</span> : !user ? (
                <span className="mission-action mission-action-disabled" aria-label="로그인 후 미션 수행 가능">로그인 필요</span>
              ) : pending.has(id) || !ready ? (
                <span className="mission-action mission-action-disabled">{pending.has(id) ? "지급 중…" : "확인 중…"}</span>
              ) : (
                <Link
                  className="mission-action"
                  to={mission.href}
                  onClick={(event) => {
                    event.preventDefault();
                    void complete(id, mission.href);
                  }}
                >
                  {mission.action}
                </Link>
              )}
            </article>
          );
        })}
      </div>
    </Panel>
  );
}
