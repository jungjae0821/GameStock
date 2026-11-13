import { useEffect, useState } from "react";
import { AccountTabs } from "../AccountTabs";
import { CareerPanel } from "../engagement/CareerPanel";
import { UnlockProgressPanel } from "../engagement/EngagementPanels";
import { FandomSettings } from "../engagement/FandomSettings";
import { fetchWallet, useCareer, type WalletEntry } from "../../lib/career";
import { won } from "../../market/format";

const KIND_LABEL: Record<string, string> = {
  BONUS: "성과급", ATTENDANCE: "출석", MISSION: "미션", PREDICTION: "예측", SEASON: "시즌", PROMO: "프로모션",
  SEVERANCE: "퇴직금", BILLBOARD: "전광판", TAP: "탭 베팅",
};
const kstDate = (iso: string) => new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Seoul" });

/** 내 지갑에 들어오고 나간 최근 기록. 직급이나 지갑이 바뀔 때마다 다시 읽는다. */
function WalletHistory() {
  const career = useCareer();
  const [entries, setEntries] = useState<WalletEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const balance = career?.personalCash;
  useEffect(() => {
    let active = true;
    fetchWallet()
      .then((wallet) => { if (active) { setEntries(wallet.entries); setFailed(false); } })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [balance]);

  return (
    <section className="nx-panel nx-wallet" aria-labelledby="nx-wallet-title">
      <header className="nx-panel-head is-stacked">
        <h2 id="nx-wallet-title" className="nx-panel-title">내 지갑 기록</h2>
        <p className="nx-muted">성과급과 보상이 들어오고, 탭 베팅과 전광판에 나가요. 운용계좌로는 옮길 수 없어요.</p>
      </header>
      {failed && !entries ? <p className="nx-note is-error" role="alert">지갑 기록을 불러오지 못했어요.</p>
        : !entries ? <div className="nx-skeleton" role="status" aria-label="지갑 기록을 불러오는 중"><span /><span /></div>
        : entries.length === 0 ? <p className="nx-empty-state">아직 들어온 돈이 없어요. 출석하고 미션을 끝내면 여기에 쌓여요.</p>
        : (
          <ol className="nx-wallet-list">
            {entries.map((entry) => (
              <li key={entry.id}>
                <span className="nx-wallet-kind">{KIND_LABEL[entry.kind] ?? entry.kind}</span>
                <span className="nx-wallet-memo">{entry.memo}<span className="nx-muted"> · {kstDate(entry.at)}</span></span>
                <span className={`nx-num ${entry.amount >= 0 ? "is-up" : "is-down"}`}>{entry.amount >= 0 ? "+" : ""}{won(entry.amount)}</span>
              </li>
            ))}
          </ol>
        )}
    </section>
  );
}

/** 직급과 승진 조건, OJT, 데스크 발령, 내 지갑을 한곳에서 본다. 모바일 앱은 이 화면을 웹으로 연다. */
export function NxCareerPage() {
  const career = useCareer();
  const choosingFandom = career?.grade === "TRAINEE" && career.promotion.some((step) => step.id === "FANDOM" && !step.done);
  return (
    <div className="nx-screen nx-career-screen">
      <AccountTabs group="funds" active="career" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">내 커리어</h1>
          <p className="nx-screen-sub">슈엔증권 프랍 데스크에서 회사 돈을 굴려 승진하고, 성과급은 내 지갑에 모여요. 실제 돈은 오가지 않아요.</p>
        </div>
      </header>
      <CareerPanel />
      {choosingFandom && <FandomSettings />}
      <UnlockProgressPanel />
      <WalletHistory />
    </div>
  );
}
