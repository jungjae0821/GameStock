import { Fragment, useMemo, useState } from "react";
import { Delta } from "../../../components/Delta";
import { countdown, useNow, type DerivativeAccount, type DerivativeOrderRequest, type DerivativePosition, type DerivativeSnapshot, type OptionContract } from "../../../market/derivatives";
import { indexValue, rate, won } from "../../../market/format";
import { entryCost, impliedVolatility, optionDelta, optionFee, type OptionLeg } from "../../../market/optionMath";
import { AnimatedNav } from "../../AnimatedNav";
import { OptionFlowChart } from "./OptionFlowChart";
import { OptionProfitCalculator } from "./OptionProfitCalculator";

interface Props {
  snapshot: DerivativeSnapshot;
  account: DerivativeAccount | null;
  busy: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
}

type CycleId = "10M" | "1H" | "SUN";
type Side = "CALL" | "PUT";

/** 시간가치 감소를 읽는 단위. 회차 길이에 맞춰 1분·10분·하루. */
const DECAY_UNIT: Record<CycleId, number> = { "10M": 60, "1H": 600, SUN: 86400 };
const expiryFormatter = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

/**
 * 10분(0DTE)·1시간(주간)·일요일 18:00(월간) 만기 옵션.
 * 토스증권처럼 만기 → 콜/풋 → 행사가 순으로 고르고, 고른 계약은 지수 차트와 수익 계산기에 바로 겹쳐 본다.
 */
export function OptionsPanel({ snapshot, account, busy, onOpen, onClose }: Props) {
  const [cycleId, setCycleId] = useState<CycleId>("10M");
  const [side, setSide] = useState<Side>("CALL");
  const [strikeChoice, setStrikeChoice] = useState<number | null>(null);
  const [quantityInput, setQuantityInput] = useState("1");
  const [focusId, setFocusId] = useState<number | null>(null);
  const now = useNow(1000);
  const spot = snapshot.indexValue;
  const nowEpoch = Date.parse(snapshot.serverTime) / 1000;
  const volatility = useMemo(() => impliedVolatility(snapshot), [snapshot]);
  const cycle = snapshot.cycles.find((item) => item.id === cycleId) ?? snapshot.cycles[0];
  const chain = cycle
    ? snapshot.options.filter((contract) => contract.cycleId === cycle.id && contract.type === side).sort((left, right) => left.strike - right.strike)
    : [];
  const atTheMoney = chain.reduce<number | null>((best, contract) =>
    best === null || Math.abs(contract.strike - spot) < Math.abs(best - spot) ? contract.strike : best, null);
  // 고른 행사가가 새 회차에 없으면 등가격으로 돌아간다. 화면이 빈 티켓으로 남지 않게 늘 한 계약을 고른 상태로 둔다.
  const selected = chain.find((contract) => contract.strike === strikeChoice) ?? chain.find((contract) => contract.strike === atTheMoney);
  const positions = account?.positions.filter((position) => position.kind === "OPTION") ?? [];
  const focused = positions.find((position) => position.id === focusId) ?? null;
  const quantity = Number(quantityInput);
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= 100;
  const premium = selected ? selected.premium * (validQuantity ? quantity : 0) : 0;
  const fee = optionFee(premium);
  const call = side === "CALL";
  const spotIndex = chain.findIndex((contract) => contract.strike > spot);

  const leg: OptionLeg | null = focused && focused.expiryAt
    ? { call: focused.optionType === "CALL", strike: focused.strike, expiryEpoch: Date.parse(focused.expiryAt) / 1000, quantity: focused.quantity, cost: focused.entryValue }
    : selected ? { call, strike: selected.strike, expiryEpoch: selected.expiryEpoch, quantity: validQuantity ? quantity : 1, cost: entryCost(selected.premium, validQuantity ? quantity : 1) } : null;
  const focusedCycle = (focused?.symbol.split("-")[0] ?? cycleId) as CycleId;

  const choose = (contract: OptionContract) => {
    setStrikeChoice(contract.strike);
    setFocusId(null);
  };
  const step = (delta: number) => setQuantityInput(String(Math.min(100, Math.max(1, (validQuantity ? quantity : 1) + delta))));

  return (
    <div className="derivative-panel opt-panel">
      <AnimatedNav as="div" fill activeKey={cycleId} className="opt-cycles" role="group" aria-label="옵션 만기 회차">
        {snapshot.cycles.map((item) => {
          const active = item.id === cycleId;
          return (
            <button type="button" key={item.id} className={`opt-cycle${active ? " is-active" : ""}`} aria-pressed={active}
              onClick={() => { setCycleId(item.id as CycleId); setFocusId(null); }}>
              <span className="opt-cycle-name"><em>{item.tag}</em>{item.label}</span>
              <span className="opt-cycle-count num">{countdown(item.expiryEpoch, now)}</span>
              <span className="opt-cycle-expiry num">{expiryFormatter.format(item.expiryEpoch * 1000)} 만기</span>
            </button>
          );
        })}
      </AnimatedNav>

      <div className="opt-workspace">
        <div className="opt-main">
          <OptionFlowChart spot={spot} prevClose={snapshot.indexPrevClose} changePercent={snapshot.indexChangePercent} recent={snapshot.future.series}
            strike={selected?.strike ?? null} breakeven={selected?.breakeven ?? null} />

          <section className="derivative-card opt-chain-card">
            <header className="opt-chain-head">
              <h2>옵션 체인</h2>
              <AnimatedNav as="div" fill activeKey={side} className="terminal-tabs derivative-side-tabs opt-side-tabs" role="group" aria-label="옵션 종류">
                <button type="button" className={`terminal-tab is-buy${call ? " is-active" : ""}`} aria-pressed={call}
                  onClick={() => { setSide("CALL"); setFocusId(null); }}>콜 · 오르면 이익</button>
                <button type="button" className={`terminal-tab is-sell${!call ? " is-active" : ""}`} aria-pressed={!call}
                  onClick={() => { setSide("PUT"); setFocusId(null); }}>풋 · 내리면 이익</button>
              </AnimatedNav>
            </header>
            {chain.length === 0 ? (
              <p className="empty">표시할 옵션 계약이 없습니다.</p>
            ) : (
              <div className="table-scroll">
                <table className={`table opt-chain is-${call ? "call" : "put"}`}>
                  <caption className="vh">{cycle?.label} {call ? "콜" : "풋"} 옵션 체인</caption>
                  <thead>
                    <tr>
                      <th scope="col">행사가</th>
                      <th scope="col" className="cell-num">손익분기까지</th>
                      <th scope="col" className="cell-num opt-col-delta">델타</th>
                      <th scope="col" className="cell-num">프리미엄</th>
                    </tr>
                  </thead>
                  <tbody>
                    {chain.map((contract, index) => {
                      const inTheMoney = call ? contract.strike < spot : contract.strike > spot;
                      const active = !focused && contract.strike === selected?.strike;
                      const delta = optionDelta(spot, contract.strike, contract.secondsLeft, volatility, call);
                      return (
                        <Fragment key={contract.id}>
                          {index === spotIndex && <SpotLine />}
                          <tr className={`${inTheMoney ? "is-itm" : ""}${active ? " is-selected" : ""}`} onClick={() => choose(contract)}>
                            <th scope="row">
                              <span className="num">{indexValue(contract.strike)}</span>
                              {contract.strike === atTheMoney ? <em className="opt-money is-atm">등가격</em> : inTheMoney ? <em className="opt-money">내가격</em> : null}
                            </th>
                            <td className={`num ${tone(contract.breakeven / spot - 1)}`}>{rate(contract.breakeven / spot - 1)}</td>
                            <td className="num opt-col-delta">{delta.toFixed(2)}</td>
                            <td className="cell-num">
                              <button type="button" className="opt-quote" aria-pressed={active} onClick={(event) => { event.stopPropagation(); choose(contract); }}>
                                <span className="num">{won(contract.premium)}</span>
                                <small className="num">{contract.mark.toFixed(2)}p</small>
                              </button>
                            </td>
                          </tr>
                        </Fragment>
                      );
                    })}
                    {spotIndex === -1 && <SpotLine />}
                  </tbody>
                </table>
              </div>
            )}
            <p className="derivative-note">프리미엄은 1계약(지수 1포인트 × 1,000원) 기준입니다. 손익분기까지는 만기에 본전이 되려면 지수가 지금보다 얼마나 움직여야 하는지예요.</p>
          </section>
        </div>

        <aside className="opt-aside" aria-label="옵션 주문">
          {selected && (
            <section className="derivative-ticket opt-ticket">
              <header className="opt-ticket-head">
                <span className={`opt-type is-${call ? "call" : "put"}`}>{call ? "콜" : "풋"}</span>
                <h2><span className="num">{indexValue(selected.strike)}</span> · {cycle?.label}</h2>
              </header>
              <dl className="opt-ticket-facts">
                <div><dt>1계약 프리미엄</dt><dd className="num">{won(selected.premium)}</dd></div>
                <div><dt>만기 손익분기</dt><dd className="num">{indexValue(selected.breakeven)}</dd></div>
              </dl>
              <div className="derivative-field">
                <label htmlFor="opt-quantity">계약 수</label>
                <div className="opt-stepper">
                  <button type="button" aria-label="1계약 줄이기" onClick={() => step(-1)} disabled={validQuantity && quantity <= 1}>−</button>
                  <input id="opt-quantity" type="number" inputMode="numeric" min={1} max={100} step={1} value={quantityInput}
                    onChange={(event) => setQuantityInput(event.target.value)} />
                  <button type="button" aria-label="1계약 늘리기" onClick={() => step(1)} disabled={validQuantity && quantity >= 100}>+</button>
                </div>
              </div>
              <dl className="derivative-order-summary">
                <div><dt>프리미엄 합계</dt><dd className="num">{won(premium)}</dd></div>
                <div><dt>수수료 0.3%</dt><dd className="num">{won(fee)}</dd></div>
                <div className="is-emphasis"><dt>즉시 차감 <small>= 최대 손실</small></dt><dd className="num">{won(premium + fee)}</dd></div>
              </dl>
              <button type="button" className={`derivative-submit is-${call ? "buy" : "sell"}`}
                disabled={busy || !validQuantity}
                onClick={() => onOpen({
                  kind: "OPTION", side, quantity,
                  cycleId: selected.cycleId as CycleId,
                  strike: selected.strike, expiryEpoch: selected.expiryEpoch,
                })}>
                {busy ? "처리 중…" : `${call ? "콜" : "풋"} ${validQuantity ? quantity : ""}계약 매수`}
              </button>
              <p className="derivative-note">만기에는 내재가치로 현금결제되고, 그 전에는 현재 프리미엄으로 청산할 수 있어요.</p>
            </section>
          )}
        </aside>
      </div>

      {leg && (
        <div className="opt-calc-wrap">
          <div className="opt-calc-source" role="status">
            <span className={`opt-type is-${leg.call ? "call" : "put"}`}>{leg.call ? "콜" : "풋"}</span>
            <span className="num">{indexValue(leg.strike)}</span>
            <span className="num">× {leg.quantity}</span>
            <span>{focused ? `보유 · ${focused.name}` : "주문 전 미리 보기"}</span>
            {focused && <button type="button" className="nx-link-button" onClick={() => setFocusId(null)}>고른 계약으로 돌아가기</button>}
          </div>
          <OptionProfitCalculator leg={leg} spot={spot} nowEpoch={nowEpoch} volatility={volatility}
            decayUnit={Math.min(DECAY_UNIT[focusedCycle] ?? 60, Math.max(60, leg.expiryEpoch - nowEpoch))} />
        </div>
      )}

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div><h2>보유 옵션</h2><p>만기 시 자동 결제되며, 그 전에는 청산할 수 있습니다. 계산을 누르면 수익 계산기에 불러옵니다.</p></div>
          <span className="derivative-badge">{positions.length}건</span>
        </header>
        {positions.length === 0 ? (
          <p className="empty">보유 중인 옵션 포지션이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">보유 옵션</caption>
              <thead>
                <tr><th scope="col">계약</th><th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">진입 프리미엄</th>
                  <th scope="col" className="cell-num">현재 프리미엄</th><th scope="col" className="cell-num">평가액</th>
                  <th scope="col" className="cell-num">만기</th><th scope="col" className="cell-num">평가손익</th><th scope="col" className="cell-num">관리</th></tr>
              </thead>
              <tbody>
                {positions.map((position) => <PositionRow key={position.id} position={position} now={now} busy={busy}
                  focused={position.id === focusId} onFocus={() => setFocusId(position.id)} onClose={onClose} />)}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

/** 표시 자릿수(0.01%)에서 0이면 색을 빼 "0.00%"가 오르내림처럼 보이지 않게 한다. */
function tone(ratio: number): string {
  return Math.abs(ratio) < 0.00005 ? "" : ratio > 0 ? "is-up" : "is-down";
}

function SpotLine() {
  return (
    <tr className="opt-chain-spot" aria-hidden="true">
      <td colSpan={4}><span>현재 지수</span></td>
    </tr>
  );
}

function PositionRow({ position, now, busy, focused, onFocus, onClose }: {
  position: DerivativePosition; now: number; busy: boolean; focused: boolean; onFocus: () => void; onClose: (positionId: number, quantity: number) => void;
}) {
  return (
    <tr className={focused ? "is-selected" : undefined}>
      <td>{position.name}</td>
      <td className="num">{position.quantity}</td>
      <td className="num">{position.entryPrice.toFixed(2)}p</td>
      <td className="num">{position.markPrice.toFixed(2)}p</td>
      <td className="num">{won(position.value)}</td>
      <td className="num">{position.expiryAt ? countdown(Date.parse(position.expiryAt) / 1000, now) : "–"}</td>
      <td className="cell-num"><Delta change={position.unrealizedPnl} ratio={position.unrealizedPercent / 100} /></td>
      <td className="cell-num opt-position-actions">
        <button type="button" className="derivative-close" aria-pressed={focused} disabled={!position.expiryAt} onClick={onFocus}>계산</button>
        <button type="button" className="derivative-close" disabled={busy} onClick={() => onClose(position.id, position.quantity)}>전량</button>
      </td>
    </tr>
  );
}
