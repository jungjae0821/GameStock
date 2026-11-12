import { useEffect, useState } from "react";
import { useProfile } from "../../lib/profile";
import { checkTitles, equipTitle, titleInkStyle, type TitleStatus, type UserTitle } from "../../lib/titles";
import { AnimatedNav } from "../AnimatedNav";
import { AccountTabs } from "../AccountTabs";

const CATEGORY_ORDER = ["자산", "수익률", "보유", "거래", "팬덤", "예측", "활동", "수집", "주간", "시즌", "특별"];
const dateFormatter = new Intl.DateTimeFormat("ko-KR", { year: "2-digit", month: "2-digit", day: "2-digit", timeZone: "Asia/Seoul" });

function TitleCard({ title, pending, onToggle }: { title: UserTitle; pending: boolean; onToggle: (title: UserTitle) => void }) {
  const date = title.weekly ? title.updatedAt : title.acquiredAt;
  const body = (
    <>
      <span className="nx-title-name has-title-ink" style={title.owned ? titleInkStyle(title.name) : undefined}>
        {title.name}
        {title.weekly && title.owned && <span className="nx-title-crown nx-num">{title.count}관왕</span>}
      </span>
      <span className="nx-title-cond">{title.description}</span>
      <span className="nx-title-state">
        {title.equipped ? "장착 중" : title.owned ? <>획득{date && <span className="nx-num"> · {dateFormatter.format(new Date(date))}</span>}</> : (
          <>
            <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="7" width="9" height="6.5" rx="1.5" /><path d="M5.5 7V5.5a2.5 2.5 0 015 0V7" /></svg>
            미획득
          </>
        )}
      </span>
    </>
  );
  const className = `nx-title${title.owned ? " is-owned" : " is-locked"}${title.equipped ? " is-equipped" : ""}`;
  return (
    <li>
      {title.owned ? (
        <button type="button" className={className} aria-pressed={title.equipped} disabled={pending} onClick={() => onToggle(title)}
          title={title.equipped ? "누르면 장착을 해제해요" : "누르면 이 칭호를 장착해요"}>{body}</button>
      ) : <div className={className}>{body}</div>}
    </li>
  );
}

export function NxTitlesPage() {
  const profile = useProfile();
  const [status, setStatus] = useState<TitleStatus | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState("");
  const [category, setCategory] = useState<string>("all");

  useEffect(() => {
    const controller = new AbortController();
    checkTitles(controller.signal)
      .then((next) => { setStatus(next); setError(""); })
      .catch(() => { if (!controller.signal.aborted) setError("칭호 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."); });
    return () => controller.abort();
  }, []);

  // 이미 장착한 칭호를 다시 누르면 장착을 해제한다.
  const toggle = async (title: UserTitle) => {
    if (pending) return;
    setPending(true);
    setNotice("");
    try {
      setStatus(await equipTitle(title.equipped ? null : title.id));
    } catch (failure) {
      setNotice(failure instanceof Error ? failure.message : "칭호를 장착하지 못했어요.");
    } finally {
      setPending(false);
    }
  };

  const categories = status ? CATEGORY_ORDER.filter((name) => status.titles.some((title) => title.category === name)) : [];
  const visible = status ? status.titles.filter((title) => category === "all" || title.category === category)
    .sort((a, b) => Number(b.owned) - Number(a.owned) || CATEGORY_ORDER.indexOf(a.category) - CATEGORY_ORDER.indexOf(b.category)) : [];
  const equipped = status?.titles.find((title) => title.equipped);
  const percent = status ? Math.round((status.ownedCount / Math.max(1, status.totalCount)) * 100) : 0;

  return (
    <div className="nx-screen nx-titles">
      <AccountTabs group="decor" active="titles" />
      <header className="nx-screen-head">
        <div>
          <h1 className="vh">칭호</h1>
          <p className="nx-screen-sub">조건을 달성하면 자동으로 지급돼요. 획득한 칭호를 누르면 랭킹에서 닉네임 위에 달려요.</p>
        </div>
      </header>

      {error ? <p className="nx-note is-error">{error}</p> : !status ? (
        <div className="nx-skeleton" role="status" aria-label="칭호를 확인하는 중"><span /><span /><span /></div>
      ) : (
        <>
          <section className="nx-titles-hero" aria-label="칭호 수집 현황">
            <div>
              <p className="nx-panel-title">수집한 칭호</p>
              <p className="nx-num nx-big">{status.ownedCount}<span className="nx-titles-total"> / {status.totalCount}</span></p>
              <div className="nx-meter" role="progressbar" aria-valuemin={0} aria-valuemax={status.totalCount} aria-valuenow={status.ownedCount} aria-label={`${percent}% 수집`}>
                <span style={{ width: `${percent}%` }} />
              </div>
            </div>
            {/* 랭킹에서 보이는 모습 그대로 미리 보여 준다. */}
            <div className="nx-nameplate" aria-label="랭킹에서 보이는 모습">
              <span className="nx-nameplate-label">랭킹에서 보이는 모습</span>
              <span className="nx-nameplate-title has-title-ink" style={equipped ? titleInkStyle(equipped.name) : undefined}>{equipped ? equipped.name : "장착한 칭호 없음"}</span>
              <span className="nx-nameplate-name">{profile?.nickname ?? "내 닉네임"}</span>
            </div>
          </section>

          {notice && <p className="nx-msg is-error" role="alert">{notice}</p>}

          <section className="nx-panel nx-titles-board">
            <AnimatedNav as="div" activeKey={category} className="nx-tabs" role="group" aria-label="칭호 분류">
              <button type="button" className={category === "all" ? "is-active" : undefined} aria-pressed={category === "all"} onClick={() => setCategory("all")}>
                전체 <span className="nx-tab-count nx-num">{status.ownedCount}/{status.totalCount}</span>
              </button>
              {categories.map((name) => {
                const titles = status.titles.filter((title) => title.category === name);
                return (
                  <button key={name} type="button" className={category === name ? "is-active" : undefined} aria-pressed={category === name} onClick={() => setCategory(name)}>
                    {name} <span className="nx-tab-count nx-num">{titles.filter((title) => title.owned).length}/{titles.length}</span>
                  </button>
                );
              })}
            </AnimatedNav>
            <ul className="nx-title-grid">
              {visible.map((title) => <TitleCard key={title.id} title={title} pending={pending} onToggle={toggle} />)}
            </ul>
            <p className="nx-muted nx-titles-foot">주간 수익률 칭호는 매주 월요일에 지급돼요. 특별 칭호는 충전하기 화면에서 프로모션 코드를 넣으면 받을 수 있어요.</p>
          </section>
        </>
      )}
    </div>
  );
}
