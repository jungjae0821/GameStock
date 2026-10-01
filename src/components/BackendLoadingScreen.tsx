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
    line: "웨이~ 서버가 열릴 때까지 텐션 올려서 가보자고☆",
  },
  {
    name: "메지로 파머",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/mejiro-palmer-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/mejiro-palmer-racing.png",
    ],
    line: "서버 대기도 훈련의 하나! ...라고 하지만 너무 긴거 아니야?",
  },
  {
    name: "토센 조던",
    particle: "이",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/tosen-jordan-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/tosen-jordan-racing.png",
    ],
    line: "진심 서버로딩 겁나 오래걸림... 이거 진짜로 기다려야 하는거 맞지?",
  },
  {
    name: "골드 시티",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/gold-city-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/gold-city-racing.png",
    ],
    line: "이거...도대체 언제 열리는거야 트레이너?",
  },
  {
    name: "에스포와르 시티",
    particle: "가",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/espoir-city-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/espoir-city-racing.png",
    ],
    line: "야...트레이너...나 참을성 별로없는거 알지?",
  },
  {
    name: "포에버 영",
    particle: "이",
    images: [
      "https://media.gametora.com/umamusume/characters/official-art/forever-young-uniform.png",
      "https://media.gametora.com/umamusume/characters/official-art/forever-young-racing.png",
    ],
    line: "서버가 열릴 때까지 기다리는게 이렇게 힘들 줄이야...",
  },
  {
    name: "이치카",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10077.webp",
      "https://schaledb.com/images/student/lobby/10077.webp",
    ],
    line: "서버 말입니까? 좀만 기다려주시면 되는검다.",
  },
  {
    name: "시로코",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10010.webp",
      "https://schaledb.com/images/student/lobby/10010.webp",
    ],
    line: "응. 서버가 좀있으면 열릴거야. 조금만 기다려줘.",
  },
  {
    name: "시로코:테러",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10100.webp",
      "https://schaledb.com/images/student/lobby/10100.webp",
    ],
    line: "응. 이제 좀있으면 서버가 열릴거야.",
  },
  {
    name: "미야코",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10038.webp",
      "https://schaledb.com/images/student/lobby/10038.webp",
    ],
    line: "RABBIT1. 서버가 열릴때까지 규율대로 차분히 기다리겠습니다.",
  },
  {
    name: "카즈사",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10049.webp",
      "https://schaledb.com/images/student/lobby/10049.webp",
    ],
    line: "선생님, 서버 열릴때까지 나 마카롱 좀 먹는다?",
  },
  {
    name: "히카리",
    particle: "가",
    images: [
      "https://schaledb.com/images/student/portrait/10117.webp",
      "https://schaledb.com/images/student/lobby/10117.webp",
    ],
    line: "히카리-서버-기다리기-힘들어-",
  },
  {
    name: "슈엔",
    particle: "이",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/c/c3/Syuen_FB.png/revision/latest?cb=20260907234307",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/3/3a/Syuen_MI.png/revision/latest?cb=20260907234840",
    ],
    line: "내가 누군줄 알고 이렇게 서버를 안여는거야?",
  },
  {
    name: "라피",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/3/37/Rapi_Red_Hood_FB.png/revision/latest?cb=20241226095800",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/c/c5/Rapi_Red_Hood_MI.png/revision/latest?cb=20241226095930",
    ],
    line: "지휘관, 조금만 기다려주시면 서버가 열릴겁니다. 그전까지 저와...아무것도 아닙니다.",
  },
  {
    name: "아니스",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/d/d9/Anis_Star_%28NPC%29_FB.png/revision/latest?cb=20251107051523",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/f/f8/Anis_Star_%28NPC%29_FB_2.png/revision/latest?cb=20251113115315",
    ],
    line: "지휘관님...그래도 나 아이돌인데...이런거 꼭 기다려야 돼?",
  },
  {
    name: "드레이크",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/1/17/Drake_Great_Villain_FB.png/revision/latest?cb=20260908021534",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/b/bb/Drake_Great_Villain_MI.png/revision/latest?cb=20260908021520",
    ],
    line: "네놈! 서버가 열리는걸 가만히 기다려라! ...라고 말하고 싶지만, 나도 기다리기 싫다.",
  },
  {
    name: "맥스웰",
    particle: "이",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/9/98/Maxwell_Ordinary_Mechanic_%28Chief_Researcher%29_FB.png/revision/latest?cb=20260724175927",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/6/6b/Maxwell_Ordinary_Mechanic_%28Chief_Researcher%29_MI.png/revision/latest?cb=20260724180433",
    ],
    line: "연결 완료까지 남은 시간이...어디보자...계산상 아주 짧아졌어.",
  },
  {
    name: "라플라스",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/6/62/Laplace_Ultimate_Hero_FB.png/revision/latest?cb=20250424134845",
      "https://static.wikia.nocookie.net/nikke-goddess-of-victory-international/images/2/25/Laplace_Ultimate_Hero_MI.png/revision/latest?cb=20260724180321",
    ],
    line: "히어로 등장! 서버는 아직인가, 버드보이?",
  },
  {
    name: "미야비",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/d/da/Agent_Hoshimi_Miyabi_Portrait.png/revision/latest?cb=20250329051641",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/b/b9/Agent_Hoshimi_Miyabi_In-Game.png/revision/latest?cb=20250427182359",
    ],
    local: "/characters/miyabi.svg",
    line: "로프꾼님, 서버는 아직인가요?",
  },
  {
    name: "엘렌 조",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/e/e3/Agent_Ellen_Joe_Portrait.png/revision/latest?cb=20241007222138",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/f/f8/Agent_Ellen_Joe_In-Game.png/revision/latest?cb=20250427190022",
    ],
    line: "로딩이 길다는 건 그만큼 쉴 수 있다는 뜻이죠... 로딩 끝나면 깨워주세요...",
  },
  {
    name: "의현",
    particle: "이",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/8/89/Agent_Yixuan_Portrait.png/revision/latest?cb=20250606025206",
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/5/5b/Agent_Yixuan_In-Game.png/revision/latest?cb=20250607125126",
    ],
    line: "좀만 기다려봐 로프꾼. 금방 될것같아.",
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
    line: "힝...분명 서버가 연결돼야 하는데...왜 안되는거야...",
  },
  {
    name: "시시아",
    particle: "가",
    images: [
      "https://static.wikia.nocookie.net/zenless-zone-zero/images/8/83/Agent_Cissia_Portrait.png/revision/latest?cb=20260306125558",
    ],
    line: "로프꾼! 서버가 좀있으면 열린대!",
  },
] as const;

function shuffledIndices(length: number): number[] {
  const indices = Array.from({ length }, (_, index) => index);
  for (let index = indices.length - 1; index > 0; index -= 1) {
    const swapIndex = Math.floor(Math.random() * (index + 1));
    [indices[index], indices[swapIndex]] = [indices[swapIndex], indices[index]];
  }
  return indices;
}

export function BackendLoadingScreen() {
  // 화면이 열릴 때마다 전체 캐릭터 순서를 새로 섞고, 섞인 순서대로 한 번씩 보여준다.
  const [characterOrder] = useState(() =>
    shuffledIndices(LOADING_CHARACTERS.length),
  );
  const [orderIndex, setOrderIndex] = useState(0);
  /* 공식 URL 배열을 순서대로 시도하고, 모두 실패하면 CSS 실루엣으로 마무리한다. */
  const [artStep, setArtStep] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(
      () => setOrderIndex((current) => (current + 1) % characterOrder.length),
      4000,
    );
    return () => window.clearInterval(timer);
  }, [characterOrder.length]);

  const index = characterOrder[orderIndex] ?? 0;

  // 캐릭터가 바뀌면 첫 공식 URL부터 다시 시도한다.
  useEffect(() => {
    setArtStep(0);
  }, [index]);

  const { name, particle, line } = LOADING_CHARACTERS[index];
  const character = LOADING_CHARACTERS[index];
  const sources: readonly string[] =
    "local" in character
      ? [...character.images, character.local]
      : character.images;

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
        <p className="backend-loading-copy">
          <strong>{name}</strong>
          {particle} 서버를 기다리고 있어요!
        </p>
        <p className="backend-loading-line">{line}</p>
        <p className="backend-loading-status">백엔드 연결 중…</p>
        <span className="backend-loading-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </div>
    </main>
  );
}
