import { useCallback, useEffect, useId, useState, type FormEvent } from "react";
import { apiFetch } from "../../lib/api";
import { useAuthUser } from "../../lib/auth";
import { kstDateTime, serverTimestamp, won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import type { LoanEntry, LoanStatus } from "../../market/types";
import { AccountTabs } from "../AccountTabs";

/* 대출 규칙과 서버 호출은 기존 대출 화면과 같다. 배치와 게이지만 새로 그렸다. */

const KIND_LABEL: Record<LoanEntry["kind"], string> = { BORROW: "대출", REPAY: "상환", INTEREST: "이자", LIQUIDATION: "반대매매 상환" };
const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];
/** 담보비율 게이지는 100%~300% 구간을 그린다. 300%가 넘으면 꽉 찬다. */
const GAUGE_MAX = 3;
const gaugeShare = (ratio: number) => Math.max(0, Math.min(1, (ratio - 1) / (GAUGE_MAX - 1)));
const percent = (ratio: number, digits = 0) => `${(ratio * 100).toFixed(digits)}%`;
const parseAmount = (value: string) => Number(value.replace(/[^0-9]/g, ""));

function Gauge({ loan }: { loan: LoanStatus }) {
  const ratio = loan.collateralRatio;
  const zone = ratio === null ? "is-none" : ratio < loan.liquidationRatio ? "is-danger" : ratio < loan.warningRatio ? "is-warning" : "is-safe";
  return (
    <div className={`nx-gauge ${zone}`}>
      <div className="nx-gauge-head">
        <span>담보비율 <small>총 자산 ÷ 대출 잔액</small></span>
        <strong className="nx-num">{ratio === null ? "대출 없음" : percent(ratio)}</strong>
      </div>
      <div className="nx-gauge-track" aria-hidden="true">
        <span className="nx-gauge-zone is-danger" style={{ width: `${gaugeShare(loan.liquidationRatio) * 100}%` }} />
        <span className="nx-gauge-zone is-warning" style={{ left: `${gaugeShare(loan.liquidationRatio) * 100}%`, width: `${(gaugeShare(loan.warningRatio) - gaugeShare(loan.liquidationRatio)) * 100}%` }} />
        {ratio !== null && <span className="nx-gauge-now" style={{ left: `${gaugeShare(ratio) * 100}%` }} />}
      </div>
      <div className="nx-gauge-scale nx-num" aria-hidden="true">
        <span style={{ left: 0 }}>100%</span>
        <span style={{ left: `${gaugeShare(loan.liquidationRatio) * 100}%` }}>반대매매 {percent(loan.liquidationRatio)}</span>
        <span style={{ left: `${gaugeShare(loan.warningRatio) * 100}%` }}>경고 {percent(loan.warningRatio)}</span>
        <span style={{ left: "100%" }}>300%+</span>
      </div>
    </div>
  );
}

export function NxLoanPage() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const borrowId = useId();
  const repayId = useId();
  const [loan, setLoan] = useState<LoanStatus | null>(null);
  const [loadError, setLoadError] = useState("");
  const [borrowAmount, setBorrowAmount] = useState("");
  const [repayAmount, setRepayAmount] = useState("");
  const [pending, setPending] = useState<"borrow" | "repay" | null>(null);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const next = await apiFetch<LoanStatus>("/api/loan", { signal });
      if (signal?.aborted) return;
      setLoan(next);
      setLoadError("");
    } catch (cause) {
      if (!signal?.aborted) setLoadError(cause instanceof Error ? cause.message : "대출 정보를 불러오지 못했습니다.");
    }
  }, []);

  // 한도와 담보비율은 시세에 따라 바뀌므로 10초마다 다시 읽는다.
  useEffect(() => {
    if (!user) return undefined;
    const controller = new AbortController();
    void load(controller.signal);
    const timer = window.setInterval(() => void load(controller.signal), 10_000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [user?.uid, load]);

  const cash = snapshot.portfolio.cash;
  const balance = loan?.balance ?? snapshot.portfolio.loanBalance ?? 0;
  const repayMax = Math.min(cash, balance);
  const borrowValue = parseAmount(borrowAmount);
  const repayValue = parseAmount(repayAmount);

  const act = async (kind: "borrow" | "repay", amount: number) => {
    if (pending) return;
    setPending(kind);
    setMessage(null);
    try {
      const result = await api.loanAction(kind, amount);
      setLoan(result.loan);
      setMessage({ text: result.message, ok: true });
      if (kind === "borrow") setBorrowAmount(""); else setRepayAmount("");
    } catch (cause) {
      setMessage({ text: cause instanceof Error ? cause.message : "요청을 처리하지 못했습니다.", ok: false });
    } finally {
      setPending(null);
    }
  };

  const submitBorrow = (event: FormEvent) => {
    event.preventDefault();
    if (loan && borrowValue >= loan.minAmount && borrowValue <= loan.available) void act("borrow", borrowValue);
  };
  const submitRepay = (event: FormEvent) => {
    event.preventDefault();
    const amount = repayAmount === "" ? repayMax : repayValue;
    if (amount > 0 && amount <= repayMax) void act("repay", amount);
  };

  const borrowError = !loan || borrowAmount === "" ? ""
    : borrowValue < loan.minAmount ? `${won(loan.minAmount)}부터 빌릴 수 있어요.`
    : borrowValue > loan.available ? `지금은 최대 ${won(loan.available)}까지 빌릴 수 있어요.` : "";
  const repayError = repayAmount === "" ? "" : repayValue < 1 ? "갚을 금액을 입력해 주세요."
    : repayValue > repayMax ? `지금은 최대 ${won(repayMax)}까지 갚을 수 있어요.` : "";
  const blockedReason = !loan || !loan.borrowBlocked || loan.warning ? ""
    : loan.limit - loan.balance < loan.minAmount ? `남은 한도가 최소 대출 금액 ${won(loan.minAmount)}보다 적어 지금은 빌릴 수 없어요.`
    : "관리자 계정은 대출을 이용할 수 없어요.";
  const ratio = loan?.collateralRatio ?? null;

  return (
    <div className="nx-screen nx-loan">
      <AccountTabs group="funds" active="loan" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">대출</h1>
          <p className="nx-screen-sub">모의 자금을 빌려 더 크게 투자할 수 있어요. 빌린 돈은 수익률과 랭킹에 넣지 않고, 실제 돈은 오가지 않아요.</p>
        </div>
      </header>

      {loadError && !loan && <p className="nx-note is-error" role="alert">{loadError} <button type="button" className="nx-link-button" onClick={() => void load()}>다시 시도</button></p>}
      {!loan && !loadError && <div className="nx-skeleton" role="status" aria-label="대출 정보를 불러오는 중"><span /><span /><span /></div>}

      {loan && (
        <>
          {loan.warning && (
            <p className={`nx-note ${ratio !== null && ratio < loan.liquidationRatio ? "is-error" : "is-guard"}`} role="alert">
              담보비율이 {percent(ratio ?? 0)}예요. {percent(loan.warningRatio)} 밑에서는 새로 빌릴 수 없고,
              {" "}{percent(loan.liquidationRatio)} 밑으로 내려가면 보유 주식이 자동으로 팔려 대출을 갚아요(반대매매).
            </p>
          )}

          <section className="nx-loan-hero" aria-label="대출 현황">
            <div>
              <p className="nx-panel-title">대출 잔액</p>
              <span className="nx-num nx-big">{won(loan.balance)}</span>
            </div>
            <dl className="nx-figures">
              <div><dt>대출 한도</dt><dd className="nx-num">{won(loan.limit)}</dd></div>
              <div><dt>지금 빌릴 수 있는 금액</dt><dd className="nx-num">{won(loan.available)}</dd></div>
              <div><dt>순자산</dt><dd className="nx-num">{won(loan.netAsset)}</dd></div>
            </dl>
          </section>

          <section className="nx-panel nx-loan-gauge" aria-label="담보비율">
            <Gauge loan={loan} />
            {blockedReason && <p className="nx-note is-guard" role="status">{blockedReason}</p>}
            <ul className="nx-loan-rules">
              <li>한도는 순자산의 {percent(loan.limitRatio)}예요.</li>
              <li>이자는 하루 {percent(loan.dailyInterestRate, 1)}이고, 한국시간 자정마다 잔액에 붙어요.</li>
              <li>언제든 일부 또는 전액을 수수료 없이 갚을 수 있어요.</li>
            </ul>
          </section>

          {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}

          <div className="nx-board nx-loan-forms">
            <section className="nx-panel" aria-labelledby="nx-borrow-title">
              <header className="nx-panel-head">
                <h2 id="nx-borrow-title" className="nx-panel-title">빌리기</h2>
                <span className="nx-muted nx-num">최대 {won(loan.available)}</span>
              </header>
              <form className="nx-form" onSubmit={submitBorrow} noValidate>
                <div className="nx-field">
                  <label htmlFor={borrowId}>빌릴 금액</label>
                  <div className={`nx-stepper is-plain${loan.borrowBlocked ? " is-disabled" : ""}`}>
                    <input id={borrowId} inputMode="numeric" autoComplete="off" className="nx-num" placeholder={loan.borrowBlocked ? "지금은 빌릴 수 없어요" : "0"}
                      value={borrowAmount ? borrowValue.toLocaleString("ko-KR") : ""} disabled={loan.borrowBlocked || pending !== null}
                      onChange={(event) => setBorrowAmount(event.target.value.replace(/[^0-9]/g, ""))} aria-invalid={Boolean(borrowError)} />
                    <span className="nx-stepper-unit">원</span>
                  </div>
                </div>
                <div className="nx-ratios">
                  {RATIOS.map((item) => (
                    <button key={item.label} type="button" disabled={loan.borrowBlocked || pending !== null}
                      onClick={() => setBorrowAmount(String(Math.floor(loan.available * item.ratio)))}>{item.label}</button>
                  ))}
                </div>
                <p className={`nx-field-hint${borrowError ? " is-error" : ""}`}>
                  {borrowError || (borrowValue > 0 ? `하루 이자 약 ${won(Math.ceil(borrowValue * loan.dailyInterestRate))}` : "")}
                </p>
                <button type="submit" className="nx-btn is-primary is-wide" disabled={loan.borrowBlocked || pending !== null || !borrowAmount || Boolean(borrowError)}>
                  {pending === "borrow" ? "처리 중…" : "대출받기"}
                </button>
              </form>
            </section>

            <section className="nx-panel" aria-labelledby="nx-repay-title">
              <header className="nx-panel-head">
                <h2 id="nx-repay-title" className="nx-panel-title">갚기</h2>
                <span className="nx-muted nx-num">보유 현금 {won(cash)}</span>
              </header>
              <form className="nx-form" onSubmit={submitRepay} noValidate>
                <div className="nx-field">
                  <div className="nx-field-head">
                    <label htmlFor={repayId}>갚을 금액</label>
                    <span className="nx-field-note">비워 두면 갚을 수 있는 만큼 전부</span>
                  </div>
                  <div className={`nx-stepper is-plain${balance === 0 ? " is-disabled" : ""}`}>
                    <input id={repayId} inputMode="numeric" autoComplete="off" className="nx-num" placeholder={balance === 0 ? "갚을 대출이 없어요" : won(repayMax)}
                      value={repayAmount ? repayValue.toLocaleString("ko-KR") : ""} disabled={balance === 0 || pending !== null}
                      onChange={(event) => setRepayAmount(event.target.value.replace(/[^0-9]/g, ""))} aria-invalid={Boolean(repayError)} />
                    <span className="nx-stepper-unit">원</span>
                  </div>
                </div>
                <p className={`nx-field-hint${repayError ? " is-error" : ""}`}>
                  {repayError || (balance > cash ? "보유 현금이 잔액보다 적어요. 주식을 팔면 더 갚을 수 있어요." : "")}
                </p>
                <button type="submit" className="nx-btn is-primary is-wide" disabled={balance === 0 || repayMax === 0 || pending !== null || Boolean(repayError)}>
                  {pending === "repay" ? "처리 중…" : repayAmount === "" ? "전액 상환" : "상환하기"}
                </button>
              </form>
            </section>
          </div>

          <section className="nx-panel" aria-labelledby="nx-loan-history">
            <header className="nx-panel-head">
              <h2 id="nx-loan-history" className="nx-panel-title">대출 내역</h2>
              <span className="nx-muted nx-num">최근 {loan.history.length}건</span>
            </header>
            {loan.history.length === 0 ? <p className="nx-empty-state">아직 대출 내역이 없어요.</p> : (
              <div className="nx-scroll-x">
                <table className="nx-grid-table">
                  <caption className="vh">최근 대출·상환·이자 내역</caption>
                  <thead><tr><th scope="col">일시</th><th scope="col">구분</th><th scope="col">금액</th><th scope="col">남은 잔액</th></tr></thead>
                  <tbody>
                    {loan.history.map((entry) => (
                      <tr key={entry.id}>
                        <th scope="row" className="nx-num">{kstDateTime(serverTimestamp(entry.createdAt))}</th>
                        <td><span className={`nx-side is-${entry.kind === "BORROW" || entry.kind === "INTEREST" ? "buy" : "sell"}`}>{KIND_LABEL[entry.kind] ?? entry.kind}</span></td>
                        <td className="nx-num">{won(entry.amount)}</td>
                        <td className="nx-num">{won(entry.balanceAfter)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
