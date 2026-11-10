import { useEffect, useState } from "react";
import { apiFetch } from "../lib/api";
import { takeSourceLink } from "../lib/sourceLink";


export const EXCERPT_PARAGRAPHS = 2;

/** 저장된 설명에서 원문 링크와 글을 나눈다. 미디어 보도는 RSS의 HTML 조각에 "출처: 주소" 줄이 붙어 온다. */
export function readDescription(raw: string | undefined, title: string): { paragraphs: string[]; link: string | null } {
  if (!raw?.trim()) return { paragraphs: [], link: null };
  const taken = takeSourceLink(raw);
  let link = taken.link;
  let source = taken.text;
  if (/<[a-z][\s\S]*>/i.test(source)) {
    const doc = new DOMParser().parseFromString(source, "text/html");
    const href = doc.querySelector("a[href]")?.getAttribute("href");
    if (!link && href && /^https?:\/\//i.test(href)) link = href;
    doc.querySelectorAll("br").forEach((br) => br.replaceWith("\n"));
    source = doc.body.textContent ?? "";
  }
  /* 제목을 되풀이할 뿐인 RSS 요약은 문단으로 치지 않는다. */
  const squeeze = (value: string) => value.replace(/[\s\-–·]+/g, "");
  const titleKey = squeeze(title).slice(0, 20);
  const paragraphs = source
    .replace(/\u00a0/g, " ")
    .split(/\n\s*\n|\r?\n/)
    .map((line) => line.replace(/[ \t]+/g, " ").trim())
    .filter((line) => line.length > 0 && !squeeze(line).startsWith(titleKey))
    .slice(0, EXCERPT_PARAGRAPHS);
  return { paragraphs, link };
}

export type Excerpt = { paragraphs: string[]; url: string | null };
const excerptCache = new Map<string, Excerpt>();

/** 서버가 원문 기사에서 뽑은 첫 두 문단. 목록에 미리 실려 왔으면 그대로 쓰고, 아니면 Google News 링크가 있는 소식만 묻는다. */
export function useExcerpt(link: string | null, preloaded: Excerpt | null): { excerpt: Excerpt | null; loading: boolean } {
  const [state, setState] = useState<{ link: string | null; excerpt: Excerpt | null }>(() => ({
    link,
    excerpt: link ? excerptCache.get(link) ?? null : null,
  }));
  const remote = preloaded === null && link !== null && link.startsWith("https://news.google.com/");

  useEffect(() => {
    if (!remote || !link) return;
    const cached = excerptCache.get(link);
    if (cached) {
      setState({ link, excerpt: cached });
      return;
    }
    setState({ link, excerpt: null });
    const controller = new AbortController();
    apiFetch<Excerpt>(`/api/news/excerpt?link=${encodeURIComponent(link)}`, { signal: controller.signal })
      .then((result) => {
        const excerpt = { paragraphs: (result.paragraphs ?? []).slice(0, EXCERPT_PARAGRAPHS), url: result.url ?? null };
        excerptCache.set(link, excerpt);
        setState({ link, excerpt });
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ link, excerpt: { paragraphs: [], url: null } });
      });
    return () => controller.abort();
  }, [link, remote]);

  if (preloaded) return { excerpt: preloaded, loading: false };
  const current = state.link === link ? state.excerpt : null;
  return { excerpt: current, loading: remote && current === null };
}

export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}

