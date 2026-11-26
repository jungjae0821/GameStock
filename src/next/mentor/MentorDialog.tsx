import { useState } from "react";
import { useModalDialog } from "../../lib/useModalDialog";
import { navigate } from "../../router";
import { won } from "../../market/format";
import { MentorPortrait, useTypewriter } from "./MentorPortrait";

export type MentorTone = "info" | "praise" | "scold";

/** 발령장 한 장. 승진 때만 대사 위에 붙는다. */
export type Certificate = { nickname: string; gradeLabel: string; allocation: number; date: string };

type Props = {
  tone: MentorTone;
  title: string;
  pages: string[];
  certificate?: Certificate;
  /** 마지막 장의 진한 버튼. to가 있으면 그 화면으로 가고, onSelect가 있으면 닫지 않고 그것만 부른다. */
  primary?: { label: string; to?: string; onSelect?: () => void };
  /** 마지막 장의 옅은 버튼 문구. */
  dismissLabel?: string;
  /** true면 Esc로 닫지 않는다. 마지막 장의 버튼으로만 나간다. */
  locked?: boolean;
  onClose: () => void;
};

const KICKER: Record<MentorTone, string> = { info: "", praise: "발령", scold: "호출" };

/**
 * 사수를 자처한 슈엔 사장이 한 장씩 말하는 대화창. 왼쪽에 슈엔이 서 있고 오른쪽 말상자에 대사가 한 글자씩 찍힌다.
 * 글자가 다 나오기 전에 "다음"을 누르면 먼저 남은 글자를 채운다.
 */
export function MentorDialog({ tone, title, pages, certificate, primary, dismissLabel = "네, 사장님", locked = false, onClose }: Props) {
  const dialog = useModalDialog();
  const [page, setPage] = useState(0);
  const text = pages[page] ?? "";
  const typing = useTypewriter(text);
  const last = page >= pages.length - 1;
  const finish = (to?: string) => {
    onClose();
    if (to) navigate(to);
  };
  const choosePrimary = () => {
    if (primary?.onSelect) primary.onSelect();
    else finish(primary?.to);
  };
  const next = () => (typing.done ? setPage(page + 1) : typing.skip());

  return (
    <dialog ref={dialog} className={`mentor-dialog is-${tone}`} aria-labelledby="mentor-title" aria-describedby="mentor-line-full"
      onCancel={(event) => { event.preventDefault(); if (!locked) onClose(); }}>
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
      <div className="mentor-stage">
        <MentorPortrait variant="bust" talking={!typing.done} />
        <div className="mentor-box">
          <p className="mentor-nameplate">
            슈엔 사장{KICKER[tone] && <span className="mentor-nameplate-tag">{KICKER[tone]}</span>}
          </p>
          <h2 id="mentor-title">{title}</h2>
          {/* 화면 읽기 프로그램에는 한 장을 통째로 읽어 주고, 찍히는 글자는 숨긴다. */}
          <p id="mentor-line-full" className="vh" aria-live="polite">{text}</p>
          <p className="mentor-line" aria-hidden="true" onClick={typing.skip}>
            {typing.shown}
            {!typing.done && <span className="mentor-caret" />}
          </p>
          {pages.length > 1 && (
            <p className="mentor-progress" aria-label={`${pages.length}장 중 ${page + 1}장`}>
              {pages.map((_, index) => <span key={index} className={index === page ? "is-current" : undefined} />)}
            </p>
          )}
          <div className="mentor-actions">
            {page > 0 && <button type="button" className="mentor-button is-quiet" onClick={() => setPage(page - 1)}>이전</button>}
            {!last ? (
              <button type="button" className="mentor-button is-strong" onClick={next} autoFocus>다음</button>
            ) : !typing.done ? (
              <button type="button" className="mentor-button is-strong" onClick={typing.skip} autoFocus>다음</button>
            ) : (
              <>
                {primary && <button type="button" className="mentor-button is-quiet" onClick={() => finish()}>{dismissLabel}</button>}
                <button type="button" className="mentor-button is-strong" autoFocus onClick={choosePrimary}>
                  {primary?.label ?? dismissLabel}
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </dialog>
  );
}
