# GameStock

실제 게임 뉴스와 이용자 거래가 가상 게임주 가격에 반영되는 참여형 시장 서비스입니다. 가상 거래만 제공하며 실제 금융상품 추천이나 수익을 보장하지 않습니다.

## 실행 방법

사전 준비: Java 17 이상, MySQL 8.0, 루트 `.env`(`DB_PASSWORD` 필수).

`scripts\start.cmd`를 더블클릭하거나 PowerShell에서 다음을 실행합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start.ps1
```

웹 화면은 [http://localhost:8080](http://localhost:8080), 종료는 `scripts\stop.cmd` 또는 `.\scripts\stop.ps1`입니다.

로컬 주소로 접속하면 프론트엔드가 자동으로 로컬 백엔드 `http://localhost:8081`을 사용합니다. Firebase Hosting 주소로 접속할 때만 Railway 백엔드 주소를 사용합니다.

### 프론트엔드 배포 (Firebase Hosting)

Firebase Hosting 프로젝트는 `gamestock-20994`이며, `firebase.json`의 `public` 경로인 `frontend/` 폴더가 웹사이트로 배포됩니다. 프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
firebase login
firebase use gamestock-20994
firebase deploy --only hosting
```

배포가 완료되면 [https://gamestock-20994.web.app](https://gamestock-20994.web.app)에 최신 프론트엔드가 반영됩니다. 프론트엔드 배포는 Railway 백엔드 배포와 별개이며, 백엔드 서버가 실행 중이어야 로그인·뉴스·거래 API가 정상 동작합니다.

현재 저장소에는 Firebase GitHub Actions 워크플로가 없으므로 `git push`만으로는 프론트엔드가 자동 반영되지 않습니다. 자동 배포를 설정한 뒤에는 `main` 브랜치에 push할 때 GitHub Actions가 위 배포를 대신 실행합니다.

### 무료 외부 테스트 재실행

Firebase Hosting에는 프론트엔드를, Cloudflare Quick Tunnel에는 로컬 백엔드를 연결합니다. 데이터는 계속 로컬 MySQL에 저장됩니다.

처음 한 번만 프로젝트 루트에서 초기화합니다.

```powershell
npm install -g firebase-tools
firebase login
firebase init hosting
```

`gamestock-20994`, Public directory `frontend`, Single-page app `Yes`, GitHub 배포 `No`, `index.html` 덮어쓰기 `No`를 선택합니다.

외부 테스트를 반복할 때는 다음 순서입니다.

1. `.\scripts\start.ps1` 실행
2. `cloudflared.exe tunnel --url http://localhost:8081` 실행 후 출력된 주소를 복사하고 터널 창 유지
3. `frontend\index.html`에서 `app.js` 앞에 다음 설정 추가

   ```html
   <script>
     window.GAMESTOCK_API_BASE_URL = "https://<random>.trycloudflare.com";
   </script>
   ```

4. 루트 `.env`에 Firebase Hosting 주소를 허용합니다.

   ```text
   GAMESTOCK_CORS_ORIGIN=https://gamestock-20994.web.app
   ```

5. 백엔드를 재시작해 환경변수를 반영합니다.

   ```powershell
   .\scripts\stop.ps1
   .\scripts\start.ps1
   ```

6. 프론트엔드를 다시 배포합니다.

   ```powershell
   firebase deploy --only hosting
   ```

7. `https://<random>.trycloudflare.com/api/health`가 `{"status":"ok"}`인지 확인하고 `https://gamestock-20994.web.app`에서 로그인·조회·거래·실시간 가격을 테스트합니다.

Quick Tunnel은 재실행할 때마다 주소가 바뀌므로 2~6번을 반복해야 합니다. 테스트 종료 시 터널 창을 닫고 `.\scripts\stop.ps1`을 실행합니다.

`.env` 예시:

```text
DB_USERNAME=jungjae0821
DB_PASSWORD=your-mysql-password
# 관리자 지정은 UID 사용을 권장하며, 이메일은 보조 수단입니다.
GAMESTOCK_ADMIN_GOOGLE_UID=your-firebase-google-uid
# GAMESTOCK_ADMIN_GOOGLE_EMAIL=admin@example.com
GAMESTOCK_SIMULATION_SEED=20260910
```

`.env`와 Firebase 서비스 계정 키는 Git에 포함하지 않습니다. 관리자도 Google 로그인이 필요하며 `ADMIN` 계정은 투자 랭킹에서 제외됩니다.

### 컴퓨터 재부팅 후 MySQL

MySQL 서비스가 없다면 관리자 PowerShell에서 한 번만 등록합니다.

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe" `
	--install MySQL80 `
	--defaults-file="C:\ProgramData\MySQL\MySQL Server 8.0\my.ini"

Set-Service MySQL80 -StartupType Automatic
Start-Service MySQL80
```

`Get-Service MySQL80`에서 `Status=Running`, `StartType=Automatic`이면 재부팅 후에도 자동 실행됩니다.

## 프로젝트 구조

```text
backend/            Spring Boot REST/WebSocket 백엔드
frontend/           HTML·CSS·JavaScript 웹 화면
mobile/             Expo Android/iOS 앱
database/schema.sql MySQL 스키마
scripts/            실행·종료·테스트 스크립트
```

## 개발 현황

- MySQL/JDBC 저장소, Firebase Google 로그인, 사용자 자산·주문·출석 보상
- 지정가·시장가·부분 체결·취소, 봇 호가, 호가창·실시간 가격
- 종목별 가격 그래프 기간 선택(5분·10분·30분·1시간·6시간·12시간·24시간·일주일)
- Expo 앱 로그인·거래·호가 그래프·체결 내역
- 웹·앱 공통 다크모드와 한국어·일본어·영어 UI (첫 실행은 기기 설정, 이후 선택값은 기기에 저장)
- 웹·앱에 동일한 게임별 레터마크 아이콘(UMA·BA·GOV) 적용
- 예정: APK 배포, 출석 보상 고도화

## 외부 데이터 연동

### 게임 뉴스 수집

종목별 RSS를 시작 시와 10분마다 조회해 관련성·중복을 검사한 최신 뉴스 최대 5개를 표시합니다. 게임 문맥과 사건사고 신호를 사용하며 영향도는 -10~+10입니다. 제목의 ‘흥행 돌풍·좋은 반응·성공적 출시·매출 신기록·이벤트 개막·신규 캐릭터 등장·신규 보스 공개’ 등 게임 업계 표현은 제목 가중치를 높여 긍정 신호로 판정하고, 부정 구문을 먼저 제거해 ‘출시 지연’ 같은 문맥 오판을 줄입니다.

RSS를 끄려면 실행 전에 설정합니다.

```powershell
$env:GAMESTOCK_NEWS_ENABLED = "false"
```

피드는 `gamestock.news.feeds`에서 `종목코드|RSS주소` 형식으로 수정합니다. 수집 실패 시에도 시장 API와 거래는 작동합니다.

### 한강 수온

웹·모바일 메뉴는 [한강 수온 사이트](https://xn--939at9l4tgt7l6ps.com/ko)의 `선유` 측정소 수온을 30분마다 조회·캐시합니다. 실패 시 마지막 성공값을 사용하며 `GAMESTOCK_HANGANG_SITE_URL`, `GAMESTOCK_HANGANG_ENABLED`, `GAMESTOCK_HANGANG_REFRESH_MS`로 조정합니다.

## 주식시장 유사성 개선 계획

호가창, 지정가·시장가 주문, 부분 체결, 사용자별 자산 계산을 제공합니다.

### 0순위: 기타옵션

- [x] Google 로그인·닉네임, 로그인 전 메뉴, 마이페이지, 사용자 투자 랭킹
- [x] 신규 계정은 `슈엔단`+무작위 4자리 기본 닉네임으로 생성되며, 마이페이지에서 변경 가능
- [x] 로그인 여부와 무관한 다크모드 및 한국어·일본어·영어 설정

### 1순위: 주문·체결

- [x] 개별 체결, 가격·시간 우선 매칭, 시장가 호가 소진
- [x] 현금·주식 예약 및 체결·취소·만료 반환
- [x] 부분 체결·주문 취소 API·마이페이지

### 2순위: 가격 형성

- [x] 스프레드·유동성·호가 잔량·tick size
- [x] 24시간 거래, 슬리피지, 호가 부족 처리
- [x] 기준가 대비 ±30% 일일 가격 제한, 봇 ±20% 내부 가격대
- [ ] 변동성 완화장치·시장 전체 거래 중단

※ 봇 주문과 봇이 참여한 체결은 일일 ±20% 안에서만 허용하므로 봇만으로 상한가·하한가를 만들 수 없습니다.

### 3순위: 계좌·결제

- [x] 체결 수수료(0.10%), 즉시 현금·주식 반영, 체결 내역
- [x] 평균 매입가·실현 손익·평가 손익 분리
- [ ] 배당·분할·공매도·감사 로그

### 4순위: 시세·뉴스·운영

- [x] 시드·틱 저장, RSS 영향도(-10~+10), 일별 시세
- [x] DB 잠금·중복/속도 제한·트랜잭션 복구
- [x] Firebase 토큰 검증, USER/ADMIN 권한, 랭킹 개인정보 보호

### 5순위: 부가 기능

- [x] 계정별 인생 리셋
- [x] 선유 수온 30분 조회·캐시

## 졸업 프로젝트 보완 계획

현재 기능 구현은 완료되었지만, 졸업작품으로 제출할 때는 다음 내용을 보완해 프로젝트의 목적·시장성·검증 가능성을 강화합니다. 이 프로젝트의 핵심은 주식 연습이 아니라, 실제 게임 뉴스와 참여자 거래가 함께 가상의 게임주 가격을 만드는 과정을 구현하고 관찰하는 것입니다.

### 1. 문제 정의와 사용자 가치

- 프로젝트 정의: 실제 게임 뉴스를 바탕으로 사람과 봇이 거래하고, 뉴스 영향과 투자 행동이 최종 가상 게임주 가격을 결정하는 참여형 시장 시뮬레이션 플랫폼
- 핵심 작동 흐름: `실제 게임 뉴스 수집 → 뉴스 관련성·영향도 분석 → 사람·봇의 매수·매도 → 호가·체결 → 최종 가상 주가와 변동 이유 표시`
- 해결하려는 문제: 게임 뉴스가 투자자 심리와 게임 가치에 어떤 영향을 주는지, 그리고 그 심리가 거래와 가격으로 어떻게 이어지는지 한눈에 확인하기 어려움
- 핵심 사용자: 게임 이용자·관심자, 게임사·퍼블리셔, 게임산업 분석가·투자자
- 제공 가치:
  - 이용자: 뉴스만 보고 판단하지 않고 다른 참여자의 거래와 시장 반응을 함께 확인
  - 게임사·퍼블리셔: 특정 이슈에 대한 이용자 관심과 시장 기대의 변화를 확인
- 분석가·투자자: 게임별 뉴스 신호·거래량·호가·가격 변화를 하나의 흐름으로 탐색
- 가격 결정 원칙: 뉴스는 가격이 움직일 방향과 강도를 만들고, 사람과 봇의 매수·매도·거래량·호가 불균형이 실제 체결 가격을 결정하도록 기록하고 설명
- 1차 구현 완료: 최근 24시간 뉴스 흐름, 이용자·자동 유동성 체결량, 현재 호가 잔량을 `/api/stocks/{stockCode}/price-drivers`로 계산하고, 종목 화면에는 이용자가 이해할 수 있는 뉴스·거래·호가 근거만 표시. 자동 유동성은 내부 가격 형성에만 사용하며 화면에 별도 항목으로 노출하지 않음. 뉴스는 기준 호가를 이동시키고, 최종 현재가는 실제 체결 가격(VWAP)으로 갱신
- 졸업 프로젝트와의 연관성: 뉴스 감성 분석, 거래 매칭 엔진, 실시간 데이터 처리, 가격 형성 모델, 시각화를 하나의 서비스 흐름으로 통합해 ‘뉴스와 집단 투자 행동이 가상 시장을 형성하는 과정’을 구현하고 검증
- 투자받을 이유: 실제 게임 뉴스와 참여자 거래가 누적되면 게임산업의 기대와 반응을 관찰할 수 있는 특화 데이터가 만들어지며, 장기적으로 게임사·분석 조직용 대시보드·리포트·API로 확장할 가능성이 있음
- 현실적인 한계: 가상 주가는 실제 금융상품의 가격이나 수익을 보장하지 않음. 사업성을 주장하려면 공개 게임 지표·실제 이용자 반응·협력 데이터와 가상 시장 결과의 관계를 추가로 검증해야 함
- 성공 기준: 뉴스가 가격에 반영되는 과정과 거래가 최종 가격을 바꾸는 원리를 사용자가 이해하는지, 뉴스·거래·가격 데이터가 일관된 흐름으로 재현되는지, 게임사·분석가 관점에서 시장 반응을 확인하는 데 유용한지 테스트와 인터뷰로 확인

### 2. 뉴스·거래 기반 가격 형성 모델 문서화

- [x] 뉴스 영향도·최신성·이용자·봇 체결량·호가 불균형·최근 가격의 역할과 반영 범위를 명시
- [x] 뉴스 한 건 및 최근 24시간 누적 영향의 계산식과 상한을 명시
- [x] 가격·시간 우선 매칭, VWAP, 봇 틱 상한, 뉴스가 현재가를 직접 변경하지 않는 원칙을 명시
- [x] 가격 변동 이유와 `/api/stocks/{stockCode}/price-drivers` 기대 지표의 의미를 정의
- [x] 일반·특별·대형 사건 예시와 같은 시드·DB 상태에서의 재현 절차를 정리
- 상세 문서: [`docs/price-formation-model.md`](docs/price-formation-model.md)

### 3. 뉴스 필터·감성 분석과 시장 신호 검증

- [x] 게임명만 포함된 오탐을 제외하고 사건·사고를 포함하는 관련성 규칙과 긍정·부정 키워드 가중치를 문서화
- [x] 손으로 분류한 뉴스 표본과 관련성·긍정·부정·중립 방향 회귀 테스트를 추가
- [x] 뉴스 신호·거래량·호가·가격을 비교하고 설명이 어긋나는 경우의 기록 기준을 정의
- [x] RSS 원문·출처·수집 실패·중복·오래된 뉴스 처리 방식을 정리
- 상세 문서: [`docs/news-analysis-validation.md`](docs/news-analysis-validation.md)

### 4. 거래 엔진·데이터 신뢰성과 서비스 품질 검증

- [x] 시장가·지정가·부분 체결·주문 취소·예약 자산 반환 시나리오 테스트 러너 작성
- [x] 동시 주문 정합성 규칙과 읽기 전용 DB 점검 기준을 정의
- [x] API 응답 시간·WebSocket 갱신·서버 재시작 복구 측정 절차를 정리
- 상세 문서·러너: [`docs/market-engine-validation.md`](docs/market-engine-validation.md), [`scripts/test-market-quality.ps1`](scripts/test-market-quality.ps1), [`scripts/check-market-invariants.ps1`](scripts/check-market-invariants.ps1)

### 5. 배포·구조 정리

- [x] Spring Boot 백엔드·웹·Expo 앱을 기준 구조로 명시하고 `server/src` 기존 데모 서버의 역할을 정리
- [x] 실행·환경변수·Firebase·MySQL 설정을 처음 설치하는 사람이 재현할 수 있도록 문서화
- [x] 정적 웹·Expo Go 테스트 배포 절차와 개인정보·서비스 계정·RSS 출처 점검표를 정리
- APK는 Expo/EAS 서명 계정이 필요한 별도 릴리스 단계로 남겨 둠
- 상세 문서: [`docs/deployment-structure.md`](docs/deployment-structure.md)

### 6. 기능 우선순위 관리

- [x] 핵심 발표 범위를 뉴스 수집·필터링·영향 분석·사람·봇 거래·호가·체결·최종 가격·변동 이유로 고정
- [x] 주문·체결·봇을 핵심 시장 구성 요소로, 손익·랭킹을 참여 보조 기능으로 구분
- [x] 시즌제·미션·공유 카드는 핵심 흐름 검증 후 추가하도록 우선순위를 정의
- [x] 한강 수온·인생 리셋·출석 보상은 부가 기능으로 분리
- 상세 범위 문서: [`docs/project-scope.md`](docs/project-scope.md)

### 메모용

#### 현재 고민

1. 왜 이 서비스에 투자해야 됨? 게임사와 협업하지 않는 비공식 서비스만으로는 메리트가 부족할 수 있음. 협찬이 생기면 주간 수익 1·2·3위 사용자에게 소정의 보상을 제공하는 방안을 검토.
   -> 사용 대상자를 서브컬처 게임을 좋아하는 사람이지만 주식경험도 한번 해보고 싶은 사람들 위주로 잡기? (교육용으로 방향성을 잡으면 될듯?)
2. 실제 사용자가 얻는 혜택은 무엇인가?
   -> 실제 주식을 할때 손해를 보지 않고 투자를 잘할수 있는 법을 공부할수 있음
   주간 랭킹에서 좋은 성적을 낸 사용자에게 프로필 꾸미기 아이템 등을 제공하는 방안을 검토
   -> 랭킹 옆에 칭호가 붙는 방식으로 칭호를 추가해야 될듯? (닉네임 - 칭호 - +- 몇퍼) -> 이걸 통해서 좀더 열심히 할수있도록 동기부여?
3. 구조 자체는 괜찮지만 전체 서비스로 보면 부족한 느낌이 있음. 졸업 프로젝트라는 큰 틀에서 서비스의 목적과 반복 이용 이유를 더 명확히 해야 함.
   -> 목적을 주식투자 공부용으로 잡는다고 치면 아무래도 반복적으로 사용하는게 도움이 많이 되겠지?

#### 우선적으로 추가하면 좋은 기능

1. 메인 화면에서 게임 뉴스와 가격 변동 이유를 함께 표시
   - 구현 완료: 메인 화면 종목 카드와 시장 이벤트에 최근 가격 흐름·뉴스 영향도·변동 이유를 표시하고, 뉴스 제목을 누르면 요약·영향 방향·게시 시각을 상세 모달로 확인할 수 있음. 상승 영향은 빨간색, 하락 영향은 파란색으로 표시.
2. 1~2주 단위 시즌제와 시즌별 랭킹 도입
   - 시즌 종료 후 초기화할지, 기존 주식·현금을 유지한 채 다음 주간 경쟁을 시작할지 결정 필요.
3. 뉴스 확인, 특정 수익률 달성, 손실 제한 등 투자 미션 추가
   - 프로필과 랭킹에 표시되는 칭호를 보상으로 제공하는 방안을 검토.
4. 뉴스·거래량·시장 이벤트·봇 거래가 가격에 준 영향 설명
   - 복잡한 AI보다 최근 뉴스 영향도, 거래량 변화, 호가 불균형을 근거로 한 규칙 기반 설명부터 구현.
5. 친구나 커뮤니티에 올릴 수 있는 투자 성적 공유 카드 제공
   - 포트폴리오와 시즌 성적을 카드 이미지로 저장하거나 공유하는 기능을 검토.
