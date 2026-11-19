import { useState } from "react";
import { useProfile } from "../../lib/profile";
import { useEngagement } from "../../lib/engagement";
import { useCareer } from "../../lib/career";
import { LISTING_BY_CODE } from "../../market/universe";
import { rate, signedWon } from "../../market/format";

type Best = { code: string; name: string; pnlRate: number } | null;

const W = 1080, H = 1080;
/* 카드 색은 다크 테마 값을 그대로 쓴다. 핑크는 상단 로고 글자에만 쓰며, 사이트 로고(BrandMark)와 같은 색이다. */
const COLORS = { stage: "#12101c", panel: "#1a1726", line: "#2e2942", ink: "#f1eef8", muted: "#aaa4be", brand: "#fd5886", up: "#ff4d5e", down: "#4f7cff" };
/** 공유 문구에 붙는 주소. 링크 미리보기(og 태그)는 index.html에 있다. */
export const SITE_URL = "https://syuenasset.xyz";
const FONT = '"GyeonggiTitle", "Pretendard Variable", Pretendard, "Malgun Gothic", sans-serif';

/** 수익률 구간마다 붙는 한 줄. 잃었을 때도 웃고 넘길 수 있게 칭호와 같은 말투를 쓴다. */
function caption(ratio: number): string {
  if (ratio >= 1) return "두 배 달성. 이제 전설이에요";
  if (ratio >= 0.3) return "떡상 인증";
  if (ratio > 0) return "꾸준히 오르는 중";
  if (ratio === 0) return "본전도 실력이에요";
  if (ratio > -0.3) return "잠깐 쉬어 가는 중";
  if (ratio > -0.5) return "구조대 대기 중";
  return "한강 수온 체크";
}

async function draw(nickname: string, pnl: number, pnlRate: number, best: Best, fandom: string | null, grade: string | null): Promise<Blob> {
  await document.fonts?.ready;
  const canvas = document.createElement("canvas");
  canvas.width = W;
  canvas.height = H;
  const g = canvas.getContext("2d");
  if (!g) throw new Error("이미지를 만들 수 없는 브라우저예요.");
  const tint = pnlRate > 0 ? COLORS.up : pnlRate < 0 ? COLORS.down : COLORS.muted;

  g.fillStyle = COLORS.stage;
  g.fillRect(0, 0, W, H);
  g.fillStyle = COLORS.panel;
  g.beginPath();
  g.roundRect(64, 64, W - 128, H - 128, 28);
  g.fill();

  g.textBaseline = "alphabetic";
  g.fillStyle = COLORS.brand;
  g.font = `700 40px ${FONT}`;
  g.fillText("슈엔증권", 120, 168);
  g.fillStyle = COLORS.muted;
  g.font = `500 30px ${FONT}`;
  g.textAlign = "right";
  g.fillText(new Date().toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric", timeZone: "Asia/Seoul" }), W - 120, 168);
  g.textAlign = "left";

  g.fillStyle = COLORS.ink;
  g.font = `700 52px ${FONT}`;
  g.fillText(nickname, 120, 300);
  /* 직급과 주력 게임을 이름 아래 한 줄로. 승진이 이 게임의 목표라서 카드에서도 먼저 보이게 한다. */
  const tagline = [grade ? `${grade} 트레이더` : null, fandom ? `${fandom} 팬` : null].filter(Boolean).join(" · ");
  if (tagline) {
    g.fillStyle = COLORS.muted;
    g.font = `500 30px ${FONT}`;
    g.fillText(tagline, 120, 350);
  }

  g.fillStyle = tint;
  g.font = `800 176px ${FONT}`;
  g.fillText(rate(pnlRate), 112, 560);
  g.fillStyle = COLORS.ink;
  g.font = `600 44px ${FONT}`;
  g.fillText(signedWon(pnl), 120, 636);

  g.fillStyle = tint;
  g.fillRect(120, 690, 8, 56);
  g.fillStyle = COLORS.ink;
  g.font = `700 44px ${FONT}`;
  g.fillText(caption(pnlRate), 152, 734);

  g.strokeStyle = COLORS.line;
  g.lineWidth = 2;
  g.beginPath();
  g.moveTo(120, 820);
  g.lineTo(W - 120, 820);
  g.stroke();
  if (best) {
    g.fillStyle = COLORS.muted;
    g.font = `500 30px ${FONT}`;
    g.fillText("가장 많이 번 종목", 120, 880);
    g.fillStyle = COLORS.ink;
    g.font = `700 40px ${FONT}`;
    g.fillText(best.name, 120, 936);
    g.fillStyle = best.pnlRate >= 0 ? COLORS.up : COLORS.down;
    g.textAlign = "right";
    g.fillText(rate(best.pnlRate), W - 120, 936);
    g.textAlign = "left";
  }
  g.fillStyle = COLORS.muted;
  g.font = `500 22px ${FONT}`;
  g.fillText("모의투자 결과예요. 실제 돈은 오가지 않아요.", 120, H - 96);
  g.textAlign = "right";
  g.fillText(SITE_URL.replace("https://", ""), W - 120, H - 96);
  g.textAlign = "left";

  return new Promise((resolve, reject) => canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("이미지를 만들지 못했어요."))), "image/png"));
}

/** 내 계좌의 수익률을 정사각형 이미지로 만들어 공유하거나 저장한다. */
export function ShareCardButton({ pnl, pnlRate, best }: { pnl: number; pnlRate: number; best: Best }) {
  const profile = useProfile();
  const engagement = useEngagement();
  const career = useCareer();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const nickname = profile?.nickname ?? "익명 투자자";
  /* 기존 회원(이전 전)과 운영 예외 계정은 직급이 의미가 없어 카드에 적지 않는다. */
  const grade = career && !career.legacy && !career.exempt ? career.gradeLabel : null;
  const shareText = `${nickname}님의 슈엔증권 모의투자 수익률 ${rate(pnlRate)}${grade ? ` (${grade})` : ""}. 좋아하는 게임 주식으로 같이 겨뤄 봐요!`;

  const copyLink = async () => {
    setMessage("");
    try {
      await navigator.clipboard.writeText(`${shareText}\n${SITE_URL}`);
      setMessage("공유 문구와 링크를 복사했어요. 카톡이나 커뮤니티에 붙여 넣어 보세요.");
    } catch {
      setMessage(`복사하지 못했어요. 주소를 직접 알려 주세요: ${SITE_URL}`);
    }
  };

  const share = async () => {
    setBusy(true);
    setMessage("");
    try {
      const fandom = engagement?.fandomCode ? LISTING_BY_CODE[engagement.fandomCode]?.name ?? null : null;
      const blob = await draw(nickname, pnl, pnlRate, best, fandom, grade);
      const file = new File([blob], "syuen-return.png", { type: "image/png" });
      /* 이미지와 함께 문구·주소를 넘긴다. 받는 쪽 앱이 문구를 버려도 카드 아래에 주소가 찍혀 있다. */
      const payload = { files: [file], title: "슈엔증권 수익 인증", text: `${shareText}\n${SITE_URL}` };
      if (navigator.canShare?.(payload)) {
        await navigator.share(payload);
      } else if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: "슈엔증권 수익 인증" });
      } else {
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = file.name;
        link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
        setMessage("이미지를 저장했어요. 링크 복사로 문구와 주소도 함께 올려 보세요.");
      }
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) setMessage(error instanceof Error ? error.message : "이미지를 만들지 못했어요.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="nx-share-card">
      <button type="button" className="nx-btn is-small" onClick={() => void share()} disabled={busy}>{busy ? "만드는 중…" : "수익 인증 카드 만들기"}</button>
      <button type="button" className="nx-btn is-small" onClick={() => void copyLink()}>링크 복사</button>
      {message && <span className="nx-muted" role="status">{message}</span>}
    </div>
  );
}
