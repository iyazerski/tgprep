# TG Prep

A tiny private Android app (~80 KB, no network, no dependencies). It takes a Dolby Vision / HDR
video from the camera and makes a file that Telegram sends **as is**, so the colors and brightness
that other people see are the ones this app produced.

**Download the latest APK:** https://github.com/iyazerski/tgprep/releases/latest/download/tgprep.apk

Flow: Gallery → Share → **TG Prep** → **Optimize for Telegram** → **Send to Telegram** → in Telegram,
pick the top quality (1080p) → send.

## Why HDR videos look wrong in Telegram

Findings from the Telegram Android source (`DrKLO/Telegram`, `SendMessagesHelper`,
`MediaController`, `MediaCodecVideoConvertor`, `gifvideo.cpp`):

1. **Telegram re-encodes almost every video.** It sends the original file untouched only when all
   of these are true: the video is **H.264**, the **rotation is 0**, the user picked the **top
   quality preset** (the resolution stays the same, and that preset only exists for videos whose
   long side is ≤ 1920 px), there are no edits, and the audio is a supported codec (AAC is).
   The editor never preselects the top preset for videos above 1280 px: its default is capped
   at the 3rd step (720p), even when you chose a higher one before. So you have to pick 1080p
   by hand every time.
   Camera DV clips are HEVC 10-bit, usually 4K, and stored with rotation metadata, so they fail
   three of these checks.
2. **Its re-encode is low quality.** The bitrate caps are about 6.8 Mbps at 1080p, 2.6 Mbps at
   720p, and 1 Mbps at 480p.
3. **Its HDR→SDR conversion is basic.** When it detects HLG/PQ BT.2020, it tone-maps in a
   shader (BT.2446 method A with a fixed 1000-nit HDR peak). It doesn't parse the Dolby Vision
   RPU itself. It decodes with whatever decoder the device picks for the file, so the colors it
   gets depend on how that vendor decoder handles DV. Then it compresses again at the low
   bitrates above. The usual result: flat or shifted colors, clipped highlights, and blocky
   pixels. (I haven't measured this on the Find X9 Pro yet. The baseline test below covers it.)
4. **HDR can't reliably survive.** HEVC is always re-encoded, so it is always tone-mapped by
   Telegram. Untouched passthrough needs H.264, and HDR H.264 means 10-bit High10 HLG/PQ.
   Most receiving phones can't decode that in hardware, and Telegram doesn't treat such files
   as HDR on every platform. Sending SDR that was tone-mapped well once is the only result that
   looks the same for everyone.

**Decision:** convert once, with good tone mapping and enough bitrate, into a file that passes
every passthrough check: H.264 8-bit SDR BT.709, rotation applied to the pixels, long side ≤ 1920,
AAC audio copied as is. When you pick the top quality, Telegram uploads these bytes unchanged.
The comparison tool below confirms this by matching the bitstream hash.

**Why not a 720p file:** Telegram's quality picker never preselects the top option. It reuses
the quality you last picked for that size class, or picks from your network settings. In a real
test, a 720p file sent at the default quality was re-encoded to 360x640 at about 1 Mbps. So the
app makes 1080p and you pick 1080p by hand.

## Options considered

| Option | Verdict |
|---|---|
| Send the original HEVC DV | Telegram re-encodes: its tone map, 720p preselected, low bitrate. This is the problem itself. |
| Keep HDR (H.264 High10 HLG) | Unreliable hardware decode on receivers and inconsistent HDR handling in Telegram clients. Rejected. |
| Media3 Transformer | Works, but brings AndroidX plus ~1-2 MB, and its tone mapping/decoder choice is harder to control for DV 8.4 on ColorOS. Rejected for size and control. |
| FFmpeg (libplacebo/zscale) in the APK | Best tone mapping, but 20-40 MB of native code, slow software decode of 4K 10-bit, and battery drain. Rejected. |
| **MediaCodec + GLES shader (chosen)** | Hardware decode and encode, my own tone mapping, framework APIs only, ~80 KB APK. |

## How it works

- **Probe** (`Optimizer.probe`): rotation, fps, HDR transfer, Dolby Vision profile and
  compatibility ID (from the `dvcC`/`dvvC` box in `csd-2`), HDR static metadata (peak nits).
- **Decode:** for DV profile 8.x/9, the app decodes the **base layer** with the normal HEVC/AVC
  decoder. That gives the HLG (8.4) or HDR10 (8.1) signal without any vendor DV processing.
  If that fails, it falls back to the DV decoder with `KEY_COLOR_TRANSFER_REQUEST = HLG`.
  Decoders are ranked 10-bit-capable → hardware → platform order. If a decoder fails before
  the first frame, the app tries the next one.
- **Frames from the decoder** come as CPU-visible YUV images (P010 for 10-bit HDR when the
  decoder offers it, else 8-bit YUV 4:2:0), not through a Surface. Every decoded frame is
  processed, in order, so none can be dropped. The Surface/SurfaceTexture path used before
  could drop frames, which made 60 fps output stutter.
- **Render** (`Gl.kt`, GLES3):
  - The Y, U, and V planes are uploaded as plain byte textures. The shader rebuilds the exact
    8- or 10-bit code values and does YUV→RGB with the BT.2020/709/601 matrix and range from
    the file. Earlier versions let the GPU driver do this. On the Find X9 Pro (MediaTek) that
    gave a green image with `GL_EXT_YUV_target` and a pink tint on whites with the driver's
    own conversion.
  - Applies the rotation and downscales (with 4 taps when shrinking a lot).
  - HLG (phone cameras): scene-light conversion. Inverse OETF to scene light, scaled so HLG
    reference white (75% signal) lands at 62% linear, then a smooth exponential shoulder above
    55% for highlights. An earlier display-light mapping (BT.2100 OOTF for 1000 nits, BT.2390 EETF
    to 203 nits) turned overcast skies flat white and made shadows too dark.
  - PQ: the EOTF with the mastering peak, then the BT.2390 EETF to 203-nit SDR white.
  - Then a BT.2020→BT.709 gamut map that preserves luminance and desaturates out-of-gamut colors.
  - Then the BT.709 OETF and dithering before 8-bit. Android's compositor and Apple's players
    decode BT.709 video with the inverse of this curve, not gamma 2.4. Encoding with gamma 2.4
    made mid-tones look about 35% too bright on phones (18% gray shown as 25%).
  - SDR sources pass through with only the matrix conversion.
- **Encode:** hardware H.264 High, VBR, 2 s GOP, no B-frames, tagged BT.709 limited range. Bitrate is
  0.18 bits per pixel with a weighting for frame rate: about 11 Mbps at 1080p30 and about 16 Mbps
  at 1080p60. It is never higher than the bitrate of a source that is already H.264. The frame
  rate is capped at 60.
- **Mux:** MediaMuxer MP4 with the audio track copied as is. The result is shared through a
  read-only `content://dev.tgprep.out/…` provider. "Send to Telegram" targets Telegram directly
  (`org.telegram.messenger`, `…web`, `…beta`); long-press opens the system share sheet.
  "Save copy to gallery" writes `<original name>_TG.mp4` to the camera folder `DCIM/Camera` with
  the original's date taken, so it shows up in Photos next to the original.
- **Metadata:** the recording date and the GPS location are copied from the original.
  - MediaMuxer always stamps "now" as the creation time, so `Mp4Meta` rewrites the
    `mvhd`/`tkhd`/`mdhd` times in place after muxing. MediaMuxer can set the location itself.
  - Android removes the GPS from shared videos unless the app holds `ACCESS_MEDIA_LOCATION`. The
    app asks for it once, on the first video. On Android 16 this shows the "photos and videos"
    dialog: **Allow all** grants only media location (the app still can't browse your library).
    If you deny it, everything works except the location.
  - **Privacy:** Telegram uploads the file untouched, so a location inside it would reach every
    chat member. The copy sent to Telegram has the location box renamed to `free`. That's
    4 bytes, and the video stream stays identical. Only the gallery copy keeps the location.

The detail panel shows what really happened: which decoder ran, the color signal it reported,
the encoder, the encoded frame count, and the H.264 profile it actually produced (read from the SPS).

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools). The Gradle wrapper
downloads Gradle 9.8.2 and AGP 9.5.0.

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # macOS/Homebrew
./gradlew lintRelease assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`minSdk` is 33 and `targetSdk` is 36 (Android 16). The release build is minified.

Signing: if `TGPREP_KEYSTORE` (a path) and `TGPREP_KEYSTORE_PASSWORD` are set, the release is
signed with that key (alias `tgprep`). Otherwise the local debug key is used. `BUILD_NUMBER` sets
`versionCode` and `versionName`.

### CI

`.github/workflows/build.yml` runs lint and the release build on every push and pull request.
On `main` it signs the APK with the release key from the repo secrets `TGPREP_KEYSTORE_BASE64`
and `TGPREP_KEYSTORE_PASSWORD`, then publishes a GitHub release `v1.0.<run>` with `tgprep.apk`
attached. Because the key never changes, each new build installs over the previous one. An APK
built locally with the debug key has a different signature, so you have to uninstall the app
before switching between the two.

## Install on the phone

1. On the phone: Settings → About device → Version → tap **Build number** 7 times. Then
   Settings → Additional settings → Developer options → enable **USB debugging**.
2. Connect it over USB and accept the prompt, then run:
   ```sh
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   Without adb: open the download link above in the phone's browser, open the downloaded file,
   and allow "Install unknown apps" for the browser. ColorOS may run an extra security scan, which you can let pass.
3. In the camera, record with Dolby Vision on. In Photos, open the clip → Share → **TG Prep**.
   On the first video, the app asks for media access: pick **Allow all** to keep the location.

## Tests

All tools run on the computer (`uv`, `ffmpeg`, `ffprobe`; plus `dovi_tool` and `MP4Box` for DV
test clips; an ffmpeg with `libplacebo` for HDR reference frames).

### 1. Synthetic, exact (emulator or phone)

`adb_run.py` opens the clip the way the gallery does: it pushes the clip to `DCIM/TgPrepTest`,
then sends a MediaStore `content://` URI with a read grant. File paths behave differently
(SELinux blocks the media server, and the location removal doesn't apply), so they would hide
metadata bugs. Run `adb shell pm grant dev.tgprep android.permission.ACCESS_MEDIA_LOCATION`
once after installing.

`make_hlg_test.py` makes a portrait HLG clip with known gray, color, and skin patches, a
top-left orientation marker, rotation −90, and AAC audio. `tonemap_ref.py` computes the expected
SDR values with the same math as the shader. `check_output.py` checks the passthrough rules
and compares every patch, with a tolerance of 6 codes.

```sh
uv run --with numpy tools/make_hlg_test.py /tmp/hlg.mp4 --size 2160x3840          # phone: HEVC 10-bit 4K
uv run tools/adb_run.py /tmp/hlg.mp4 /tmp/out    # push, open in app, Optimize, save, pull
cd tools && uv run --with numpy check_output.py /tmp/out/*_TG*.mp4
```

DV 8.4 variant: inject an RPU with `dovi_tool inject-rpu`, mux with
`MP4Box -add x.hevc:dvp=8.4`, then set the rotation and the `dvh1` tag with
`ffmpeg -display_rotation:v:0 -90 -i in.mp4 -c copy -tag:v dvh1 -strict unofficial out.mp4`.

Results on the Android 16 arm64 emulator (verified):

| Clip | Path | Result |
|---|---|---|
| HLG BT.2020 HEVC 8-bit, 1080x1920, rot −90 | goldfish HEVC → shader YUV→RGB → c2.android.avc | all PASS, worst patch error 5.1 |
| HLG BT.2020 AV1 10-bit, 1080x1920, rot −90 | dav1d (8-bit output) | all PASS, worst 4.3 |
| HLG BT.2020 HEVC 60 fps, 3 s | all frames kept | all PASS, 180/180 frames, even 16.7 ms spacing |
| **DV 8.4** (HEVC base + RPU, `dvh1`), rot −90 | DV base layer via plain HEVC decoder | decodes, 60/60 frames |
| SDR BT.601 | no tone map, matrix only | within 5 codes of the source |
| HEVC 10-bit HLG / DV 8.4 10-bit | — | emulator decoders can't decode Main10; the app reports "No decoder on this device could decode video/hevc HDR (tried …)". This is a limit of the emulator only. |

Note on test clips: `make_hlg_test.py` tags the raw input with the output's color settings.
Without that, ffmpeg 9 silently converts the samples to the output matrix, and the clip's colors
are wrong before the app ever sees them.

### 2. Real camera clip: original vs optimized vs received through Telegram

1. Record a 10-20 s Dolby Vision clip with a sky, skin, a dark corner, and a bright light source.
   Hold the phone in portrait.
2. Optimize it, then tap **Save copy to gallery** and **Send to Telegram**. In Telegram's quality
   menu, pick the top option (1080p), then send it to *Saved Messages*.
3. On a second device, or in Telegram Desktop, download the received video (Save to
   Downloads / Save as).
4. Copy all three files to the computer:
   `adb pull /sdcard/DCIM/Camera/<clip>.mp4 orig.mp4`,
   `adb pull "/sdcard/DCIM/Camera/<clip>_TG.mp4" opt.mp4`, plus the received `recv.mp4`. Then run:
   ```sh
   uv run tools/compare.py orig.mp4 opt.mp4 recv.mp4 --out cmp
   ```
   It prints a metadata table (codec, profile, resolution, rotation, fps, color tags, DV profile,
   audio) and whether **Telegram kept the video bitstream untouched** (MD5 of the video packets).
   It also prints SSIM/PSNR of received vs optimized and writes frames at 20/50/80 %. The
   original frame is tone-mapped with libplacebo BT.2390 as an independent reference.

Pass criteria:
- `optimized`: h264 High, rotation 0, long side ≤ 1920, bt709/bt709/bt709 tv, AAC.
- `received`: "untouched: YES". If it says NO, the top quality wasn't picked, or Telegram changed
  its rules. In that case SSIM shows how much was lost.
- Visual check (`cmp/*.png` side by side, plus playback in Telegram on the phone next to Photos):
  - Skin tones and sky hue match the libplacebo reference.
  - Highlights roll off with no clipped blocks, and blacks are not lifted.
  - No banding in the sky, and no blocking in dark or moving areas.
  - The orientation is correct.
- Compare with the baseline: send the original clip straight to Telegram with default settings.
  It should come back as a ~2.6 Mbps 720p re-encode with Telegram's own tone mapping.

## Known limits

- The emulator can't decode 10-bit HEVC, so the phone's real decode path is only tested on the
  phone. Things to check in the details panel:
  - The "Decoder" line should show the base-layer HEVC decoder.
  - The encoder should report High.
  - The frame count should be about duration × fps.
- Telegram shows the frame rate as `(int) avg_frame_rate` (`gifvideo.cpp`), which truncates.
  Camera clips use real capture timestamps, so a "60 fps" clip can average slightly under 60
  (e.g. 59.95) and then shows as 59. The app keeps the
  source timestamps on purpose: retiming them to exactly 60 would make audio drift.
- The Dolby Vision RPU (per-scene dynamic metadata) is ignored. The app tone-maps the HLG base
  layer, which is what the profile 8.4 compatibility guarantees. Profile 5 (no compatible base
  layer) isn't produced by phone cameras, and it would only be decoded via the vendor DV decoder.
- The app processes one video at a time, in the foreground. Leave the screen on for long clips.
