import { useAiNicknames } from "../../lib/engagement";

/** 운영 봇(AI 트레이더) 이름 옆에 붙는 표시. 사람이 아닌 참여자를 사람처럼 보이지 않게 한다. */
export function AiBadge({ nickname }: { nickname: string | null | undefined }) {
  const names = useAiNicknames();
  if (!nickname || !names.has(nickname)) return null;
  return <span className="nx-ai-badge" title="슈엔증권이 운영하는 AI 트레이더예요. 사람과 같은 규칙으로 주문해요.">AI</span>;
}
