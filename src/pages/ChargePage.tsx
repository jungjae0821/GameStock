import { MissionBoard } from "../components/MissionBoard";

/** 모의 자금은 결제가 아니라 매일 바뀌는 투자 미션 보상으로만 충전된다. */
export function ChargePage() {
  return (
    <div className="page-stack charge-page">
      <h1 className="page-title">충전하기</h1>
      <p className="charge-intro">
        오늘의 투자 미션을 완료하면 모의 자금이 충전됩니다. 미션은 한국시간 매일 자정에 새로 열립니다.
        실제 돈은 오가지 않습니다.
      </p>
      <MissionBoard />
    </div>
  );
}
