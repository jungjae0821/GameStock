import { useId, useState } from "react";
import { Delta } from "../../../components/Delta";
import { PriceCell } from "../../../components/PriceCell";
import { SeriesChart } from "../../../components/SeriesChart";
import { useProfile } from "../../../lib/profile";
import { indexValue, kstDateTime, serverTimestamp, signedNumber, signedWon, won } from "../../../market/format";
import type { DerivativeAccount, DerivativeOrderRequest, DerivativePosition, DerivativeSnapshot, DerivativeTrade, FutureQuote } from "../../../market/derivatives";
import { AnimatedNav } from "../../AnimatedNav";
import { OrderDock } from "../../trade/OrderDock";

/*
 * 지수 선물 거래 화면. 주식 거래 화면(TradeTerminal)과 같은 판을 쓴다:
 * 왼쪽에 시세·차트·상품 정보·내 포지션, 오른쪽에 붙어 따라오는 주문서. 휴대폰에서는 주문서가 아래 시트로 뜬다.
 */

/** 선물 수수료율. 서버 DerivativePricing.FUTURE_FEE_RATE와 같다. */
const FEE_RATE = 0.0002;
const MAX_CONTRACTS = 100;
const NAME = "게임주 지수 선물";

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

const ACTION_LABEL: Record<string, string> = {
  OPEN: "신규",
  CLOSE: "청산",
  LIQUIDATE: "강제청산",
};

type Notice = { tone: "ok" | "error"; text: string } | null;
type AccountTab = "positions" | "fills";

interface Props {
  snapshot: DerivativeSnapshot;
  account: DerivativeAccount | null;
  signedIn: boolean;
  busy: boolean;
  notice: Notice;
  stale: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
}

/** 1계약에 드는 증거금과 수수료. 서버와 같이 증거금은 올림, 수수료도 올림이다. */
function contractCost(future: FutureQuote, leverage: number, quantity: number) {
  const notional = Math.round(future.price * future.multiplier * quantity);
  const margin = Math.ceil(notional / leverage);
  const fee = Math.ceil(notional * FEE_RATE);
  return { notional, margin, fee };
}

/**
 * 평가액(증거금 + 손익)이 증거금의 유지 비율까지 줄어드는 지수. 서버 refreshMarks의 강제청산 조건을 거꾸로 푼 값이다.
 * 손익 = (지수 − 진입) × 승수 × 계약 × 방향 이므로, 증거금 × (1 − 유지 비율)만큼 잃는 지점이다.
 */
function liquidationIndex(entry: number, margin: number, quantity: number, long: boolean, future: FutureQuote): number {
  const room = (margin * (1 - future.maintenanceRate)) / (future.multiplier * quantity);
  return Math.max(0, long ? entry - room : entry + room);
}

const tone = (amount: number) => amount > 0 ? "is-up" : amount < 0 ? "is-down" : "";

export function FuturesTerminal({ snapshot, account, signedIn, busy, notice, stale, onOpen, onClose }: Props) {
  const future = snapshot.future;
  const [orderOpen, setOrderOpen] = useState(false);
  const [accountTab, setAccountTab] = useState<AccountTab>("positions");
  const positions = account?.positions.filter((position) => position.kind === "FUTURE") ?? [];
  const fills = account?.trades.filter((trade) => trade.kind === "FUTURE") ?? [];

  const focusTicket = () => {
    if (window.matchMedia("(max-width: 960px)").matches) setOrderOpen(true);
    else {
      const ticket = document.querySelector<HTMLFormElement>(".nx-ticket");
      ticket?.focus({ preventScroll: true });
      ticket?.scrollIntoView({ block: "nearest" });
    }
  };

  return (
    <div className="nx-workspace">
      {stale && <p role="status" className="nx-alert">시세 갱신이 지연되어 이전 시세를 표시합니다.</p>}
      <div className="nx-trade-main">
        <section className="nx-trade-chart" aria-label={`${NAME} 시세와 차트`}>
          <FuturesHead future={future} />
          <div className="nx-chart integration-tools">
            <SeriesChart code={future.code} index={{ price: future.price, prevClose: future.prevClose }} initialMode="candle" />
          </div>
        </section>
        <FuturesFacts future={future} />
        <section className="nx-panel nx-account" aria-label="내 선물 포지션과 체결 내역" tabIndex={-1}>
          <FuturesAccount tab={accountTab} onTabChange={setAccountTab} signedIn={signedIn} loaded={Boolean(account)}
            positions={positions} fills={fills} future={future} busy={busy} onClose={onClose} onFocusTicket={focusTicket} />
        </section>
        <div className="nx-trade-details">
          <FuturesWallet account={account} signedIn={signedIn} positions={positions} />
          <FuturesRules future={future} />
        </div>
        <p className="nx-trade-disclaimer">게임주 지수를 따르는 모의 선물입니다. 실제 금융상품이나 투자 수익과 관계없습니다.</p>
      </div>
      <OrderDock title={NAME} open={orderOpen} onOpenChange={setOrderOpen}>
        <FuturesTicket future={future} cash={account?.cash ?? null} signedIn={signedIn} busy={busy} notice={notice} onOpen={onOpen} />
      </OrderDock>
    </div>
  );
}

function FuturesHead({ future }: { future: FutureQuote }) {
  return (
    <header className="nx-head">
      <div className="nx-head-id">
        <span className="nx-futures-mark" aria-hidden="true">F</span>
        <div>
          <p className="nx-head-sub"><span className="nx-code">{future.code}</span>무기한 · 게임주 지수 추종</p>
          <h1 className="nx-head-name">{NAME}</h1>
        </div>
      </div>
      <div className="nx-head-price">
        <PriceCell className="nx-num nx-price" value={future.price} format={indexValue} />
        <Delta change={future.price - future.prevClose} ratio={future.changePercent / 100} format={signedNumber} className="nx-head-delta" />
      </div>
    </header>
  );
}

function FuturesFacts({ future }: { future: FutureQuote }) {
  const maintenance = Math.round(future.maintenanceRate * 100);
  return (
    <section className="nx-trade-facts" aria-labelledby="nx-futures-info-title">
      <h2 id="nx-futures-info-title">상품 정보</h2>
      <dl className="nx-stats">
        <div><dt>기준 지수</dt><dd className="nx-num">{indexValue(future.prevClose)}</dd></div>
        <div><dt>계약 단위</dt><dd className="nx-num">1pt × {won(future.multiplier)}</dd></div>
        <div><dt>1계약 명목가</dt><dd className="nx-num">{won(Math.round(future.price * future.multiplier))}</dd></div>
        <div><dt>만기</dt><dd>없음 · 무기한</dd></div>
        <div><dt>레버리지</dt><dd className="nx-num">{future.leverages.map((item) => `${item}배`).join(" · ")}</dd></div>
        <div><dt>유지증거금</dt><dd className="nx-num">{maintenance}%</dd></div>
        <div><dt>수수료</dt><dd className="nx-num">{(FEE_RATE * 100).toFixed(2)}%</dd></div>
        <div><dt>거래 시간</dt><dd>24시간</dd></div>
      </dl>
    </section>
  );
}

function FuturesTicket({ future, cash, signedIn, busy, notice, onOpen }: {
  future: FutureQuote;
  cash: number | null;
  signedIn: boolean;
  busy: boolean;
  notice: Notice;
  onOpen: (request: DerivativeOrderRequest) => void;
}) {
  const quantityId = useId();
  const profile = useProfile();
  const [side, setSide] = useState<"LONG" | "SHORT">("LONG");
  const [leverage, setLeverage] = useState(() => future.leverages.includes(5) ? 5 : future.leverages[0] ?? 1);
  const [quantityInput, setQuantityInput] = useState("1");
  const [touched, setTouched] = useState(false);

  const quantity = Number(quantityInput);
  const valid = Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_CONTRACTS;
  const { notional, margin, fee } = contractCost(future, leverage, valid ? quantity : 0);
  const unit = contractCost(future, leverage, 1);
  const maxQty = cash === null ? 0 : Math.min(MAX_CONTRACTS, Math.floor(cash / (unit.margin + unit.fee)));
  const affordable = cash === null || margin + fee <= cash;
  const long = side === "LONG";
  const liquidation = valid ? liquidationIndex(future.price, margin, quantity, long, future) : null;
  const profileLoading = signedIn && !profile;
  const adminBlocked = Boolean(profile?.admin);
  const sideLabel = long ? "롱" : "숏";

  const quantityError = quantityInput === "" ? "" : !valid ? `계약 수는 1~${MAX_CONTRACTS} 사이의 정수로 입력해 주세요.`
    : !affordable ? `지금 현금으로는 최대 ${maxQty.toLocaleString("ko-KR")}계약까지 열 수 있어요.` : "";
  const status = (touched ? null : notice?.text) ?? (!signedIn ? "로그인 후 주문할 수 있습니다."
    : profileLoading ? "계정 권한을 확인하고 있어요."
    : adminBlocked ? "관리자 계정은 파생상품을 거래할 수 없어요."
    : valid && affordable ? `${indexValue(future.price)}에 ${quantity}계약 ${sideLabel} 포지션을 ${leverage}배로 엽니다.` : "");
  const statusTone = !touched && notice ? ` is-${notice.tone === "ok" ? "success" : "error"}` : "";

  const edit = (next: string) => { setQuantityInput(next); setTouched(true); };
  const step = (delta: number) => edit(String(Math.min(MAX_CONTRACTS, Math.max(1, (valid ? quantity : 1) + delta))));
  const disabled = busy || profileLoading || adminBlocked || !valid || !affordable;

  return (
    <form tabIndex={-1} className={`nx-ticket nx-futures-ticket is-${long ? "buy" : "sell"}`} aria-label="선물 주문 입력" aria-busy={busy}
      onSubmit={(event) => {
        event.preventDefault();
        if (disabled) return;
        setTouched(false);
        onOpen({ kind: "FUTURE", side, symbol: future.code, leverage, quantity });
      }}>
      <AnimatedNav as="div" fill activeKey={side} className="nx-ticket-sides" role="group" aria-label="포지션 방향">
        {(["LONG", "SHORT"] as const).map((option) => (
          <button key={option} type="button" className={`nx-ticket-side is-${option === "LONG" ? "buy" : "sell"}${side === option ? " is-active" : ""}`}
            aria-pressed={side === option} onClick={() => { setSide(option); setTouched(true); }}>
            {option === "LONG" ? "롱 · 오르면 이익" : "숏 · 내리면 이익"}
          </button>
        ))}
      </AnimatedNav>

      <div className="nx-field">
        <div className="nx-field-head">
          <span className="nx-futures-label">레버리지</span>
          <span className="nx-field-note">1계약 증거금 <span className="nx-num">{won(unit.margin)}</span></span>
        </div>
        <AnimatedNav as="div" fill activeKey={String(leverage)} className="nx-seg nx-futures-leverage" role="group" aria-label="레버리지">
          {future.leverages.map((item) => (
            <button key={item} type="button" className={leverage === item ? "is-active" : undefined} aria-pressed={leverage === item}
              onClick={() => { setLeverage(item); setTouched(true); }}>{item}배</button>
          ))}
        </AnimatedNav>
      </div>

      {signedIn && <dl className="nx-ticket-power">
        <div><dt>주문 가능 현금</dt><dd className="nx-num">{cash === null ? "—" : won(cash)}</dd></div>
      </dl>}

      <div className="nx-field">
        <div className="nx-field-head">
          <label htmlFor={quantityId}>계약 수</label>
          <span className="nx-field-note">1~{MAX_CONTRACTS}계약</span>
        </div>
        <div className="nx-stepper">
          <button type="button" aria-label="1계약 줄이기" disabled={!valid || quantity <= 1} onClick={() => step(-1)}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8h9" /></svg>
          </button>
          <input id={quantityId} type="number" inputMode="numeric" className="nx-num" min={1} max={MAX_CONTRACTS} step={1} value={quantityInput} placeholder="0"
            aria-invalid={Boolean(quantityError)} aria-describedby={`${quantityId}-hint`} onChange={(event) => edit(event.target.value)} />
          <span className="nx-stepper-unit">계약</span>
          <button type="button" aria-label="1계약 늘리기" disabled={valid && quantity >= MAX_CONTRACTS} onClick={() => step(1)}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8h9M8 3.5v9" /></svg>
          </button>
        </div>
        {signedIn && <div className="nx-ratios" role="group" aria-label="현금 대비 계약 수">
          {RATIOS.map((item) => (
            <button key={item.label} type="button" disabled={maxQty === 0}
              onClick={() => edit(String(Math.max(1, Math.floor(maxQty * item.ratio))))}>{item.label}</button>
          ))}
        </div>}
        <p id={`${quantityId}-hint`} className={`nx-field-hint${quantityError ? " is-error" : ""}`} aria-live="polite">{quantityError}</p>
      </div>

      <dl className="nx-ticket-total">
        <div><dt>필요 증거금</dt><dd className="nx-num nx-ticket-amount">{won(margin)}</dd></div>
        <div><dt>계약 명목가</dt><dd className="nx-num">{won(notional)}</dd></div>
        <div><dt>수수료 {(FEE_RATE * 100).toFixed(2)}%</dt><dd className="nx-num">{won(fee)}</dd></div>
        <div><dt>강제청산 예상 지수</dt><dd className="nx-num">{liquidation === null ? "—" : indexValue(liquidation)}</dd></div>
      </dl>

      <button type="submit" className={`nx-ticket-submit is-${long ? "buy" : "sell"}`} disabled={disabled}>
        {busy ? "주문 처리 중…" : profileLoading ? "권한 확인 중…" : adminBlocked ? "관리자 계정 주문 불가"
          : valid ? `${quantity}계약 ${sideLabel} 열기 · ${won(margin + fee)}` : `${sideLabel} 주문`}
      </button>

      <p className={`nx-ticket-status${statusTone}`} role="status" aria-live="polite">{status}</p>
    </form>
  );
}

function FuturesAccount({ tab, onTabChange, signedIn, loaded, positions, fills, future, busy, onClose, onFocusTicket }: {
  tab: AccountTab;
  onTabChange: (tab: AccountTab) => void;
  signedIn: boolean;
  loaded: boolean;
  positions: DerivativePosition[];
  fills: DerivativeTrade[];
  future: FutureQuote;
  busy: boolean;
  onClose: (positionId: number, quantity: number) => void;
  onFocusTicket: () => void;
}) {
  const count = tab === "positions" ? positions.length : fills.length;
  const emptyText = !signedIn ? "로그인하면 선물 포지션과 체결 내역을 볼 수 있어요."
    : !loaded ? "계좌를 확인하고 있어요."
    : tab === "positions" ? "열려 있는 선물 포지션이 없어요."
    : "선물 체결 내역이 아직 없어요.";

  return (
    <div className="nx-acct">
      <h2 className="nx-acct-title">내 포지션</h2>
      <div className="nx-acct-bar">
        <AnimatedNav as="div" activeKey={tab} className="nx-tabs" role="group" aria-label="포지션 내역 선택">
          {([["positions", "보유 포지션"], ["fills", "체결 내역"]] as const).map(([value, label]) => (
            <button key={value} type="button" className={tab === value ? "is-active" : undefined} aria-pressed={tab === value} onClick={() => onTabChange(value)}>
              {label}{value === "positions" && positions.length > 0 && <span className="nx-tab-count nx-num">{positions.length}</span>}
            </button>
          ))}
        </AnimatedNav>
      </div>

      <div className="nx-acct-scroll" tabIndex={0} role="region" aria-label="선물 내역 표">
        {count === 0 ? (
          <div className="nx-acct-empty">
            <p>{emptyText}</p>
            {signedIn && loaded && tab === "positions" && <button type="button" className="nx-btn" onClick={onFocusTicket}>주문 입력하기</button>}
          </div>
        ) : tab === "positions" ? (
          <table className="nx-grid-table">
            <caption className="vh">보유 선물 포지션</caption>
            <thead><tr>
              <th scope="col">포지션</th><th scope="col">계약</th><th scope="col">진입 지수</th>
              <th scope="col">강제청산 지수</th><th scope="col">증거금</th><th scope="col">평가손익</th><th scope="col"><span className="vh">청산</span></th>
            </tr></thead>
            <tbody>
              {positions.map((position) => {
                const long = position.side === "LONG";
                return <tr key={position.id}>
                  <th scope="row"><span className={`nx-side is-${long ? "buy" : "sell"}`}>{long ? "롱" : "숏"} {position.leverage}배</span></th>
                  <td className="nx-num">{position.quantity}계약</td>
                  <td className="nx-num">{indexValue(position.entryPrice)}</td>
                  <td className="nx-num">{indexValue(liquidationIndex(position.entryPrice, position.margin, position.quantity, long, future))}</td>
                  <td className="nx-num">{won(position.margin)}</td>
                  <td><Delta change={position.unrealizedPnl} ratio={position.unrealizedPercent / 100} /></td>
                  <td><button type="button" className="nx-btn is-small" disabled={busy}
                    aria-label={`${long ? "롱" : "숏"} ${position.leverage}배 ${position.quantity}계약 전량 청산`}
                    onClick={() => onClose(position.id, position.quantity)}>청산</button></td>
                </tr>;
              })}
            </tbody>
          </table>
        ) : (
          <table className="nx-grid-table">
            <caption className="vh">선물 체결 내역</caption>
            <thead><tr>
              <th scope="col">구분</th><th scope="col">방향</th><th scope="col">계약</th>
              <th scope="col">지수</th><th scope="col">현금 흐름</th><th scope="col">시각</th>
            </tr></thead>
            <tbody>
              {fills.map((trade) => <tr key={trade.id}>
                <th scope="row">{ACTION_LABEL[trade.action] ?? trade.action}</th>
                <td><span className={`nx-side is-${trade.side === "LONG" ? "buy" : "sell"}`}>{trade.side === "LONG" ? "롱" : "숏"}</span></td>
                <td className="nx-num">{trade.quantity}계약</td>
                <td className="nx-num">{indexValue(trade.price)}</td>
                <td className={`nx-num ${tone(trade.cashFlow)}`}>{signedWon(trade.cashFlow)}</td>
                <td className="nx-num">{kstDateTime(serverTimestamp(trade.createdAt))}</td>
              </tr>)}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

/** 선물에 묶인 돈만 모은 요약. 확정 손익·수수료는 계좌 전체(ETF·옵션 포함) 누적이라 따로 밝힌다. */
function FuturesWallet({ account, signedIn, positions }: { account: DerivativeAccount | null; signedIn: boolean; positions: DerivativePosition[] }) {
  const margin = positions.reduce((sum, position) => sum + position.margin, 0);
  const value = positions.reduce((sum, position) => sum + position.value, 0);
  const pnl = positions.reduce((sum, position) => sum + position.unrealizedPnl, 0);
  const show = (amount: number, signed = false) => account ? (signed ? signedWon(amount) : won(amount)) : "—";
  return (
    <section className="nx-futures-wallet" aria-labelledby="nx-futures-wallet-title">
      <h2 id="nx-futures-wallet-title">선물 계좌</h2>
      {!signedIn ? <p className="nx-muted">로그인하면 증거금과 평가손익을 볼 수 있어요.</p> : <>
        <dl className="nx-ticket-power" aria-busy={!account}>
          <div><dt>주문 가능 현금</dt><dd className="nx-num">{show(account?.cash ?? 0)}</dd></div>
          <div><dt>묶인 증거금</dt><dd className="nx-num">{show(margin)}</dd></div>
          <div><dt>선물 평가액</dt><dd className="nx-num">{show(value)}</dd></div>
          <div><dt>선물 평가손익</dt><dd className={`nx-num ${account ? tone(pnl) : ""}`}>{show(pnl, true)}</dd></div>
        </dl>
        {account && <p className="nx-futures-note">
          계좌 전체 누적 · 확정 손익 <span className={`nx-num ${tone(account.realizedPnl)}`}>{signedWon(account.realizedPnl)}</span>
          {" "}· 수수료 <span className="nx-num">{won(account.totalFees)}</span>
        </p>}
      </>}
    </section>
  );
}

function FuturesRules({ future }: { future: FutureQuote }) {
  const maintenance = Math.round(future.maintenanceRate * 100);
  return (
    <section className="nx-futures-rules" aria-labelledby="nx-futures-rules-title">
      <h2 id="nx-futures-rules-title">거래 방법</h2>
      <ol>
        <li><strong>방향 고르기</strong><span>지수가 오를 것 같으면 롱, 내릴 것 같으면 숏을 엽니다.</span></li>
        <li><strong>증거금 맡기기</strong><span>계약 명목가를 레버리지로 나눈 만큼만 현금에서 빠집니다. 지수 1포인트가 1계약당 {won(future.multiplier)}입니다.</span></li>
        <li><strong>청산하기</strong><span>만기가 없어 원할 때 청산합니다. 평가액이 증거금의 {maintenance}% 아래로 내려가면 자동으로 청산되고, 손실은 맡긴 증거금을 넘지 않습니다.</span></li>
      </ol>
    </section>
  );
}
