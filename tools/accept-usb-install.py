#!/usr/bin/env python3
"""Install an APK on a MIUI device, answering the "USB安装提示" dialog automatically.

MIUI gates every `adb install` behind a modal confirmation that auto-dismisses after ten seconds as
"Install canceled by user", which looks exactly like a permissions failure in the adb output. This
drives the dialog over adb so installs work unattended.

Usage:
    python tools/accept-usb-install.py <apk> <serial>
    # Or set ANDROID_SERIAL and omit <serial>.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
CONFIRM_LABEL = "继续安装"
# MIUI shows the dialog only once the APK has finished streaming, so the wait has to cover the
# transfer too — a 90 MB debug build over WiFi takes well past the dialog's own ten-second life.
DIALOG_TIMEOUT_S = 90.0
DUMP_PATH = "/sdcard/whatsbird-ui.xml"


def adb(serial: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def find_confirm_bounds(serial: str) -> tuple[int, int] | None:
    adb(serial, "shell", "uiautomator", "dump", DUMP_PATH)
    xml = adb(serial, "shell", "cat", DUMP_PATH).stdout
    match = re.search(
        re.escape(f'text="{CONFIRM_LABEL}"') + r'.{0,400}?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        xml,
        re.S,
    )
    if not match:
        return None
    left, top, right, bottom = (int(value) for value in match.groups())
    return (left + right) // 2, (top + bottom) // 2


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = os.path.abspath(sys.argv[1])
    serial = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("ANDROID_SERIAL")
    if not serial:
        raise SystemExit("provide a device serial or set ANDROID_SERIAL; see adb devices -l")
    if not os.path.exists(apk):
        raise SystemExit(f"no such apk: {apk}")

    install = subprocess.Popen(
        [ADB, "-s", serial, "install", "-r", "-t", apk],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    deadline = time.time() + DIALOG_TIMEOUT_S
    tapped = False
    while time.time() < deadline and install.poll() is None:
        bounds = find_confirm_bounds(serial)
        if bounds is not None:
            adb(serial, "shell", "input", "tap", str(bounds[0]), str(bounds[1]))
            print(f"accepted the MIUI install dialog at {bounds}")
            tapped = True
            break

    output = install.communicate(timeout=90)[0] or ""
    print(output.strip())
    if install.returncode != 0:
        print("install failed" + ("" if tapped else " (the confirmation dialog never appeared)"))
    return install.returncode


if __name__ == "__main__":
    raise SystemExit(main())
