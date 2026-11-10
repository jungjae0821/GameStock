/*
 * 미디어 보도 설명의 "출처: 주소" 줄에서 원문 주소를 꺼낸다. RSS HTML 조각에서는 주소 바로 뒤에
 * 공백 없이 태그가 붙어 오기도 해서(출처: https://x.com/a</p>) 주소는 공백이나 '<' 앞에서 끊는다.
 */
const SOURCE_LINE = /출처:\s*(https?:\/\/[^\s<]+)/gi;

/** 첫 출처 주소와, 출처 줄을 모두 지운 나머지 글. */
export function takeSourceLink(raw: string): { text: string; link: string | null } {
  let link: string | null = null;
  const text = raw.replace(SOURCE_LINE, (_, url: string) => {
    link ??= url;
    return "";
  });
  return { text, link };
}
