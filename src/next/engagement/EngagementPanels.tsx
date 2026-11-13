import { useState, type FormEvent } from "react";
import { submitPrediction, useEngagement, type Prediction } from "../../lib/engagement";
import { LISTINGS, LISTING_BY_CODE } from "../../market/universe";
import { won } from "../../market/format";
import { GameIcon } from "../../components/GameIcon";
import { ApiError } from "../../lib/api";
import { LockGlyph } from "./UnlockNotice";
import { withCopula } from "../../lib/korean";

const errorText = (error: unknown) => (error instanceof ApiError || error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.");
const PREDICTION_REWARD = 10_000;
const kstHour = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString("ko-KR", { hour: "numeric", timeZone: "Asia/Seoul" }) : "");

function PredictionResult({ prediction }: { prediction: Prediction }) {
  const name = prediction.stockCode ? LISTING_BY_CODE[prediction.stockCode]?.name ?? prediction.stockCode : "";
  if (!prediction.result) return null;
  const hit = prediction.result === "HIT";
  return (
    <p className={`nx-predict-last ${hit ? "is-hit" : ""}`}>
      어제 고른 {name}: <span className="nx-num">{won(prediction.basePrice)}</span> → <span className="nx-num">{won(prediction.closePrice ?? 0)}</span>
      {hit ? <> · 적중, <span className="nx-num">{won(prediction.reward)}</span> 받았어요</> : " · 이번엔 빗나갔어요"}
    </p>
  );
}

/** 하루 한 종목. 고른 뒤 그날 마지막 체결가가 고를 때보다 높으면 적중이다. */
export function PredictionPanel() {
  const engagement = useEngagement();
  const [code, setCode] = useState(LISTINGS[0].code);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  if (!engagement) return null;
  const prediction = engagement.prediction;
  const picked = prediction.stockCode;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setPending(true);
    setMessage(null);
    try {
      await submitPrediction(code);
      setMessage({ ok: true, text: "골랐어요. 자정에 결과가 나와요." });
    } catch (error) {
      setMessage({ ok: false, text: errorText(error) });
    } finally {
      setPending(false);
    }
  };

  return (
    <section className="nx-panel nx-predict" aria-labelledby="nx-predict-title">
      <header className="nx-panel-head is-stacked">
        <h2 id="nx-predict-title" className="nx-panel-title">오늘 오를 종목 고르기</h2>
        <p className="nx-muted">고를 때 가격보다 오늘 마지막 가격이 높으면 내 지갑에 <span className="nx-num">{won(PREDICTION_REWARD)}</span>이 들어와요. {kstHour(prediction.closesAt)}까지 고를 수 있어요.</p>
      </header>
      {picked ? (
        <div className="nx-predict-picked">
          <GameIcon code={picked} size="table" />
          <div>
            <strong>{LISTING_BY_CODE[picked]?.name ?? picked}</strong>
            <p className="nx-muted">고른 가격 <span className="nx-num">{won(prediction.basePrice)}</span> · 자정에 결과가 나와요</p>
          </div>
        </div>
      ) : prediction.open ? (
        <form className="nx-inline-form" onSubmit={submit}>
          <label htmlFor="nx-predict-stock" className="vh">오를 것 같은 종목</label>
          <select id="nx-predict-stock" className="nx-input" value={code} onChange={(event) => setCode(event.target.value)} disabled={pending}>
            {LISTINGS.map((listing) => <option key={listing.code} value={listing.code}>{listing.name}</option>)}
          </select>
          <button type="submit" className="nx-btn is-primary" disabled={pending}>{pending ? "고르는 중…" : "이 종목으로 고르기"}</button>
        </form>
      ) : (
        <p className="nx-muted">오늘 예측은 마감했어요. 내일 0시부터 다시 고를 수 있어요.</p>
      )}
      {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}
      {prediction.previous && <PredictionResult prediction={prediction.previous} />}
    </section>
  );
}

const REASON_TEXT: Record<string, string> = {
  EARLY_MEMBER: "프랍 데스크 이전 전이라 모든 기능이 열려 있어요.",
  STAFF: "운영 계정이라 모든 기능이 열려 있어요.",
};

/** 탭 베팅 → 선물 → 옵션 → 퀀트가 어느 직급에서 열리는지 한눈에 보여 준다. */
export function UnlockProgressPanel() {
  const engagement = useEngagement();
  if (!engagement) return null;
  const { unlocks } = engagement;
  return (
    <section className="nx-panel nx-unlocks" aria-labelledby="nx-unlocks-title">
      <header className="nx-panel-head is-stacked">
        <h2 id="nx-unlocks-title" className="nx-panel-title">데스크 발령</h2>
        <p className="nx-muted">
          {unlocks.reason ? REASON_TEXT[unlocks.reason] : `승진할 때마다 데스크가 하나씩 열려요. 지금은 ${withCopula(unlocks.gradeLabel)}.`}
        </p>
      </header>
      {!unlocks.allUnlocked && (
        <ul className="nx-unlock-list">
          {unlocks.features.map((feature) => (
            <li key={feature.id} className={feature.unlocked ? "is-open" : undefined}>
              <span className="nx-unlock-name">
                {!feature.unlocked && <LockGlyph small />}
                {feature.name}
                {feature.unlocked && <span className="nx-unlock-badge">열림</span>}
              </span>
              {!feature.unlocked && <span className="nx-muted">{feature.gradeLabel}부터</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
