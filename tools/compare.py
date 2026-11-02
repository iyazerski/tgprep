# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Compare the original recording, the TG Prep output, and the file received through Telegram.

Usage: uv run tools/compare.py ORIGINAL.mp4 OPTIMIZED.mp4 [RECEIVED.mp4] [--out DIR]

Prints a metadata table, tells whether Telegram re-encoded the video (packet hashes), measures
SSIM/PSNR of the received file against the optimized one, and writes side-by-side frames to DIR.
"""

import argparse
import json
import re
import subprocess
from pathlib import Path


def run(*cmd: str) -> str:
    """Run a command and return stdout."""
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def info(path: str) -> dict[str, str]:
    """Return the key facts of a video file."""
    data = json.loads(run("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", path))
    v = next(s for s in data["streams"] if s["codec_type"] == "video")
    a = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)
    side = v.get("side_data_list", [])
    rotation = next((d.get("rotation") for d in side if "rotation" in d), 0)
    dv = next((d for d in side if d.get("side_data_type") == "DOVI configuration record"), None)
    size = int(data["format"]["size"])
    duration = float(data["format"]["duration"])
    return {
        "size": f"{size / 1e6:.1f} MB",
        "duration": f"{duration:.2f} s",
        "bitrate": f"{size * 8 / duration / 1e6:.1f} Mbps",
        "video": f"{v['codec_name']} {v.get('profile', '')} L{v.get('level', '?')}",
        "resolution": f"{v['width']}x{v['height']}",
        "rotation": str(rotation),
        "fps": v.get("avg_frame_rate", "?"),
        "pixel format": v.get("pix_fmt", "?"),
        "color": f"{v.get('color_primaries', '?')}/{v.get('color_transfer', '?')}/{v.get('color_space', '?')} {v.get('color_range', '')}",
        "dolby vision": f"profile {dv['dv_profile']}.{dv['dv_bl_signal_compatibility_id']}" if dv else "-",
        "audio": f"{a['codec_name']} {a.get('sample_rate', '')} Hz {a.get('channels', '')}ch" if a else "-",
    }


def packet_hash(path: str) -> str:
    """Return an MD5 over the video bitstream, independent of the container."""
    out = run("ffmpeg", "-v", "error", "-i", path, "-map", "0:v:0", "-c", "copy", "-f", "hash", "-hash", "md5", "-")
    return out.strip()


def quality(reference: str, test: str) -> str:
    """Return SSIM and PSNR of test against reference (test scaled to the reference size)."""
    ref = info(reference)["resolution"].replace("x", ":")
    log = subprocess.run(
        [
            "ffmpeg",
            "-v",
            "info",
            "-i",
            reference,
            "-i",
            test,
            "-lavfi",
            f"[1:v]scale={ref}:flags=bicubic,split[t1][t2];[0:v]split[r1][r2];[r1][t1]ssim;[r2][t2]psnr",
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        text=True,
    ).stderr
    ssim = re.search(r"SSIM .*All:([\d.]+)", log)
    psnr = re.search(r"PSNR .*average:([\d.inf]+)", log)
    return f"SSIM {ssim.group(1) if ssim else '?'} · PSNR {psnr.group(1) if psnr else '?'} dB"


def has_filter(name: str) -> bool:
    """Return whether this ffmpeg build has the given filter."""
    return re.search(rf"^\s*\S+\s+{name}\s", run("ffmpeg", "-hide_banner", "-filters"), re.M) is not None


def frames(path: str, out: Path, label: str, times: list[float], hdr_tonemap: bool) -> None:
    """Write PNG frames at the given times; tone-map HDR with libplacebo (BT.2390) when asked."""
    vf = (
        "libplacebo=tonemapping=bt.2390:colorspace=bt709:color_primaries=bt709:color_trc=bt709:format=yuv420p"
        if hdr_tonemap
        else "null"
    )
    for t in times:
        run(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-ss",
            f"{t:.2f}",
            "-i",
            path,
            "-frames:v",
            "1",
            "-vf",
            vf,
            str(out / f"t{t:06.2f}_{label}.png"),
        )


def main() -> None:
    """Print the comparison and write frames."""
    ap = argparse.ArgumentParser()
    ap.add_argument("original")
    ap.add_argument("optimized")
    ap.add_argument("received", nargs="?")
    ap.add_argument("--out", default="compare_out")
    args = ap.parse_args()

    files = {"original": args.original, "optimized": args.optimized}
    if args.received:
        files["received"] = args.received
    infos = {k: info(v) for k, v in files.items()}
    keys = list(infos["original"])
    print(f"{'':14}" + "".join(f"{k:>34}" for k in files))
    for key in keys:
        print(f"{key:14}" + "".join(f"{infos[k][key]:>34}" for k in files))

    if args.received:
        same = packet_hash(args.optimized) == packet_hash(args.received)
        print(f"\nTelegram kept the video bitstream untouched: {'YES' if same else 'NO (re-encoded)'}")
        print(f"Received vs optimized: {quality(args.optimized, args.received)}")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    duration = float(infos["optimized"]["duration"].split()[0])
    times = [duration * f for f in (0.2, 0.5, 0.8)]
    tonemap = infos["original"]["color"].split("/")[1] in ("arib-std-b67", "smpte2084") and has_filter("libplacebo")
    frames(args.original, out, "1_original" + ("_libplacebo" if tonemap else "_raw"), times, tonemap)
    frames(args.optimized, out, "2_optimized", times, False)
    if args.received:
        frames(args.received, out, "3_received", times, False)
    print(
        f"\nFrames written to {out}/ (original {'tone-mapped with libplacebo BT.2390' if tonemap else 'shown raw; HDR will look flat'})"
    )


if __name__ == "__main__":
    main()
