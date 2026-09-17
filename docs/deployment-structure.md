# 배포·구조 정리

## 기준 구조

| 구성 | 현재 기준 | 역할 |
| --- | --- | --- |
| `backend/` | Spring Boot 3.5 + Java 17 | MySQL 저장소, Firebase 토큰 검증, 주문·체결, 봇, 뉴스·수온 수집, REST/WebSocket |
| `src/` + `public/` | React·Vite 웹 프론트엔드 | 웹 시장 화면과 로그인·메뉴·뉴스·호가·가격 설명 |
| `mobile/` | Expo React Native | 웹과 같은 API를 사용하는 Android/iOS 테스트 앱 |
| `server/src/` | 기존 데모 서버 | 현재 실행 기준이 아님. 비교용으로만 보존 |
| `database/schema.sql` | MySQL 8 스키마 | 신규 DB 초기화 기준 |
| `scripts/` | Windows 실행·종료·검증 | MySQL 확인, Maven 실행, 웹 서버, 품질 점검 |

배포와 발표에서 기준으로 삼는 경로는 `backend/` + `frontend/` + `mobile/`이다. `server/src/`는 기능을 중복 실행하지 않으며, 제거 여부는 최종 제출물 크기와 비교 필요성에 따라 결정한다.

## 처음 설치하는 사람의 순서

1. Java 17, MySQL 8.0, Node.js와 Expo 도구를 설치한다.
2. `database/schema.sql`을 `gamestock` 데이터베이스에 적용한다.
3. 프로젝트 루트 `.env`에 `DB_USERNAME`, `DB_PASSWORD`를 설정한다.
4. Firebase Console에서 Google 로그인과 승인 도메인을 설정하고 웹 환경변수 `VITE_FIREBASE_*`를 `.env.local`에 넣는다.
5. Firebase Admin 서비스 계정 JSON은 저장소 밖에 보관하고 `.env`의 `FIREBASE_SERVICE_ACCOUNT_JSON`으로만 연결한다.
6. `scripts/start.ps1`로 백엔드와 웹을 시작하고 `http://localhost:5180`에서 확인한다.
7. 모바일 테스트는 `mobile/`에서 `EXPO_PUBLIC_API_BASE_URL`과 Google OAuth 클라이언트 ID를 지정한 뒤 Expo Go로 실행한다.

## 환경변수 점검

- 필수: `DB_PASSWORD`, `FIREBASE_SERVICE_ACCOUNT_JSON`(Firebase 인증을 켤 때)
- 관리자: `GAMESTOCK_ADMIN_GOOGLE_UID` 또는 보조 이메일
- 시장 재현: `GAMESTOCK_SIMULATION_SEED`
- 외부 수집: `GAMESTOCK_NEWS_ENABLED`, `GAMESTOCK_NEWS_REFRESH_MS`, `GAMESTOCK_HANGANG_SITE_URL`, `GAMESTOCK_HANGANG_REFRESH_MS`
- 배포 주소: `GAMESTOCK_CORS_ORIGIN`, 모바일 `EXPO_PUBLIC_API_BASE_URL`

서비스 계정 키, DB 비밀번호, Firebase ID 토큰, 개인 프로필 정보는 Git·문서·로그에 넣지 않는다. RSS는 원문을 재배포하지 않고 제목·짧은 요약·출처 링크만 저장한다.

## 웹 테스트 배포

웹은 `npm run build`로 만든 `dist/`를 Nginx·Firebase Hosting·정적 파일 서버에 배포한다. 배포 시 `VITE_API_BASE_URL`을 백엔드 공개 주소로 설정한다. 백엔드는 외부에서 직접 노출할 때 HTTPS·WSS와 허용 Origin을 함께 설정한다.

발표 전에는 다음을 확인한다.

- `/api/health`가 200이고 DB 연결 오류가 없다.
- Google 로그인 승인 도메인이 실제 웹 주소와 일치한다.
- 서비스 계정 JSON이 배포 이미지·Git 기록에 포함되지 않는다.
- RSS 출처와 개인정보 처리 안내가 화면에 표시된다.
- 모바일은 실기기에서 API·WebSocket 주소로 연결된다.

APK 빌드는 Expo/EAS 계정과 서명 키가 필요한 별도 릴리스 단계다. 계정 없이도 Expo Go 테스트 배포와 정적 웹 배포를 먼저 검증할 수 있다.
