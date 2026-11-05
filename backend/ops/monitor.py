#!/usr/bin/env python3
"""Monitor GameStock from a separate host; uses only the Python standard library."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import fcntl
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import signal
import socket
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class MonitorError(Exception):
    pass


class DeliveryError(MonitorError):
    def __init__(self, message, retry_after=30, received_at=None):
        super().__init__(message)
        self.retry_after = retry_after
        self.received_at = received_at


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_config(path):
    config = json.loads(path.read_text())
    if not isinstance(config, dict):
        raise MonitorError("Monitor configuration must be a JSON object.")
    for key, default, low, high in (
        ("interval_seconds", 30, 5, 60),
        ("timeout_seconds", 8, 1, 10),
        ("attempts", 2, 1, 3),
        ("retry_delay_seconds", 3, 0, 10),
        ("reminder_seconds", 1800, 60, 86400),
    ):
        value = config.setdefault(key, default)
        if type(value) is not int or not low <= value <= high:
            raise MonitorError("Invalid monitor setting: " + key)
    targets = config.get("targets")
    if not isinstance(targets, list) or not 1 <= len(targets) <= 16:
        raise MonitorError("Configure between 1 and 16 targets.")
    names = set()
    for target in targets:
        if not isinstance(target, dict):
            raise MonitorError("Each target must be a JSON object.")
        name, kind, url = target.get("name"), target.get("type"), target.get("url")
        if not isinstance(name, str) or not 1 <= len(name) <= 64 or name in names:
            raise MonitorError("Each target needs a unique name of 1-64 characters.")
        names.add(name)
        if kind not in ("http", "json", "websocket") or not isinstance(url, str):
            raise MonitorError("Invalid target type or URL.")
        parsed = urllib.parse.urlsplit(url)
        schemes = ("ws", "wss") if kind == "websocket" else ("http", "https")
        if (parsed.scheme not in schemes or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment
                or any(char.isspace() for char in url)):
            raise MonitorError("Target URLs must not contain credentials, queries or fragments.")
        # Also validate the optional port before starting the monitoring loop.
        parsed.port
        if kind == "json" and (not isinstance(target.get("expect"), dict) or not target["expect"]
                               or not all(isinstance(key, str) and key for key in target["expect"])):
            raise MonitorError("JSON targets need nonempty expected fields.")
        if "contains" in target and not isinstance(target["contains"], str):
            raise MonitorError("The contains setting must be text.")
    return config


def check_websocket(url, timeout):
    parsed = urllib.parse.urlsplit(url)
    connection_type = http.client.HTTPSConnection if parsed.scheme == "wss" else http.client.HTTPConnection
    connection = connection_type(parsed.hostname, parsed.port, timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    try:
        connection.request("GET", parsed.path or "/", headers={
            "Connection": "Upgrade", "Upgrade": "websocket",
            "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": key,
            "User-Agent": "GameStock-Monitor/1.0",
        })
        response = connection.getresponse()
        expected = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
        ).digest()).decode()
        if response.status != 101:
            raise MonitorError("WebSocket HTTP " + str(response.status))
        if (response.getheader("Upgrade", "").lower() != "websocket"
                or "upgrade" not in response.getheader("Connection", "").lower().replace(" ", "").split(",")
                or response.getheader("Sec-WebSocket-Accept") != expected):
            raise MonitorError("Invalid WebSocket handshake")
    finally:
        connection.close()


def check_target(target, timeout):
    try:
        if target["type"] == "websocket":
            check_websocket(target["url"], timeout)
            return None
        request = urllib.request.Request(target["url"], headers={"User-Agent": "GameStock-Monitor/1.0"})
        with urllib.request.build_opener(NoRedirect).open(request, timeout=timeout) as response:
            if response.status != 200:
                return "HTTP " + str(response.status)
            body = response.read(65537)
        if target["type"] == "json":
            if len(body) > 65536:
                return "Health response too large"
            payload = json.loads(body)
            for field, expected in target["expect"].items():
                actual = payload
                for part in field.split("."):
                    actual = actual.get(part) if isinstance(actual, dict) else None
                if type(actual) is not type(expected) or actual != expected:
                    return "Unexpected health field: " + field
        elif target.get("contains") and target["contains"].encode() not in body:
            return "Expected page content missing"
        return None
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        return "HTTP " + str(code)
    except (TimeoutError, socket.timeout):
        return "Connection timed out"
    except (json.JSONDecodeError, UnicodeError):
        return "Invalid health JSON"
    except (OSError, urllib.error.URLError, http.client.HTTPException) as error:
        # Do not expose URLs, credentials or server response bodies in logs/Discord.
        return "Connection error: " + type(error).__name__
    except MonitorError as error:
        return str(error)


def probe(config):
    def retry(target):
        for attempt in range(config["attempts"]):
            error = check_target(target, config["timeout_seconds"])
            if error is None:
                return None
            if attempt + 1 < config["attempts"]:
                time.sleep(config["retry_delay_seconds"])
        return error

    with ThreadPoolExecutor(max_workers=min(8, len(config["targets"]))) as pool:
        outcomes = list(pool.map(retry, config["targets"]))
    return {target["name"]: error for target, error in zip(config["targets"], outcomes) if error is not None}


def webhook_url(value):
    parsed = urllib.parse.urlsplit(value.strip())
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    if (parsed.scheme != "https" or parsed.netloc not in ("discord.com", "canary.discord.com", "ptb.discord.com")
            or parsed.fragment or not re.fullmatch(r"/api/(?:v[0-9]+/)?webhooks/[0-9]+/[A-Za-z0-9._-]+", parsed.path)
            or set(query) - {"wait", "thread_id"}
            or ("thread_id" in query and (len(query["thread_id"]) != 1 or not query["thread_id"][0].isdigit()))):
        raise MonitorError("Invalid Discord webhook URL.")
    query["wait"] = ["true"]
    return urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query, doseq=True)))


def send_discord(url, content):
    request = urllib.request.Request(url, method="POST", headers={
        "Content-Type": "application/json", "User-Agent": "GameStock-Monitor/1.0",
    }, data=json.dumps({"content": content[:1900], "allowed_mentions": {"parse": []}}).encode())
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=8) as response:
            # wait=true makes Discord confirm that the message was actually saved.
            if response.status != 200 or not json.loads(response.read(65536)).get("id"):
                raise DeliveryError("Discord did not confirm message delivery.")
    except urllib.error.HTTPError as error:
        retry_after = 30
        if error.code == 429:
            try:
                retry_after = float(json.loads(error.read(65536))["retry_after"])
                if not math.isfinite(retry_after) or retry_after < 0:
                    retry_after = 30
            except (ValueError, KeyError, TypeError, OSError):
                retry_after = 30
        code = error.code
        error.close()
        raise DeliveryError("Discord HTTP " + str(code), max(30, retry_after), time.time()) from None
    except (OSError, urllib.error.URLError, ValueError, AttributeError, http.client.HTTPException) as error:
        raise DeliveryError("Discord delivery failed: " + type(error).__name__) from None


def initial_state(config):
    fingerprint = hashlib.sha256(json.dumps(config["targets"], sort_keys=True).encode()).hexdigest()
    return {"version": 1, "targets": fingerprint, "incident": None, "next_notification_at": 0}


def load_state(path, config):
    fresh = initial_state(config)
    try:
        state = json.loads(path.read_text())
        if state["version"] != 1 or state["targets"] != fresh["targets"]:
            return fresh
        if not isinstance(state["next_notification_at"], (int, float)):
            raise ValueError()
        if not math.isfinite(state["next_notification_at"]) or state["next_notification_at"] < 0:
            raise ValueError()
        incident = state["incident"]
        if incident is not None:
            if (not isinstance(incident["started_at"], (int, float))
                    or not isinstance(incident["last_notified_at"], (int, float))
                    or not math.isfinite(incident["started_at"]) or incident["started_at"] < 0
                    or not math.isfinite(incident["last_notified_at"]) or incident["last_notified_at"] < 0
                    or not all(isinstance(incident[key], list)
                               and all(isinstance(item, str) for item in incident[key])
                               for key in ("affected", "notified"))):
                raise ValueError()
        return state
    except FileNotFoundError:
        return fresh
    except (ValueError, KeyError, TypeError):
        print("Monitor state is invalid; starting a new incident history.", flush=True)
        return fresh


def save_state(path, state):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            json.dump(state, output, ensure_ascii=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def update_notifications(state, failures, now, reminder_seconds, sender, test=False):
    incident = state["incident"]
    if failures:
        if incident is None:
            incident = {"started_at": now, "affected": [], "notified": [], "last_notified_at": 0}
            state["incident"] = incident
        incident["affected"] = sorted(set(incident["affected"]) | set(failures))
    if now < state["next_notification_at"]:
        return False
    if failures:
        changed = sorted(failures) != incident["notified"]
        if not changed and not test and now - incident["last_notified_at"] < reminder_seconds:
            return True
        title = "GameStock 장애 감지" if not incident["notified"] else "GameStock 장애 지속 / 상태 변경"
        content = title + "\n" + "\n".join(name + ": " + reason for name, reason in failures.items())
        content += "\n최초 감지: " + datetime.fromtimestamp(incident["started_at"], timezone.utc).isoformat()
    elif incident is not None:
        content = ("GameStock 복구 완료\n모든 감시 항목이 정상입니다.\n영향 항목: "
                   + ", ".join(incident["affected"])
                   + "\n감지 후 경과: " + str(max(0, int(now - incident["started_at"]))) + "초")
    elif test:
        content = "GameStock 감시 테스트\n별도 감시 서버에서 Discord 연결을 확인했습니다. 모든 감시 항목이 정상입니다."
    else:
        return True
    try:
        sender(content)
    except DeliveryError as error:
        # Keep the incident and last successful notification unchanged for the next cycle.
        state["next_notification_at"] = max(now, error.received_at or now) + error.retry_after
        print(str(error) + "; notification remains pending.", flush=True)
        return False
    state["next_notification_at"] = 0
    if failures:
        incident["notified"] = sorted(failures)
        incident["last_notified_at"] = now
    else:
        state["incident"] = None
    return True


def notify_systemd(message):
    address = os.environ.get("NOTIFY_SOCKET")
    if address:
        if address.startswith("@"):
            address = "\0" + address[1:]
        with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as connection:
            connection.sendto(message.encode(), address)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--state-file", type=Path, default=Path("/var/lib/gamestock-monitor/state.json"))
    parser.add_argument("--webhook-file", type=Path)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--check-only", action="store_true", help="Probe once without credentials, notifications or state writes")
    parser.add_argument("--test-notification", action="store_true", help="Send a confirmation on the first cycle even if healthy")
    args = parser.parse_args()
    config = load_config(args.config)
    if args.check_only:
        failures = probe(config)
        print(json.dumps({"healthy": not failures, "failures": failures}, ensure_ascii=False))
        return 1 if failures else 0
    credential = args.webhook_file
    if credential is None and os.environ.get("CREDENTIALS_DIRECTORY"):
        credential = Path(os.environ["CREDENTIALS_DIRECTORY"]) / "discord-webhook"
    if credential is None:
        raise MonitorError("Supply --webhook-file or the systemd discord-webhook credential.")
    url = webhook_url(credential.read_text())
    args.state_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with args.state_file.with_suffix(".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise MonitorError("Another monitor is already using this state file.") from None
        state = load_state(args.state_file, config)
        stopping = threading.Event()
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, lambda *_: stopping.set())
        notify_systemd("READY=1")
        test = args.test_notification
        while not stopping.is_set():
            started = time.monotonic()
            failures = probe(config)
            previous = json.dumps(state, sort_keys=True)
            delivered = update_notifications(state, failures, time.time(), config["reminder_seconds"],
                                             lambda content: send_discord(url, content), test=test)
            if json.dumps(state, sort_keys=True) != previous or not args.state_file.exists():
                save_state(args.state_file, state)
            print(json.dumps({"healthy": not failures, "failures": failures,
                              "notification_pending": not delivered}, ensure_ascii=False), flush=True)
            notify_systemd("WATCHDOG=1")
            if args.once:
                return 2 if not delivered else (1 if failures else 0)
            if delivered:
                test = False
            stopping.wait(max(0, config["interval_seconds"] - (time.monotonic() - started)))
        notify_systemd("STOPPING=1")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (MonitorError, OSError, ValueError, KeyError, TypeError) as error:
        # Only our own controlled errors are safe to print: a URL can contain a webhook token.
        print(str(error) if isinstance(error, MonitorError) else "Monitor failed: " + type(error).__name__, flush=True)
        raise SystemExit(2)
