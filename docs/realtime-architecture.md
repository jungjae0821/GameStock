# 웹·앱 동시 실시간 구조

```text
웹 브라우저 (기존 frontend/) ─ REST API ─┐
                                      │
모바일 앱 (mobile/ Expo) ─ REST API ───┼─ Spring Boot backend (8081)
                                      │       ├─ 시장/주문 서비스
웹 브라우저 ─ WebSocket /ws/market ────┤       ├─ 봇 시세 시뮬레이터
모바일 앱 ─ WebSocket /ws/market ──────┘       └─ 이후 MySQL 저장소
```

## 실행 순서

1. `scripts/run-backend.ps1`로 Spring Boot API를 시작한다.
2. 웹은 기존 `scripts/run.ps1`을 실행한다. 웹 API 주소는 이후 Spring Boot의 `8081`로 연결한다.
3. `mobile` 폴더에서 `npm install`, `npm start`를 실행하고 Expo Go 또는 Android 에뮬레이터로 연다.
4. 실기기 사용 시 `mobile/src/config.js`의 IP를 개발 PC의 Wi-Fi IPv4 주소로 변경한다.

## 서로 다른 네트워크에서 사용하기

서버를 인터넷에서 접근 가능한 한 대의 호스트에 배포하고, 웹과 앱 모두 그 공개 주소를 사용한다.

1. Spring Boot 서버를 클라우드 VM 또는 호스팅 서버에 배포한다.
2. 도메인과 HTTPS를 설정하고 `/api/*`, `/ws/market`을 백엔드로 프록시한다.
3. 백엔드 실행 환경에 `GAMESTOCK_CORS_ORIGIN=https://웹_주소`를 설정한다.
4. 앱 빌드 환경에 `EXPO_PUBLIC_API_BASE_URL=https://API_주소`를 설정한다.
5. 웹을 같은 도메인에서 제공하면 `frontend/app.js`가 현재 도메인을 API와 WebSocket 주소로 자동 사용한다. API가 별도 도메인이라면 `window.GAMESTOCK_API_BASE_URL`을 그 주소로 설정한다.

현재 저장소는 메모리 기반이므로 백엔드 서버를 한 대만 실행해야 웹과 앱이 같은 시장 상태를 본다. 여러 대를 실행할 때는 MySQL과 Redis 또는 WebSocket 브로커로 상태와 이벤트를 공유해야 한다.

현재 Spring Boot 서버는 메모리 데이터를 사용한다. MySQL 초기 설정이 끝나면 `MarketService`의 메모리 Map을 JPA Repository로 교체하되 REST·WebSocket 계약은 유지한다.
