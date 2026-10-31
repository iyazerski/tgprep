"""Drive TG Prep over adb: open a local video in it, tap Optimize, save the result, pull it.

Usage: uv run tools/adb_run.py VIDEO.mp4 OUT_DIR
Works on an emulator or a USB-connected phone (screen unlocked).
"""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")


def adb(*args: str) -> str:
    """Run an adb command and return stdout."""
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True).stdout


def ui_nodes() -> list[ET.Element]:
    """Return all nodes of the current UI hierarchy."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml")).iter("node"))


def find_text(pattern: str) -> ET.Element | None:
    """Return the first node whose text matches the regex."""
    return next((n for n in ui_nodes() if re.search(pattern, n.get("text", ""))), None)


def tap(node: ET.Element) -> None:
    """Tap the center of a node."""
    x0, y0, x1, y1 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    adb("shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))


def wait_for(pattern: str, timeout: float = 300) -> ET.Element:
    """Poll the UI until a node's text matches the regex."""
    end = time.time() + timeout
    while time.time() < end:
        if (node := find_text(pattern)) is not None:
            return node
        time.sleep(1.5)
    raise SystemExit(f"timeout waiting for {pattern!r}")


def main() -> None:
    """Run one optimization and pull the result."""
    video, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    # Open the clip like the gallery does: a MediaStore content:// URI with a read grant. File paths behave
    # differently (SELinux blocks the media server, and MediaProvider's location redaction is skipped).
    remote = f"/sdcard/DCIM/TgPrepTest/{video.name}"
    adb("push", str(video), remote)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{remote}")
    uri = None
    for _ in range(20):
        rows = adb("shell", "content", "query", "--uri", "content://media/external/video/media", "--projection",
                   "_id", "--where", f"\"relative_path='DCIM/TgPrepTest/' AND _display_name='{video.name}'\"")
        if m := re.search(r"_id=(\d+)", rows):
            uri = f"content://media/external/video/media/{m.group(1)}"
            break
        time.sleep(0.5)
    if uri is None:
        raise SystemExit(f"MediaStore did not index {remote}")
    adb("shell", "am", "force-stop", "dev.tgprep")
    adb(
        "shell", "am", "start", "-a", "android.intent.action.VIEW", "-t", "video/mp4",
        "-d", uri, "--grant-read-uri-permission", "-n", "dev.tgprep/.MainActivity",
    )
    tap(wait_for(r"^Optimize for Telegram$", 30))
    t0 = time.time()
    done = wait_for(r"^(Done|Failed)")
    print(f"finished in ~{time.time() - t0:.0f}s: {done.get('text')!r}")
    details = max((n.get("text", "") for n in ui_nodes()), key=len)
    print(details)
    if done.get("text", "").startswith("Failed"):
        raise SystemExit(1)
    tap(wait_for(r"^Save copy to gallery$", 10))
    time.sleep(3)
    # The gallery copy lands in the camera folder; take the newest one (never delete the user's files).
    newest = adb("shell", "ls -t /sdcard/DCIM/Camera/*_TG*.mp4 | head -n 1").strip()
    if not newest:
        raise SystemExit("no *_TG*.mp4 in /sdcard/DCIM/Camera")
    target = out_dir / Path(newest).name.replace(" ", "_")
    adb("pull", newest, str(target))
    print(f"pulled {target}")


if __name__ == "__main__":
    main()
