#!/usr/bin/env python3
"""빌드한 백엔드 JAR를 실제로 띄워 운영 배포와 같은 준비 상태 검사를 통과하는지 확인한다.

단위·통합 테스트는 일부 빈만 만들거나 객체를 직접 생성하므로, Spring이 앱 전체를 조립하다
실패하는 문제(예: 생성자 주입 오류)는 배포 단계에서야 드러났다. 이 검사를 CI에서 먼저 돌린다.

사용법:
    DB_URL=... DB_USERNAME=... DB_PASSWORD=... python3 backend/ops/startup-check.py backend/target/gamestock-backend-0.1.0.jar

DB에는 database/schema.sql이 먼저 적용돼 있어야 한다. 외부 뉴스·한강 수집은 끄고 띄운다.
"""
import os
import socket
import subprocess
import sys
import tempfile
import time
from collections import deque
from pathlib import Path

HEALTHCHECK = Path(__file__).with_name("oci-healthcheck.py")
DEADLINE_SECONDS = int(os.environ.get("STARTUP_DEADLINE_SECONDS", "180"))
LOG_TAIL_LINES = 120


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def tail(path: Path, lines: int) -> str:
    with path.open(encoding="utf-8", errors="replace") as handle:
        return "".join(deque(handle, maxlen=lines))


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    jar = Path(sys.argv[1])
    if not jar.is_file():
        print(f"STARTUP_CHECK_FAILED: JAR가 없습니다: {jar}", file=sys.stderr)
        return 2
    for name in ("DB_URL", "DB_USERNAME"):
        if not os.environ.get(name):
            print(f"STARTUP_CHECK_FAILED: {name} 환경 변수가 필요합니다.", file=sys.stderr)
            return 2

    port = free_port()
    env = {
        **os.environ,
        "PORT": str(port),
        "SERVER_ADDRESS": "127.0.0.1",
        # 외부 사이트 수집은 기동 검사와 무관하고 CI 네트워크 상태에 따라 흔들리므로 끈다.
        "GAMESTOCK_NEWS_ENABLED": "false",
        "GAMESTOCK_OFFICIAL_NEWS_ENABLED": "false",
        "GAMESTOCK_HANGANG_ENABLED": "false",
    }
    base = f"http://127.0.0.1:{port}"
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"

    with tempfile.NamedTemporaryFile("w+", suffix=".log", delete=False) as log:
        log_path = Path(log.name)
        process = subprocess.Popen([java, "-jar", str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
    started = time.monotonic()
    last_error = ""
    try:
        while time.monotonic() - started < DEADLINE_SECONDS:
            if process.poll() is not None:
                print(f"STARTUP_CHECK_FAILED: 앱이 준비되기 전에 종료됐습니다(exit {process.returncode}).", file=sys.stderr)
                print(tail(log_path, LOG_TAIL_LINES), file=sys.stderr)
                return 1
            check = subprocess.run([sys.executable, str(HEALTHCHECK), base], capture_output=True, text=True, timeout=30)
            if check.returncode == 0:
                print(f"STARTUP_CHECK_OK: {time.monotonic() - started:.0f}초 만에 준비 상태 검사를 통과했습니다.")
                return 0
            last_error = (check.stderr.strip().splitlines() or [""])[-1]
            time.sleep(2)
        print(f"STARTUP_CHECK_FAILED: {DEADLINE_SECONDS}초 안에 준비되지 않았습니다. 마지막 검사 오류: {last_error}", file=sys.stderr)
        print(tail(log_path, LOG_TAIL_LINES), file=sys.stderr)
        return 1
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        log_path.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
