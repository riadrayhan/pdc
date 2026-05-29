"""
RR Locker - Device Owner Activator
==================================
Robust one-shot script to set the EMILocker app as Device Owner.

The pure-batch script is unreliable because `adb shell` always returns
exit-code 0 even when the command inside fails. This script parses the
actual stdout/stderr of every adb call and tells you exactly what went
wrong (accounts present, app not installed, already provisioned, etc.)
and then verifies the result via `dumpsys device_policy`.

Usage (from this folder):
    python activate_device_owner.py
    python activate_device_owner.py --apk path\to\app-release.apk
    python activate_device_owner.py --force-uninstall   (re-install fresh)
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.riad.rrlkr"
RECEIVER = f"{PACKAGE}/.admin.EMIDeviceAdminReceiver"

HERE = Path(__file__).resolve().parent
DEFAULT_APK_CANDIDATES = [
    HERE / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk",
    HERE / "app-release.apk",
    HERE / "app" / "release" / "app-release.apk",
]


# ---------------------------------------------------------------------------
# Small console helpers
# ---------------------------------------------------------------------------
def _c(code: str, text: str) -> str:
    if os.name == "nt":
        os.system("")  # enable VT on Windows
    return f"\033[{code}m{text}\033[0m"


def info(msg: str) -> None:
    print(_c("36", "[INFO]"), msg)


def ok(msg: str) -> None:
    print(_c("32", "[ OK ]"), msg)


def warn(msg: str) -> None:
    print(_c("33", "[WARN]"), msg)


def err(msg: str) -> None:
    print(_c("31", "[FAIL]"), msg)


def step(title: str) -> None:
    print()
    print(_c("1;35", "=" * 60))
    print(_c("1;35", f"  {title}"))
    print(_c("1;35", "=" * 60))


# ---------------------------------------------------------------------------
# ADB locator + runner
# ---------------------------------------------------------------------------
def find_adb() -> str:
    exe = shutil.which("adb")
    if exe:
        return exe
    candidates = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / "adb.exe",
        Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools" / "adb.exe",
        HERE / "platform-tools" / "adb.exe",
        HERE / "adb.exe",
        Path("C:/platform-tools/adb.exe"),
    ]
    for c in candidates:
        if c and c.exists():
            return str(c)
    err("adb not found. Install Android platform-tools and add to PATH:")
    err("  https://developer.android.com/tools/releases/platform-tools")
    sys.exit(2)


ADB = ""  # set in main()


def adb(*args: str, check: bool = False, timeout: int = 60) -> subprocess.CompletedProcess:
    cmd = [ADB, *args]
    try:
        cp = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        err(f"adb timeout: {' '.join(args)}")
        if check:
            sys.exit(3)
        return subprocess.CompletedProcess(cmd, 124, "", "timeout")
    if check and cp.returncode != 0:
        err(f"adb failed: {' '.join(args)}\n{cp.stderr.strip()}")
        sys.exit(cp.returncode)
    return cp


def adb_shell(cmd: str, timeout: int = 60) -> tuple[str, str]:
    """Run `adb shell <cmd>` and return (stdout, stderr) trimmed."""
    cp = adb("shell", cmd, timeout=timeout)
    return cp.stdout.strip(), cp.stderr.strip()


# ---------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------
def wait_for_device() -> str:
    step("Step 1/6 — Detect device")
    adb("start-server")
    for attempt in range(1, 31):
        cp = adb("devices")
        lines = [ln.strip() for ln in cp.stdout.splitlines() if ln.strip()]
        # skip header "List of devices attached"
        rows = [ln for ln in lines[1:] if "\t" in ln]
        if not rows:
            warn(f"No device detected (try {attempt}/30). Plug USB, enable USB-debugging.")
            time.sleep(2)
            continue
        unauthorized = [r for r in rows if r.endswith("\tunauthorized")]
        if unauthorized:
            warn("Device is UNAUTHORIZED. Accept the RSA fingerprint prompt on the phone.")
            time.sleep(3)
            continue
        ready = [r for r in rows if r.endswith("\tdevice")]
        if ready:
            serial = ready[0].split("\t", 1)[0]
            ok(f"Device ready: {serial}")
            return serial
        warn(f"Unexpected state: {rows}")
        time.sleep(2)
    err("Could not detect an authorized device after 60s.")
    sys.exit(4)


def disable_play_protect() -> None:
    step("Step 2/6 — Disable Play Protect / package verifier")
    cmds = [
        "settings put global package_verifier_enable 0",
        "settings put global verifier_verify_adb_installs 0",
        "settings put global upload_apk_enable 0",
        "settings put secure package_verifier_user_consent -1",
        "settings put global package_verifier_state 0",
    ]
    for c in cmds:
        adb_shell(c)
    ok("Package verifier disabled (best-effort).")


def find_apk(explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit).expanduser().resolve()
        if not p.exists():
            err(f"APK not found at {p}")
            sys.exit(5)
        return p
    for c in DEFAULT_APK_CANDIDATES:
        if c.exists():
            return c
    err("app-release.apk not found. Build the APK or pass --apk PATH.")
    err("Looked in:")
    for c in DEFAULT_APK_CANDIDATES:
        err(f"  - {c}")
    sys.exit(5)


def app_installed() -> bool:
    out, _ = adb_shell(f"pm list packages {PACKAGE}")
    return f"package:{PACKAGE}" in out


def install_apk(apk: Path, force_uninstall: bool) -> None:
    step("Step 3/6 — Install RR Locker APK")
    if force_uninstall and app_installed():
        warn("Force-uninstall requested — removing existing install.")
        adb("uninstall", PACKAGE)

    if app_installed():
        ok(f"{PACKAGE} already installed — skipping install.")
        return

    info(f"Installing {apk}")
    cp = adb("install", "-r", "-g", str(apk), timeout=180)
    out = (cp.stdout + cp.stderr).strip()
    if "Success" in out:
        ok("APK installed.")
        return
    warn(f"First install attempt failed:\n{out}")
    info("Retrying with -d (allow downgrade)...")
    cp = adb("install", "-r", "-d", "-g", str(apk), timeout=180)
    out = (cp.stdout + cp.stderr).strip()
    if "Success" in out:
        ok("APK installed (with -d).")
        return
    err("APK install failed. Output:\n" + out)
    sys.exit(6)


def list_accounts() -> list[str]:
    out, _ = adb_shell("dumpsys account")
    # Lines like:  Account {name=foo@gmail.com, type=com.google}
    return re.findall(r"Account \{name=([^,}]+)", out)


def current_device_owner() -> str | None:
    out, _ = adb_shell("dumpsys device_policy")
    m = re.search(r"Device Owner:\s*[\r\n].*?name=.*?package=([^\s,}]+)", out, re.S)
    if m:
        return m.group(1)
    m = re.search(r"Device Owner.*?ComponentInfo\{([^/]+)/", out, re.S)
    if m:
        return m.group(1)
    return None


def preflight_owner_check() -> None:
    step("Step 4/6 — Pre-flight checks")
    existing = current_device_owner()
    if existing:
        if existing == PACKAGE:
            ok(f"Device Owner is ALREADY set to {PACKAGE}. Nothing to do.")
            sys.exit(0)
        err(f"Another app is already Device Owner: {existing}")
        err("A device can only have ONE device owner. Factory-reset required to change it.")
        sys.exit(7)

    accounts = list_accounts()
    if accounts:
        err("Device has user accounts — `dpm set-device-owner` WILL fail.")
        err("Remove these accounts on the phone first:")
        for a in accounts:
            err(f"  - {a}")
        err("Settings → Accounts → tap each account → Remove account, then re-run.")
        sys.exit(8)

    if not app_installed():
        err(f"{PACKAGE} is not installed on the device.")
        sys.exit(9)

    ok("No accounts, no existing owner, app installed — ready to provision.")


def set_device_owner() -> None:
    step("Step 5/6 — Set Device Owner")
    out, errout = adb_shell(f"dpm set-device-owner {RECEIVER}")
    combined = (out + "\n" + errout).strip()
    print(combined)
    success_markers = ("Success:", "Active admin set to", "set as device owner")
    if any(m.lower() in combined.lower() for m in success_markers):
        ok("dpm reported success.")
        return
    # Fallback to verification via dumpsys
    if current_device_owner() == PACKAGE:
        ok("Device Owner verified via dumpsys.")
        return

    err("Failed to set Device Owner. Common causes:")
    lc = combined.lower()
    if "account" in lc:
        err("  → Accounts still present. Remove all Google/Samsung/etc. accounts.")
    if "already" in lc and "provision" in lc:
        err("  → Device already provisioned (user setup completed with accounts).")
        err("    Factory reset and run this BEFORE adding any account.")
    if "not found" in lc or "unknown admin" in lc:
        err("  → Receiver not recognized. Re-install APK and retry.")
    if "user 0" in lc or "secondary user" in lc:
        err("  → Not on primary user (user 0). Switch to owner profile.")
    sys.exit(10)


def verify_and_finalize() -> None:
    step("Step 6/6 — Verify")
    owner = current_device_owner()
    if owner != PACKAGE:
        err(f"Verification failed. dumpsys reports owner={owner!r}")
        sys.exit(11)
    ok(f"Device Owner confirmed: {PACKAGE}")
    # re-apply verifier-off post-provisioning
    for c in [
        "settings put global package_verifier_enable 0",
        "settings put global verifier_verify_adb_installs 0",
        "settings put secure package_verifier_user_consent -1",
    ]:
        adb_shell(c)
    print()
    print(_c("1;32", "╔════════════════════════════════════════════════════╗"))
    print(_c("1;32", "║          DEVICE OWNER SETUP COMPLETE              ║"))
    print(_c("1;32", "║                                                    ║"))
    print(_c("1;32", f"║   Package : {PACKAGE:<38}║"))
    print(_c("1;32", "║   Status  : ACTIVE                                ║"))
    print(_c("1;32", "║                                                    ║"))
    print(_c("1;32", "║   Open RR Locker on the phone to enroll.          ║"))
    print(_c("1;32", "╚════════════════════════════════════════════════════╝"))


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main() -> None:
    global ADB
    parser = argparse.ArgumentParser(description="Activate RR Locker as Device Owner")
    parser.add_argument("--apk", help="Path to app-release.apk", default=None)
    parser.add_argument(
        "--force-uninstall",
        action="store_true",
        help="Uninstall existing app before installing (clean state).",
    )
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="Assume APK is already installed; just provision.",
    )
    args = parser.parse_args()

    ADB = find_adb()
    info(f"Using adb: {ADB}")

    wait_for_device()
    disable_play_protect()

    if not args.skip_install:
        apk = find_apk(args.apk)
        install_apk(apk, args.force_uninstall)
    elif not app_installed():
        err("--skip-install used but app is not installed.")
        sys.exit(5)

    preflight_owner_check()
    set_device_owner()
    verify_and_finalize()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        err("Aborted by user.")
        sys.exit(130)
