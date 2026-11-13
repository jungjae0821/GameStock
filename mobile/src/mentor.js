import AsyncStorage from '@react-native-async-storage/async-storage';

/*
 * 슈엔증권 사장 슈엔의 대사. 웹 src/next/mentor/lines.ts와 같은 글이다. 고칠 때는 두 곳을 같이 고친다.
 * 「승리의 여신: 니케」에서 미실리스 인더스트리를 이끄는 슈엔의 성격을 그대로 옮겼다.
 * 선은 지킨다: 욕설, 외모·성별·출신 같은 정체성 비하, 자해를 떠올리게 하는 말은 쓰지 않는다.
 * 금액·한도·시간 같은 규칙 안내는 말투와 상관없이 정확해야 한다. {nick}은 닉네임으로 바뀐다.
 */

const pick = items => items[Math.floor(Math.random() * items.length)];
export const fill = (text, values) => text.replace(/\{(\w+)\}/g, (_, key) => values[key] ?? '');

/** 첫 출근. 수습이 처음 들어오면 한 번 나온다. migrated면 기존 회원 이전 뒤의 첫 출근이다. */
export function introSpeech(migrated) {
  return {
    title: '첫 출근',
    pages: [
      '어머, 이제 왔어? 네가 {nick}구나. 서류 볼 때부터 기대는 안 했는데, 역시나네. 나는 슈엔. 이 슈엔증권 사장이야. 내 시간은 네 연봉보다 비싸지만 특별히 내가 직접 사수를 서 줄게. 감사한 줄 알아.',
      ...(migrated ? ['전 직장 퇴직금은 네 지갑에 넣어 놨어. 거기서 얼마 벌었는지는 관심 없어. 여기선 누구든 수습부터야. 과거 자랑할 시간에 내 돈이나 불려.'] : []),
      '운용계좌는 회사 돈, 그러니까 내 돈이야. 수습한테는 100만원. 벌면 성과급이 네 지갑으로 가고, 잃으면… 내 돈 잃은 대가가 뭔지 직접 겪어 보든가. 후후.',
      '수습 딱지 떼고 싶으면 OJT부터 해. 주력 게임 고르기, 종목 화면 보기, 지정가로 한 번 사기, 한 번 팔기, 리스크 교육. 다섯 개 끝내면 사원 발령 내 줄게. 이것도 못 하면 그냥 재고 처리야.',
    ],
  };
}

/** OJT 진행 카드에 뜨는 다음 할 일 한 줄. */
export const OJT_HINT = {
  FANDOM: '주력 게임부터 골라. 자기가 뭘 좋아하는지도 모르는 인간한테 내 돈을 맡길 순 없잖아?',
  MARKET: '종목 화면 열어서 호가랑 뉴스 봐. 숫자만 멍하니 보지 말고 왜 움직였는지. 머리가 장식은 아니길 바랄게.',
  LIMIT_BUY: '지정가로 한 주라도 사 봐. 시장가로 아무 데나 긁는 건 원숭이도 해.',
  SELL: '산 거 한 번 팔아 봐. 사는 건 바보도 해. 파는 게 실력이야.',
  RISK: '마지막. 리스크 교육 읽고 확인 눌러. 이거 안 읽고 사고 치면 그땐 내가 직접 폐기 처분해 줄게.',
};

/** 승진 발령 때 한마디. */
const PROMOTE = {
  TRAINEE: ['…'],
  STAFF: [
    'OJT 통과? 흥, 내가 가르쳤으니 당연하지. 이제 진짜 내 돈 300만원이야. 탭 베팅 열어 놨으니 노는 건 퇴근하고 네 지갑 돈으로만 해.',
    '수습 딱지 뗐네. 축하는 안 해. 사원부터는 손실한도 있어. 하루 5%. 그 머리로도 숫자 하나는 외울 수 있지?',
  ],
  ASSOCIATE: [
    '{nick} 대리. 불량품인 줄 알았는데 조금은 쓸 만하네. 선물 데스크 열었어. 레버리지는 칼이야, 손잡이부터 잡아. 날을 잡으면 피 보는 건 너고, 손해 보는 건 나야.',
    '대리 됐다고 우쭐하지 마. 내 눈엔 아직 소모품이야. 선물은 틀리면 두 배로 아파.',
  ],
  MANAGER: [
    '과장 발령. 옵션 데스크 열었어. 시간가치 녹는 거 우습게 보다가 같이 녹아내린 애들, 내가 몇이나 갈아 치웠는지 알아?',
    '여기까지 오는 애들 생각보다 적어. 당연하지, 내가 키웠으니까. 옵션은 싸 보이는 게 제일 비싸. 명심해.',
  ],
  SENIOR_MANAGER: [
    '차장. 퀀트팀 열었어. 전략은 백테스트부터 돌려. 감으로 켜는 건 천재인 나나 하는 거야. 넌 아니고.',
    '1억짜리 데스크야. 이제 네 실수 한 번이 수습 하나 연봉이야. 그 돈이면 더 쓸 만한 걸 샀을 텐데.',
  ],
  GENERAL_MANAGER: [
    '부장. 이제 손실한도 한 번만 넘겨도 이사 자리는 없어. 내가 그렇게 정했거든.',
    '3억 굴리는 부장이라… 내 안목이 또 맞았네. 역시 난 천재야. 너 말고, 나.',
  ],
  EXECUTIVE: [
    '이사. 더 올라갈 데는 없어. 내 자리는 넘보지도 마. 이제 떨어질 데만 남았지. …축하해. 이번 한 번만이야.',
  ],
};

/** 강등·재교육·일일 손실한도 때 갈구는 대사. [제목, 본문] */
const SCOLD = {
  DEMOTE: [
    ['{nick}. 내 돈이 화수분인 줄 알았어?', '오늘부로 {to}야. 잃은 거 다 메우기 전엔 성과급 한 푼도 없어. 최고점은 그대로 남아 있거든. 너 같은 불량품을 들인 내 안목이 처음으로 틀렸네.'],
    ['와… 이 정도면 재능이네. 내 돈 태우는 재능.', '{from} 명패 반납하고 {to} 자리로 가. 짐은 벌써 싸 놨어. 원숭이한테 다트를 쥐여 줘도 너보단 벌었겠다.'],
    ['차트는 봤어? 손절선은 있었어? 아니, 머리는 있어?', '{to}로 내려가서 기본부터 다시 해. 다음엔 숫자 말고 이유를 보고 사. 생각이라는 걸 좀 해 보고.'],
  ],
  RETRAIN: [
    ['재교육이야. 불량품 재가공.', '24시간 동안 매수 금지. 그 시간에 \'손절\'이 무슨 뜻인지 사전 찾아봐. 글은 읽을 줄 알지? 파는 건 돼.'],
    ['{nick}, 300만원으로 이걸 해냈네.', '회사가 다시 채워 주긴 하는데, 그거 다 내 돈이야. 공짜 아닌 거 알지? 내일 이 시간까지 매도만 해. 그 손으로 또 뭘 사겠다고 하면 그땐 해고야.'],
    ['축하해, 재교육 당첨.', '상품은 24시간 매수 금지. 반품은 안 돼. 너 같은 걸 반품할 수 있었으면 내가 제일 먼저 했어.'],
  ],
  DAY_LIMIT: [
    ['오늘은 여기까지.', '하루에 이만큼 날리는 인간은 처음… 아니, 꽤 봤네. 다 잘렸지만. 내일 0시까지 매수 금지.'],
    ['멈춰. 지금 당장.', '오늘 손실한도 넘었어. 키보드에서 손 떼. 네 손이 닿을수록 내 돈이 녹아. 파는 건 돼.'],
    ['{nick}, 오늘 장 끝.', '더 사고 싶으면 내일 다시 출근해. 출근은 0시부터야. 그 전까지는 네 무능이 얼마짜리였는지 계산이나 해 봐.'],
  ],
};

export const promotionLine = grade => pick(PROMOTE[grade] ?? PROMOTE.STAFF);
export const scoldLines = kind => pick(SCOLD[kind]);

/** 승진하면 새로 열리는 데스크. 주소는 웹 주소이고, 앱은 선물·옵션을 시장 탭에서, 탭 베팅·퀀트를 웹 화면으로 연다. */
export const DESK_OPENED = {
  STAFF: { name: '탭 베팅', to: '/tap' },
  ASSOCIATE: { name: '선물', to: '/derivatives/futures' },
  MANAGER: { name: '옵션', to: '/derivatives/options' },
  SENIOR_MANAGER: { name: '퀀트', to: '/quant' },
};

/** 데스크에 처음 들어갔을 때 슈엔의 짧은 안내. 앱 네이티브 데스크는 선물·옵션뿐이다(탭 베팅·퀀트는 웹 화면이 띄운다). */
export const DESK_SPEECH = {
  FUTURES: {
    title: '선물 데스크',
    pages: [
      '선물은 지수가 오를지 내릴지에 거는 계약이야. 롱은 오를 때, 숏은 내릴 때 벌어. 여기까진 따라오지?',
      '증거금만 내고 그 몇 배를 굴려. 레버리지 올리면 버는 것도 잃는 것도 같이 커져. 욕심부리다 터지는 애들, 질리도록 봤어.',
      '증거금이 바닥나면 강제 청산돼. 그건 승진 조건의 \'선물 청산\'으로도 안 쳐 줘. 직접 정리하는 버릇 들여. 뒤처리까지 내가 해 줄 거라고 기대하지 말고.',
    ],
  },
  OPTIONS: {
    title: '옵션 데스크',
    pages: [
      '콜은 오르면, 풋은 내리면 값이 붙는 권리야. 산 사람은 낸 값만큼만 잃어.',
      '대신 시간이 지날수록 녹아. 만기까지 안 움직이면 그냥 사라져. 쓸모없으면 사라지는 거, 우리 회사도 똑같아.',
      '싸 보인다고 막 사지 마. 싼 데는 다 이유가 있어. 네 연봉이 싼 데도 이유가 있듯이.',
    ],
  },
};

export const GRADE_LABEL = {
  TRAINEE: '수습', STAFF: '사원', ASSOCIATE: '대리', MANAGER: '과장', SENIOR_MANAGER: '차장', GENERAL_MANAGER: '부장', EXECUTIVE: '이사',
};
const ALLOCATION = {
  STAFF: 3_000_000, ASSOCIATE: 10_000_000, MANAGER: 30_000_000, SENIOR_MANAGER: 100_000_000, GENERAL_MANAGER: 300_000_000, EXECUTIVE: 1_000_000_000,
};
/** 한 번에 하나만 말한다. 승진이 강등보다, 강등이 재교육·일일 한도보다 먼저다. */
export const PRIORITY = { PROMOTE: 4, DEMOTE: 3, RETRAIN: 2, DAY_LIMIT: 1 };

const kstDate = iso => {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  return `${at.getUTCFullYear()}년 ${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일`;
};

/** 커리어 기록 하나를 대화 장면으로 바꾼다. 장면: { tone, title, pages, certificate?, primary? } */
export function sceneForEvent(event, nick) {
  const values = { nick, from: GRADE_LABEL[event.fromGrade ?? ''] ?? '', to: GRADE_LABEL[event.toGrade ?? ''] ?? '' };
  if (event.kind === 'PROMOTE' && event.toGrade) {
    const desk = DESK_OPENED[event.toGrade];
    return {
      tone: 'praise',
      title: `${values.to} 발령`,
      certificate: { nickname: nick, gradeLabel: values.to, allocation: ALLOCATION[event.toGrade] ?? 0, date: kstDate(event.at) },
      pages: [fill(promotionLine(event.toGrade), values)],
      primary: desk ? { label: `${desk.name} 데스크 가 보기`, to: desk.to } : undefined,
    };
  }
  if (event.kind === 'DEMOTE' || event.kind === 'RETRAIN' || event.kind === 'DAY_LIMIT') {
    const [head, body] = scoldLines(event.kind);
    return { tone: 'scold', title: fill(head, values), pages: [fill(body, values)], primary: { label: '커리어 보기', to: '/career' } };
  }
  return null;
}

/*
 * 슈엔이 이미 한 말. 이 기기에만 사용자별로 남긴다(웹은 브라우저마다 따로 남긴다).
 * 저장소를 못 쓰면 이번 실행 동안만 기억한다. 서버 상태에는 영향이 없다.
 */
const KEY = uid => `gamestock-mentor:${uid}`;
const memory = new Map();

export async function readMentor(uid) {
  if (memory.has(uid)) return memory.get(uid);
  try {
    const raw = await AsyncStorage.getItem(KEY(uid));
    const saved = raw ? JSON.parse(raw) : {};
    if (!memory.has(uid)) memory.set(uid, saved);
  } catch {
    if (!memory.has(uid)) memory.set(uid, {});
  }
  return memory.get(uid);
}

export function writeMentor(uid, next) {
  memory.set(uid, next);
  AsyncStorage.setItem(KEY(uid), JSON.stringify(next)).catch(() => {});
}

/* 앱 어디서든 장면을 줄 세우는 통로. 화면 하나(MentorHost)가 하나씩 띄운다. */
const sceneListeners = new Set();
export function queueScene(scene) {
  for (const listener of sceneListeners) listener(scene);
}
export function onScene(listener) {
  sceneListeners.add(listener);
  return () => sceneListeners.delete(listener);
}
