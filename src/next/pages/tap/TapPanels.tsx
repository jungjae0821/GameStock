import { Link } from "../../../components/Link";
import { openLoginPrompt } from "../../../lib/auth";
import { won } from "../../../market/format";
import { TAP_PATH } from "../../../router";
import { shortWon, type TapBet, type TapState } from "./tapShared";

/** 한 칸 금액 고르기와 오늘 탭 성적. */
export function TapControls({ stakes, stake, onStake, signedIn, cash, today, openCount }: {
  stakes: number[]; stake: number; onStake: (amount: number) => void; signedIn: boolean; cash: number;
  today: TapState["today"] | undefined; openCount: number;
}) {
  const net = today ? today.paid - today.staked : 0;
  return (
    <div className="nx-tap-controls">
      <fieldset className="nx-tap-stakes">
        <legend>한 칸에 걸 금액</legend>
        {stakes.map((amount) => (
          <button key={amount} type="button" className={`nx-tap-stake${amount === stake ? " is-active" : ""}`} aria-pressed={amount === stake} onClick={() => onStake(amount)}>
            {shortWon(amount)}원
          </button>
        ))}
      </fieldset>
      <dl className="nx-tap-stats">
        <div><dt>보유 현금</dt><dd className="nx-num">{signedIn ? won(cash) : "—"}</dd></div>
        <div><dt>오늘 탭 손익</dt><dd className={`nx-num${net > 0 ? " is-up" : net < 0 ? " is-down" : ""}`}>{signedIn && today ? `${net > 0 ? "+" : ""}${won(net)}` : "—"}</dd></div>
        <div><dt>오늘 적중</dt><dd className="nx-num">{signedIn && today ? `${today.wins} / ${today.wins + today.losses}` : "—"}</dd></div>
        <div><dt>결과 대기</dt><dd className="nx-num">{openCount}칸</dd></div>
      </dl>
    </div>
  );
}

/** 규칙 안내와 최근 결과 8칸. */
export function TapFoot({ code, name, signedIn, recent }: { code: string; name: string; signedIn: boolean; recent: TapBet[] }) {
  return (
    <div className="nx-tap-foot">
      <section className="nx-tap-rules" aria-labelledby="nx-tap-rules-title">
        <h2 id="nx-tap-rules-title">이렇게 정해져요</h2>
        <ul>
          <li>칸 하나는 앞으로의 5초와 가격 한 호가예요. 배당은 최근 실제로 그만큼 떨어진 가격까지 닿은 비율로 정해요.</li>
          <li>그 5초 안에 실제 체결가가 칸에 닿으면 바로 당첨이고, 끝까지 안 닿으면 건 금액을 잃어요.</li>
          <li>칸이 열리기 1.5초 전까지만 걸 수 있어요. 지금 가격이 든 칸, 거의 확실하거나 거의 안 닿는 칸은 열리지 않아요.</li>
          <li>건 금액과 받은 금액은 계좌 현금에 바로 반영돼 순자산과 랭킹에도 들어가요.</li>
        </ul>
        {!signedIn && <button type="button" className="nx-btn is-primary" onClick={() => openLoginPrompt(TAP_PATH(code))}>로그인하고 걸기</button>}
        {signedIn && <Link className="nx-tap-link" to={`/market/${code}`}>{name} 주식 거래하기</Link>}
      </section>
      <section className="nx-tap-history" aria-labelledby="nx-tap-history-title">
        <h2 id="nx-tap-history-title">최근 결과</h2>
        {recent.length === 0 ? <p className="nx-tap-empty">아직 결과가 난 칸이 없어요. 격자에서 칸을 눌러 걸어 보세요.</p> : (
          <ol>
            {recent.map((bet) => (
              <li key={bet.id} className={`is-${bet.status.toLowerCase()}`}>
                <span className="nx-num">{won(bet.bandLow)}~</span>
                <span className="nx-num">{bet.multiplier.toFixed(1)}× · {shortWon(bet.stake)}</span>
                <strong className="nx-num">{bet.status === "WON" ? `+${won(bet.payout)}` : bet.status === "VOID" ? "환불" : `-${won(bet.stake)}`}</strong>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
