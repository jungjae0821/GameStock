import { useEffect, useState } from "react";
import { useAuthUser } from "../../lib/auth";
import { useCareer } from "../../lib/career";
import { featureLock, useEngagement, type FeatureId } from "../../lib/engagement";
import { MentorDialog } from "./MentorDialog";
import { DESK_SPEECH, type Desk } from "./lines";
import { readMentor, writeMentor } from "./mentorStore";

const FEATURE: Record<Desk, FeatureId> = { TAP: "TAP", FUTURES: "FUTURES", OPTIONS: "OPTIONS", QUANT: "QUANT" };

/**
 * 열린 데스크에 처음 들어오면 슈엔이 핵심만 짚어 준다. 한 번 보면 다시 뜨지 않고,
 * 이전을 기다리는 기존 회원처럼 원래 쓰던 사람에게는 띄우지 않는다.
 */
export function DeskIntro({ desk }: { desk: Desk }) {
  const { user } = useAuthUser();
  const career = useCareer();
  const engagement = useEngagement();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const uid = user?.uid ?? null;
  const unlocked = engagement !== null && featureLock(engagement, FEATURE[desk]) === null;

  useEffect(() => {
    if (!uid || !career || career.legacy || career.exempt || !unlocked) return;
    const saved = readMentor(uid);
    if (saved.desks?.includes(desk)) return;
    writeMentor(uid, { ...saved, desks: [...(saved.desks ?? []), desk] });
    setPending(true);
  }, [uid, career, unlocked, desk]);

  // 승진 발령장 같은 다른 창이 먼저 떠 있으면 닫힌 뒤에 연다.
  useEffect(() => {
    if (!pending) return;
    const timer = window.setInterval(() => {
      if (document.querySelector("dialog[open]")) return;
      setPending(false);
      setOpen(true);
    }, 800);
    return () => window.clearInterval(timer);
  }, [pending]);

  if (!open) return null;
  const speech = DESK_SPEECH[desk];
  return <MentorDialog tone="info" title={speech.title} pages={speech.pages} onClose={() => setOpen(false)} />;
}
