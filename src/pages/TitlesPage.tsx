/** 랭킹에서 닉네임과 함께 보일 칭호를 고르는 화면. 칭호 목록은 아직 준비 중이다. */
export function TitlesPage() {
  return (
    <div className="page-stack titles-page">
      <h1 className="page-title">칭호 설정</h1>
      <p className="charge-intro">고른 칭호는 랭킹 페이지에서 닉네임과 함께 표시됩니다.</p>
      <p className="titles-empty" role="status">
        아직 받을 수 있는 칭호가 없어요. 곧 추가될 예정입니다.
      </p>
    </div>
  );
}
