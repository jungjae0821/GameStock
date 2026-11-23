import { useEffect, useState } from "react";
import { apiFetch } from "./lib/api";

// Site notices shown on first entry. Notices are written in the admin page (공지 tab)
// and served by /api/content. One notice opens per visit: the newest one not yet confirmed.
// `key` changes whenever a notice is edited, so everyone who already confirmed sees it again.
export type NoticeAction = { label: string; url: string };
export type SiteNoticeItem = {
  key: string;
  heading: string;
  body: string;
  code?: string;
  /** 머리 그림. 없으면 글만 있는 창이 된다. */
  image?: string;
  /** 오른쪽 강조 버튼. 없으면 닫기 버튼 하나만 둔다. */
  action?: NoticeAction;
  dismissLabel?: string;
  /** 마지막으로 올리거나 고친 시각(ms). 처음 온 방문자에게 그 전에 올라온 공지를 거르는 데 쓴다. */
  postedAt?: number;
};

/**
 * Shown only when the content API cannot be reached (for example a backend
 * deployed before the admin content API). An empty list from the API means
 * "no notice" and does not fall back to this.
 */
export const SITE_NOTICE: SiteNoticeItem[] = [
  {
    key: "2026-10-10",
    heading: "프로모션 코드",
    body: "충전 페이지의 프로모션 코드 칸에 입력하면 특별 칭호 3종을 받을 수 있어요.",
    code: "syuen",
  },
];

/*
 * 콘텐츠 API에는 코드 칸이 없어서 본문 마지막 줄 "코드: ..."로 저장한다.
 * 관리자 화면은 이 줄을 따로 입력받아 붙이고, 공지 창은 떼어 내 코드 상자로 보여 준다.
 */
const CODE_LINE = /\n*코드:[ \t]*(\S+)[ \t]*$/;

export function splitNoticeCode(body: string): { body: string; code: string } {
  const match = body.match(CODE_LINE);
  return match ? { body: body.slice(0, match.index).trimEnd(), code: match[1] } : { body, code: "" };
}

export function joinNoticeCode(body: string, code: string): string {
  const trimmed = body.trim();
  return code.trim() ? `${trimmed}\n\n코드: ${code.trim()}` : trimmed;
}

export type NoticeRow = {
  id: number;
  title: string;
  body: string;
  updated_at?: string | number | null;
  image_url?: string | null;
  action_label?: string | null;
  action_url?: string | null;
  dismiss_label?: string | null;
};

/** 공지 하나의 확인 기록 열쇠. 공지를 고치면 updated_at이 바뀌어 다시 보인다. */
export const noticeKey = (row: Pick<NoticeRow, "id" | "updated_at">) => `${row.id}:${row.updated_at ?? ""}`;

export function noticeItem(row: NoticeRow): SiteNoticeItem {
  const { body, code } = splitNoticeCode(row.body);
  const postedAt = row.updated_at == null ? NaN : new Date(row.updated_at).getTime();
  return {
    ...(Number.isFinite(postedAt) ? { postedAt } : {}),
    key: noticeKey(row),
    heading: row.title,
    body,
    ...(code ? { code } : {}),
    ...(row.image_url ? { image: row.image_url } : {}),
    ...(row.action_label && row.action_url ? { action: { label: row.action_label, url: row.action_url } } : {}),
    ...(row.dismiss_label ? { dismissLabel: row.dismiss_label } : {}),
  };
}

/** undefined는 아직 불러오는 중, 빈 배열은 보여 줄 공지가 없다는 뜻이다. 최신 공지가 앞. */
export function useSiteNotices(): SiteNoticeItem[] | undefined {
  const [notices, setNotices] = useState<SiteNoticeItem[] | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    apiFetch<NoticeRow[]>("/api/content?kind=NOTICE&limit=20")
      .then((rows) => { if (alive) setNotices(rows.map(noticeItem)); })
      .catch(() => { if (alive) setNotices(SITE_NOTICE); });
    return () => { alive = false; };
  }, []);
  return notices;
}
