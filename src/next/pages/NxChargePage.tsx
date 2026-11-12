import { Link } from "../../components/Link";
import { missionLabel, MISSION_REWARD, useDailyMissions } from "../../components/MissionBoard";
import { usePromoCode } from "../../components/PromoCodePanel";
import { won } from "../../market/format";
import { AccountTabs } from "../AccountTabs";
import { PredictionPanel, RescuePanel, UnlockProgressPanel } from "../engagement/EngagementPanels";

function Missions() {
  const { user, ready, error, pending, completed, count, visibleMissionIds, complete } = useDailyMissions();
  const total = visibleMissionIds.length;
  return (
    <>
      <section className="nx-charge-hero" aria-label="오늘의 미션 진행">
        <div>
          <p className="nx-panel-title">오늘 받은 미션 보상</p>
          <span className="nx-num nx-big">{won(count * MISSION_REWARD)}</span>
          <p className="nx-muted">하루 최대 <span className="nx-num">{won(total * MISSION_REWARD)}</span> · 한국시간 자정에 새 미션</p>
        </div>
        {/* 미션 하나가 칸 하나. 채워진 칸이 완료한 미션이다. */}
        <ol className="nx-mission-steps" aria-label={`${total}개 중 ${count}개 완료`}>
          {visibleMissionIds.map((id) => <li key={id} className={completed.has(id) ? "is-done" : undefined} />)}
        </ol>
      </section>

      {user && !ready && !error && <p className="nx-msg" role="status">미션 상태를 확인하고 있어요.</p>}
      {error && <p className="nx-note is-error" role="status">{error}</p>}

      <section className="nx-panel nx-mission-list" aria-label="오늘의 투자 미션">
        <ol>
          {visibleMissionIds.map((id, index) => {
            const mission = missionLabel(id);
            const done = completed.has(id);
            return (
              <li key={id} className={done ? "is-done" : undefined}>
                <span className="nx-mission-index nx-num" aria-hidden="true">
                  {done ? <svg viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6-7" /></svg> : index + 1}
                </span>
                <div className="nx-mission-copy">
                  <h2>{mission.title}</h2>
                  <p>{mission.description}</p>
                </div>
                <span className="nx-mission-reward nx-num">+{won(MISSION_REWARD)}</span>
                {done ? <span className="nx-mission-state">받음</span> : !user ? (
                  <button type="button" className="nx-btn is-small" aria-label={`${mission.title} 수행을 위해 로그인`} onClick={() => window.dispatchEvent(new Event("gamestock-request-login"))}>로그인</button>
                ) : pending.has(id) || !ready ? (
                  <span className="nx-mission-state">{pending.has(id) ? "지급 중" : "확인 중"}</span>
                ) : (
                  <Link className="nx-btn is-small is-primary" to={mission.href} onClick={(event) => { event.preventDefault(); void complete(id, mission.href); }}>
                    {mission.action}
                  </Link>
                )}
              </li>
            );
          })}
        </ol>
      </section>
    </>
  );
}

function PromoCode() {
  const { user, code, setCode, pending, message, submit } = usePromoCode();
  return (
    <section className="nx-panel nx-promo" aria-labelledby="nx-promo-title">
      <header className="nx-panel-head">
        <h2 id="nx-promo-title" className="nx-panel-title">프로모션 코드</h2>
      </header>
      <form className="nx-inline-form" onSubmit={submit}>
        <label htmlFor="nx-promo-input" className="vh">프로모션 코드</label>
        <input id="nx-promo-input" className="nx-input" value={code} onChange={(event) => setCode(event.target.value)}
          placeholder={user ? "코드를 넣으면 특별 칭호를 받아요" : "로그인 후 입력할 수 있어요"}
          autoComplete="off" spellCheck={false} maxLength={40} disabled={!user || pending} />
        <button type="submit" className="nx-btn is-primary" disabled={!user || pending || !code.trim()}>{pending ? "확인 중…" : "사용"}</button>
      </form>
      {message && <p className={`nx-msg ${message.ok ? "is-success" : "is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>}
    </section>
  );
}

/** 모의 자금은 결제가 아니라 매일 바뀌는 투자 미션 보상으로만 충전된다. */
export function NxChargePage() {
  return (
    <div className="nx-screen nx-charge">
      <AccountTabs group="funds" active="charge" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">충전하기</h1>
          <p className="nx-screen-sub">오늘의 투자 미션을 끝내면 모의 자금이 충전돼요. 실제 돈은 오가지 않아요.</p>
        </div>
      </header>
      <RescuePanel />
      <Missions />
      <PredictionPanel />
      <UnlockProgressPanel />
      <PromoCode />
    </div>
  );
}
