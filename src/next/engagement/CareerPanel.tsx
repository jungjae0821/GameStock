import { useId, useRef, useState } from "react";
import { acknowledgeOjt, refreshCareer, useCareer, useCareerFailed, type Career, type CareerStep } from "../../lib/career";
import { won } from "../../market/format";
import { Link } from "../../components/Link";
import { ApiError } from "../../lib/api";
import { withSubject } from "../../lib/korean";

const errorText = (error: unknown) => (error instanceof ApiError || error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.");
const percent = (value: number) => `${value >= 0 ? "+" : ""}${value.toFixed(1)}%`;
const kstTime = (iso: string) => new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Seoul" });

/** OJT 단계 중 화면에서 바로 할 수 있는 일로 가는 길. 주력 게임은 이 화면 아래에서 고른다. */
const OJT_ACTION: Record<string, { to: string; label: string }> = {
  FANDOM: { to: "#nx-fandom-title", label: "아래에서 고르기" },
  MARKET: { to: "/trade", label: "종목 보러 가기" },
  LIMIT_BUY: { to: "/trade", label: "주문하러 가기" },
  SELL: { to: "/trade", label: "주문하러 가기" },
};

function stepCount(step: CareerStep) {
  if (step.unit === "CHECK") return step.done ? "완료" : "아직";
  if (step.unit === "PERMIL") return `${(step.current / 10).toFixed(1)}/${step.target / 10}%`;
  return `${step.current}/${step.target}`;
}

function RiskBriefing({ onDone }: { onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const id = useId();
  const confirm = async () => {
    setPending(true);
    setError(null);
    try {
      await acknowledgeOjt("RISK");
      onDone();
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setPending(false);
    }
  };
  return (
    <>
      <button type="button" className="nx-btn is-small" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>
        {open ? "교육 접기" : "교육 읽기"}
      </button>
      {open && (
        <div className="nx-career-brief" id={id}>
          <p>운용계좌는 회사 돈이에요. 직급마다 운용한도가 있고, 그 돈으로 거래해요.</p>
          <p>
            사원부터는 하루에 기준자산의 5% 넘게 잃으면 그날은 새로 살 수 없어요. 기준자산에서 사원~과장은 20%, 차장부터는 15% 넘게 잃으면
            강등돼요. 사원이면 하루 동안 재교육을 받아요. 강등돼도 성과급 기준이 되는 최고점은 그대로라, 잃은 만큼 되찾기 전에는 성과급이 없어요.
          </p>
          <p>매일 0시에 정산해요. 판 뒤 남은 이익이 지난 최고점을 넘으면, 넘은 만큼에 성과급률을 곱해 내 지갑으로 들어와요. 내 지갑 돈은 운용계좌로 옮길 수 없어요.</p>
          <p>사원부터는 한 종목에 기준자산의 30%까지만 담을 수 있어요. 한 번에 몰빵해서 승진하는 길을 막기 위해서예요.</p>
          <button type="button" className="nx-btn is-small is-primary" onClick={() => void confirm()} disabled={pending}>{pending ? "확인 중…" : "확인했어요"}</button>
          {error && <p className="nx-msg is-error" role="alert">{error}</p>}
        </div>
      )}
    </>
  );
}

function StepAction({ step, onRiskDone }: { step: CareerStep; onRiskDone: () => void }) {
  if (step.done) return null;
  if (step.id === "RISK") return <RiskBriefing onDone={onRiskDone} />;
  const action = OJT_ACTION[step.id];
  if (!action) return null;
  return action.to.startsWith("#")
    ? <a className="nx-btn is-small" href={action.to}>{action.label}</a>
    : <Link className="nx-btn is-small" to={action.to}>{action.label}</Link>;
}

function Steps({ career, onRiskDone }: { career: Career; onRiskDone: () => void }) {
  const trainee = career.grade === "TRAINEE";
  return (
    <div className="nx-career-next">
      <h3>{trainee ? "OJT를 끝내면 사원으로 발령나요" : `${career.nextGradeLabel} 승진 조건`}</h3>
      <ol className="nx-unlock-steps nx-career-steps">
        {career.promotion.map((step) => (
          <li key={step.id} className={step.done ? "is-done" : undefined}>
            <span className="nx-unlock-step-label">{step.label}<span className="vh">: {step.done ? "완료" : "남음"}</span></span>
            <span className="nx-unlock-meter" aria-hidden="true">
              <span style={{ width: `${Math.min(100, (step.current / Math.max(1, step.target)) * 100)}%` }} />
            </span>
            <span className="nx-num nx-unlock-count">{stepCount(step)}</span>
            {trainee && <StepAction step={step} onRiskDone={onRiskDone} />}
          </li>
        ))}
      </ol>
      {!trainee && (
        <p className="nx-muted">
          수익률은 판 뒤 남은 이익으로만 쳐요. 들고 있는 주식은 산 값과 지금 값 중 낮은 쪽으로 계산하고, 목표 수익률은 매일 0시 정산 때
          연속으로 넘겨야 해요. 체결은 기준자산의 1% 이상인 것만 세고, 한 종목에는 기준자산의 30%까지만 담을 수 있어요.
        </p>
      )}
    </div>
  );
}

/** 직급, 다음 직급까지 남은 조건, 손실한도, 내 지갑. 커리어 이전 전 기존 회원에게는 안내만 보여 준다. */
export function CareerPanel() {
  const career = useCareer();
  const failed = useCareerFailed();
  const heading = useRef<HTMLHeadingElement>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (!career) {
    if (failed) {
      return (
        <p className="nx-note is-error" role="alert">
          커리어 정보를 불러오지 못했어요. <button type="button" className="nx-link-button" onClick={refreshCareer}>다시 시도</button>
        </p>
      );
    }
    return <div className="nx-skeleton" role="status" aria-label="커리어 정보를 불러오는 중"><span /><span /><span /></div>;
  }
  if (career.exempt) return <p className="nx-note">운영 계정은 커리어 없이 모든 데스크를 쓸 수 있어요.</p>;
  if (career.legacy) {
    return (
      <section className="nx-panel nx-career" aria-labelledby="nx-career-title">
        <h2 id="nx-career-title" className="nx-panel-title">프랍 데스크 이전 대기</h2>
        <p className="nx-muted">곧 모든 회원이 수습 트레이더로 새로 시작해요. 지금 자산의 10%(최대 1,000만원)는 퇴직금으로 내 지갑에 들어가요. 들고 있는 주식은 산 값과 지금 값 중 낮은 쪽으로 쳐요.</p>
        <p className="nx-muted">내 지갑 <span className="nx-num">{won(career.personalCash)}</span></p>
      </section>
    );
  }

  const dayLoss = Math.max(0, career.dayOpenAsset - career.markAsset);
  const dayLeft = Math.max(0, career.dayLossLimit - dayLoss);
  const onRiskDone = () => {
    setNotice("리스크 교육을 확인했어요.");
    heading.current?.focus();
  };

  return (
    <section className="nx-panel nx-career" aria-labelledby="nx-career-title">
      <header className="nx-panel-head is-stacked">
        <h2 id="nx-career-title" className="nx-panel-title" ref={heading} tabIndex={-1}>내 커리어 · {career.gradeLabel}</h2>
        <p className="nx-muted">
          운용한도 <span className="nx-num">{won(career.allocation)}</span> · 성과급률 <span className="nx-num">{Math.round(career.bonusRate * 100)}%</span>
          {" "}· {kstTime(career.gradeSince)}에 {withSubject(career.gradeLabel)} 됐어요
        </p>
      </header>
      {notice && <p className="nx-msg is-success" role="status">{notice}</p>}

      {career.lockedUntil && (
        <p className="nx-note is-error" role="status">
          {career.lockReason === "RETRAIN" ? "손실한도를 넘어 재교육 중이에요." : "오늘 일일 손실한도를 넘었어요."} {kstTime(career.lockedUntil)}까지 새로 사거나 진입할 수 없어요. 파는 건 할 수 있어요.
        </p>
      )}

      <dl className="nx-career-stats">
        <div><dt>운용계좌 평가</dt><dd className="nx-num">{won(career.markAsset)}</dd></div>
        <div><dt>확정 수익률</dt><dd className="nx-num">{percent(career.returnPercent)}</dd></div>
        {career.demotionAsset > 0 && <div><dt>{career.grade === "STAFF" ? "재교육선" : "강등선"}</dt><dd className="nx-num">{won(career.demotionAsset)}</dd></div>}
        {career.dayLossLimit > 0 && <div><dt>오늘 더 잃어도 되는 돈</dt><dd className="nx-num">{won(dayLeft)}</dd></div>}
        <div><dt>내 지갑</dt><dd className="nx-num">{won(career.personalCash)}</dd></div>
      </dl>

      {career.nextGradeLabel && career.promotion.length > 0 && <Steps career={career} onRiskDone={onRiskDone} />}
    </section>
  );
}
