# Discord 장애 알림

GameStock 운영 서버와 **다른 Linux 서버**에서 `backend/ops/monitor.py`를 계속 실행한다. 기본 확인 주기는 30초이며 Python 3.9 이상과 systemd 247 이상만 사용한다. 추가 Python 패키지나 DB 계정은 필요하지 않다. 설정 파일은 `backend/ops/monitor-targets.json`이다.

| 감시 항목 | 정상 판단 |
| --- | --- |
| Firebase 웹 | HTTP 200과 앱의 root 요소 |
| API·매칭 스케줄러 | `/api/health`의 정상 상태, 매칭·배치 봇 활성화, 스케줄러 시작 여부 |
| DB·매칭 준비 상태 | `/api/health/ready`의 HTTP 200과 `status=ready` |
| 시장 실시간 연결 | `/ws/market`의 WebSocket 연결 승인과 응답 키 |
| 구독 실시간 연결 | `/ws/stream`의 WebSocket 연결 승인과 응답 키 |

준비 상태 경로는 실제 DB 쿼리와 매칭 소유권을 확인하므로 MySQL 중단도 감지한다. 이 경로의 실패만으로 DB와 매칭 엔진 중 어느 쪽이 원인인지 확정하지는 않는다. 웹 확인은 페이지 제공 여부, WebSocket 확인은 연결 성립 여부까지이며 로그인·주문·실시간 데이터 내용 전체를 검증하는 검사는 아니다. 서버가 더 늘어나면 각 서버를 직접 확인하는 대상을 `targets`에 추가한다.

## 알림 동작

- 각 항목을 병렬로 검사하고, 실패한 항목은 3초 후 한 번 더 확인한다. 요청별 기본 대기는 8초다.
- 재검사에도 실패하면 장애 알림을 보낸다. 장애 항목이 달라지면 다시 알리며, 같은 장애가 계속되면 30분마다 알린다.
- 모든 항목이 정상으로 돌아오면 복구 알림과 최초 감지 후 경과 시간을 보낸다.
- Discord 전송에 실패하면 전송 완료로 기록하지 않고 다음 확인 주기에 재시도한다. HTTP 429의 재시도 대기시간도 따른다.
- 장애 이력은 `/var/lib/gamestock-monitor/state.json`에 원자적으로 기록한다. 감시 프로세스가 재시작돼도 마지막 알림 상태를 유지한다. 상태 파일을 지우거나 감시 대상을 바꾸면 새 이력을 시작한다.
- 프로세스 종료는 systemd가 다시 시작한다. 90초 동안 감시 프로세스가 응답하지 않아도 재시작한다.

30초는 검사 시작 주기다. 장애 발생 시점, 연결 대기, 재검사, Discord 응답에 따라 알림까지 더 오래 걸릴 수 있고, 한 검사가 길어지면 다음 검사를 겹쳐 실행하지 않는다. 감시 서버 자체나 Discord까지 중단되면 그동안 전송할 수 없다. 가능하면 운영 OCI 서버와 장애를 공유하지 않는 네트워크·제공자의 감시 서버를 사용한다.

## 별도 서버에 설치

이 절차는 감시 서버에서 실행한다. GameStock 운영 서버와 DB에는 설치하지 않는다. 저장소 전체 대신 아래 파일만 같은 디렉터리에 복사해도 된다.

- `monitor.py`
- `monitor-targets.json`
- `gamestock-monitor.service`
- `install-monitor.py`

1. Discord에서 알림 받을 채널의 웹훅을 만들고 URL을 복사한다.
2. 감시 서버에서 필요하면 `monitor-targets.json`의 대상 주소를 수정한다. 외부 HTTPS/WSS 443 포트로 나갈 수 있어야 하며, 감시용 인바운드 포트는 열 필요가 없다.
3. 아래 명령을 실제 터미널에서 실행하고 웹훅 URL을 숨김 입력한다. SSH로 접속할 때는 터미널을 할당한다(`ssh -t`).

```fish
sudo python3 -B backend/ops/install-monitor.py
```

설치기는 웹훅을 root 전용 파일 `/etc/gamestock-monitor/discord-webhook`에 0600 권한으로 저장한다. 서비스에는 systemd의 [LoadCredential](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#LoadCredential=ID:PATH)로 전달한다. `.env` 파일을 읽거나 수정하지 않으며, 웹훅을 명령행 인자·Git·로그에 기록하지 않는다.

설치 중 실제 Discord 시험 알림을 보내고 수신 확인 응답까지 검사한다. 감시 대상이 이미 장애 상태면 시험 안내 대신 해당 장애 알림을 보낸다. 전달이 확인돼야 서비스를 활성화하고 시작한다. 웹훅 호출에는 [Discord의 wait=true](https://docs.discord.com/developers/resources/webhook#execute-webhook)를 사용하며 자동 멘션은 끈다.

4. Discord 메시지와 서비스 상태를 확인한다.

```fish
sudo systemctl status gamestock-monitor --no-pager
sudo journalctl -u gamestock-monitor --since '10 minutes ago' --no-pager
```

서비스는 재부팅 뒤 자동으로 시작한다. `/etc/gamestock-monitor/targets.json`을 수정한 뒤에는 `sudo systemctl restart gamestock-monitor`를 실행한다. 설치기를 다시 실행하면 기존 대상 설정과 웹훅은 유지한다. 기본 대상 파일로 교체할 때만 `--update-targets`, 웹훅을 교체할 때만 `--set-webhook`을 붙인다. 설치기의 시험 알림 이력은 별도로 처리하므로 이미 장애 중이면 서비스의 실제 장애 알림이 한 번 더 올 수 있다.

## 배포 전 확인

웹훅 없이 감시 대상만 읽기 전용으로 확인할 수 있다. 상태 파일을 만들거나 Discord로 전송하지 않는다.

```fish
python3 -B backend/ops/monitor.py --config backend/ops/monitor-targets.json --check-only
python3 -B backend/ops/test-monitor.py
```

종료 코드는 정상 0, 감시 대상 장애 1, 설정·알림 전송 오류 2다. 테스트는 로컬 임시 HTTP/WebSocket 서버와 가짜 Discord 응답만 사용한다. GitHub의 `Check external monitor` 워크플로에서도 테스트와 Linux 서비스 설정 검증을 실행한다.
