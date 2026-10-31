"""Generate a portrait HLG BT.2020 10-bit HEVC test clip stored with 90° rotation, like phone video.

Usage: uv run --with numpy tools/make_hlg_test.py OUT.mp4 [--size 2160x3840] [--seconds 2] [--codec hevc|av1]

Use --codec av1 on the Android emulator: its HEVC decoders can't do 10-bit, its AV1 decoder can.

Upright content (portrait) has known HLG patches; see PATCHES. The frame is stored rotated 90°
counter-clockwise with a display matrix telling players to rotate it back, like a phone held upright.
"""

import argparse
import subprocess

import numpy as np

# name -> (x0, y0, x1, y1) as fractions of the upright frame, HLG R'G'B' value.
PATCHES: dict[str, tuple[tuple[float, float, float, float], tuple[float, float, float]]] = {}
for i, v in enumerate(np.linspace(0.0, 1.0, 11)):
    x0, y0 = 0.05 + 0.15 * (i % 6), 0.10 + 0.09 * (i // 6)
    PATCHES[f"gray_{v:.1f}"] = ((x0, y0, x0 + 0.10, y0 + 0.06), (v, v, v))
PATCHES["ref_white_0.75"] = ((0.05, 0.30, 0.45, 0.40), (0.75, 0.75, 0.75))
PATCHES["red"] = ((0.55, 0.30, 0.95, 0.40), (0.75, 0.0, 0.0))
PATCHES["green"] = ((0.05, 0.42, 0.45, 0.52), (0.0, 0.75, 0.0))
PATCHES["blue"] = ((0.55, 0.42, 0.95, 0.52), (0.0, 0.0, 0.75))
PATCHES["skin"] = ((0.05, 0.54, 0.45, 0.64), (0.62, 0.50, 0.43))
PATCHES["sky"] = ((0.55, 0.54, 0.95, 0.64), (0.45, 0.58, 0.72))
# Orientation marker: a white square in the upright top-left corner.
PATCHES["marker_top_left"] = ((0.0, 0.0, 0.06, 0.04), (1.0, 1.0, 1.0))


def upright_frame(w: int, h: int, t: float) -> np.ndarray:
    """Return an upright HLG R'G'B' frame (h, w, 3) in 0..1."""
    img = np.full((h, w, 3), 0.25)
    # Smooth horizontal ramp to reveal banding.
    ramp = np.linspace(0, 1, w)[None, :, None]
    img[int(0.70 * h) : int(0.80 * h)] = ramp
    for (x0, y0, x1, y1), rgb in PATCHES.values():
        img[int(y0 * h) : int(y1 * h), int(x0 * w) : int(x1 * w)] = rgb
    # Moving bar for motion/timestamp sanity.
    x = int((t % 1.0) * (w - w // 20))
    img[int(0.85 * h) : int(0.95 * h), x : x + w // 20] = 0.9
    return img


def to_yuv420p10(rgb: np.ndarray, matrix: str = "bt2020") -> bytes:
    """Convert R'G'B' (0..1) to limited-range 10-bit yuv420p planes with the given matrix."""
    kr, kb = (0.2627, 0.0593) if matrix == "bt2020" else (0.299, 0.114)
    kg = 1 - kr - kb
    y = kr * rgb[..., 0] + kg * rgb[..., 1] + kb * rgb[..., 2]
    cb = (rgb[..., 2] - y) / (2 * (1 - kb))
    cr = (rgb[..., 0] - y) / (2 * (1 - kr))
    yq = np.round(64 + 876 * y)
    cbq = np.round(512 + 896 * cb)
    crq = np.round(512 + 896 * cr)

    def sub(c: np.ndarray) -> np.ndarray:
        return np.round((c[0::2, 0::2] + c[1::2, 0::2] + c[0::2, 1::2] + c[1::2, 1::2]) / 4)

    planes = [yq, sub(cbq), sub(crq)]
    return b"".join(np.clip(p, 0, 1023).astype("<u2").tobytes() for p in planes)


def main() -> None:
    """Write the test clip with ffmpeg/x265 and AAC audio."""
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--size", default="2160x3840", help="upright WxH")
    ap.add_argument("--seconds", type=float, default=2.0)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--codec", choices=["hevc", "av1"], default="hevc")
    ap.add_argument("--bits", type=int, choices=[8, 10], default=10, help="8 = HLG-tagged 8-bit (emulator GPU test)")
    ap.add_argument("--matrix", choices=["bt2020", "bt601"], default="bt2020",
                    help="YUV matrix; bt601 sidesteps GPU drivers that ignore BT.2020 (emulator)")
    ap.add_argument("--transfer", choices=["hlg", "sdr"], default="hlg", help="sdr: tag values as BT.601 SDR")
    args = ap.parse_args()
    w, h = map(int, args.size.split("x"))
    frames = int(args.seconds * args.fps)
    prim = "bt2020" if args.transfer == "hlg" else "smpte170m"
    trc = "arib-std-b67" if args.transfer == "hlg" else "smpte170m"
    space = "bt2020nc" if args.matrix == "bt2020" else "smpte170m"
    av1_ids = {"bt2020": 9, "smpte170m": 6, "arib-std-b67": 18, "bt2020nc": 9}
    # Stored frame is the upright frame rotated 90° counter-clockwise (stored size h x w).
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-display_rotation", "-90", "-noautorotate",
        "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{h}x{w}", "-r", str(args.fps), "-i", "-",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={args.seconds}",
        *(
            ["-c:v", "libx265", "-preset", "fast", "-crf", "16", "-tag:v", "hvc1",
             "-x265-params", f"colorprim={prim}:transfer={trc}:colormatrix={space}:range=limited:log-level=error"]
            if args.codec == "hevc"
            else ["-c:v", "libsvtav1", "-preset", "8", "-crf", "20",
                  "-svtav1-params", f"color-primaries={av1_ids[prim]}:transfer-characteristics={av1_ids[trc]}:matrix-coefficients={av1_ids[space]}:color-range=0"]
        ),
        "-pix_fmt", "yuv420p10le" if args.bits == 10 else "yuv420p",
        "-color_primaries", prim, "-color_trc", trc, "-colorspace", space, "-color_range", "tv",
        "-c:a", "aac", "-b:a", "128k", "-shortest",
        args.out,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i in range(frames):
        stored = np.rot90(upright_frame(w, h, i / args.fps), k=1)  # counter-clockwise
        proc.stdin.write(to_yuv420p10(np.ascontiguousarray(stored), args.matrix))
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit("ffmpeg failed")


if __name__ == "__main__":
    main()
