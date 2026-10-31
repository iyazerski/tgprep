"""Check a TG Prep output made from a make_hlg_test.py clip.

Usage: uv run --with numpy tools/check_output.py OUTPUT.mp4

Verifies Telegram passthrough requirements (H.264, no rotation, long side <= 1920, BT.709 tags) and
compares measured patch colors with the reference tone mapping in tonemap_ref.py.
"""

import json
import subprocess
import sys

import numpy as np

from make_hlg_test import PATCHES
from tonemap_ref import hlg_to_sdr_code

TOLERANCE = 6.0  # 8-bit codes; covers encoder quantization, chroma subsampling and dither


def probe(path: str) -> dict:
    """Return ffprobe JSON for the first video stream."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_streams", "-of", "json", path],
        check=True, capture_output=True, text=True,
    ).stdout
    return json.loads(out)["streams"][0]


def first_frame_rgb(path: str, w: int, h: int) -> np.ndarray:
    """Decode the first frame as full-range R'G'B' using the BT.709 matrix (as players do)."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-frames:v", "1",
         "-vf", "scale=in_color_matrix=bt709:in_range=tv:out_range=pc,format=rgb48le", "-f", "rawvideo", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<u2").reshape(h, w, 3).astype(np.float64) / 257.0


def main() -> None:
    """Print a pass/fail table and exit non-zero on failure."""
    path = sys.argv[1]
    s = probe(path)
    w, h = s["width"], s["height"]
    rotation = next((d.get("rotation", 0) for d in s.get("side_data_list", []) if "rotation" in d), 0)
    checks = {
        "codec is h264": s["codec_name"] == "h264",
        "no rotation metadata": rotation == 0,
        "long side <= 1920": max(w, h) <= 1920,
        "8-bit yuv420p": s.get("pix_fmt") == "yuv420p",
        "tagged bt709": s.get("color_primaries") == "bt709" and s.get("color_transfer") == "bt709",
    }
    for name, ok in checks.items():
        print(f"{'PASS' if ok else 'FAIL'}  {name}  ({s['codec_name']} {w}x{h} rot={rotation} {s.get('pix_fmt')} "
              f"{s.get('color_primaries')}/{s.get('color_transfer')}/{s.get('color_space')})" if name == "codec is h264" else f"{'PASS' if ok else 'FAIL'}  {name}")

    img = first_frame_rgb(path, w, h)
    worst = 0.0
    print(f"\n{'patch':18} {'expected':>18} {'measured':>18} {'max err':>8}")
    for name, ((x0, y0, x1, y1), hlg) in PATCHES.items():
        # Measure the inner half of the patch to avoid edges.
        cx0, cx1 = x0 + (x1 - x0) / 4, x1 - (x1 - x0) / 4
        cy0, cy1 = y0 + (y1 - y0) / 4, y1 - (y1 - y0) / 4
        region = img[int(cy0 * h) : max(int(cy1 * h), int(cy0 * h) + 1), int(cx0 * w) : max(int(cx1 * w), int(cx0 * w) + 1)]
        measured = region.reshape(-1, 3).mean(axis=0)
        expected = hlg_to_sdr_code(np.array(hlg))
        err = float(np.abs(measured - expected).max())
        if not name.startswith("marker"):
            worst = max(worst, err)
        print(f"{name:18} {np.array2string(expected, precision=0):>18} {np.array2string(measured, precision=0):>18} {err:8.1f}")
    checks["patch colors within tolerance"] = worst <= TOLERANCE
    checks["orientation marker is top-left"] = bool(img[: max(1, int(0.02 * h)), : max(1, int(0.03 * w))].mean() > 230)
    print(f"\n{'PASS' if checks['patch colors within tolerance'] else 'FAIL'}  patch colors (worst {worst:.1f}, tolerance {TOLERANCE})")
    print(f"{'PASS' if checks['orientation marker is top-left'] else 'FAIL'}  orientation marker is top-left")
    sys.exit(0 if all(checks.values()) else 1)


if __name__ == "__main__":
    main()
