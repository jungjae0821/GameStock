import { onAuthStateChanged, type User } from "firebase/auth";
import { useEffect, useRef, useState } from "react";
import { Link } from "./Link";
import { Panel } from "./Panel";
import { announceMissionReward } from "./MissionRewardToast";
import { firebaseAuth } from "../lib/firebase";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { navigate } from "../router";

const STORAGE_KEY = "gamestock-missions";
const MISSION_IDS = ["market", "news", "watch"] as const;
type MissionId = (typeof MISSION_IDS)[number];
type CompletedMissions = Record<MissionId, boolean>;

const labels: Record<MissionId, { title: string; description: string; action: string; href: string }> = {
  market: { title: "시장 둘러보기", description: "시세표에서 오늘 움직이는 종목을 찾아봐.", action: "시장 보기", href: "/market" },
  news: { title: "뉴스 읽기", description: "가격이 왜 움직였는지 뉴스에서 확인해봐.", action: "뉴스 보기", href: "/news" },
  watch: { title: "관심종목 등록", description: "마음에 드는 종목 하나를 관심 목록에 담아봐.", action: "종목 고르기", href: "/market" },
};

function emptyCompleted(): CompletedMissions {
  return { market: false, news: false, watch: false };
}

function readCompleted(userId: string): CompletedMissions {
  try {
    const value = JSON.parse(window.localStorage.getItem(`${STORAGE_KEY}:${userId}`) ?? "{}") as Partial<Record<MissionId, boolean>>;
    return Object.fromEntries(MISSION_IDS.map((id) => [id, value[id] === true])) as Record<MissionId, boolean>;
  } catch {
    return emptyCompleted();
  }
}

export function MissionBoard() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [completed, setCompleted] = useState<CompletedMissions>(() => (
    firebaseAuth.currentUser ? readCompleted(firebaseAuth.currentUser.uid) : emptyCompleted()
  ));
  const pending = useRef(new Set<MissionId>());
  useEffect(() => onAuthStateChanged(firebaseAuth, (current) => {
    setUser(current);
    setCompleted(current ? readCompleted(current.uid) : emptyCompleted());
  }), []);

  const done: CompletedMissions = user
    ? { ...completed, watch: completed.watch || snapshot.watch.length > 0 }
    : emptyCompleted();
  const count = Object.values(done).filter(Boolean).length;

  const complete = async (id: MissionId, href: string) => {
    if (!user || done[id] || pending.current.has(id)) return;
    pending.current.add(id);
    try {
      const result = await api.claimMissionReward(id);
      const next = { ...completed, [id]: true };
      setCompleted(next);
      window.localStorage.setItem(`${STORAGE_KEY}:${user.uid}`, JSON.stringify(next));
      if (result.awarded) announceMissionReward(result.rewardCash);
      navigate(href);
    } catch (error) {
      console.error("미션 보상 지급 실패", error);
    } finally {
      pending.current.delete(id);
    }
  };

  return (
    <Panel id="mission-board" title="오늘의 투자 미션" meta={`${count}/${MISSION_IDS.length} 완료`}>
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
              ) : (
                <Link
                  className="mission-action"
                  to={mission.href}
                  onClick={(event) => {
                    event.preventDefault();
                    void complete(id, mission.href);
                  }}
                >
                  {pending.current.has(id) ? "지급 중…" : mission.action}
                </Link>
              )}
            </article>
          );
        })}
      </div>
    </Panel>
  );
}
