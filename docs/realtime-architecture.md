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

현재 Spring Boot 서버는 메모리 데이터를 사용한다. MySQL 초기 설정이 끝나면 `MarketService`의 메모리 Map을 JPA Repository로 교체하되 REST·WebSocket 계약은 유지한다.
