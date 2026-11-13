/* 한국어 조사. 받침 유무에 따라 이/가, 은/는, 이에요/예요가 갈린다. 한글이 아닌 끝 글자는 받침 없는 것으로 친다. */

function hasFinalConsonant(word: string): boolean {
  const code = word.trim().charCodeAt(word.trim().length - 1);
  if (Number.isNaN(code) || code < 0xac00 || code > 0xd7a3) return false;
  return (code - 0xac00) % 28 !== 0;
}

/** "사원" → "사원이", "대리" → "대리가" */
export const withSubject = (word: string) => `${word}${hasFinalConsonant(word) ? "이" : "가"}`;
/** "선물" → "선물은", "퀀트" → "퀀트는" */
export const withTopic = (word: string) => `${word}${hasFinalConsonant(word) ? "은" : "는"}`;
/** "사원" → "사원이에요", "대리" → "대리예요" */
export const withCopula = (word: string) => `${word}${hasFinalConsonant(word) ? "이에요" : "예요"}`;
