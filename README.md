# GameStock

모바일 게임 데이터를 바탕으로 가상 종목을 거래하는 모의주식 서비스의 실행 가능한 초기 골격입니다.

현재 버전은 별도 데이터베이스 설정 없이 실행되는 **메모리 기반 데모**입니다. 종목 조회, 시장 이벤트 조회, 매수·매도, 보유 자산 계산이 실제로 동작합니다. 서버를 다시 시작하면 데모 데이터는 초기화됩니다.

## 실행 방법

PowerShell에서 프로젝트 폴더를 연 뒤 다음을 실행합니다.

먼저 새 Spring Boot 실시간 서버를 실행하고, 별도 PowerShell 창에서 웹 화면을 실행합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\run-backend.ps1
```

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\run.ps1
```

브라우저에서 [http://localhost:8080](http://localhost:8080)을 엽니다.

Java 17 이상이 설치되어 있어야 합니다. 실행 스크립트는 Microsoft OpenJDK 17을 사용합니다.

## 현재 구조

```text
backend/                  Spring Boot REST API 및 실시간 WebSocket 서버
frontend/                 웹 화면 (HTML, CSS, JavaScript)
mobile/                   Expo 기반 Android/iOS 앱
server/src/.../           기존 단독 실행 데모 서버
database/schema.sql       MySQL 전환용 초기 테이블 구조
scripts/run-backend.ps1   Spring Boot 서버 실행 스크립트
scripts/run.ps1           웹 화면용 기존 데모 서버 실행 스크립트
```

## 다음 단계

1. MySQL 서버 초기 설정 후 `database/schema.sql` 적용
2. 메모리 저장소를 MySQL 저장소로 교체
3. 로그인·회원가입 추가
4. 봇 주문과 주문장/체결 엔진 추가
5. 모바일 앱에 매수·매도 화면 추가
