"""
RR Locker - Wireless Device Owner Setup (NO USB CABLE)
======================================================
End-to-end provisioning over Wi-Fi only. After a one-time pairing on the
phone (Settings -> Developer options -> Wireless debugging), this tool:

    1. Pairs the PC with the phone over Wi-Fi (adb pair)
    2. Connects (adb connect)
    3. Disables Play Protect package verifier
    4. Installs / re-installs the APK
    5. Verifies no Google/Samsung accounts are present (blocking)
    6. Runs `dpm set-device-owner` and verifies via dumpsys
    7. Launches the RR Locker enrollment screen on the phone

Usage:
    python wireless_owner_setup.py
    python wireless_owner_setup.py --ip 192.168.0.42 --pair-port 41234 --pair-code 123456
    python wireless_owner_setup.py --connect-only --ip 192.168.0.42 --connect-port 5555

Requirements on the phone (one-time, no cable needed after this):
    Settings -> About phone -> tap "Build number" 7 times  (enables developer mode)
    Settings -> Developer options -> turn ON "Wireless debugging"
    Inside Wireless debugging -> "Pair device with pairing code"
        => shows IP:PORT and a 6-digit code  -> feed them to this script
"""

from __future__ import annotations

import argparse
import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path

# Reuse the proven activator helpers
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import activate_device_owner as core  # noqa: E402


PACKAGE = core.PACKAGE
RECEIVER = core.RECEIVER
LAUNCH_ACTIVITY = f"{PACKAGE}/.ui.MainActivity"


# ---------------------------------------------------------------------------
# Wireless ADB plumbing
# ---------------------------------------------------------------------------
def _guess_lan_subnet() -> str | None:
    """Return the /24 prefix of the PC's primary LAN interface (e.g. '192.168.0.')."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        return ip.rsplit(".", 1)[0] + "."
    except OSError:
        return None


def adb_pair(ip: str, port: int, code: str) -> bool:
    core.step("Wireless ADB - Pair")
    core.info(f"Pairing with {ip}:{port}")
    proc = subprocess.run(
        [core.ADB, "pair", f"{ip}:{port}"],
        input=f"{code}\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    output = (proc.stdout + proc.stderr).strip()
    print(output)
    if "Successfully paired" in output or "paired to" in output.lower():
        core.ok("Pairing succeeded.")
        return True
    core.err("Pairing failed. Verify IP, port and 6-digit code shown on the phone.")
    return False


def adb_connect(ip: str, port: int, retries: int = 8) -> bool:
    core.step("Wireless ADB - Connect")
    target = f"{ip}:{port}"
    for attempt in range(1, retries + 1):
        cp = subprocess.run(
            [core.ADB, "connect", target],
            capture_output=True, text=True,
            encoding="utf-8", errors="replace",
            timeout=15,
        )
        out = (cp.stdout + cp.stderr).strip()
        if "connected to" in out.lower() or "already connected" in out.lower():
            core.ok(f"Connected to {target}")
            time.sleep(1)
            return True
        core.warn(f"connect attempt {attempt}/{retries}: {out}")
        time.sleep(2)
    core.err(f"Could not connect to {target}. Ensure the phone is on the same Wi-Fi.")
    return False


def autodiscover_connect_port(ip: str) -> int | None:
    """
    Wireless debugging chooses a random connection port. The phone advertises
    it via mDNS as `_adb-tls-connect._tcp`. If `adb mdns services` is available
    we use it; otherwise we fall back to scanning the well-known range.
    """
    cp = subprocess.run(
        [core.ADB, "mdns", "services"],
        capture_output=True, text=True,
        encoding="utf-8", errors="replace",
        timeout=10,
    )
    for line in cp.stdout.splitlines():
        m = re.search(r"_adb-tls-connect\._tcp\s+([\d\.]+):(\d+)", line)
        if m and m.group(1) == ip:
            return int(m.group(2))

    # Fallback: scan typical wireless-debugging port range
    core.warn("mDNS lookup failed - scanning ports 30000-44999 on the phone (slow)...")
    for port in range(30000, 45000, 1):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(0.05)
                if s.connect_ex((ip, port)) == 0:
                    return port
        except OSError:
            continue
    return None


# ---------------------------------------------------------------------------
# Interactive prompts
# ---------------------------------------------------------------------------
def prompt_int(label: str, default: int | None = None) -> int:
    while True:
        suffix = f" [{default}]" if default is not None else ""
        raw = input(f"  {label}{suffix}: ").strip()
        if not raw and default is not None:
            return default
        if raw.isdigit():
            return int(raw)
        print("    please enter a number")


def prompt_str(label: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default else ""
    raw = input(f"  {label}{suffix}: ").strip()
    return raw or (default or "")


def gather_pairing_info(args) -> tuple[str, int, str]:
    if args.ip and args.pair_port and args.pair_code:
        return args.ip, args.pair_port, args.pair_code

    print()
    core.step("Wireless pairing - one-time setup on phone")
    print("  On the phone:")
    print("    1. Settings -> Developer options -> Wireless debugging  (turn ON)")
    print("    2. Tap 'Pair device with pairing code'")
    print("    3. Read the IP, port and 6-digit code from the dialog")
    print()

    guess = _guess_lan_subnet()
    if guess:
        print(f"  Hint: your PC LAN is on {guess}0/24")
    ip = args.ip or prompt_str("Phone IP (e.g. 192.168.0.42)")
    if not ip:
        core.err("IP required.")
        sys.exit(2)
    port = args.pair_port or prompt_int("Pairing port (5-digit number from phone)")
    code = args.pair_code or prompt_str("6-digit pairing code")
    if not code:
        core.err("Pairing code required.")
        sys.exit(2)
    return ip, port, code


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="Wireless Device Owner setup for RR Locker")
    parser.add_argument("--ip", help="Phone IP on the LAN")
    parser.add_argument("--pair-port", type=int, help="Pairing port shown on phone")
    parser.add_argument("--pair-code", help="6-digit pairing code shown on phone")
    parser.add_argument("--connect-port", type=int, help="Already-known connection port (skip mDNS)")
    parser.add_argument("--connect-only", action="store_true",
                        help="Skip pairing; phone is already paired with this PC.")
    parser.add_argument("--apk", help="Path to app-release.apk (auto-detected if omitted).")
    parser.add_argument("--force-uninstall", action="store_true",
                        help="Uninstall existing app before re-install.")
    parser.add_argument("--skip-install", action="store_true",
                        help="Skip APK install (already on device).")
    parser.add_argument("--no-launch", action="store_true",
                        help="Do not auto-launch the app at the end.")
    args = parser.parse_args()

    core.ADB = core.find_adb()
    core.info(f"Using adb: {core.ADB}")
    subprocess.run([core.ADB, "start-server"], capture_output=True)

    # 1. Pair (unless already paired)
    if args.connect_only:
        if not args.ip:
            core.err("--connect-only requires --ip")
            sys.exit(2)
        ip = args.ip
    else:
        ip, pair_port, code = gather_pairing_info(args)
        if not adb_pair(ip, pair_port, code):
            sys.exit(3)

    # 2. Connect
    conn_port = args.connect_port or autodiscover_connect_port(ip)
    if conn_port is None:
        core.err("Could not discover the wireless-debugging connection port.")
        core.err("Look at 'Wireless debugging' on the phone for 'IP & Port' value and pass it via --connect-port.")
        sys.exit(4)
    if not adb_connect(ip, conn_port):
        sys.exit(5)

    serial = f"{ip}:{conn_port}"
    core.info(f"All subsequent adb commands target {serial}")
    # Patch the adb() helper to always target this serial
    _orig_adb = core.adb

    def _scoped_adb(*a, **kw):
        return _orig_adb("-s", serial, *a, **kw)

    core.adb = _scoped_adb  # type: ignore[assignment]

    # 3..6. Standard provisioning steps (re-uses proven logic)
    core.disable_play_protect()

    if not args.skip_install:
        apk = core.find_apk(args.apk)
        core.install_apk(apk, args.force_uninstall)
    elif not core.app_installed():
        core.err("--skip-install used but app is not installed.")
        sys.exit(6)

    core.preflight_owner_check()
    core.set_device_owner()
    core.verify_and_finalize()

    # 7. Launch enrollment UI on phone
    if not args.no_launch:
        core.step("Launching RR Locker")
        core.adb("shell", "am", "start", "-n", LAUNCH_ACTIVITY)
        core.ok("App launched on phone. Complete enrollment on-screen.")

    print()
    print(f"  Phone is paired at {serial}. To re-run later without re-pairing:")
    print(f"     python wireless_owner_setup.py --connect-only --ip {ip} --connect-port {conn_port}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        core.err("Aborted by user.")
        sys.exit(130)
