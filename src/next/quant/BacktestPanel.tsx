import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import { StudyChart, type StudyMarker, type StudyPane } from "../../components/StudyChart";
import { backtestQuant, failure, isAbort, type BacktestResult, type Source } from "../../lib/research";
import { kstDateTime, serverTimestamp, won } from "../../market/format";

const priceFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });
const decimalFormat = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 2 });
const percent = (value: number) => `${value > 0 ? "+" : ""}${value.toFixed(2)}%`;
const digits = (text: string) => text.replace(/[^0-9]/g, "");

/** 백테스트와 지표 미리보기. 실제 주문은 내지 않는다. */
export function BacktestPanel({ stockCode, range, code, ready }: { stockCode: string; range: string; code: Source | null; ready: boolean }) {
  const id = useId();
  const [cash, setCash] = useState("1000000");
  const [fee, setFee] = useState("10");
  const [slippage, setSlippage] = useState("5");
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const controller = useRef<AbortController | null>(null);

  useEffect(() => () => controller.current?.abort(), []);

  const cashValue = Number(cash);
  const feeValue = Number(fee);
  const slippageValue = Number(slippage);
  const issue = !cash || cashValue < 1 || cashValue > 1e12 ? "초기 자금은 1원~1조 원이에요."
    : fee === "" || feeValue > 1000 || slippage === "" || slippageValue > 1000 ? "수수료와 슬리피지는 0~1,000bp예요." : "";

  const run = async (event: FormEvent) => {
    event.preventDefault();
    if (!code || issue || busy) return;
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setBusy(true);
    setError("");
    try {
      setResult(await backtestQuant({ stockCode, range, code, initialCash: cashValue, feeBps: feeValue, slippageBps: slippageValue }, current.signal));
    } catch (reason) {
      if (!isAbort(reason)) setError(failure(reason, "백테스트를 실행하지 못했어요."));
    } finally {
      if (controller.current === current) setBusy(false);
    }
  };

  const times = useMemo(() => result?.equity.map((point) => serverTimestamp(point.time)) ?? [], [result]);
  const panes = useMemo<StudyPane[]>(() => {
    if (!result) return [];
    const indexOf = new Map(result.equity.map((point, index) => [point.time, index]));
    const markers: StudyMarker[] = result.trades.flatMap((trade) => {
      const index = indexOf.get(trade.filledAt);
      return index === undefined ? [] : [{ index, side: trade.side, label: `${trade.side === "BUY" ? "매수" : "매도"} ${trade.quantity}주 · ${decimalFormat.format(trade.price)}원` }];
    });
    const plots = Object.entries(result.plots);
    return [
      { id: "equity", title: "평가 자산", height: 200, series: [{ key: "equity", label: "자산", values: result.equity.map((point) => point.equity), tone: 1 }],
        guides: [result.initialCash], format: (value) => priceFormat.format(value), markers },
      ...plots.map(([name, values], index): StudyPane => ({
        id: `plot-${name}`, title: name, height: 110, series: [{ key: name, label: name, values, tone: (index % 5) + 2 }], format: (value) => decimalFormat.format(value),
      })),
    ];
  }, [result]);

  return (
    <section className="nx-panel qx-backtest" aria-labelledby={`${id}-title`}>
      <header className="nx-panel-head">
        <h2 id={`${id}-title`} className="nx-panel-title">백테스트 · 미리보기</h2>
        <span className="nx-muted">실제 주문은 나가지 않아요</span>
      </header>
      <form className="qx-backtest-form" onSubmit={run} noValidate>
        <div className="nx-field">
          <label htmlFor={`${id}-cash`}>초기 자금</label>
          <div className="nx-stepper is-plain">
            <input id={`${id}-cash`} className="nx-num" inputMode="numeric" value={cash ? Number(cash).toLocaleString("ko-KR") : ""} onChange={(event) => setCash(digits(event.target.value).slice(0, 13))} />
            <span className="nx-stepper-unit">원</span>
          </div>
        </div>
        <div className="nx-field">
          <label htmlFor={`${id}-fee`}>수수료</label>
          <div className="nx-stepper is-plain">
            <input id={`${id}-fee`} className="nx-num" inputMode="numeric" value={fee} onChange={(event) => setFee(digits(event.target.value).slice(0, 4))} />
            <span className="nx-stepper-unit">bp</span>
          </div>
        </div>
        <div className="nx-field">
          <label htmlFor={`${id}-slip`}>슬리피지</label>
          <div className="nx-stepper is-plain">
            <input id={`${id}-slip`} className="nx-num" inputMode="numeric" value={slippage} onChange={(event) => setSlippage(digits(event.target.value).slice(0, 4))} />
            <span className="nx-stepper-unit">bp</span>
          </div>
        </div>
        <button type="submit" className="nx-btn is-primary" disabled={!ready || !code || busy || Boolean(issue)}>{busy ? "계산 중…" : "백테스트 실행"}</button>
      </form>
      <p className={`nx-field-hint${issue ? " is-error" : ""}`}>
        {issue || (!ready ? "컴파일 오류를 먼저 고쳐 주세요." : "1bp = 0.01%. 신호가 난 다음 캔들 시가에 체결하고, 롱 포지션만 가정해요.")}
      </p>
      {error && <p className="nx-note is-error" role="alert">{error}</p>}
      {result && (
        <div className="qx-result" aria-live="polite">
          <dl className="qx-stats">
            <div><dt>최종 자산</dt><dd className="nx-num">{won(result.finalEquity)}</dd></div>
            <div><dt>수익률</dt><dd className={`nx-num ${result.returnPercent > 0 ? "is-up" : result.returnPercent < 0 ? "is-down" : ""}`}>{percent(result.returnPercent)}</dd></div>
            <div><dt>최대 낙폭</dt><dd className="nx-num">{result.maxDrawdownPercent.toFixed(2)}%</dd></div>
            <div><dt>체결</dt><dd className="nx-num">{result.trades.length}건</dd></div>
            <div><dt>거절</dt><dd className="nx-num">{result.rejectedOrders}건</dd></div>
          </dl>
          {times.length > 0 ? <StudyChart times={times} panes={panes} label={`${stockCode} 백테스트 자산 곡선과 그래프`} />
            : <p className="nx-empty-state">완성된 캔들이 아직 없어 계산할 구간이 없어요.</p>}
          {result.trades.length > 0 && (
            <div className="nx-scroll-x qx-trades">
              <table className="nx-grid-table">
                <caption className="vh">백테스트 체결 내역</caption>
                <thead><tr><th scope="col">체결 시각</th><th scope="col">구분</th><th scope="col">수량</th><th scope="col">체결가</th><th scope="col">수수료</th><th scope="col">신호 시각</th></tr></thead>
                <tbody>
                  {result.trades.slice().reverse().map((trade, index) => (
                    <tr key={index}>
                      <th scope="row" className="nx-num">{kstDateTime(serverTimestamp(trade.filledAt))}</th>
                      <td><span className={`nx-side is-${trade.side === "BUY" ? "buy" : "sell"}`}>{trade.side === "BUY" ? "매수" : "매도"}</span></td>
                      <td className="nx-num">{trade.quantity.toLocaleString("ko-KR")}주</td>
                      <td className="nx-num">{decimalFormat.format(trade.price)}원</td>
                      <td className="nx-num">{won(trade.fee)}</td>
                      <td className="nx-num">{kstDateTime(serverTimestamp(trade.signalAt))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="nx-field-note">공매도·레버리지·분할 체결·미체결·가격 제한은 반영하지 않아요. 마지막 포지션은 팔지 않고 종가로 평가해요. 실제 자동매매(지정가)와 결과가 다를 수 있어요.</p>
        </div>
      )}
    </section>
  );
}
