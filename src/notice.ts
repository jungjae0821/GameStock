// Site notice shown once per visitor on first entry. Change `id` whenever the
// content changes so everyone who already confirmed sees the new notice again.
// Set SITE_NOTICE to null to stop showing a notice.
export type SiteNotice = {
  id: string;
  items: { heading: string; body: string; code?: string }[];
};

export const SITE_NOTICE: SiteNotice | null = {
  id: "2026-10-10",
  items: [
    {
      heading: "프로모션 코드",
      body: "충전 페이지의 프로모션 코드 칸에 입력하면 특별 칭호 3종을 받을 수 있어요.",
      code: "syuen",
    },
  ],
};
