import { useEffect, useRef, useState } from "react";
import { apiFetch } from "../lib/api";
import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";

type RankingEntry = {
  rank: number;
  nickname: string;
  profileImageUrl?: string | null;
  totalAsset: number;
  changePercent: number;
};

const RANKING_CACHE_KEY = "gamestock-ranking-cache";

function readRankingCache(): RankingEntry[] {
  try {
    const value = JSON.parse(window.sessionStorage.getItem(RANKING_CACHE_KEY) ?? "null");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

const medalByRank: Record<number, { rank: number; label: string; className: string }> = {
  1: { rank: 1, label: "1위", className: "is-gold" },
  2: { rank: 2, label: "2위", className: "is-silver" },
  3: { rank: 3, label: "3위", className: "is-bronze" },
};

export function RankingPage() {
  const [ranking, setRanking] = useState<RankingEntry[]>(readRankingCache);
  const [loading, setLoading] = useState(() => readRankingCache().length === 0);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const hasData = useRef(false);

  useEffect(() => {
    let active = true;

    const loadRanking = async (initial = false) => {
      // 1초 주기 요청이 느린 네트워크에서 겹치지 않도록 한 번에 하나만 보낸다.
      if (!active || inFlight.current) return;
      inFlight.current = true;
      if (initial) setLoading(true);
      try {
        const entries = await apiFetch<RankingEntry[]>("/api/ranking");
        if (!active) return;
        // 기존 행을 유지한 채 데이터만 교체하므로 갱신 때 표가 깜빡이지 않는다.
        setRanking(entries);
        try { window.sessionStorage.setItem(RANKING_CACHE_KEY, JSON.stringify(entries)); } catch { /* storage may be disabled */ }
        hasData.current = true;
        setError("");
      } catch {
        if (active && !hasData.current) {
          setError("랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      } finally {
        inFlight.current = false;
        // StrictMode에서 초기 effect가 한 번 정리돼도, 다음 주기에서 로딩 표시가 남지 않게 한다.
        if (active) setLoading(false);
      }
    };

    void loadRanking(true);
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
                        {medalByRank[entry.rank] && (
                          <span
                            className={`ranking-medal ${medalByRank[entry.rank].className}`}
                            role="img"
                            aria-label={medalByRank[entry.rank].label}
                          >
                            <span className="num">{medalByRank[entry.rank].rank}</span>
                          </span>
                        )}
                        {!medalByRank[entry.rank] && <span>{entry.rank}</span>}
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
                        <span>{entry.nickname}</span>
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
