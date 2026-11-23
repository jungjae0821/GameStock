import { useEffect, useRef, useState, type MouseEvent } from "react";
import { createPortal } from "react-dom";
import { useSiteNotices, type SiteNoticeItem } from "../notice";
import { navigate } from "../router";

const SEEN_KEY = "gamestock-site-notice-seen";

/** 확인한 공지 열쇠들. 예전에 한 창으로 묶어 저장한 값("1:t,2:t")도 그대로 읽힌다. */
function readSeen(): string[] {
  try { return (localStorage.getItem(SEEN_KEY) ?? "").split(",").filter(Boolean); }
  catch { return []; }
}

const FIRST_VISIT_KEY = "gamestock-first-visit";

/**
 * 이 브라우저로 처음 들어온 시각. 공지를 하나라도 확인한 기존 방문자는 0(모든 공지 대상)으로 둔다.
 * 처음 온 사람에게 "화면을 새롭게 다듬었어요" 같은 지난 업데이트 소식을 띄우지 않으려는 기준이다.
 */
function readFirstVisit(seen: string[]): number {
  try {
    const raw = localStorage.getItem(FIRST_VISIT_KEY);
    const stored = raw === null ? NaN : Number(raw);
    if (Number.isFinite(stored) && stored >= 0) return stored;
    const value = seen.length > 0 ? 0 : Date.now();
    localStorage.setItem(FIRST_VISIT_KEY, String(value));
    return value;
  } catch {
    return 0;
  }
}

export function SiteNoticeDialog() {
  const [seen] = useState(readSeen);
  const [firstVisit] = useState(() => readFirstVisit(seen));
  // 한 번 들어올 때 공지는 하나만 띄운다. 남은 공지는 다음 접속 때 차례로 뜬다.
  const [closed, setClosed] = useState(false);
  const notices = useSiteNotices();
  const current = notices?.find((item) => !seen.includes(item.key) && (item.postedAt ?? Infinity) >= firstVisit);
  if (closed || !current) return null;
  const dismiss = () => {
    // API가 안 될 때 뜨는 대체 공지도 같은 기록에 남으므로, 지금 목록에 없는 열쇠도 지우지 않고 최근 50개만 둔다.
    const next = [...seen, current.key].slice(-50);
    try { localStorage.setItem(SEEN_KEY, next.join(",")); } catch { /* Hide for this session only. */ }
    setClosed(true);
  };

  return <NoticeDialog item={current} onDismiss={dismiss} />;
}

const isExternal = (url: string) => /^https:\/\//i.test(url);

/** 공지 창 한 장. 관리자 화면의 미리보기도 같은 마크업을 쓴다. 미리보기(onDismiss 없음)에서는 버튼이 눌리지 않는다. */
export function SiteNoticeCard({ item, onDismiss, titleId = "site-notice-title" }: { item: SiteNoticeItem; onDismiss?: () => void; titleId?: string }) {
  const live = Boolean(onDismiss);
  const inert = live ? {} : { tabIndex: -1, "aria-hidden": true as const };
  const { action } = item;
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    if (!live || !action) { event.preventDefault(); return; }
    if (!isExternal(action.url)) { event.preventDefault(); navigate(action.url); }
    onDismiss?.();
  };

  return (
    <article className={`site-notice-card${item.image ? " has-image" : ""}`}>
      <button type="button" className="site-notice-close" aria-label="닫기" onClick={onDismiss} {...inert}>
        <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true"><path d="M3.5 3.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
      </button>
      {item.image && <img className="site-notice-image" src={item.image} alt="" />}
      <div className="site-notice-text">
        <h2 id={titleId}>{item.heading}</h2>
        <p className="site-notice-body">{item.body}</p>
        {item.code && <p className="site-notice-code">코드 <strong>{item.code}</strong></p>}
      </div>
      <div className="site-notice-actions">
        <button type="button" className={`site-notice-button ${action ? "is-quiet" : "is-strong"}`} onClick={onDismiss} {...inert}>
          {item.dismissLabel || (action ? "닫기" : "확인했어요")}
        </button>
        {action && (
          <a className="site-notice-button is-strong" href={action.url} onClick={follow}
            {...(isExternal(action.url) ? { target: "_blank", rel: "noopener noreferrer" } : {})} {...inert}>
            {action.label}
          </a>
        )}
      </div>
    </article>
  );
}

function NoticeDialog({ item, onDismiss }: { item: SiteNoticeItem; onDismiss: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    return () => {
      element.close();
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus({ preventScroll: true });
    };
  }, []);
  // showModal은 첫 번째 버튼(X)에 초점을 둔다. 공지가 열리거나 다음 공지로 넘어가면 진한 버튼으로 옮긴다.
  useEffect(() => {
    dialog.current?.querySelector<HTMLElement>(".site-notice-button.is-strong")?.focus();
  }, [item.key]);

  return createPortal(
    <dialog ref={dialog} className="circuit-notice site-notice" aria-labelledby="site-notice-title"
      onCancel={(event) => { event.preventDefault(); onDismiss(); }}>
      <SiteNoticeCard key={item.key} item={item} onDismiss={onDismiss} />
    </dialog>, document.body,
  );
}
