import { apiFetch } from "./api";

export type UserTitle = {
  id: string;
  name: string;
  description: string;
  category: string;
  weekly: boolean;
  owned: boolean;
  equipped: boolean;
  /** 주간 수익률 칭호의 n관왕 횟수. 일반 칭호는 1. */
  count: number;
  acquiredAt: string | null;
  updatedAt: string | null;
};

export type TitleStatus = {
  titles: UserTitle[];
  newlyAwarded: UserTitle[];
  ownedCount: number;
  totalCount: number;
};

const AWARD_EVENT = "gamestock:title-awarded";

/** 주간 칭호는 "주간 수익률 1위 2관왕"처럼 횟수를 붙여 보여 준다. */
export function titleLabel(title: UserTitle): string {
  return title.weekly && title.owned ? `${title.name} ${title.count}관왕` : title.name;
}

/**
 * 서버가 현재 조건을 확인해 새 칭호를 지급한다. 아직 안내하지 않은 칭호는
 * 한 번만 돌아오므로 여기서 바로 알림 이벤트로 내보낸다.
 */
export async function checkTitles(signal?: AbortSignal): Promise<TitleStatus> {
  const status = await apiFetch<TitleStatus>("/api/titles/check", { method: "POST", signal });
  if (status.newlyAwarded.length > 0) {
    window.dispatchEvent(new CustomEvent<UserTitle[]>(AWARD_EVENT, { detail: status.newlyAwarded }));
  }
  return status;
}

/** 프로모션 코드로 특별 칭호를 받는다. 새로 받은 칭호는 바로 알림 이벤트로 내보낸다. */
export async function redeemPromoCode(code: string): Promise<TitleStatus> {
  const status = await apiFetch<TitleStatus>("/api/titles/promo", { method: "POST", body: JSON.stringify({ code }) });
  if (status.newlyAwarded.length > 0) {
    window.dispatchEvent(new CustomEvent<UserTitle[]>(AWARD_EVENT, { detail: status.newlyAwarded }));
  }
  return status;
}

/** 보유한 칭호를 장착한다. 빈 값을 보내면 장착을 해제한다. */
export function equipTitle(titleId: string | null): Promise<TitleStatus> {
  return apiFetch<TitleStatus>("/api/titles/equipped", { method: "PUT", body: JSON.stringify({ titleId: titleId ?? "" }) });
}

export function onTitlesAwarded(listener: (titles: UserTitle[]) => void): () => void {
  const handle = (event: Event) => listener((event as CustomEvent<UserTitle[]>).detail);
  window.addEventListener(AWARD_EVENT, handle);
  return () => window.removeEventListener(AWARD_EVENT, handle);
}
