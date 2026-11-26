import { apiFetch } from "./api";

/* 종목토론방. 종목마다 글과 한 단계 답글, 좋아요를 둔다. 첫 글이 올라온 지 일주일이 지나면 글타래째 지워진다. */
export type TalkPost = {
  id: number;
  nickname: string;
  body: string;
  createdAt: string;
  likes: number;
  liked: boolean;
  mine: boolean;
  /** 글을 쓸 때의 현재가. 예전 글에는 없을 수 있다. */
  priceAtPost: number | null;
  replies: TalkPost[];
};
export type TalkPage = { posts: TalkPost[]; hasMore: boolean; maxLength: number; retentionDays: number };
export type TalkLike = { id: number; likes: number; liked: boolean };

export function loadTalk(code: string, before = 0, signal?: AbortSignal) {
  return apiFetch<TalkPage>(`/api/stocks/${encodeURIComponent(code)}/talk${before ? `?before=${before}` : ""}`, { signal });
}

export function writeTalk(code: string, body: string, parentId: number | null = null) {
  return apiFetch<TalkPost>(`/api/stocks/${encodeURIComponent(code)}/talk`, { method: "POST", body: JSON.stringify({ body, parentId }) });
}

export function deleteTalk(id: number) {
  return apiFetch<{ id: number }>(`/api/talk/${id}`, { method: "DELETE" });
}

export function likeTalk(id: number) {
  return apiFetch<TalkLike>(`/api/talk/${id}/like`, { method: "POST" });
}

/** 홈 화면용. 모든 종목에서 막 올라온 원글. 답글은 개수만 온다. */
export type RecentTalk = { id: number; stockCode: string; nickname: string; body: string; createdAt: string; likes: number; replies: number };

export function loadRecentTalk(limit = 6, signal?: AbortSignal) {
  return apiFetch<RecentTalk[]>(`/api/talk/recent?limit=${limit}`, { auth: false, signal });
}
