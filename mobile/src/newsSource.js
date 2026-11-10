// 웹 src/market/newsSource.ts를 그대로 옮겼다. 규칙을 바꾸면 두 곳을 함께 고친다.
// 서버는 Google News로 모은 글을 모두 "미디어 보도"로 저장한다. 그 안에 섞인 게임사 공지·영상을
// 원문 주소와 게시처, 공식 채널 특유의 제목 형식으로 다시 나눈다.

/** 화면에 보이는 출처. 나머지는 내부 시뮬레이션 신호로만 남는다. */
export const VISIBLE_NEWS_SOURCES = ['공식 발표', '미디어 보도'];

/* 게임사·게임 공식 사이트. 하위 도메인까지 공식으로 본다. */
const OFFICIAL_HOSTS = [
  'hoyoverse.com', 'hoyolab.com', 'mihoyo.com',
  'nexon.com', 'nexon.co.kr', 'nexongames.co.kr',
  'shiftup.co.kr', 'nikke-kr.com', 'nikke-en.com',
  'umamusume.jp', 'umamusume.kakaogames.com', 'kakaogames.com', 'cygames.co.jp',
  'gryphline.com', 'arknights.global', 'hypergryph.com', 'endfield.gryphline.com',
  'kurogames.com', 'wutheringwaves.kurogames.com',
  'devsisters.com', 'cookierun-kingdom.com',
  'playvalorant.com', 'riotgames.com',
  'smilegate.com', 'onstove.com', 'epicseven.onstove.com',
  'blizzard.com', 'playoverwatch.com', 'overwatch.blizzard.com',
  'pocketpair.jp', 'palworld.com',
  'epidgames.com', 'trickcal.com',
  'game.naver.com',
];

/* 게시처 이름. 게임 영문명과 공식 계정이 쓰는 이름이다. */
const OFFICIAL_PUBLISHERS = new Set([
  'Genshin Impact', 'Honkai: Star Rail', 'Zenless Zone Zero', 'HoYoLAB', 'HoYoverse',
  'GODDESS OF VICTORY: NIKKE', 'NIKKE', 'Blue Archive', 'Umamusume', 'Arknights: Endfield',
  'Wuthering Waves', 'Cookie Run: Kingdom', 'VALORANT', 'Riot Games', 'Epic Seven', 'Overwatch',
  'Blizzard', 'Palworld', 'Pocketpair', 'KartRider Rush+', 'Trickcal',
  '호요버스', '넥슨', '시프트업', '카카오게임즈', '쿠로게임즈', '데브시스터즈', '라이엇 게임즈',
  '스마일게이트', '블리자드', '포켓페어', '에피드게임즈', '네이버 게임',
]);

/* 공식 공지에서만 쓰는 제목 표현. 기사 제목의 '업데이트 총정리' 같은 말은 걸리지 않게 좁게 잡는다. */
const OFFICIAL_TITLE = /패치\s?노트|업데이트\s?(?:안내|노트|공지)|점검\s?(?:안내|공지)|patch\s?notes?|release\s?notes?/i;

/*
 * 웹처럼 URL로 hostname을 읽는다. React Native의 URL은 hostname을 지원하지 않는 버전이 있어
 * 그때는 정규식으로 꺼낸다. 정규식도 URL처럼 앞뒤 공백·탭·줄바꿈을 걷고 역슬래시를 슬래시로 읽는다.
 */
const URL_HAS_HOSTNAME = (() => {
  try {
    return new URL('https://a.example/').hostname === 'a.example';
  } catch {
    return false;
  }
})();

export function hostOf(url, useUrl = URL_HAS_HOSTNAME) {
  if (!url) return '';
  if (!useUrl) return hostPattern(url);
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

/** URL이 없을 때의 대안. 퍼센트 인코딩·국제 도메인 변환은 하지 않는다. */
function hostPattern(url) {
  const text = String(url || '').replace(/[\t\n\r]/g, '').trim().replace(/\\/g, '/');
  const match = /^([a-z][a-z0-9+.-]*):(\/\/)?(?:[^@/?#]*@)?([^:/?#]*)/i.exec(text);
  if (!match) return '';
  // "https:host"처럼 슬래시가 빠진 주소도 URL은 http(s)면 호스트로 읽는다.
  if (!match[2] && !/^https?$/i.test(match[1])) return '';
  return match[3].toLowerCase();
}

/** Google News 제목은 '기사 제목 - 게시처' 꼴이다. */
function splitTitle(title) {
  const cut = title.lastIndexOf(' - ');
  return cut < 0 ? { headline: title, publisher: '' } : { headline: title.slice(0, cut), publisher: title.slice(cut + 3).trim() };
}

/* 공식 채널은 '원신 | 7.2 버전 특별 방송 예고', '캐릭터 PV | 젠레스 존 제로'처럼 게임명을 세로줄로 붙인다. */
function hasOfficialSignature(headline, name) {
  if (!name || !headline.includes('|')) return false;
  return headline.split('|').some(part => part.trim() === name);
}

/** names는 종목 코드별 이름이다. 웹은 상장 목록에서, 앱은 시세 목록에서 꺼낸다. */
export function classifyNewsSource(event, names = {}) {
  if (event.source === '공식 발표') return '공식 발표';
  const host = hostOf(event.articleUrl);
  if (host && OFFICIAL_HOSTS.some(official => host === official || host.endsWith(`.${official}`))) return '공식 발표';
  const { headline, publisher } = splitTitle(event.title);
  if (OFFICIAL_PUBLISHERS.has(publisher)) return '공식 발표';
  if (hasOfficialSignature(headline, names[event.stockCode])) return '공식 발표';
  if (OFFICIAL_TITLE.test(headline)) return '공식 발표';
  return '미디어 보도';
}
