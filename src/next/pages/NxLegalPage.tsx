import { Link } from "../../components/Link";
import { PRIVACY_PATH, TERMS_PATH } from "../../router";
import { CONTACT, EFFECTIVE_DATE, OPERATOR } from "../LegalFooter";

/*
 * 이용약관과 개인정보처리방침. 원본 설명과 근거 위치(코드의 테이블·클래스)는 docs/legal/*.md에 있고,
 * 이 화면은 그 문서에서 이용자에게 보여 줄 내용만 옮겼다. 저장 항목이나 규칙이 바뀌면 둘 다 고친다.
 */

export type LegalDoc = "terms" | "privacy";


export const LEGAL_TITLES: Record<LegalDoc, string> = { terms: "이용약관", privacy: "개인정보처리방침" };

export function NxLegalPage({ doc }: { doc: LegalDoc }) {
  return (
    <article className="nx-legal">
      <header className="nx-legal-head">
        <nav className="nx-legal-switch" aria-label="약관 문서">
          <Link to={TERMS_PATH} aria-current={doc === "terms" ? "page" : undefined}>이용약관</Link>
          <Link to={PRIVACY_PATH} aria-current={doc === "privacy" ? "page" : undefined}>개인정보처리방침</Link>
        </nav>
        <h1 className="nx-page-title">{LEGAL_TITLES[doc]}</h1>
        <p className="nx-screen-sub">시행일 {EFFECTIVE_DATE}</p>
      </header>
      {doc === "terms" ? <Terms /> : <Privacy />}
    </article>
  );
}

function Terms() {
  return (
    <>
      <section>
        <h2>제1조 (목적)</h2>
        <p>이 약관은 슈엔증권(이하 “서비스”)의 이용 조건과 절차, 운영자와 이용자의 권리·의무를 정합니다.</p>
      </section>
      <section>
        <h2>제2조 (서비스의 성격)</h2>
        <ol>
          <li>서비스는 게임을 가상의 종목으로 삼아 뉴스·호가·체결·가격 형성을 체험하는 <strong>모의 투자 교육용 시뮬레이션</strong>입니다.</li>
          <li>서비스 안의 자금, 자산, 수익, 보상, 칭호는 모두 가상이며 <strong>현금이나 실제 금융 상품, 게임 재화로 바꿀 수 없습니다.</strong></li>
          <li>서비스는 투자 자문이나 투자 권유가 아니며, 서비스의 가격·뉴스 분석은 실제 시장이나 실제 기업 가치를 나타내지 않습니다.</li>
          <li>서비스에 나오는 게임 이름·로고·이미지는 각 권리자의 소유이며, 서비스는 각 게임사와 제휴 관계가 없습니다.</li>
        </ol>
      </section>
      <section>
        <h2>제3조 (회원 가입과 계정)</h2>
        <ol>
          <li>이용자는 이메일 또는 Google 계정으로 가입할 수 있습니다.</li>
          <li>서비스는 <strong>만 14세 이상</strong>만 가입할 수 있습니다. 만 14세 미만으로 확인된 계정은 이용을 제한하고 삭제할 수 있습니다.</li>
          <li>이용자는 자신의 계정을 직접 관리하며, 타인에게 양도하거나 빌려줄 수 없습니다.</li>
          <li>이용자는 언제든지 설정 화면의 “회원 탈퇴”로 탈퇴할 수 있으며, 탈퇴하면 계정과 모의 거래 기록이 즉시 삭제되고 되돌릴 수 없습니다.</li>
        </ol>
      </section>
      <section>
        <h2>제4조 (모의 거래 규칙)</h2>
        <ol>
          <li>신규 계정은 모의 자금 100만 원으로 시작합니다.</li>
          <li>체결 수수료, 가격 제한폭, 변동성 완화 장치, 서킷브레이커, 직급별 운용한도 등 거래 규칙은 서비스 안내 화면을 따르며, 운영자는 시뮬레이션의 품질을 위해 이를 바꿀 수 있습니다.</li>
          <li>운영자는 시스템 오류로 잘못 처리된 모의 거래나 자산을 바로잡을 수 있습니다.</li>
          <li>서비스에는 시장 유동성을 위해 자동으로 주문하는 봇이 참여합니다. 봇 계정은 랭킹에서 제외됩니다.</li>
        </ol>
      </section>
      <section>
        <h2>제5조 (이용자의 의무)</h2>
        <p>이용자는 다음 행위를 해서는 안 됩니다.</p>
        <ol>
          <li>자동화 도구나 스크립트로 서비스에 과도한 요청을 보내는 행위(공식 OpenAPI를 정해진 한도 안에서 쓰는 것은 제외)</li>
          <li>시스템 오류를 이용해 모의 자산을 부당하게 늘리는 행위</li>
          <li>다른 이용자를 비방하거나 불쾌감을 주는 글, 광고, 불법 정보를 올리는 행위</li>
          <li>다른 사람의 계정을 쓰거나 운영자를 사칭하는 행위</li>
        </ol>
      </section>
      <section>
        <h2>제6조 (이용 제한)</h2>
        <p>운영자는 제5조를 어긴 이용자에게 경고, 거래 정지, 작성물 삭제, 계정 이용 제한을 할 수 있으며, 그 기간과 사유를 서비스 안에서 알립니다.</p>
      </section>
      <section>
        <h2>제7조 (게시물)</h2>
        <ol>
          <li>이용자가 올린 글의 권리는 작성자에게 있습니다.</li>
          <li>운영자는 제5조에 해당하는 게시물을 알리지 않고 삭제할 수 있습니다.</li>
          <li>종목 실시간 대화는 작성 후 7일이 지나면 자동으로 지워집니다.</li>
        </ol>
      </section>
      <section>
        <h2>제8조 (서비스의 변경·중단)</h2>
        <ol>
          <li>운영자는 시스템 점검, 장애, 운영상 필요에 따라 서비스의 전부 또는 일부를 바꾸거나 멈출 수 있습니다.</li>
          <li>서비스는 운영 기간을 따로 정하지 않습니다. 서비스를 종료할 때는 종료일 30일 전까지 서비스 안에 알리고, 종료하면 회원 정보와 이용 기록을 모두 삭제합니다.</li>
        </ol>
      </section>
      <section>
        <h2>제9조 (책임의 한계)</h2>
        <p>서비스는 무료로 제공되는 모의 투자 서비스이며, 운영자는 서비스 안의 정보를 근거로 한 실제 투자 결과에 책임지지 않습니다.</p>
      </section>
      <section>
        <h2>제10조 (개인정보 보호)</h2>
        <p>개인정보 처리는 별도의 <Link to={PRIVACY_PATH}>개인정보처리방침</Link>을 따릅니다.</p>
      </section>
      <section>
        <h2>제11조 (문의)</h2>
        <dl className="nx-legal-facts">
          <dt>운영자</dt><dd>{OPERATOR}</dd>
          <dt>연락처</dt><dd><a href={`mailto:${CONTACT}`}>{CONTACT}</a></dd>
        </dl>
      </section>
      <section>
        <h2>부칙</h2>
        <p>이 약관은 {EFFECTIVE_DATE}부터 적용합니다.</p>
      </section>
    </>
  );
}

function Privacy() {
  return (
    <>
      <p className="nx-legal-lede">
        슈엔증권(이하 “서비스”)은 이용자의 개인정보를 「개인정보 보호법」에 따라 처리합니다. 서비스는 게임을 가상 종목으로 다루는 모의 투자 교육용 서비스이며, 실제 돈·주식·결제 정보를 다루지 않습니다.
      </p>
      <section>
        <h2>1. 처리하는 개인정보 항목과 목적</h2>
        <div className="nx-legal-table">
          <table>
            <thead><tr><th scope="col">구분</th><th scope="col">항목</th><th scope="col">목적</th></tr></thead>
            <tbody>
              <tr><th scope="row">회원 가입·로그인</th><td>이메일, 로그인 식별자(Google 계정 연동 시 Google 식별자). 이메일 가입 비밀번호는 Firebase Authentication이 보관하며 서비스 DB에는 저장하지 않습니다.</td><td>본인 확인, 계정 유지</td></tr>
              <tr><th scope="row">프로필</th><td>닉네임, 직접 올린 프로필 사진 또는 Google 프로필 사진 주소</td><td>랭킹·토론 등 서비스 내 표시</td></tr>
              <tr><th scope="row">서비스 이용 기록</th><td>모의 주문·체결·정산 내역, 모의 자산, 관심 종목, 가격 알림, 출석·미션·칭호, 대출, 퀀트 전략, 탭 베팅 기록</td><td>모의 거래 제공, 랭킹 산정</td></tr>
              <tr><th scope="row">이용자 작성물</th><td>종목 토론 글·댓글, 실시간 대화, 전광판 문구, 신고 내용</td><td>커뮤니티 기능 제공, 신고 처리</td></tr>
              <tr><th scope="row">외부 API 이용 시</th><td>API 키(복원할 수 없게 변환해 저장), 주문 요청 기록</td><td>OpenAPI 제공, 중복 요청 방지</td></tr>
              <tr><th scope="row">운영·보안</th><td>접속 IP(호출 한도 계산에만 쓰고 저장하지 않음), 이용 제한 기록과 사유</td><td>부정 이용 방지</td></tr>
              <tr><th scope="row">방문 통계</th><td>방문한 화면, 머문 시간, 기기·브라우저 종류, 대략적 지역(국가·도시), 쿠키 식별자. Google Analytics가 자동으로 수집하며 회원 계정과 연결하지 않습니다.</td><td>서비스 이용 현황 분석과 개선</td></tr>
            </tbody>
          </table>
        </div>
        <p>서비스는 주민등록번호, 실명, 연락처, 결제 정보를 수집하지 않습니다.</p>
      </section>
      <section>
        <h2>2. 보유 기간</h2>
        <ul>
          <li>회원 정보와 이용 기록: 회원 탈퇴 시까지. 탈퇴하면 즉시 삭제합니다. 탈퇴 사실은 운영 감사 기록에 닉네임과 처리 시각만 남기며 이메일은 남기지 않습니다.</li>
          <li>종목 실시간 대화: 작성 후 7일이 지나면 자동 삭제합니다.</li>
          <li>봇 거래·가격 세부 기록 등 운영 데이터: 데이터 정리 정책에 따라 주기적으로 삭제합니다.</li>
          <li>백업: 매일 만들고 최근 7일분만 보관합니다.</li>
        </ul>
      </section>
      <section>
        <h2>3. 개인정보의 제3자 제공</h2>
        <p>서비스는 이용자의 개인정보를 제3자에게 제공하지 않습니다. 다만 법령에 따라 요구되는 경우는 예외로 합니다.</p>
      </section>
      <section>
        <h2>4. 처리 위탁과 국외 이전</h2>
        <p>서비스는 아래 업체에 업무를 맡기며, 이 과정에서 개인정보가 국외에 저장됩니다. 서비스를 이용하는 동안 네트워크를 통해 수시로 전송되며, 보유 기간은 위 2항과 같습니다. 국외 이전을 원하지 않으면 회원 탈퇴로 거부할 수 있으나, 이 경우 서비스를 이용할 수 없습니다.</p>
        <div className="nx-legal-table">
          <table>
            <thead><tr><th scope="col">수탁자</th><th scope="col">위탁 업무</th><th scope="col">이전 국가</th><th scope="col">이전 항목</th></tr></thead>
            <tbody>
              <tr><th scope="row">Google LLC (Firebase Authentication, Firebase Hosting)</th><td>로그인 처리, 웹 화면 제공</td><td>미국 등 Google 데이터센터 소재국</td><td>이메일, 로그인 식별자, 비밀번호(이메일 가입 시)</td></tr>
              <tr><th scope="row">Oracle (Oracle Cloud Infrastructure)</th><td>서버·데이터베이스 운영</td><td>일본(오사카 리전)</td><td>1항의 모든 항목</td></tr>
              <tr><th scope="row">Google LLC (Google Analytics)</th><td>방문 통계 분석</td><td>미국 등 Google 데이터센터 소재국</td><td>1항의 방문 통계 항목</td></tr>
            </tbody>
          </table>
        </div>
      </section>
      <section>
        <h2>5. 이용자의 권리와 행사 방법</h2>
        <p>이용자는 언제든지 자신의 개인정보를 조회·수정·삭제하거나 처리 정지를 요구할 수 있습니다.</p>
        <ul>
          <li>닉네임·프로필 사진: 설정 화면에서 직접 고칠 수 있습니다.</li>
          <li>모의 자산 초기화: 설정 화면의 계좌 초기화로 할 수 있습니다.</li>
          <li>회원 탈퇴·개인정보 삭제: 설정 화면의 “회원 탈퇴”로 직접 할 수 있습니다. 서비스 DB의 회원 정보·이용 기록·작성물·프로필 사진과 로그인 계정이 함께 삭제됩니다. 정산 대기 중인 모의 체결이 있으면 정산이 끝난 뒤(다음 날) 탈퇴할 수 있습니다.</li>
          <li>그 밖의 요청은 아래 개인정보 보호책임자에게 이메일로 보내 주세요.</li>
        </ul>
      </section>
      <section>
        <h2>6. 파기 절차와 방법</h2>
        <p>보유 기간이 끝났거나 처리 목적을 달성한 개인정보는 지체 없이 데이터베이스에서 삭제합니다. 백업에 남은 정보는 백업 보관 기간(7일)이 지나면 함께 사라집니다.</p>
      </section>
      <section>
        <h2>7. 안전성 확보 조치</h2>
        <ul>
          <li>전송 구간 암호화(HTTPS·WSS), 웹 응답 보안 헤더 적용</li>
          <li>비밀번호는 서비스가 직접 저장하지 않고 Firebase Authentication에 맡김</li>
          <li>데이터베이스와 백업 파일 접근 권한 제한</li>
          <li>관리자 작업 감사 기록</li>
        </ul>
      </section>
      <section>
        <h2>8. 쿠키 등 자동 수집 장치</h2>
        <p>서비스는 광고 쿠키를 쓰지 않습니다. 로그인 상태 유지와 화면 설정(밝기, 탭 선택 등)을 위해 브라우저 저장소(localStorage, IndexedDB)를 쓰며, 브라우저 설정에서 지울 수 있습니다.</p>
        <p>방문 통계를 위해 Google Analytics 쿠키(_ga 등)를 씁니다. 이 쿠키는 브라우저를 구분하는 임의의 식별자만 담고 이름·이메일 같은 정보는 담지 않으며, 최대 2년간 브라우저에 남습니다. Google Analytics에 모인 통계는 최대 14개월 뒤 삭제됩니다.</p>
        <p>쿠키 저장을 원하지 않으면 브라우저 설정에서 쿠키를 차단하거나 <a href="https://tools.google.com/dlpage/gaoptout" target="_blank" rel="noopener noreferrer">Google Analytics 차단 부가기능</a>을 설치하면 됩니다. 쿠키를 막아도 서비스는 그대로 쓸 수 있습니다.</p>
      </section>
      <section>
        <h2>9. 만 14세 미만 아동</h2>
        <p>서비스는 만 14세 미만 아동의 가입을 받지 않습니다. 만 14세 미만 아동의 개인정보가 수집된 사실을 알게 되면 지체 없이 삭제합니다.</p>
      </section>
      <section>
        <h2>10. 개인정보 보호책임자</h2>
        <dl className="nx-legal-facts">
          <dt>성명</dt><dd>{OPERATOR}</dd>
          <dt>이메일</dt><dd><a href={`mailto:${CONTACT}`}>{CONTACT}</a></dd>
        </dl>
      </section>
      <section>
        <h2>11. 처리방침의 변경</h2>
        <p>이 처리방침은 {EFFECTIVE_DATE}부터 적용합니다. 내용이 바뀌면 서비스 공지로 알립니다.</p>
      </section>
    </>
  );
}
