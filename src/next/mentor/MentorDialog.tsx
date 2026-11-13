import { useState } from "react";
import { useModalDialog } from "../../lib/useModalDialog";
import { navigate } from "../../router";
import { won } from "../../market/format";

export type MentorTone = "info" | "praise" | "scold";

/** 발령장 한 장. 승진 때만 대사 위에 붙는다. */
export type Certificate = { nickname: string; gradeLabel: string; allocation: number; date: string };

type Props = {
  tone: MentorTone;
  title: string;
  pages: string[];
  certificate?: Certificate;
  /** 마지막 장의 진한 버튼. to가 있으면 그 화면으로 간다. */
  primary?: { label: string; to?: string };
  /** 마지막 장의 옅은 버튼 문구. */
  dismissLabel?: string;
  onClose: () => void;
};

const KICKER: Record<MentorTone, string> = { info: "슈엔 사장", praise: "슈엔 사장 · 발령", scold: "슈엔 사장 · 호출" };

/** 사수를 자처한 슈엔 사장이 한 장씩 말하는 대화창. 마지막 장에서 닫거나 다음 화면으로 보낸다. */
export function MentorDialog({ tone, title, pages, certificate, primary, dismissLabel = "네, 사장님", onClose }: Props) {
  const dialog = useModalDialog();
  const [page, setPage] = useState(0);
  const last = page >= pages.length - 1;
  const finish = (to?: string) => {
    onClose();
    if (to) navigate(to);
  };

  return (
    <dialog ref={dialog} className={`mentor-dialog is-${tone}`} aria-labelledby="mentor-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}>
      <div className="mentor-card">
        {certificate && (
          <div className="mentor-certificate" aria-label="발령장">
            <p className="mentor-certificate-head">발 령</p>
            <dl>
              <div><dt>성명</dt><dd>{certificate.nickname}</dd></div>
              <div><dt>직위</dt><dd>{certificate.gradeLabel}</dd></div>
              <div><dt>운용한도</dt><dd className="nx-num">{won(certificate.allocation)}</dd></div>
            </dl>
            <p className="mentor-certificate-body">위 사람을 {certificate.gradeLabel}(으)로 발령함.</p>
            <p className="mentor-certificate-foot">{certificate.date} · 슈엔증권 대표이사 슈엔</p>
          </div>
        )}
        <div className="mentor-speaker">
          <img className="mentor-avatar" src="/brand/shuen-mark.png" alt="" width="48" height="48" />
          <div>
            <p className="mentor-kicker">{KICKER[tone]}</p>
            <h2 id="mentor-title">{title}</h2>
          </div>
        </div>
        <p className="mentor-line" aria-live="polite">{pages[page]}</p>
        {pages.length > 1 && (
          <p className="mentor-progress" aria-label={`${pages.length}장 중 ${page + 1}장`}>
            {pages.map((_, index) => <span key={index} className={index === page ? "is-current" : undefined} />)}
          </p>
        )}
        <div className="mentor-actions">
          {page > 0 && <button type="button" className="mentor-button is-quiet" onClick={() => setPage(page - 1)}>이전</button>}
          {!last ? (
            <button type="button" className="mentor-button is-strong" onClick={() => setPage(page + 1)} autoFocus>다음</button>
          ) : (
            <>
              {primary && <button type="button" className="mentor-button is-quiet" onClick={() => finish()}>{dismissLabel}</button>}
              <button type="button" className="mentor-button is-strong" autoFocus onClick={() => finish(primary?.to)}>
                {primary?.label ?? dismissLabel}
              </button>
            </>
          )}
        </div>
      </div>
    </dialog>
  );
}
