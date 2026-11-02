import { useCallback, useEffect, useId, useState, type FormEvent } from "react";
import { useAuthUser } from "../../lib/auth";
import {
  buyBillboard, myBillboard, refreshBillboard, reportBillboard, slotTime, useBillboard, useMinuteClock,
  type BillboardBooking, type BillboardShowing, type BillboardState,
} from "../../lib/billboard";
import { useProfile } from "../../lib/profile";
import { won } from "../../market/format";
import { useMarket, useMarketApi } from "../../market/MarketProvider";
import { BillboardLine } from "../BillboardStrip";
import { AccountTabs } from "../AccountTabs";

const STATE_LABEL: Record<BillboardState, string> = {
  UPCOMING: "예약됨",
  LIVE: "보이는 중",
  ENDED: "끝남",
  HIDDEN: "운영자가 내림",
  CANCELLED: "취소함",
};

const length = (text: string) => [...text].length;
const squeeze = (text: string) => text.replace(/\s+/g, " ");

export function NxBillboardPage() {
  const { board, failed } = useBillboard();
  const now = useMinuteClock();
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const profile = useProfile();
  const bodyId = useId();
  const [body, setBody] = useState("");
  const [hours, setHours] = useState(1);
  const [mine, setMine] = useState<BillboardBooking[] | null>(null);
  const [mineError, setMineError] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);

  const loadMine = useCallback(async (signal?: AbortSignal) => {
    try {
      const rows = await myBillboard(signal);
      if (signal?.aborted) return;
      setMine(rows);
      setMineError(false);
    } catch {
      if (!signal?.aborted) setMineError(true);
    }
  }, []);

  useEffect(() => {
    if (!user) return undefined;
    const controller = new AbortController();
    void loadMine(controller.signal);
    return () => controller.abort();
  }, [user?.uid, loadMine]);

  const cash = snapshot.portfolio.cash;
  const nickname = profile?.nickname || "내 닉네임";
  const text = squeeze(body).trim();
  const max = board?.maxLength ?? 40;
  const min = board?.minLength ?? 2;
  const price = (board?.pricePerHour ?? 100_000) * hours;
  const ends = new Date(now + hours * 3_600_000).toISOString();
  const open = mine?.find((item) => item.state === "UPCOMING" || item.state === "LIVE") ?? null;

  const bodyError = body === "" ? "" : length(text) < min ? `${min}자 이상 적어 주세요.` : length(text) > max ? `${max}자까지 적을 수 있어요.` : "";
  const blocked = open ? "올린 문구가 끝나면 새로 올릴 수 있어요."
    : board?.nextOpening ? `전광판에 문구가 가득 찼어요. ${slotTime(board.nextOpening, now)}에 자리가 나요.`
    : cash < price ? `보유 현금이 ${won(price - cash)} 모자라요.` : "";
  const ready = Boolean(board) && text !== "" && !bodyError && !blocked && !pending;

  const finish = async (note: string) => {
    setMessage({ text: note, ok: true });
    await Promise.all([refreshBillboard(), loadMine(), api.refreshPortfolio()]);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!ready) return;
    setPending(true);
    setMessage(null);
    try {
      const result = await buyBillboard(text, hours);
      setBody("");
      await finish(result.message);
    } catch (cause) {
      setMessage({ text: cause instanceof Error ? cause.message : "문구를 올리지 못했어요.", ok: false });
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="nx-screen nx-billboard-page">
      <AccountTabs group="decor" active="billboard" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">전광판</h1>
          <p className="nx-screen-sub">홈 맨 위, 게임주 지수 바로 위에 한 줄 문구를 띄워요. 모의 자금으로 사고, 실제 돈은 오가지 않아요.</p>
        </div>
      </header>

      {failed && !board && <p className="nx-note is-error" role="alert">전광판 정보를 불러오지 못했어요. <button type="button" className="nx-link-button" onClick={() => void refreshBillboard()}>다시 시도</button></p>}

      <section className="nx-panel nx-billboard-compose" aria-labelledby="nx-billboard-compose-title">
        <header className="nx-panel-head">
          <h2 id="nx-billboard-compose-title" className="nx-panel-title">문구 올리기</h2>
          <span className="nx-muted nx-num">보유 현금 {won(cash)}</span>
        </header>

        <div className="nx-billboard-preview">
          <p className="nx-field-note">홈에서는 이렇게 보여요</p>
          <BillboardLine preview nickname={nickname} body={text} />
        </div>

        <form className="nx-form" onSubmit={submit} noValidate>
          <div className="nx-field">
            <div className="nx-field-head">
              <label htmlFor={bodyId}>문구</label>
              <span className={`nx-field-note nx-num${length(text) > max ? " is-over" : ""}`} aria-live="polite">{length(text)}/{max}자</span>
            </div>
            <input id={bodyId} className="nx-input" value={body} autoComplete="off" enterKeyHint="done" placeholder="예: 오늘 우마무스메 업데이트 다들 화이팅"
              onChange={(event) => setBody(event.target.value.replace(/[\r\n]+/g, " "))} aria-invalid={Boolean(bodyError)} aria-describedby={`${bodyId}-hint`} />
            <p id={`${bodyId}-hint`} className={`nx-field-hint${bodyError ? " is-error" : ""}`}>{bodyError || "링크와 욕설은 올릴 수 없고, 줄바꿈은 한 칸 띄어쓰기로 바뀌어요."}</p>
          </div>

          <div className="nx-field">
            <span className="nx-field-label" id={`${bodyId}-hours`}>보이는 시간</span>
            <div className="nx-seg nx-billboard-hours" role="group" aria-labelledby={`${bodyId}-hours`}>
              {(board?.durations ?? [1, 3, 6]).map((item) => (
                <button key={item} type="button" aria-pressed={hours === item} className={hours === item ? "is-active" : undefined}
                  onClick={() => setHours(item)}>{item}시간</button>
              ))}
            </div>
          </div>

          <dl className="nx-figures nx-billboard-figures">
            <div><dt>값</dt><dd className="nx-num">{won(price)}</dd></div>
            <div><dt>끝나는 시각</dt><dd className="nx-num">{slotTime(ends, now)}</dd></div>
            <div><dt>낸 뒤 현금</dt><dd className="nx-num">{won(Math.max(0, cash - price))}</dd></div>
          </dl>

          {blocked && <p className="nx-note" role="status">{blocked}</p>}
          {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}

          <button type="submit" className="nx-btn is-primary is-wide" disabled={!ready}>
            {pending ? "올리는 중…" : `${won(price)} 내고 올리기`}
          </button>
        </form>

        <ul className="nx-loan-rules">
          <li>사는 즉시 홈에 보여요. 여러 문구가 떠 있으면 10초마다 번갈아 보여요.</li>
          <li>문구에 쓴 돈은 수익률과 주간 랭킹 계산에서 빠져요. 순자산은 그만큼 줄어요.</li>
          <li>신고가 들어온 문구는 운영자가 내릴 수 있어요.</li>
        </ul>
      </section>

      <section className="nx-panel" aria-labelledby="nx-billboard-now-title">
        <header className="nx-panel-head">
          <h2 id="nx-billboard-now-title" className="nx-panel-title">지금 보이는 문구</h2>
          <span className="nx-muted nx-num">{board ? `${board.live.length}/${board.maxLive}개` : ""}</span>
        </header>
        {!board ? <div className="nx-skeleton" role="status" aria-label="문구를 불러오는 중"><span /><span /></div>
          : board.live.length === 0 ? <p className="nx-empty-state">지금 떠 있는 문구가 없어요. 올리면 바로 홈에 보여요.</p> : (
          <ul className="nx-billboard-live">
            {board.live.map((item) => <LiveItem key={item.id} item={item} mine={mine?.some((row) => row.id === item.id) ?? false} />)}
          </ul>
        )}
      </section>

      <section className="nx-panel" aria-labelledby="nx-billboard-mine-title">
        <header className="nx-panel-head">
          <h2 id="nx-billboard-mine-title" className="nx-panel-title">내 문구</h2>
          <span className="nx-muted nx-num">{mine ? `최근 ${mine.length}건` : ""}</span>
        </header>
        {mineError && !mine ? <p className="nx-note is-error" role="alert">내 문구를 불러오지 못했어요. <button type="button" className="nx-link-button" onClick={() => void loadMine()}>다시 시도</button></p>
          : !mine ? <div className="nx-skeleton" role="status" aria-label="내 문구를 불러오는 중"><span /><span /></div>
          : mine.length === 0 ? <p className="nx-empty-state">아직 올린 문구가 없어요.</p> : (
          <div className="nx-scroll-x">
            <table className="nx-grid-table nx-billboard-table">
              <caption className="vh">내가 올린 전광판 문구</caption>
              <thead><tr><th scope="col">문구</th><th scope="col">보이는 시간</th><th scope="col">값</th><th scope="col">상태</th></tr></thead>
              <tbody>
                {mine.map((item) => (
                  <tr key={item.id}>
                    <th scope="row" className="nx-billboard-cell">{item.body}</th>
                    <td className="nx-num">{slotTime(item.startsAt, now)}부터 {item.hours}시간</td>
                    <td className="nx-num">{won(item.price)}{item.refunded > 0 && <small className="nx-billboard-refund">{won(item.refunded)} 돌려받음</small>}</td>
                    <td className={`nx-billboard-state is-${item.state.toLowerCase()}`}>
                      {STATE_LABEL[item.state]}
                      {item.hiddenReason && <small className="nx-billboard-refund">{item.hiddenReason}</small>}
                    </td>
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

/** 지금 홈에 떠 있는 문구 하나와 신고. 신고는 기존 신고함으로 가고 운영자가 확인한다. */
function LiveItem({ item, mine }: { item: BillboardShowing; mine: boolean }) {
  const detailId = useId();
  const [reporting, setReporting] = useState(false);
  const [detail, setDetail] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ text: string; ok: boolean } | null>(null);

  const report = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await reportBillboard(item.id, detail.trim());
      setResult({ text: "신고했어요. 운영자가 확인할게요.", ok: true });
      setReporting(false);
      setDetail("");
    } catch (cause) {
      setResult({ text: cause instanceof Error ? cause.message : "신고하지 못했어요.", ok: false });
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="nx-billboard-live-item">
      <div className="nx-billboard-live-row">
        <BillboardLine nickname={item.nickname} body={item.body} />
        {!mine && !reporting && !result?.ok && <button type="button" className="nx-link-button" onClick={() => setReporting(true)}>신고하기</button>}
      </div>
      {reporting && (
        <form className="nx-form nx-billboard-report" onSubmit={report}>
          <div className="nx-field">
            <label htmlFor={detailId}>신고 이유 (적지 않아도 돼요)</label>
            <input id={detailId} className="nx-input" value={detail} maxLength={300} placeholder="예: 욕설이 들어 있어요" onChange={(event) => setDetail(event.target.value)} autoFocus />
          </div>
          <div className="nx-billboard-report-actions">
            <button type="submit" className="nx-btn" disabled={busy}>{busy ? "보내는 중…" : "신고 보내기"}</button>
            <button type="button" className="nx-link-button" disabled={busy} onClick={() => setReporting(false)}>그만두기</button>
          </div>
        </form>
      )}
      {result && <p className={`nx-msg ${result.ok ? "is-success" : "is-error"}`} role={result.ok ? "status" : "alert"}>{result.text}</p>}
    </li>
  );
}
