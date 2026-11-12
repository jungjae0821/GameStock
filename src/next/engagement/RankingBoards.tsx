import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { ApiError } from "../../lib/api";
import { fetchFandoms, fetchPublicProfile, fetchSeason, useEngagement, type Fandom, type PublicProfile, type Season } from "../../lib/engagement";
import { useAuthUser } from "../../lib/auth";
import { GameIcon } from "../../components/GameIcon";
import { RankingAvatar } from "../../components/RankingAvatar";
import { LISTING_BY_CODE } from "../../market/universe";
import { won } from "../../market/format";
import { titleInkStyle } from "../../lib/titles";
import { useModalDialog } from "../../lib/useModalDialog";

const signed = (percent: number) => `${percent >= 0 ? "+" : ""}${percent.toFixed(2)}%`;
const tone = (percent: number) => (percent > 0 ? "is-up" : percent < 0 ? "is-down" : "");
const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("ko-KR", { month: "numeric", day: "numeric", timeZone: "Asia/Seoul" }) : "");
const MEDALS: Record<number, string> = { 1: "1위 금메달", 2: "2위 은메달", 3: "3위 동메달" };

/** 10초마다 다시 읽는다. 오류가 나도 마지막 값은 남긴다. */
function usePolled<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error" | "missing">("loading");
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    let active = true;
    const run = async () => {
      try {
        const next = await loadRef.current();
        if (!active) return;
        setData(next);
        setState("ready");
      } catch (error) {
        if (!active) return;
        setState((current) => (current === "ready" ? current : error instanceof ApiError && error.status === 404 ? "missing" : "error"));
      }
    };
    void run();
    const timer = window.setInterval(() => void run(), 10_000);
    return () => { active = false; window.clearInterval(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { data, state };
}

const Loading = ({ label }: { label: string }) => (
  <div className="nx-skeleton nx-rank-loading" aria-label={label}><span /><span /><span /><span /><span /></div>
);

/** 랭킹에서 닉네임을 누르면 그 사람이 공개한 포트폴리오를 연다. */
export function ProfileButton({ nickname, open, children }: { nickname: string; open: boolean; children: ReactNode }) {
  if (!open) return <>{children}</>;
  return (
    <button type="button" className="nx-player-link" onClick={() => window.dispatchEvent(new CustomEvent("gamestock:open-profile", { detail: nickname }))}
      aria-label={`${nickname}의 공개 포트폴리오 보기`}>
      {children}
    </button>
  );
}

export function SeasonBoard() {
  const { user } = useAuthUser();
  const { data, state } = usePolled<Season | undefined>(fetchSeason, [user?.uid]);
  if (state === "loading") return <Loading label="시즌 랭킹을 불러오는 중" />;
  if (state === "missing" || (state === "ready" && !data)) {
    return <p className="nx-empty-state">아직 열린 시즌이 없어요. 시즌이 시작되면 그날 자산을 기준으로 수익률 순위를 매겨요.</p>;
  }
  if (state === "error" || !data) return <p className="nx-note is-error">시즌 랭킹을 불러오지 못했어요. 잠시 후 다시 확인해 주세요.</p>;
  const active = data.status === "ACTIVE";
  const me = data.me;
  const meShown = me !== null && data.entries.some((entry) => entry.rank === me.rank);
  const rows = me && !meShown ? [...data.entries, me] : data.entries;
  return (
    <>
      <dl className="nx-figures nx-week-figures">
        <div><dt>시즌</dt><dd>{data.name}</dd></div>
        <div><dt>{active ? "시작" : "기간"}</dt><dd className="nx-num">{active ? day(data.startedAt) : `${day(data.startedAt)} ~ ${day(data.endedAt)}`}</dd></div>
        <div><dt>참여</dt><dd className="nx-num">{data.participants.toLocaleString("ko-KR")}명</dd></div>
        {me && <div><dt>내 순위</dt><dd className="nx-num">{me.rank}위 <span className={tone(me.returnPercent)}>{signed(me.returnPercent)}</span></dd></div>}
      </dl>
      {rows.length === 0 ? <p className="nx-empty-state">아직 순위에 오른 사람이 없어요.</p> : (
        <div className="nx-rank-scroll">
          <table className="nx-grid-table nx-rank-table" aria-label={`${data.name} 수익률 랭킹`}>
            <thead><tr><th scope="col" className="nx-rank-cell">순위</th><th scope="col">투자자</th><th scope="col">시즌 수익률</th><th scope="col">{active ? "지금 자산" : "마감 자산"}</th></tr></thead>
            <tbody>
              {rows.map((entry) => {
                const mine = me !== null && entry.rank === me.rank;
                return (
                  <tr key={`${entry.rank}-${entry.nickname}`} className={`${mine ? "is-mine" : ""}${entry.rank <= 3 ? " is-top" : ""}`} aria-current={mine ? "true" : undefined}>
                    <th scope="row" className="nx-rank-cell" aria-label={MEDALS[entry.rank]}>
                      {MEDALS[entry.rank] ? <RankingAvatar rank={entry.rank} src={entry.profileImageUrl} /> : <span className="nx-rank">{entry.rank}</span>}
                    </th>
                    <td className="nx-player-cell">
                      <ProfileButton nickname={entry.nickname} open={entry.publicProfile}>
                        <span className="nx-player-name" title={entry.nickname}>{entry.nickname}{mine && <span className="nx-player-me">나</span>}</span>
                      </ProfileButton>
                    </td>
                    <td className={`nx-num ${tone(entry.returnPercent)}`}>{signed(entry.returnPercent)}</td>
                    <td className="nx-num">{Math.round(entry.currentAsset).toLocaleString("ko-KR")}원</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="nx-rank-note">
        {active ? "시즌이 끝나는 순간의 순위로 1~3위에게 시즌 칭호를 줘요. " : "지난 시즌 최종 순위예요. "}
        자산은 그대로 두고, 시즌 시작 때 자산과 비교해 수익률을 매겨요. 관리자 지급·예측 보상·재기 지원금은 빼고 계산해요.
      </p>
    </>
  );
}

/** 주력 게임으로 고른 사람 수가 많은 순. 이번 주 수익률은 그 팬들의 평균이다. */
export function FandomBoard() {
  const engagement = useEngagement();
  const { data, state } = usePolled<Fandom[]>(() => fetchFandoms());
  if (state === "loading") return <Loading label="팬덤 순위를 불러오는 중" />;
  if (state === "missing") return <p className="nx-empty-state">팬덤 대항전은 곧 열려요.</p>;
  if (state === "error" || !data) return <p className="nx-note is-error">팬덤 순위를 불러오지 못했어요. 잠시 후 다시 확인해 주세요.</p>;
  const mine = engagement?.fandomCode ?? null;
  const top = Math.max(1, ...data.map((fandom) => fandom.fans));
  return (
    <>
      <div className="nx-rank-scroll">
        <table className="nx-grid-table nx-rank-table nx-fandom-table" aria-label="팬덤 대항전">
          <thead><tr><th scope="col" className="nx-rank-cell">순위</th><th scope="col">게임</th><th scope="col">팬</th><th scope="col">주주</th><th scope="col">팬 주간 수익률</th></tr></thead>
          <tbody>
            {data.map((fandom) => (
              <tr key={fandom.stockCode} className={fandom.stockCode === mine ? "is-mine" : undefined} aria-current={fandom.stockCode === mine ? "true" : undefined}>
                <th scope="row" className="nx-rank-cell"><span className="nx-rank">{fandom.rank}</span></th>
                <td className="nx-player-cell">
                  <span className="nx-fandom-name">
                    <GameIcon code={fandom.stockCode} size="table" />
                    <span>{LISTING_BY_CODE[fandom.stockCode]?.name ?? fandom.name}{fandom.stockCode === mine && <span className="nx-player-me">내 팬덤</span>}</span>
                  </span>
                </td>
                <td className="nx-num">
                  <span className="nx-fandom-fans">
                    <span className="nx-fandom-bar" aria-hidden="true"><span style={{ width: `${(fandom.fans / top) * 100}%` }} /></span>
                    {fandom.fans.toLocaleString("ko-KR")}명
                  </span>
                </td>
                <td className="nx-num">{fandom.holders.toLocaleString("ko-KR")}명</td>
                <td className={`nx-num ${fandom.weeklyReturnPercent === null ? "" : tone(fandom.weeklyReturnPercent)}`}>
                  {fandom.weeklyReturnPercent === null ? <span className="nx-muted">집계 전</span> : signed(fandom.weeklyReturnPercent)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="nx-rank-note">팬은 설정에서 주력 게임으로 고른 사람, 주주는 지금 그 주식을 1주 이상 가진 사람이에요. {!mine && "설정에서 주력 게임을 고르면 내 팬덤 순위에 힘을 보탤 수 있어요."}</p>
    </>
  );
}

/** 공개 포트폴리오 창. 랭킹 어느 탭에서든 닉네임을 누르면 열린다. */
export function PublicProfileDialog() {
  const [nickname, setNickname] = useState<string | null>(null);
  useEffect(() => {
    const open = (event: Event) => setNickname(String((event as CustomEvent<string>).detail));
    window.addEventListener("gamestock:open-profile", open);
    return () => window.removeEventListener("gamestock:open-profile", open);
  }, []);
  return nickname ? <ProfileDialog key={nickname} nickname={nickname} onClose={() => setNickname(null)} /> : null;
}

function ProfileDialog({ nickname, onClose }: { nickname: string; onClose: () => void }) {
  const [profile, setProfile] = useState<PublicProfile | null>(null);
  const [error, setError] = useState("");
  const dialog = useModalDialog();

  useEffect(() => {
    let active = true;
    fetchPublicProfile(nickname).then((next) => { if (active) setProfile(next); })
      .catch((failure) => { if (active) setError(failure instanceof Error ? failure.message : "프로필을 불러오지 못했어요."); });
    return () => { active = false; };
  }, [nickname]);

  return createPortal(
    <dialog ref={dialog} className="nx-profile-dialog" aria-labelledby="nx-public-profile-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="nx-profile-dialog-body">
        <header className="nx-profile-dialog-head">
          <div>
            {profile?.equippedTitle && <span className="nx-player-title has-title-ink" style={titleInkStyle(profile.equippedTitle)}>{profile.equippedTitle}</span>}
            <h2 id="nx-public-profile-title">{nickname}</h2>
            {profile && (
              <p className="nx-muted">
                순자산 <span className="nx-num">{won(profile.totalAsset)}</span>
                {profile.fandomCode && <> · {LISTING_BY_CODE[profile.fandomCode]?.name ?? profile.fandomCode} 팬</>}
              </p>
            )}
          </div>
          <button type="button" className="nx-icon-button" onClick={onClose} aria-label="닫기">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </header>
        {error ? <p className="nx-note is-error">{error}</p> : !profile ? <Loading label="포트폴리오를 불러오는 중" /> : profile.holdings.length === 0 ? (
          <p className="nx-empty-state">지금은 들고 있는 주식이 없어요.</p>
        ) : (
          <table className="nx-grid-table nx-public-holdings" aria-label={`${nickname}의 보유 종목`}>
            <thead><tr><th scope="col">종목</th><th scope="col">수량</th><th scope="col">평가금액</th><th scope="col">수익률</th></tr></thead>
            <tbody>
              {profile.holdings.map((holding) => (
                <tr key={holding.stockCode}>
                  <td><span className="nx-fandom-name"><GameIcon code={holding.stockCode} size="table" /><span>{LISTING_BY_CODE[holding.stockCode]?.name ?? holding.name}</span></span></td>
                  <td className="nx-num">{holding.quantity.toLocaleString("ko-KR")}주</td>
                  <td className="nx-num">{won(holding.value)}</td>
                  <td className={`nx-num ${tone(holding.returnPercent)}`}>{signed(holding.returnPercent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </dialog>, document.body,
  );
}
