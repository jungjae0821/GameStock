import { Link } from "../components/Link";
import { PRIVACY_PATH, TERMS_PATH } from "../router";

/* 약관 화면(pages/NxLegalPage)은 열 때 받아 오고, 모든 화면 아래에 붙는 이 짧은 고지만 처음부터 싣는다. */
export const EFFECTIVE_DATE = "2026년 11월 19일";
export const OPERATOR = "Park JungJae";
export const CONTACT = "wizjungjae2@gmail.com";

export function LegalFooter() {
  return (
    <footer className="nx-legal-footer">
      <p>슈엔증권은 모의 투자 서비스예요. 화면의 모든 금액은 가상 자금이고, 각 게임사와 제휴 관계가 없어요.</p>
      <nav aria-label="약관과 정책">
        <Link to={TERMS_PATH}>이용약관</Link>
        <Link to={PRIVACY_PATH}><strong>개인정보처리방침</strong></Link>
        <a href={`mailto:${CONTACT}`}>문의</a>
      </nav>
    </footer>
  );
}
