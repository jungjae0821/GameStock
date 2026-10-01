import { useEffect, useState } from "react";

/*
 * 백엔드 대기 화면(README "로딩 화면 캐릭터 목록" 전체 24인).
 * 각 캐릭터는 공식 전신 일러스트 1~2개(외부 CDN)를 순서대로 시도하고,
 * 모두 실패하면 public/characters 의 로컬 벡터(local), 그것도 실패하면
 * CSS 실루엣으로 이어져 이미지 영역 높이는 항상 고정된다.
 */
const LOADING_CHARACTERS = [
  {
    name: "다이타쿠 헬리오스",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/daitaku-helios-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/daitaku-helios-racing.png",
    ],
    local: "/characters/daitaku-helios.svg",
    line: "오늘의 레이스처럼, 서버도 금방 결승선을 통과할 거예요!",
  },
  {
    name: "메지로 파머",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/mejiro-palmer-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/mejiro-palmer-racing.png",
    ],
    line: "서버 대기도 훈련의 하나! 나랑 기다림 근육 단련할래?",
  },
  {
    name: "토센 조던",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/tosen-jordan-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/tosen-jordan-racing.png",
    ],
    line: "긴장하면 지는 거야! 서버는 어차피 곧 열릴 거니까!",
  },
  {
    name: "골드 시티",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/gold-city-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/gold-city-racing.png",
    ],
    line: "무대의 막이 오를 때까지, 우아하게 기다려 주지.",
  },
  {
    name: "에스포와르 시티",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/espoir-city-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/espoir-city-racing.png",
    ],
    line: "espoir는 희망! 서버 개막도 그리 멀지 않아.",
  },
  {
    name: "포에버 영",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/forever-young-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/forever-young-racing.png",
    ],
    line: "몇 번을 기다려도 젊은 마음은 변하지 않지!",
  },
  {
    name: "이치카",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10077.webp",
      "https://schaledb.com/images/student/lobby/10077.webp",
    ],
    line: "게임도 로딩도 준비된 자만이 즐길 수 있는 법.",
  },
  {
    name: "시로코",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10010.webp",
      "https://schaledb.com/images/student/lobby/10010.webp",
    ],
    local: "/characters/shiroko.svg",
    line: "…서버 점검, 조준 완료. 조금만 기다려줘.",
  },
  {
    name: "시로코:테러",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10100.webp",
      "https://schaledb.com/images/student/lobby/10100.webp",
    ],
    line: "…이 도시의 연결도, 지켜내면 돼.",
  },
  {
    name: "미야코",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10038.webp",
      "https://schaledb.com/images/student/lobby/10038.webp",
    ],
    line: "작전명 '서버 대기'. 규율대로 차분히 기다리겠습니다.",
  },
  {
    name: "카즈사",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10049.webp",
      "https://schaledb.com/images/student/lobby/10049.webp",
    ],
    line: "기다림 정도는 물려줬잖아. …조금 빨리 열어줘.",
  },
  {
    name: "히카리",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10117.webp",
      "https://schaledb.com/images/student/lobby/10117.webp",
    ],
    line: "로딩 화면까지 이벤트성이라니! 완전 럭키하지 않아?",
  },
  {
    name: "슈엔",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/c/c3/Syuen_FB.png/revision/latest?cb=20260907234307",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/3/3a/Syuen_MI.png/revision/latest?cb=20260907234840",
    ],
    line: "내가 직접 기다려주고 있다는 걸, 영광으로 알아두면 좋겠어.",
  },
  {
    name: "라피:레드후드",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/d/db/Rapi_%28Red_Hood%29_FB.png/revision/latest?cb=20241208132313",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/c/c5/Rapi_Red_Hood_MI.png/revision/latest?cb=20241226095930",
    ],
    local: "/characters/rapi-redhood.svg",
    line: "늘 그렇듯, 정확하게 준비하겠습니다.",
  },
  {
    name: "아니스:스타",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/d/d9/Anis_Star_%28NPC%29_FB.png/revision/latest?cb=20251107051523",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/f/f8/Anis_Star_%28NPC%29_FB_2.png/revision/latest?cb=20251113115315",
    ],
    line: "별이 뜰 때쯤이면 연결되겠지. 그때까지 음료나 한 캔 어때?",
  },
  {
    name: "드레이크 이격",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/9/97/Drake_%28Maid_For_Villain%29_FB.png/revision/latest?cb=20240212154814",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/9/90/Drake_%28Maid_For_Villain%29_MI.png/revision/latest?cb=20240212154643",
    ],
    line: "악역의 무대에는 반전이 필요하지! 다음 장면을 기대해!",
  },
  {
    name: "맥스웰 이격",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/9/98/Maxwell_Ordinary_Mechanic_%28Chief_Researcher%29_FB.png/revision/latest?cb=20260724175927",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/6/6b/Maxwell_Ordinary_Mechanic_%28Chief_Researcher%29_MI.png/revision/latest?cb=20260724180433",
    ],
    line: "연결 완료까지 남은 시간… 계산상 아주 짧아졌어.",
  },
  {
    name: "라플라스 이격",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/6/62/Laplace_Ultimate_Hero_FB.png/revision/latest?cb=20250424134845",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/2/25/Laplace_Ultimate_Hero_MI.png/revision/latest?cb=20260724180321",
    ],
    line: "히어로의 등장을 기다리듯! 서버의 문이 열리는 순간을!",
  },
  {
    name: "미야비",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/d/da/Agent_Hoshimi_Miyabi_Portrait.png/revision/latest?cb=20250329051641",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/b/b9/Agent_Hoshimi_Miyabi_In-Game.png/revision/latest?cb=20250427182359",
    ],
    local: "/characters/miyabi.svg",
    line: "서버도 좋은 게임이군요. 빨리 접속하고 싶어요!",
  },
  {
    name: "엘렌 조",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/e/e3/Agent_Ellen_Joe_Portrait.png/revision/latest?cb=20241007222138",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/f/f8/Agent_Ellen_Joe_In-Game.png/revision/latest?cb=20250427190022",
    ],
    line: "…로딩이 길다는 건 그만큼 쉴 수 있다는 뜻이지. 히이…",
  },
  {
    name: "의현",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/8/89/Agent_Yixuan_Portrait.png/revision/latest?cb=20250606025206",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/5/5b/Agent_Yixuan_In-Game.png/revision/latest?cb=20250607125126",
    ],
    line: "기다림에도 경지가 있다. 지금은 그 수련 시간이다.",
  },
  {
    name: "엔비 0호",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/9/90/Agent_Soldier_0_-_Anby_Portrait.png/revision/latest?cb=20250312030249",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/c/cb/Agent_Soldier_0_-_Anby_In-Game.png/revision/latest?cb=20251015155413",
    ],
    line: "접속 개시까지 카운트다운. 완벽한 타이밍에 진입하겠어.",
  },
  {
    name: "벨",
    particle: "이",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/6/6e/Agent_Belle_Portrait.png/revision/latest?cb=20240707002545",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/7/75/Agent_Belle_Brilliance_of_Stars_Portrait.png/revision/latest?cb=20260617080726",
    ],
    line: "좋은 영화도 준비 시간이 필요하니까, 천천히 기다려요.",
  },
  {
    name: "시시아",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/8/83/Agent_Cissia_Portrait.png/revision/latest?cb=20260306125558",
    ],
    line: "다음 장면을 재생할 수 있도록 연결을 준비하고 있어요!",
  },
] as const;

export function BackendLoadingScreen() {
  const [index, setIndex] = useState(() => Math.floor(Date.now() / 4000) % LOADING_CHARACTERS.length);
  /* 공식 URL 배열을 순서대로 시도하고, 모두 실패하면 CSS 실루엣으로 마무리한다. */
  const [artStep, setArtStep] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => setIndex((current) => (current + 1) % LOADING_CHARACTERS.length), 4000);
    return () => window.clearInterval(timer);
  }, []);

  // 캐릭터가 바뀌면 첫 공식 URL부터 다시 시도한다.
  useEffect(() => {
    setArtStep(0);
  }, [index]);

  const { name, particle, line } = LOADING_CHARACTERS[index];
  const character = LOADING_CHARACTERS[index];
  const sources: readonly string[] =
    "local" in character ? [...character.images, character.local] : character.images;

  return (
    <main className="backend-loading" aria-live="polite">
      <div className="backend-loading-inner">
        <div className="backend-loading-stage" aria-hidden="true">
          {artStep < sources.length ? (
            <img
              className="backend-loading-art"
              key={`${name}-${artStep}`}
              src={sources[artStep]}
              alt=""
              onError={() => setArtStep((step) => step + 1)}
            />
          ) : (
            <div className="backend-loading-fallback" />
          )}
        </div>
        <p className="backend-loading-copy"><strong>{name}</strong>{particle} 서버를 기다리고 있어요!</p>
        <p className="backend-loading-line">{line}</p>
        <p className="backend-loading-status">백엔드 연결 중…</p>
        <span className="backend-loading-dots" aria-hidden="true"><i /><i /><i /></span>
      </div>
    </main>
  );
}
