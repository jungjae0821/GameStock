import { useEffect, useState } from "react";
import { useAuthUser } from "../../lib/auth";
import { useCareer, type Career, type CareerEvent } from "../../lib/career";
import { useProfile } from "../../lib/profile";
import { useRoute } from "../../router";
import { Link } from "../../components/Link";
import { MentorDialog, type Certificate, type MentorTone } from "./MentorDialog";
import { DESK_OPENED, fill, introSpeech, OJT_HINT, promotionLine, scoldLines } from "./lines";
import { readMentor, writeMentor } from "./mentorStore";

type Scene = { tone: MentorTone; title: string; pages: string[]; certificate?: Certificate; primary?: { label: string; to?: string } };

const GRADE_LABEL: Record<string, string> = {
  TRAINEE: "수습", STAFF: "사원", ASSOCIATE: "대리", MANAGER: "과장", SENIOR_MANAGER: "차장", GENERAL_MANAGER: "부장", EXECUTIVE: "이사",
};
const ALLOCATION: Record<string, number> = {
  STAFF: 3_000_000, ASSOCIATE: 10_000_000, MANAGER: 30_000_000, SENIOR_MANAGER: 100_000_000, GENERAL_MANAGER: 300_000_000, EXECUTIVE: 1_000_000_000,
};
/** 한 번에 하나만 말한다. 승진이 강등보다, 강등이 재교육·일일 한도보다 먼저다. */
const PRIORITY: Record<string, number> = { PROMOTE: 4, DEMOTE: 3, RETRAIN: 2, DAY_LIMIT: 1 };
const kstDate = (iso: string) => new Date(iso).toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric", timeZone: "Asia/Seoul" });

function sceneForEvent(event: CareerEvent, nick: string): Scene | null {
  const values = { nick, from: GRADE_LABEL[event.fromGrade ?? ""] ?? "", to: GRADE_LABEL[event.toGrade ?? ""] ?? "" };
  if (event.kind === "PROMOTE" && event.toGrade) {
    const grade = event.toGrade as keyof typeof DESK_OPENED;
    const desk = DESK_OPENED[grade];
    return {
      tone: "praise",
      title: `${values.to} 발령`,
      certificate: { nickname: nick, gradeLabel: values.to, allocation: ALLOCATION[event.toGrade] ?? 0, date: kstDate(event.at) },
      pages: [fill(promotionLine(event.toGrade as Career["grade"]), values)],
      primary: desk ? { label: `${desk.name} 데스크 가 보기`, to: desk.to } : undefined,
    };
  }
  if (event.kind === "DEMOTE" || event.kind === "RETRAIN" || event.kind === "DAY_LIMIT") {
    const [head, body] = scoldLines(event.kind);
    return { tone: "scold", title: fill(head, values), pages: [fill(body, values)], primary: { label: "커리어 보기", to: "/career" } };
  }
  return null;
}

/**
 * 사수 슈엔. 수습의 첫 출근 안내, 승진 발령장, 강등·재교육·손실한도 호출을 한 번씩 띄우고,
 * OJT 중에는 화면 구석에 다음 할 일을 붙여 둔다. 무엇을 이미 보여 줬는지는 이 브라우저에 사용자별로 남긴다.
 */
export function MentorHost() {
  const { user } = useAuthUser();
  const career = useCareer();
  const profile = useProfile();
  const route = useRoute();
  const [scene, setScene] = useState<Scene | null>(null);
  // 정한 장면은 먼저 줄에 세운다. 기억은 줄에 세울 때 남기므로, 띄우기 전에 화면이 바뀌어도 장면이 사라지지 않는다.
  const [queued, setQueued] = useState<Scene | null>(null);
  const [cardHidden, setCardHidden] = useState(false);
  const uid = user?.uid ?? null;
  const nick = profile?.nickname || "신입";

  useEffect(() => {
    if (!uid || !career || career.exempt || career.legacy || scene || queued) return;
    const saved = readMentor(uid);
    const newest = career.events[0]?.at ?? null;
    if (!saved.intro && career.grade === "TRAINEE") {
      const speech = introSpeech(career.events.some((event) => event.kind === "MIGRATE"));
      writeMentor(uid, { ...saved, intro: true, lastEventAt: newest });
      setQueued({ tone: "info", title: speech.title, pages: speech.pages.map((text) => fill(text, { nick })), primary: { label: "OJT 시작하기", to: "/career" } });
      return;
    }
    if (saved.lastEventAt === undefined) {
      // 이 브라우저에서 처음 보는 사람이면 지난 기록을 다시 틀지 않고 지금부터 센다.
      writeMentor(uid, { ...saved, intro: true, lastEventAt: newest });
      return;
    }
    const fresh = career.events.filter((event) => !saved.lastEventAt || event.at > saved.lastEventAt);
    if (!fresh.length) return;
    writeMentor(uid, { ...saved, lastEventAt: newest });
    const top = [...fresh].sort((left, right) => (PRIORITY[right.kind] ?? 0) - (PRIORITY[left.kind] ?? 0))[0];
    const next = sceneForEvent(top, nick);
    if (next) setQueued(next);
  }, [uid, career, scene, queued, nick]);

  // 공지나 로그인 창이 떠 있으면 닫힐 때까지 기다렸다가 띄운다.
  useEffect(() => {
    if (!queued) return;
    const timer = window.setInterval(() => {
      if (document.querySelector("dialog[open]")) return;
      setScene(queued);
      setQueued(null);
    }, 800);
    return () => window.clearInterval(timer);
  }, [queued]);

  const ojtCard = career && !career.legacy && !career.exempt && career.grade === "TRAINEE" && !cardHidden
    && route.name !== "career" && route.name !== "login" ? career.promotion.find((step) => !step.done) : undefined;
  const doneCount = career?.promotion.filter((step) => step.done).length ?? 0;

  return (
    <>
      {scene && <MentorDialog {...scene} dismissLabel={scene.tone === "scold" ? "죄송합니다" : "알겠어요"} onClose={() => setScene(null)} />}
      {ojtCard && (
        <aside className="mentor-ojt" aria-label="OJT 진행">
          <img className="mentor-avatar is-small" src="/brand/shuen-mark.png" alt="" width="32" height="32" />
          <div className="mentor-ojt-copy">
            <p className="mentor-kicker">OJT {doneCount}/{career?.promotion.length} · 사수 슈엔</p>
            <p>{OJT_HINT[ojtCard.id] ?? ojtCard.label}</p>
          </div>
          <Link className="nx-btn is-small" to="/career">이어서</Link>
          <button type="button" className="mentor-ojt-close" aria-label="OJT 안내 숨기기" onClick={() => setCardHidden(true)}>
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M3.5 3.5l9 9m0-9l-9 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
          </button>
        </aside>
      )}
    </>
  );
}
