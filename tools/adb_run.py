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
    # /data/local/tmp is traversable by apps, so a world-readable file there opens via file:// without
    # needing a URI grant (adb can't grant MediaStore URIs to the app).
    remote = f"/data/local/tmp/{video.name}"
    adb("push", str(video), remote)
    adb("shell", "chmod", "644", remote)
    adb("shell", "rm", "-rf", "/sdcard/Movies/TgPrep")
    adb("shell", "am", "force-stop", "dev.tgprep")
    adb(
        "shell", "am", "start", "-a", "android.intent.action.VIEW", "-t", "video/mp4",
        "-d", f"file://{remote}", "-n", "dev.tgprep/.MainActivity",
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
    for name in adb("shell", "ls", "/sdcard/Movies/TgPrep").split():
        adb("pull", f"/sdcard/Movies/TgPrep/{name}", str(out_dir / name))
        print(f"pulled {out_dir / name}")


if __name__ == "__main__":
    main()
