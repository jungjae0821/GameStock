# OCI 운영과 복구

운영 화면은 `https://gamestock-20994.web.app`, API는 `https://gamestock-api.161-33-33-45.sslip.io`를 사용한다. Firebase 인증은 유지한다. 모바일 WebView도 같은 화면을 사용하며 네이티브 인증 API 기본 주소는 `mobile/src/config.js`에 있다. 이미 설치한 앱에 이전 네이티브 주소가 포함돼 있다면 다시 빌드해야 한다.

## 서버

- Oracle Linux 9 ARM64, Java 17, Nginx, systemd.
- `gamestock.service`: 운영 백엔드, `127.0.0.1:8081`만 수신.
- `/opt/gamestock/releases/gamestock-backend-production.jar`: 실행 파일.
- `/etc/gamestock/production.env`: 사설 MySQL 연결 및 관리자 설정. 내용을 출력하거나 Git에 넣지 않는다.
- `/etc/gamestock/firebase-admin.json`: Firebase 관리자 자격증명.
- MySQL `gamestock` 스키마에만 접근하는 `gamestock_app` 계정으로 TLS 연결한다.
- `/etc/nginx/conf.d/gamestock-https.conf`: HTTPS API 및 `/ws/market`, `/ws/stream` 프록시.
- 공인 IP가 바뀌면 IP 기반 도메인, 인증서, 워크플로, 웹·모바일 기본 API 주소를 함께 바꾼다.

```sh
sudo systemctl status gamestock nginx --no-pager
sudo journalctl -u gamestock --since '10 minutes ago' --no-pager
curl -fsS https://gamestock-api.161-33-33-45.sslip.io/api/health
sudo systemctl restart gamestock
```

`matchingEnabled`, `batchBotsEnabled`와 스케줄러 시작 상태가 켜져 있어야 한다. HTTP 200만 보지 말고 종목·호가·최근 체결, 로그인, 계좌, 주문·취소, WebSocket 재연결도 확인한다. 관리자 계정은 주문이 차단된다.

## DB 유지보수와 자동 재연결

2026-11-04 한국 시간 23:02~23:26 Oracle MySQL 자동 업그레이드 이후 DB 연결이 끊기고 매칭이 재개되지 않은 장애가 있었다. DB가 중단되는 동안 주문을 계속 처리할 수는 없다. 기존 화면의 캐시와 WebSocket 재연결을 유지하면서 DB 복귀 후 매칭을 자동 재개한다.

매칭 소유권 복구는 일반 시장 스케줄러와 별도의 `matching-ownership-recovery` 스레드가 2초 간격으로 수행한다. 연결 검증에 실패하면 신규 매칭을 막고 해당 세션을 풀에서 폐기한 뒤 새 MySQL advisory lock을 획득한다. 다른 서버가 소유권을 가지고 있으면 대기하므로 매칭 엔진이 동시에 실행되지 않는다. 기존 배치 영수증으로 커밋 여부가 불명확한 재시도의 중복 처리를 방지하며, 클라이언트 주문 POST를 임의로 재전송하지 않는다.

기본 풀 획득·TCP 연결 대기는 5초, 소켓 응답 대기는 10초로 제한한다. TLS 연결 정리와 재연결 재시도가 더해질 수 있어 전체 복구가 항상 2초 이내라는 뜻은 아니다. 운영 `DB_URL`에 같은 타임아웃 속성을 따로 넣으면 드라이버 설정을 덮어쓸 수 있으므로 함께 확인한다.

`/api/health`는 프로세스 상태를 확인한다. `/api/health/ready`는 실제 DB 쿼리와 현재 매칭 소유권·봇 스케줄러를 확인하며, 복구 중에는 HTTP 503과 `{"status":"recovering"}`을 반환한다. 준비가 되면 HTTP 200과 `{"status":"ready"}`를 반환한다. 외부 점검과 배포 검증에는 준비 상태 경로를 포함한다. 장시간 503이면 소유권 복구 로그와 DB 유지보수 이력을 확인하고, 200이어도 최신 체결 시각이 진행되는지 확인한다.

```sh
curl -fsS https://gamestock-api.161-33-33-45.sslip.io/api/health/ready
sudo journalctl -u gamestock --since '10 minutes ago' --no-pager | grep 'Matching ownership'
```

## Discord 장애 알림

서버·DB 장애를 Discord로 받으려면 [별도 서버 장애 감시 설치](discord-monitoring.md)를 따른다. 운영 OCI 밖에서 30초마다 웹·API·DB 준비 상태·두 WebSocket 경로를 확인하고 장애·복구 알림을 보낸다. 운영 서버 안에서만 실행하면 VM 자체의 중단을 알릴 수 없다.

## 백업

`gamestock-backup.timer`가 매일 한국 시간 03:30에 논리 백업을 생성한다. `/var/backups/gamestock/`에는 최근 7일의 압축 SQL을 root 전용으로 보관한다. 원본 DB 외부에 복사한 백업도 유지해야 VM 손실에 대비할 수 있다. 이전 시점의 Railway 최종 백업은 Windows의 접근 제한된 OCI 설정 폴더에 별도로 보존한다. 백업에는 개인정보가 포함되므로 공유·커밋하지 않는다.

```sh
sudo systemctl start gamestock-backup.service
sudo systemctl list-timers gamestock-backup.timer gamestock-certbot-renew.timer
sudo journalctl -u gamestock-backup.service --no-pager
sudo /opt/certbot/bin/certbot renew --dry-run
```

백업은 먼저 비어 있는 별도 스키마에 복원한다. 운영 DB를 덮어쓰기 전에 54개 테이블의 행 수·체크섬과 회원 자산·주문·체결의 일관성을 비교하고 기동을 검증한다. 자격증명은 명령행 인자에 직접 쓰지 말고 접근 제한된 MySQL 클라이언트 설정을 사용한다.

## 전환과 롤백

한 시점에 하나의 운영 매칭 엔진만 실행한다. 전환은 기존 서버 중단 → 최종 백업 → 새 빈 스키마 복원 → 행 수·체크섬 비교 → OCI 기동 → Firebase 웹 배포 순서다. Railway 자동 배포를 비활성화해 GitHub push가 이전 엔진을 다시 실행하지 않게 한다. 이전 Railway DB는 복구용으로 보존하며 새 주문을 받지 않는다.

OCI가 운영 주문을 받은 뒤에는 오래된 Railway DB로 URL만 되돌리면 안 된다. 먼저 신규 주문을 막고 OCI를 중단·백업한 뒤 최신 상태를 복원해 검증하고, 그다음 웹 주소를 전환한다. 매칭 엔진을 재시작할 때도 이전 프로세스 종료를 확인한다.

## GitHub 자동 배포

웹은 기존 `.github/workflows/firebase-hosting.yml`로 배포한다. 백엔드는 `.github/workflows/oci-backend.yml`이 `main`의 `backend/**` 또는 해당 워크플로 변경을 감지해 자동 배포한다. GitHub Actions에서 수동 실행도 가능하다. 다른 브랜치에 커밋하거나 로컬에서만 커밋하면 운영에는 배포되지 않는다.

백엔드 배포는 배포 스크립트 검증 → Java 17 테스트·빌드 → 체크섬 검증된 JAR 전송 → 기존 프로세스 완전 종료 → 새 버전 기동 → API·스케줄러·호가 준비 확인 → 외부 HTTPS·두 WSS 경로 확인 순서다. GitHub 대기열과 서버 `flock`이 배포를 직렬화하며 진행 중인 배포를 새 push로 취소하지 않는다. 전송 전에 최신 `main` 커밋을 확인해 오래된 대기 작업의 뒤늦은 배포를 건너뛴다. 기동 교체 중 잠깐 연결이 끊길 수 있다.

서버의 `/usr/local/sbin/gamestock-deploy`는 JAR를 커밋 SHA와 파일 SHA256으로 구분해 보관하고 활성 경로를 교체한다. 기동·로컬 준비 확인에 실패하면 이전 JAR를 다시 실행하고 Actions는 실패로 표시한다. 외부 네트워크 검사만 실패한 경우에는 이미 정상 기동한 서버를 되돌리지 않으므로 배포 로그와 현재 상태를 함께 확인한다. 최근 JAR 5개와 현재·직전 버전을 보존한다. `/var/lib/gamestock-deploy/deployed-revision`은 마지막으로 준비 확인을 통과한 커밋이다.

```sh
sudo cat /var/lib/gamestock-deploy/deployed-revision
sudo readlink -f /opt/gamestock/releases/gamestock-backend-production.jar
```

GitHub 저장소에는 변수 `OCI_SSH_HOST`, 시크릿 `OCI_DEPLOY_SSH_KEY`와 `OCI_SSH_KNOWN_HOSTS`가 필요하다. 전용 `gamestock-deploy` 계정의 키는 정해진 배포 명령만 실행하며 일반 셸·포트 포워딩·관리자 SSH 접속은 허용하지 않는다. 서버 호스트 키를 고정해 확인한다. DB 비밀번호와 Firebase 관리자 키는 서버에 남기며 GitHub에 추가하지 않는다.

서버 배포 도우미는 관리자가 검토 후 한 번 설치한다. 이후 `backend/ops/`의 보안 도우미를 수정했다면 관리자가 같은 설치 절차로 서버 파일을 갱신해야 한다. Actions의 배포 키는 관리자 도우미를 수정할 수 없다.

```sh
sudo bash backend/ops/install-oci-deploy.sh /path/to/github-actions-key.pub
```

자동 복구는 애플리케이션 JAR만 되돌린다. DB 스키마를 바꾸는 코드는 기존 버전과 호환되게 단계적으로 적용하고 백업·별도 복원 DB에서 검증한 뒤 `main`에 병합한다. DB를 과거 백업으로 자동 덮어쓰지 않는다.
