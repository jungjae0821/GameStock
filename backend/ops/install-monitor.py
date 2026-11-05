#!/usr/bin/env python3
"""Install on a separate Linux host. Prompts for the webhook without echoing it."""
import argparse
import getpass
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import monitor


def install():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--set-webhook", action="store_true", help="Replace the existing Discord credential")
    parser.add_argument("--update-targets", action="store_true", help="Replace the installed target configuration")
    args = parser.parse_args()
    if sys.platform != "linux" or os.geteuid() != 0 or sys.version_info < (3, 9):
        raise monitor.MonitorError("Run with sudo and Python 3.9+ on a separate Linux monitoring host.")
    version = subprocess.check_output(["systemctl", "--version"], text=True)
    match = re.match(r"systemd ([0-9]+)", version)
    if not match or int(match[1]) < 247:
        raise monitor.MonitorError("systemd 247+ is required for LoadCredential.")
    source = Path(__file__).resolve().parent
    monitor.load_config(source / "monitor-targets.json")
    configuration = Path("/etc/gamestock-monitor")
    executable = Path("/opt/gamestock-monitor")
    for directory in (configuration, executable):
        directory.mkdir(mode=0o755, parents=True, exist_ok=True)
    credential = configuration / "discord-webhook"
    if args.set_webhook or not credential.exists():
        # getpass requires a real terminal; do not fall back to echoing the token.
        with open("/dev/tty", "w") as terminal:
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                value = monitor.webhook_url(getpass.getpass("Discord webhook URL (hidden): ", stream=terminal))
        descriptor = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(value + "\n")
    targets = configuration / "targets.json"
    if args.update_targets or not targets.exists():
        targets.write_bytes((source / "monitor-targets.json").read_bytes())
        targets.chmod(0o644)
    monitor.load_config(targets)
    script = executable / "monitor.py"
    script.write_bytes((source / "monitor.py").read_bytes())
    script.chmod(0o644)
    service = Path("/etc/systemd/system/gamestock-monitor.service")
    service.write_bytes((source / "gamestock-monitor.service").read_bytes())
    service.chmod(0o644)
    subprocess.run(["systemd-analyze", "verify", str(service)], check=True)
    # Verify real delivery before enabling the daemon. Test state is separate from live incident history.
    with tempfile.TemporaryDirectory(prefix="gamestock-monitor-test-") as temporary:
        result = subprocess.run([
            "/usr/bin/python3", "-B", str(script), "--config", str(targets),
            "--webhook-file", str(credential), "--state-file", str(Path(temporary) / "state.json"),
            "--once", "--test-notification",
        ])
        if result.returncode not in (0, 1):
            raise monitor.MonitorError("Notification test failed; the service has not been started or restarted.")
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    subprocess.run(["systemctl", "enable", "gamestock-monitor.service"], check=True)
    subprocess.run(["systemctl", "restart", "gamestock-monitor.service"], check=True)
    subprocess.run(["systemctl", "is-active", "--quiet", "gamestock-monitor.service"], check=True)
    print("GameStock monitor is active. Discord delivery was confirmed.")


if __name__ == "__main__":
    try:
        install()
    except (monitor.MonitorError, OSError, ValueError, subprocess.SubprocessError, getpass.GetPassWarning) as error:
        print(str(error) if isinstance(error, monitor.MonitorError)
              else "Monitor installation failed: " + type(error).__name__, file=sys.stderr)
        raise SystemExit(2)
