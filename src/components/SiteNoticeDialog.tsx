import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { SITE_NOTICE } from "../notice";

const SEEN_KEY = "gamestock-site-notice-seen";

function readSeen() {
  try { return localStorage.getItem(SEEN_KEY); }
  catch { return null; }
}

export function SiteNoticeDialog() {
  const [seen, setSeen] = useState(readSeen);
  const notice = SITE_NOTICE;
  if (!notice || seen === notice.id) return null;
  const close = () => {
    try { localStorage.setItem(SEEN_KEY, notice.id); } catch { /* Hide for this session only. */ }
    setSeen(notice.id);
  };

  return <NoticeDialog onClose={close} />;
}

function NoticeDialog({ onClose }: { onClose: () => void }) {
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

  const notice = SITE_NOTICE!;
  return createPortal(
    <dialog ref={dialog} className="circuit-notice site-notice" aria-labelledby="site-notice-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}>
      <div className="circuit-notice-content">
        <p id="site-notice-title" className="circuit-notice-kicker">📢공지사항</p>
        {notice.items.map((item) => (
          <section key={item.heading} className="site-notice-item">
            <h3>{item.heading}</h3>
            <p>{item.body}</p>
            {item.code && <p className="site-notice-code">코드 <strong>{item.code}</strong></p>}
          </section>
        ))}
        <button type="button" className="circuit-notice-confirm" onClick={onClose} autoFocus>확인했어요</button>
      </div>
    </dialog>, document.body,
  );
}
