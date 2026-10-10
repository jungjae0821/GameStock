import { useEffect, useRef, useState } from "react";
import { apiFetch } from "../lib/api";
import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";

type RankingEntry = {
  rank: number;
  nickname: string;
  profileImageUrl?: string | null;
  totalAsset: number;
  changePercent: number;
  /** 사용자가 칭호 페이지에서 장착한 칭호. 없으면 null. */
  equippedTitle?: string | null;
  equippedTitleWeekly?: boolean;
};

const RANKING_CACHE_KEY = "gamestock-ranking-cache";

function readRankingCache(): RankingEntry[] {
  try {
    const value = JSON.parse(window.localStorage.getItem(RANKING_CACHE_KEY) ?? "null");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

const medalByRank: Record<number, { emoji: string; label: string }> = {
  1: { emoji: "🥇", label: "금메달 1위" },
  2: { emoji: "🥈", label: "은메달 2위" },
  3: { emoji: "🥉", label: "동메달 3위" },
};

export function RankingPage() {
  const [ranking, setRanking] = useState<RankingEntry[]>(readRankingCache);
  // 지난번 랭킹이 남아 있으면 그것부터 보여 주고 뒤에서 갱신한다. 로딩 문구는 처음 방문할 때만 보인다.
  const [loading, setLoading] = useState(() => readRankingCache().length === 0);
  const [error, setError] = useState("");
  const hasData = useRef(readRankingCache().length > 0);

  useEffect(() => {
    let active = true;
    // 요청 중 표시는 effect마다 따로 둔다. ref로 공유하면 StrictMode가 effect를 다시 실행할 때
    // 정리된 쪽의 요청이 남아 새 effect의 첫 요청이 건너뛰어지고, 다음 주기(5초)까지 표가 비어 있다.
    let inFlight = false;

    const loadRanking = async () => {
      // 주기 요청이 느린 네트워크에서 겹치지 않도록 한 번에 하나만 보낸다.
      if (!active || inFlight) return;
      inFlight = true;
      try {
        const entries = await apiFetch<RankingEntry[]>("/api/ranking");
        if (!active) return;
        // 기존 행을 유지한 채 데이터만 교체하므로 갱신 때 표가 깜빡이지 않는다.
        setRanking(entries);
        try { window.localStorage.setItem(RANKING_CACHE_KEY, JSON.stringify(entries)); } catch { /* storage may be disabled */ }
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

    void loadRanking();
    const refreshId = window.setInterval(() => void loadRanking(), 5000);
    return () => {
      active = false;
      window.clearInterval(refreshId);
    };
  }, []);

  const displayed = [...ranking]
    .sort((left, right) => right.totalAsset - left.totalAsset)
    .map((entry, index) => ({ ...entry, rank: index + 1 }));

  return (
    <div className="page-stack is-compact">
      <h1 className="page-title">랭킹</h1>

      <section aria-label="투자 랭킹">
        {loading ? (
          <p className="empty is-inline">랭킹을 불러오는 중…</p>
        ) : error ? (
          <p className="empty is-inline">{error}</p>
        ) : ranking.length === 0 ? (
          <p className="empty is-inline">아직 랭킹에 참여한 사용자가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table integration-ranking" aria-label="랭킹 표">
              <colgroup>
                <col className="ranking-col-rank" />
                <col className="ranking-col-name" />
                <col className="ranking-col-change" />
                <col className="ranking-col-asset" />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col" className="ranking-col-rank">순위</th>
                  <th scope="col" className="ranking-col-name">닉네임</th>
                  <th scope="col" className="ranking-col-change">수익률</th>
                  <th scope="col" className="ranking-col-asset">총 자산</th>
                </tr>
              </thead>
              <tbody>
                {displayed.map((entry) => (
                  <tr key={`${entry.rank}-${entry.nickname}`}>
                    <th scope="row" className="ranking-page-rank num">
                      <span className="ranking-rank-content">
                        {medalByRank[entry.rank] ? (
                          <span className="ranking-medal" role="img" aria-label={medalByRank[entry.rank].label}>
                            {medalByRank[entry.rank].emoji}
                          </span>
                        ) : (
                          <span>{entry.rank}</span>
                        )}
                      </span>
                    </th>
                    <td className="ranking-page-name">
                      <span className="ranking-name-cell">
                        <img
                          className="ranking-avatar"
                          src={entry.profileImageUrl || DEFAULT_PROFILE_AVATAR}
                          alt=""
                          loading="lazy"
                          onError={(event) => {
                            // 깨진 외부 이미지 대신 기본 아바타를 보여 준다. 대체 텍스트가 칸을 넘치지 않게 alt 는 비운다.
                            if (event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) return;
                            event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
                          }}
                        />
                        <span className="ranking-name-text">
                          <span className="ranking-nickname">{entry.nickname}</span>
                          {entry.equippedTitle && (
                            <span className={`ranking-title${entry.equippedTitleWeekly ? " is-weekly" : ""}`}>{entry.equippedTitle}</span>
                          )}
                        </span>
                      </span>
                    </td>
                    <td className={`ranking-page-change num${entry.changePercent > 0 ? " is-up" : entry.changePercent < 0 ? " is-down" : " is-flat"}`}>
                      {entry.changePercent >= 0 ? "+" : ""}{entry.changePercent.toFixed(2)}%
                    </td>
                    <td className="ranking-page-asset num">{Math.round(entry.totalAsset).toLocaleString("ko-KR")}원</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
