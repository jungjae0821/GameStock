/*
 * 슈엔이 이미 한 말. 이 브라우저에만 사용자별로 남긴다. 저장소를 못 쓰면 이번 세션 동안만 기억하고,
 * 다음에 다시 보여 주는 정도로 끝난다(서버 상태에는 영향이 없다).
 */

export type MentorMemory = { intro?: boolean; lastEventAt?: string | null; desks?: string[] };

const KEY = (uid: string) => `gamestock-mentor:${uid}`;
const session = new Map<string, MentorMemory>();

export function readMentor(uid: string): MentorMemory {
  try {
    const raw = window.localStorage.getItem(KEY(uid));
    if (raw) return JSON.parse(raw) as MentorMemory;
  } catch { /* 저장소를 못 쓰면 세션 기억으로 대신한다. */ }
  return session.get(uid) ?? {};
}

export function writeMentor(uid: string, memory: MentorMemory): void {
  session.set(uid, memory);
  try { window.localStorage.setItem(KEY(uid), JSON.stringify(memory)); } catch { /* 세션 기억만 남는다. */ }
}
