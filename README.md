# GameStock

모바일 게임 데이터를 바탕으로 가상 종목을 거래하는 모의주식 서비스의 실행 가능한 초기 골격입니다.

현재 버전은 별도 데이터베이스 설정 없이 실행되는 **메모리 기반 데모**입니다. 종목 조회, 시장 이벤트 조회, 매수·매도, 보유 자산 계산이 실제로 동작합니다. 서버를 다시 시작하면 데모 데이터는 초기화됩니다.

## 실행 방법

PowerShell에서 프로젝트 폴더를 연 뒤 다음을 실행합니다.

프로젝트 루트의 `scripts\start.cmd`를 더블클릭하면 백엔드와 웹 화면이 창 없이 실행됩니다. 실행한 창을 닫아도 서버는 계속 유지됩니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start.ps1
```

PowerShell에서 직접 실행할 때는 위 명령을 사용하세요. 서버를 종료하려면 `scripts\stop.cmd`를 더블클릭하면 됩니다.

브라우저에서 [http://localhost:8080](http://localhost:8080)을 엽니다.

Java 17 이상이 설치되어 있어야 합니다. 실행 스크립트는 Microsoft OpenJDK 17을 사용합니다.

백엔드 실행 전에 프로젝트 루트의 `.env.example`을 복사해 `.env`를 만들고 `DB_PASSWORD`에 MySQL 비밀번호를 한 번 입력합니다. `.env`는 Git에 포함되지 않으며, 이후에는 비밀번호를 다시 입력하지 않아도 됩니다.

### 컴퓨터 재부팅 후 MySQL 설정

MySQL이 설치되어 있어도 서버 서비스가 실행 중이 아니면 백엔드가 `localhost:3306`에 연결하지 못합니다. 처음 한 번 관리자 권한 PowerShell에서 MySQL을 Windows 서비스로 등록하고 자동 시작으로 설정합니다.

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe" `
	--install MySQL80 `
	--defaults-file="C:\ProgramData\MySQL\MySQL Server 8.0\my.ini"

Set-Service MySQL80 -StartupType Automatic
Start-Service MySQL80
```

정상 설정 여부는 다음 명령으로 확인할 수 있습니다.

```powershell
Get-Service MySQL80
```

`Status`가 `Running`이고 `StartType`이 `Automatic`이면 컴퓨터를 재부팅해도 MySQL이 자동으로 실행됩니다. 서비스 등록 후에는 `start.ps1`이 백엔드 실행 전에 MySQL 서비스 상태를 확인하고, 중지되어 있으면 자동으로 시작합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start.ps1
```

MySQL과 백엔드의 로컬 연결에는 인터넷이 필요하지 않습니다. 단, RSS에서 최신 뉴스를 수집하려면 인터넷 연결이 필요합니다.

## 현재 구조

```text
backend/                  Spring Boot REST API 및 실시간 WebSocket 서버
frontend/                 웹 화면 (HTML, CSS, JavaScript)
mobile/                   Expo 기반 Android/iOS 앱
server/src/.../           기존 단독 실행 데모 서버
database/schema.sql       MySQL 전환용 초기 테이블 구조
scripts/run-backend.ps1   Spring Boot 서버 실행 스크립트
scripts/run.ps1           웹 화면용 기존 데모 서버 실행 스크립트
scripts/start.ps1         백엔드와 웹 화면을 한 번에 실행하는 시작 스크립트
scripts/start.cmd         실행 정책 우회 후 start.ps1을 호출하는 바로가기
scripts/stop.ps1          실행 중인 백엔드와 웹 화면 종료 스크립트
scripts/stop.cmd          stop.ps1을 호출하는 종료 바로가기
```

## 다음 단계

완료:

1. MySQL 서버 초기 설정 후 `database/schema.sql` 적용
2. JDBC 기반 MySQL 저장소 연결 및 종목·주문·포트폴리오 저장

남은 작업:

1. 로그인·회원가입 추가 (완료: Firebase Google 로그인, 사용자별 자산/주문, 출석 보상)
2. 봇 주문과 주문장/체결 엔진 고도화 (호가로 매도 및 매수기능 구현 -> 실제 주식 매수 및 매도하는 법과 똑같이 흘러가도록)
3. 모바일 앱에 매수·매도 화면 추가
4. 각 종목별 화면에서 종목 이름 옆에 각 게임에 맞는 어플리케이션 사진 넣을거임
5. 앱 배포는 지금 이 방식에서 apk 방식으로 배포할거임
6. 모의주식이니까 출석체크 보상같은것도 있으면 좋을듯? (첫 로그인 하면 10만원, 2일째 로그인하면 20만원.....5일째 로그인하면 50만원을 주고 6일째 로그인부터는 출석보상 상향없이 50만원으로 고정 -> 만약 중간에 로그인하는걸 하루 빼먹으면 다시 첫 로그인부터 다시 카운트되고 로그인 기준 날짜는 자정)
7. (모바일 웹사이트 공통) 다크모드 기능 키고 킬수있도록 -> 기본값은 라이트모드로

## 게임 뉴스 수집

백엔드는 `backend/src/main/resources/application.yml`에 설정된 종목별 RSS 피드를 서버 시작 후와 10분 간격으로 조회합니다. 새 뉴스는 `market_events` 테이블에 중복 없이 저장되고, 기존 웹 화면의 시장 이벤트 영역에 표시됩니다.

RSS 수집을 끄려면 실행 전에 다음 환경변수를 설정합니다.

```powershell
$env:GAMESTOCK_NEWS_ENABLED = "false"
```

피드 주소나 검색어를 바꾸려면 같은 `application.yml`의 `gamestock.news.feeds` 항목을 수정합니다. 형식은 `종목코드|RSS주소`입니다. 외부 RSS 서버가 응답하지 않아도 기존 시장 API와 거래 기능은 계속 작동합니다.
