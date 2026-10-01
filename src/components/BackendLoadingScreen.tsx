const LOADING_CHARACTERS = [
  ["우마무스메", "다이타쿠 헬리오스"],
  ["블루 아카이브", "시로코"],
  ["니케", "라피: 레드후드"],
  ["젠레스 존 제로", "미야비"],
] as const;

export function BackendLoadingScreen() {
  const [game, character] = LOADING_CHARACTERS[Math.floor(Date.now() / 4000) % LOADING_CHARACTERS.length];

  return (
    <main className="backend-loading" aria-live="polite">
      <div className="backend-loading-inner">
        <p className="backend-loading-game">{game}</p>
        <div className="backend-loading-character" aria-hidden="true">{character.slice(0, 1)}</div>
        <p className="backend-loading-copy"><strong>{character}</strong>가 준비하고 있어요~</p>
        <p className="backend-loading-status">백엔드 연결 중…</p>
        <span className="backend-loading-dots" aria-hidden="true"><i /><i /><i /></span>
      </div>
    </main>
  );
}
