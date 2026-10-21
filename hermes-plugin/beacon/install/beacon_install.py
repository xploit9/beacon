#!/usr/bin/env python3
"""Install Beacon into one selected Hermes home and profile."""

from __future__ import annotations

import argparse
import ipaddress
import csv
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


PLUGIN = "beacon"
INSTALL_SOURCE = "xploit9/beacon/hermes-plugin/beacon"
PAIR_ROUTE = "/api/plugins/beacon/mobile/bootstrap"
RELEASE_URL = "https://github.com/xploit9/beacon/releases/latest"
PROFILE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")


class InstallError(RuntimeError):
    pass


def _line(ok: bool, message: str) -> None:
    print(f"{'✓' if ok else '✗'} {message}")


def _home_root(value: str | Path) -> Path:
    path = Path(value).expanduser()
    if path.parent.name.lower() == "profiles":
        path = path.parent.parent
    return path.resolve()


def _dashboard_homes() -> list[Path]:
    """Best-effort Linux process-environment discovery; other platforms use known homes."""
    # shortcut: macOS and Windows do not expose another process's HERMES_HOME portably; upgrade if Hermes adds a home-status API.
    if not sys.platform.startswith("linux"):
        return []
    found: list[Path] = []
    try:
        processes = Path("/proc").iterdir()
    except OSError:
        return found
    for process in processes:
        if not process.name.isdigit():
            continue
        try:
            argv = (process / "cmdline").read_bytes().split(b"\0")
            joined = b" ".join(argv).lower()
            if b"hermes" not in joined or b"dashboard" not in joined:
                continue
            for item in (process / "environ").read_bytes().split(b"\0"):
                if item.startswith(b"HERMES_HOME="):
                    home = item.partition(b"=")[2].decode("utf-8", "replace").strip()
                    if home:
                        found.append(_home_root(home))
                    break
        except (OSError, PermissionError, ValueError):
            continue
    return found


def _candidate_homes() -> list[Path]:
    candidates: list[Path] = []
    configured = os.environ.get("HERMES_HOME")
    if configured:
        candidates.append(_home_root(configured))
    default = (Path.home() / ".hermes").resolve()
    if default.is_dir() or not candidates:
        candidates.append(default)
    candidates.extend(_dashboard_homes())
    unique: dict[str, Path] = {}
    for candidate in candidates:
        unique.setdefault(os.path.normcase(str(candidate)), candidate)
    return list(unique.values())


def _profile_names(home: Path) -> list[str]:
    names: list[str] = []
    profiles = home / "profiles"
    try:
        for path in profiles.iterdir():
            if (path.is_dir() and not path.is_symlink() and PROFILE_ID.fullmatch(path.name)
                    and path.name != "default"):
                names.append(path.name)
    except FileNotFoundError:
        pass
    except OSError as exc:
        raise InstallError("Could not read Hermes profiles; no changes were made.") from exc
    return ["default", *sorted(names)]


def _choose(label: str, values: list[Path] | list[str]) -> Path | str:
    if len(values) == 1:
        return values[0]
    print(f"More than one {label} could be the target:")
    for index, value in enumerate(values, 1):
        print(f"  {index}. {value}")
    try:
        choice = input(f"Select {label} (1-{len(values)}): ").strip()
    except (EOFError, KeyboardInterrupt) as exc:
        flag = "--home" if label.startswith("Hermes home") or "home" in label else "--profile"
        raise InstallError(f"No {label} was selected; nothing was changed. Ask which one, then rerun with {flag} <choice>.") from exc
    if not choice.isdigit() or not 1 <= int(choice) <= len(values):
        raise InstallError(f"Invalid {label} selection; nothing was changed.")
    return values[int(choice) - 1]


def _select_home(override: str | None) -> Path:
    if override:
        home = _home_root(override)
        _line(True, f"Using requested Hermes home {home}")
        return home
    candidates = _candidate_homes()
    home = _choose("Hermes home", candidates)
    assert isinstance(home, Path)
    _line(True, f"Selected Hermes home {home}")
    return home


def _select_profile(home: Path, override: str | None) -> str:
    profiles = _profile_names(home)
    if override:
        if override not in profiles:
            raise InstallError(f"Profile '{override}' was not found in the selected Hermes home.")
        profile = override
    else:
        profile = _choose("Hermes profile", profiles)
        assert isinstance(profile, str)
    _line(True, f"Selected Hermes profile {profile}")
    return profile


def _env(home: Path) -> dict[str, str]:
    result = os.environ.copy()
    result["HERMES_HOME"] = str(home)
    return result


def _hermes_argv(hermes: str, profile: str, *args: str) -> list[str]:
    return [hermes, "-p", profile, *args]


def _quiet(hermes: str, home: Path, profile: str, *args: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            _hermes_argv(hermes, profile, *args), env=_env(home), text=True,
            encoding="utf-8", errors="replace", capture_output=True, timeout=30, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise InstallError(f"Hermes {' '.join(args[:2])} could not be queried.") from exc


def _plugin_names(hermes: str, home: Path, profile: str) -> set[str]:
    result = _quiet(hermes, home, profile, "plugins", "list", "--json")
    if result.returncode:
        raise InstallError("Could not list Hermes plugins; nothing was changed.")
    try:
        rows = json.loads(result.stdout)
        if not isinstance(rows, list):
            raise ValueError("expected plugin list")
        names = {row["name"] for row in rows if isinstance(row, dict) and isinstance(row.get("name"), str)}
    except (TypeError, ValueError, KeyError) as exc:
        raise InstallError("Hermes returned an unreadable plugin list; nothing was changed.") from exc
    return names


def _run_mutation(hermes: str, home: Path, profile: str, *args: str) -> None:
    result = _quiet(hermes, home, profile, *args)
    if result.returncode:
        raise InstallError(f"Hermes {' '.join(args[:2])} failed (exit {result.returncode}).")


def _run_stream(hermes: str, home: Path, profile: str, *args: str) -> None:
    try:
        result = subprocess.run(_hermes_argv(hermes, profile, *args), env=_env(home), check=False)
    except OSError as exc:
        raise InstallError(f"Could not start Hermes {' '.join(args[:2])}.") from exc
    if result.returncode:
        raise InstallError(f"Hermes {' '.join(args[:2])} failed (exit {result.returncode}).")


def _public_url(hermes: str, home: Path, profile: str) -> str | None:
    result = _quiet(hermes, home, profile, "config", "get", "dashboard.public_url", "--json")
    if result.returncode:
        raise InstallError("Could not read Hermes' public dashboard URL.")
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise InstallError("Hermes returned an unreadable public dashboard URL.") from exc
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()


def _is_https_url(value: str | None) -> bool:
    if not value:
        return False
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").rstrip(".").lower()
        port = parsed.port
        if (parsed.scheme.lower() != "https" or not host or not parsed.netloc
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment):
            return False
        if port is not None and not 1 <= port <= 65535:
            return False
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            address = None
        numeric_host = re.fullmatch(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*", host) is not None
        if host == "localhost" or host.endswith(".localhost") or numeric_host:
            return False
        if address is not None and (address.is_loopback or address.is_unspecified
                or bool(getattr(address, "ipv4_mapped", None) and address.ipv4_mapped.is_loopback)):
            return False
        return True
    except ValueError:
        return False


def _dashboard_port(hermes: str, home: Path, profile: str) -> int:
    env_value = os.environ.get("HERMES_DASHBOARD_PORT")
    if env_value and env_value.isdigit() and 1 <= int(env_value) <= 65535:
        return int(env_value)
    result = _quiet(hermes, home, profile, "config", "get", "dashboard.port", "--json")
    if result.returncode == 0:
        try:
            value = json.loads(result.stdout)
            if isinstance(value, int) and not isinstance(value, bool) and 1 <= value <= 65535:
                return value
            if isinstance(value, str) and value.isdigit() and 1 <= int(value) <= 65535:
                return int(value)
        except json.JSONDecodeError:
            pass
    return 9119


def _dashboard_running(hermes: str, home: Path, profile: str) -> bool:
    result = _quiet(hermes, home, profile, "dashboard", "--status")
    if result.returncode:
        raise InstallError("Hermes could not report dashboard status.")
    output = result.stdout + "\n" + result.stderr
    if "No hermes dashboard or serve processes running." in output:
        return False
    return "process(es) running:" in output or re.search(r"\bPID\s+\d+", output) is not None


def _route_status(url: str) -> int | None:
    request = Request(url, headers={"Accept": "application/json"})
    try:
        with urlopen(request, timeout=2) as response:
            return response.status
    except HTTPError as exc:
        return exc.code
    except (OSError, URLError, ValueError):
        return None


def _wait_for_beacon_route(port: int, public_url: str | None, process: subprocess.Popen[bytes] | None) -> bool:
    local = f"http://127.0.0.1:{port}{PAIR_ROUTE}"
    public = public_url.rstrip("/") + PAIR_ROUTE if _is_https_url(public_url) else local
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            return False
        if _route_status(local) in (200, 401) or (public != local and _route_status(public) in (200, 401)):
            return True
        time.sleep(1)
    return False


def _windows_dashboard_task() -> str | None:
    """The scheduled task that launches this PC's Hermes dashboard, if one exists."""
    try:
        result = subprocess.run(["schtasks", "/query", "/fo", "csv", "/v", "/nh"], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    for row in csv.reader(result.stdout.splitlines()):
        if len(row) > 8 and "hermes" in row[8].casefold() and "dashboard" in (row[1] + " " + row[8]).casefold():
            return row[1].lstrip("\\")
    return None


def _systemd_dashboard_unit() -> str | None:
    if not shutil.which("systemctl"):
        return None
    result = subprocess.run(["systemctl", "--user", "is-active", "hermes-dashboard"], capture_output=True, text=True, check=False)
    return "hermes-dashboard" if result.stdout.strip() == "active" else None


def _detached(command: list[str], home: Path) -> subprocess.Popen[bytes]:
    """Start the dashboard so it keeps running after this installer (and the agent's shell) exits."""
    logs = home / "logs"
    log = open(logs / "beacon-install-dashboard.log", "ab") if logs.is_dir() else subprocess.DEVNULL
    options: dict = {"env": _env(home), "stdin": subprocess.DEVNULL, "stdout": log, "stderr": subprocess.STDOUT}
    if sys.platform == "win32":
        options["creationflags"] = 0x00000008 | 0x00000200 | 0x08000000  # DETACHED_PROCESS | NEW_PROCESS_GROUP | NO_WINDOW
    else:
        options["start_new_session"] = True
    return subprocess.Popen(command, **options)


def _restart_dashboard(hermes: str, home: Path, profile: str, port: int, public_url: str | None) -> None:
    """Restart the way this dashboard is run: its scheduled task or systemd unit, else a detached relaunch."""
    task = _windows_dashboard_task() if sys.platform == "win32" else None
    unit = None if task else _systemd_dashboard_unit()
    process = None
    if task:
        subprocess.run(["schtasks", "/end", "/tn", task], capture_output=True, check=False)
        _run_mutation(hermes, home, profile, "dashboard", "--stop")
        if subprocess.run(["schtasks", "/run", "/tn", task], capture_output=True, check=False).returncode:
            raise InstallError(f"Could not start the scheduled task {task!r}; start it from Task Scheduler.")
        _line(True, f"Restarted the dashboard through its scheduled task {task!r}")
    elif unit:
        if subprocess.run(["systemctl", "--user", "restart", unit], check=False).returncode:
            raise InstallError(f"systemctl could not restart {unit}.")
        _line(True, f"Restarted the {unit} service")
    else:
        _run_mutation(hermes, home, profile, "dashboard", "--stop")
        try:
            process = _detached(_hermes_argv(hermes, profile, "dashboard", "--no-open", "--port", str(port)), home)
        except OSError as exc:
            raise InstallError("Hermes could not start the selected dashboard.") from exc
        _line(True, "Restarted the dashboard in the background")
    if not _wait_for_beacon_route(port, public_url, process):
        raise InstallError(f"The dashboard did not come back with Beacon within a minute. Start it with: hermes -p {profile} dashboard --port {port}")
    _line(True, "Beacon dashboard route is responding (HTTP 401/200).")


def _yes_no(question: str) -> bool:
    try:
        return input(question).strip().lower() in {"y", "yes"}
    except (EOFError, KeyboardInterrupt):
        return False


def _tailscale_url() -> str | None:
    tailscale = shutil.which("tailscale")
    if not tailscale:
        return None
    try:
        result = subprocess.run([tailscale, "status", "--json"], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=10, check=False)
        if result.returncode:
            return None
        status = json.loads(result.stdout)
        dns_name = status.get("Self", {}).get("DNSName") if isinstance(status, dict) else None
        if not isinstance(dns_name, str) or not dns_name.strip():
            return None
        host = dns_name.strip().rstrip(".")
        return f"https://{host}" if re.fullmatch(r"[A-Za-z0-9.-]+", host) else None
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError, AttributeError):
        return None


def _configure_tailscale(hermes: str, home: Path, profile: str, port: int, allow: bool = False) -> str | None:
    tailscale = shutil.which("tailscale")
    if not tailscale:
        _line(False, "Tailscale CLI was not found; configure Hermes with a public HTTPS dashboard URL to pair.")
        return None
    command = f"tailscale serve {port}"
    print(f"No public HTTPS dashboard URL is configured. Tailscale Serve can publish the dashboard to your tailnet: {command}")
    if not (allow or _yes_no("Run that command and save its HTTPS URL as Hermes dashboard.public_url? [y/N] ")):
        _line(False, "Pairing skipped; configure a public HTTPS dashboard URL to connect a phone.")
        return None
    try:
        result = subprocess.run([tailscale, "serve", str(port)], env=_env(home), check=False)
    except OSError as exc:
        raise InstallError("Could not start Tailscale Serve.") from exc
    if result.returncode:
        raise InstallError(f"Tailscale Serve failed (exit {result.returncode}).")
    url = _tailscale_url()
    if not url:
        _line(False, "Tailscale Serve started, but its HTTPS hostname could not be read; set dashboard.public_url manually.")
        return None
    _run_mutation(hermes, home, profile, "config", "set", "dashboard.public_url", url)
    saved = _public_url(hermes, home, profile)
    if not _is_https_url(saved):
        raise InstallError("Hermes did not retain the Tailscale HTTPS dashboard URL.")
    _line(True, "Configured the Hermes public HTTPS dashboard URL for tailnet pairing.")
    return saved


def _pair(hermes: str, home: Path, profile: str, name: str | None = None) -> None:
    if name is None:
        try:
            name = input("Phone name [My phone]: ").strip() or "My phone"
        except (EOFError, KeyboardInterrupt):
            name = "My phone"
    if len(name) > 80 or any(ord(char) < 32 or ord(char) == 127 for char in name):
        raise InstallError("Invalid phone name; pairing was not started.")
    result = _quiet(hermes, home, profile, "beacon", "pair", "--name", name)
    if result.returncode:
        raise InstallError("Hermes could not create a Beacon pairing block.")
    lines = result.stdout.splitlines()
    block = [line for line in lines if line.startswith("Dashboard: ") or line.startswith("Pairing code: ") or line.startswith("expires in ")]
    if len(block) != 3:
        raise InstallError("Hermes returned an unexpected pairing block; raw output was hidden.")
    print("\n".join(block))
    _line(True, "Beacon phone pairing is ready.")


def _dry_run_summary(beacon_installed: bool, running: bool, public_url: str | None, port: int) -> None:
    action = "update Beacon" if beacon_installed else "install Beacon"
    print(f"✓ Dry run: would {action} and pair a phone.")
    print(f"✓ Dashboard running: {'yes (restart would require approval)' if running else 'no'}")
    if _is_https_url(public_url):
        _line(True, "Public HTTPS dashboard URL is configured")
    else:
        _line(True, f"Would offer Tailscale Serve for port {port} and ask before running it")
    _line(True, "Dry run made no changes.")


def _install(args: argparse.Namespace) -> int:
    hermes = shutil.which("hermes")
    if not hermes:
        raise InstallError("Hermes CLI was not found on PATH; nothing was changed.")
    _line(True, "Hermes CLI found")
    home = _select_home(args.home)
    profile = _select_profile(home, args.profile)
    beacon_installed = PLUGIN in _plugin_names(hermes, home, profile)

    running = _dashboard_running(hermes, home, profile)

    if args.dry_run:
        _dry_run_summary(beacon_installed, running, _public_url(hermes, home, profile), _dashboard_port(hermes, home, profile))
        return 0

    if beacon_installed:
        _line(True, "Updating Beacon; Hermes installer output follows")
        _run_stream(hermes, home, profile, "plugins", "update", PLUGIN)
    else:
        _line(True, "Installing Beacon; Hermes security scan and prompts follow")
        _run_stream(hermes, home, profile, "plugins", "install", INSTALL_SOURCE, "--enable")
    _line(True, "Beacon plugin installation completed")

    if running:
        print(f"Restart commands for this Hermes profile:\n  hermes -p {profile} dashboard --stop\n  hermes -p {profile} dashboard --no-open")
        restart = args.yes or _yes_no("Restart the dashboard now? [y/N] (an agent: ask the owner, then rerun with --yes) ")
        if restart:
            public_url = _public_url(hermes, home, profile)
            _restart_dashboard(hermes, home, profile, _dashboard_port(hermes, home, profile), public_url)
        else:
            _line(True, "Dashboard restart was declined")
    else:
        _line(True, "Selected Hermes dashboard is not running; restart skipped")

    public_url = _public_url(hermes, home, profile)
    if not _is_https_url(public_url):
        public_url = _configure_tailscale(hermes, home, profile, _dashboard_port(hermes, home, profile), args.tailscale)
    pairing_complete = False
    if _is_https_url(public_url):
        _pair(hermes, home, profile, args.phone_name)
        pairing_complete = True
    else:
        _line(False, "Pairing is not complete until Hermes has a public HTTPS dashboard URL.")
    _line(True, f"Install the Beacon app from {RELEASE_URL}")
    return 0 if pairing_complete else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Safely install Beacon into a Hermes profile.")
    parser.add_argument("--home", help="Hermes home to use (skips home selection)")
    parser.add_argument("--profile", help="Hermes profile to use (skips profile selection)")
    parser.add_argument("--yes", action="store_true", help="Answer yes to the dashboard restart prompt only")
    parser.add_argument("--dry-run", action="store_true", help="Show the plan without changing Hermes or the network")
    parser.add_argument("--phone-name", help="Name for the phone in the device list (default: My phone)")
    parser.add_argument("--tailscale", action="store_true", help="Allow Tailscale Serve to publish the dashboard over HTTPS when no HTTPS URL exists")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    try:
        return _install(args)
    except (InstallError, OSError, KeyboardInterrupt) as exc:
        if isinstance(exc, OSError):
            _line(False, "Installer stopped on an operating-system error; review Hermes state before retrying.")
            return 1
        _line(False, str(exc) if isinstance(exc, InstallError) else "Cancelled; review the current Hermes state before retrying.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
