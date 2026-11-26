import { useEffect, useMemo, useRef, useState } from "react";
import { useBillboard } from "../lib/billboard";
import { MentorPortrait } from "./mentor/MentorPortrait";
import { BILLBOARD_LINES } from "./mentor/lines";

/** 여러 문구가 떠 있을 때 다음 문구로 넘어가는 간격. */
const ROTATE_MS = 10_000;

type Props = {
  nickname: string;
  body: string;
  preview?: boolean;
};

type TextProps = { nickname: string; body: string; className?: string; onAnimationEnd?: () => void };

function BillboardText({ nickname, body, className = "", onAnimationEnd }: TextProps) {
  return (
    <p className={`nx-billboard-line ${className}`} onAnimationEnd={onAnimationEnd}>
      <span className="nx-billboard-author">{nickname}</span>
      <span className={`nx-billboard-body${body ? "" : " is-placeholder"}`}>{body || "여기에 적은 문구가 보여요"}</span>
    </p>
  );
}

/** 전광판 한 줄. 홈과 작성 화면의 미리보기가 같은 모양을 쓴다. */
export function BillboardLine({ nickname, body, preview = false }: Props) {
  return (
    <div className={`nx-billboard${preview ? " is-preview" : ""}`}>
      <BillboardText nickname={nickname} body={body} />
    </div>
  );
}

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

type Showing = { id: number; nickname: string; body: string };

/** 슈엔 대사를 섞어 전광판 문구 모양으로 만든다. 투자자 문구와 겹치지 않게 id는 음수다. */
function shuenReel(): Showing[] {
  const lines = [...BILLBOARD_LINES];
  for (let i = lines.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [lines[i], lines[j]] = [lines[j], lines[i]];
  }
  return lines.map((body, index) => ({ id: -1 - index, nickname: "슈엔", body }));
}

/**
 * 홈 상단 띠. 왼쪽에는 슈엔 얼굴이 늘 걸려 있다. 투자자가 올린 문구가 있으면 그것만 돌리고,
 * 하나도 없거나 받아 오지 못하면 슈엔 한마디를 대신 돌린다. 그래서 띠는 홈에서 항상 보인다.
 * 문구는 아래에서 올라오고, 둘 이상이면 10초마다 위로 밀려나며 다음 문구로 바뀐다. 마우스를 올리면 멈춘다.
 */
export function HomeBillboard() {
  const { board } = useBillboard();
  const shuen = useMemo(shuenReel, []);
  const live = board?.live ?? [];
  const items: Showing[] = live.length ? live : shuen;
  const [step, setStep] = useState(0);
  const [leaving, setLeaving] = useState<{ item: Showing; step: number } | null>(null);
  const [paused, setPaused] = useState(false);
  const shown = useRef<Showing | null>(null);
  const count = items.length;
  const current = items[step % count];
  shown.current = current;

  useEffect(() => {
    if (count < 2 || paused) return undefined;
    const timer = window.setInterval(() => {
      const outgoing = shown.current;
      if (outgoing && !reducedMotion()) setLeaving({ item: outgoing, step: Date.now() });
      setStep((value) => value + 1);
    }, ROTATE_MS);
    return () => window.clearInterval(timer);
  }, [count, paused]);

  return (
    <section className="nx-billboard-slot" aria-label="전광판" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <div className="nx-billboard has-host">
        <MentorPortrait variant="face" />
        <div className="nx-billboard-reel">
          {leaving && (
            <BillboardText key={`out-${leaving.step}`} className="is-leaving" nickname={leaving.item.nickname} body={leaving.item.body}
              onAnimationEnd={() => setLeaving(null)} />
          )}
          <BillboardText key={`in-${step}-${current.id}`} className="is-entering" nickname={current.nickname} body={current.body} />
        </div>
      </div>
    </section>
  );
}
