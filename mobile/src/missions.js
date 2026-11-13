// 오늘의 투자 미션 이름과 이동할 화면. 웹 src/components/MissionBoard.tsx의 labels와 같은 값이다.
// 서버는 미션 ID만 내려주므로 문구와 이동 주소는 앱이 갖고 있다.
// 미션 보상은 운용계좌가 아니라 내 지갑으로 들어간다(서버 MarketRankingMissions).
export const MISSION_REWARD = 10_000;

const stockMission = (name, code) => ({
  title: `${name} 종목 분석`,
  description: `${name}의 차트와 시세 흐름을 확인해 보세요.`,
  action: '종목 보기',
  href: `/market/${code}`,
});

const LABELS = {
  market: { title: '시장 둘러보기', description: '시세표에서 오늘 움직이는 종목을 찾아보세요.', action: '시장 보기', href: '/market' },
  news: { title: '뉴스 읽기', description: '가격이 왜 움직였는지 뉴스에서 확인해 보세요.', action: '뉴스 보기', href: '/news' },
  watch: { title: '관심 종목 담기', description: '마음에 드는 종목 하나를 관심 목록에 담아 보세요. 담으면 보상이 자동으로 들어와요.', action: '종목 고르기', href: '/market' },
  ranking: { title: '투자 랭킹 확인', description: '트레이더 랭킹과 부자 랭킹에서 내 위치를 확인해 보세요.', action: '랭킹 보기', href: '/ranking' },
  portfolio: { title: '내 계좌 확인', description: '보유 종목과 평가 손익을 한눈에 확인해 보세요.', action: '내 계좌 보기', href: '/mypage' },
  home: { title: '오늘의 시세 요약', description: '홈에서 많이 움직인 종목과 오늘의 소식을 확인해 보세요.', action: '홈 보기', href: '/' },
  settings: { title: '내 투자 환경 확인', description: '프로필과 계정 설정을 확인해 보세요.', action: '설정 보기', href: '/settings' },
  trade: { title: '주문 화면 살펴보기', description: '호가와 주문창을 보며 매매 방법을 익혀 보세요.', action: '시장 보기', href: '/market' },
  career: { title: '내 커리어 확인', description: '지금 직급과 다음 승진 조건을 확인해 보세요.', action: '커리어 보기', href: '/career' },
  titles: { title: '칭호 둘러보기', description: '받은 칭호와 다음 칭호 조건을 확인해 보세요.', action: '칭호 보기', href: '/titles' },
  'stock-gov': stockMission('승리의 여신: 니케', 'GOV'),
  'stock-uma': stockMission('우마무스메 프리티더비', 'UMA'),
  'stock-ba': stockMission('블루 아카이브', 'BA'),
  'stock-gi': stockMission('원신', 'GI'),
  'stock-sr': stockMission('붕괴: 스타레일', 'SR'),
  'stock-zzz': stockMission('젠레스 존 제로', 'ZZZ'),
  'stock-crk': stockMission('쿠키런: 킹덤', 'CRK'),
  'stock-vr': stockMission('발로란트', 'VR'),
  'stock-ow': stockMission('오버워치', 'OW'),
  'stock-pw': stockMission('팰월드', 'PW'),
};

/** 모르는 미션 ID(서버에 새로 생긴 미션)는 이름만 ID로 보이고 시장으로 보낸다. */
export const missionLabel = id => LABELS[id] ?? { title: id, description: '', action: '시장 보기', href: '/market' };
