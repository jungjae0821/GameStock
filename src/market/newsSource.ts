import type { NewsSource } from "./types";
import { LISTING_BY_CODE } from "./universe";

/**
 * 서버는 Google News로 모은 글을 모두 "미디어 보도"로 저장한다. 그 안에는 게임사가 직접 올린
 * 공지·영상도 섞여 있어, 원문 주소와 게시처, 공식 채널 특유의 제목 형식을 보고 다시 나눈다.
 */

/* 게임사·게임 공식 사이트. 하위 도메인까지 공식으로 본다. */
const OFFICIAL_HOSTS = [
  "hoyoverse.com", "hoyolab.com", "mihoyo.com",
  "nexon.com", "nexon.co.kr", "nexongames.co.kr",
  "shiftup.co.kr", "nikke-kr.com", "nikke-en.com",
  "umamusume.jp", "umamusume.kakaogames.com", "kakaogames.com", "cygames.co.jp",
  "gryphline.com", "arknights.global", "hypergryph.com", "endfield.gryphline.com",
  "kurogames.com", "wutheringwaves.kurogames.com",
  "devsisters.com", "cookierun-kingdom.com",
  "playvalorant.com", "riotgames.com",
  "smilegate.com", "onstove.com", "epicseven.onstove.com",
  "blizzard.com", "playoverwatch.com", "overwatch.blizzard.com",
  "pocketpair.jp", "palworld.com",
  "epidgames.com", "trickcal.com",
  "game.naver.com",
];

/* 게시처 이름. 게임 영문명과 공식 계정이 쓰는 이름이다. */
const OFFICIAL_PUBLISHERS = new Set([
  "Genshin Impact", "Honkai: Star Rail", "Zenless Zone Zero", "HoYoLAB", "HoYoverse",
  "GODDESS OF VICTORY: NIKKE", "NIKKE", "Blue Archive", "Umamusume", "Arknights: Endfield",
  "Wuthering Waves", "Cookie Run: Kingdom", "VALORANT", "Riot Games", "Epic Seven", "Overwatch",
  "Blizzard", "Palworld", "Pocketpair", "KartRider Rush+", "Trickcal",
  "호요버스", "넥슨", "시프트업", "카카오게임즈", "쿠로게임즈", "데브시스터즈", "라이엇 게임즈",
  "스마일게이트", "블리자드", "포켓페어", "에피드게임즈", "네이버 게임",
]);

/* 공식 공지에서만 쓰는 제목 표현. 기사 제목의 "업데이트 총정리" 같은 말은 걸리지 않게 좁게 잡는다. */
const OFFICIAL_TITLE = /패치\s?노트|업데이트\s?(?:안내|노트|공지)|점검\s?(?:안내|공지)|patch\s?notes?|release\s?notes?/i;

function hostOf(url: string | null | undefined): string {
  if (!url) return "";
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return "";
  }
}

/** Google News 제목은 "기사 제목 - 게시처" 꼴이다. */
function splitTitle(title: string): { headline: string; publisher: string } {
  const cut = title.lastIndexOf(" - ");
  return cut < 0 ? { headline: title, publisher: "" } : { headline: title.slice(0, cut), publisher: title.slice(cut + 3).trim() };
}

/* 공식 채널은 "원신 | 7.2 버전 특별 방송 예고", "캐릭터 PV | 젠레스 존 제로"처럼 게임명을 세로줄로 붙인다. */
function hasOfficialSignature(headline: string, code: string): boolean {
  const name = LISTING_BY_CODE[code]?.name;
  if (!name || !headline.includes("|")) return false;
  return headline.split("|").some((part) => part.trim() === name);
}

export function classifyNewsSource(event: { source?: string; title: string; stockCode: string; articleUrl?: string | null }): NewsSource {
  if (event.source === "공식 발표") return "공식 발표";
  const host = hostOf(event.articleUrl);
  if (host && OFFICIAL_HOSTS.some((official) => host === official || host.endsWith(`.${official}`))) return "공식 발표";
  const { headline, publisher } = splitTitle(event.title);
  if (OFFICIAL_PUBLISHERS.has(publisher)) return "공식 발표";
  if (hasOfficialSignature(headline, event.stockCode)) return "공식 발표";
  if (OFFICIAL_TITLE.test(headline)) return "공식 발표";
  return "미디어 보도";
}
