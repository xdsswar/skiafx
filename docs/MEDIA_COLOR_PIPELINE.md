# Media colour pipeline — caps, plane order, HDR

Reference for anyone touching the video → Skia consumer path. The
TL;DR is at the top; the rest explains why each piece exists.

## TL;DR

- Every YUV producer (ffmpegwrapper / mfwrapper / dshowwrapper) MUST
  tag its caps as **`format="I420"`** and put explicit `offset-u` /
  `offset-v` fields on the caps that point at the **actual** U and V
  data in its output buffer. `format="YV12"` is forbidden going
  forward — see "The amber-tint bug" below.
- ffmpegwrapper pushes a full **`R:M:T:P` colorimetry** string
  (range : matrix : transfer : primaries) so HDR (PQ / HLG) reaches
  the consumer correctly. mfwrapper and dshowwrapper strip these.
- The Skia consumer (`SkiaMediaTexture`) auto-detects HDR from caps;
  when present it routes through `imageCreateYuvHdr` (GPU SkRuntimeEffect
  BT.2390 tone-map) or `HdrToneMap.tonemapI420ToBgra` (pure-Java LUT
  CPU fallback). SDR content goes through `imageCreateYuvI420` (fast
  GPU YUV upload).

## The amber-tint bug

**Symptom**: dshow-decoded H.264 (and historically mfwrapper-decoded
H.265/AV1 before its fix) renders with a strong amber/orange cast on
backlit / bright areas; skin tones look orange; blues look cyan.
ffprobe shows the file is perfectly stock SDR BT.709 limited — no
metadata anomaly. Pre-skia-fx players (the BGRA path) render the
file fine. Skia-pipeline renders amber.

**Root cause**: an inconsistency between the GStreamer caps **format
tag** and the **actual plane layout** of the decoder's output buffer.

- DirectShow H.264 decoder produces I420 layout (`[Y][U][V]`, U then V).
- `dshowwrapper.cpp` used to tag its caps as `format="YV12"` despite
  the data being I420.
- `GstVideoFrame::SetFrameCaps` only sets `m_bIsI420 = true` when the
  format string literally equals `"I420"`. That flag is what triggers
  `SwapPlanes(1, 2)` to convert from the YV12-style plane ordering
  the cpp file reads-by-default (`offset-v` → plane[1], `offset-u` →
  plane[2]) to the I420-native order (plane[1] = U, plane[2] = V).
- With the wrong tag, `m_bIsI420` stayed false, no swap happened,
  and the downstream consumer received plane[1] = V, plane[2] = U.
- The old BGRA fallback worked around this by re-checking
  `m_bIsI420` and swapping indices in its own YUV→BGRA conversion
  (`u_index = m_bIsI420 ? 1 : 2`). That's why the bug was invisible
  for years.
- The Skia YUV-native upload (`SkiaMediaTexture.uploadYuvI420`)
  passes plane[1] as U and plane[2] as V directly to Skia. With the
  swapped data this becomes a U↔V swap in the YUV→RGB matrix, which
  manifests as the classic "amber backlight, blue skin, cyan
  foliage" pattern.

**Fix** (committed 2026-05-25, see `dshowwrapper.cpp:2212` and
`:1006`): tag the caps as `format="I420"` and write explicit
`offset-u` / `offset-v` fields pointing at the I420 layout. For the
YV12-data branch (when DirectShow elects to deliver YV12), still
tag caps as `"I420"` but set `offset-u` / `offset-v` to the YV12
plane positions inside the buffer (`offset-v` first, then `offset-u`)
— `m_bIsI420 = true` then ensures the swap happens and the consumer
gets the conventional plane[1] = U, plane[2] = V it expects.

mfwrapper had the same bug, fixed earlier; `mfwrapper.cpp:1229` has
a long comment explaining the same diagnosis.

**Take-away**: when adding a new YUV producer plugin in this tree,
**always tag caps as `"I420"`** and put explicit offsets matching
the *actual* in-memory layout of the output buffer. The downstream
consumer treats plane index as the source of truth and assumes the
I420 convention; anything else needs the SwapPlanes correction that
`m_bIsI420 = true` triggers.

## HDR pipeline

Three layers, top-to-bottom:

### 1. Source metadata plumbing

`MediaFrame` exposes the file's colour descriptor:

- `getYuvColorSpace()` → BT.601 / BT.709 / BT.2020 / JPEG-full
- `getColorTransfer()` → sRGB / Rec.709 / **PQ** / **HLG** / linear
- `getColorPrimaries()` → sRGB / **Rec.2020** / DCI-P3 / Rec.601
- `getColorRange()` → limited / full
- `getMasteringPeakNits()` → from MaxCLL / mastering display info

Native side: `CVideoFrame` carries the same fields (`m_iColorTransfer`,
`m_iColorPrimaries`, `m_iColorRange`, `m_fMasteringPeakNits`),
populated by `CGstVideoFrame::SetFrameCaps` parsing the caps
`colorimetry` field (both short forms like `"bt2100-pq"` and the
long `R:M:T:P` enum form) and the `content-light-level` field.

ffmpegwrapper translates `AVFrame::color_*` fields directly into a
long-form `R:M:T:P` colorimetry string on its src caps event
(`ffmpegwrapper.cpp:1674`). mfwrapper and dshowwrapper currently
emit no colorimetry — their decoded streams hit the consumer-side
resolution heuristic in `SkiaMediaTexture.resolveColorDesc`.

### 2. Consumer-side detection (`SkiaMediaTexture.resolveColorDesc`)

Three-stage resolution per frame:

1. **Explicit overrides** (system properties):
   - `-Dskia.media.yuvColorSpace=N` (0/1/2/3) forces the YUV matrix
   - `-Dskia.media.forceHdr=pq|hlg|sdr|709|auto` forces the transfer
   - `-Dskia.media.hdrPeakNits=N` overrides source peak luminance
   - `-Dskia.media.hdrDisplayNits=N` overrides display peak (default 100)
   - `-Dskia.media.hdrPath=auto|gpu|cpu|off` picks the HDR engine
2. **Caps metadata** when present (the proper auto-detect path).
3. **Resolution heuristic** when neither override nor caps fire:
   - ≥ 3840 wide → assume PQ HDR / BT.2020 (the dominant 4K format)
   - ≥ 1280 wide → SDR BT.709 (ff_default_csp_from_dims)
   - otherwise → SDR BT.601

The first frame logs the full descriptor (`[skia.media] FIRST frame:
...`) so colour-bug triage doesn't require guessing.

### 3. Render path

| Path | When | Implementation |
|---|---|---|
| **Native HDR (GPU)** | HDR descriptor + `nativeHdr=yes` + `hdrPath ≠ cpu` | `imageCreateYuvHdr` — Skia tags the source SkColorSpace with PQ/HLG transfer + Rec.2020 gamut, then a single SkRuntimeEffect pass performs BT.2390-9 Annex 2 per-channel Hermite tone-map into an sRGB target. |
| **Java HDR (CPU)** | HDR descriptor + no native HDR (or `hdrPath=cpu`) | `HdrToneMap.tonemapI420ToBgra` — LUT-based PQ/HLG EOTF + BT.2020→sRGB gamut + BT.2390 tone-map + sRGB OETF, parallel-streamed over rows. Outputs BGRA8888-premul. Slower than GPU but works without any native HDR build. |
| **SDR (GPU)** | SDR descriptor | `imageCreateYuvI420` — straight YUV→RGB on the GPU, no tone-map. Existing fast path. |
| **OFF** | `-Dskia.media.hdrPath=off` | Force the SDR path even for HDR sources. Dim and washed out but never amber — useful for A/B comparison. |

### Debug logs that matter

- `[skia.media] FIRST frame: ...` — always printed once per stream.
  Gives the full colour descriptor + chosen path.
- `[ffmpegwrapper] pushed src caps WxH I420 (range= matrix= transfer= primaries=)`
  — confirms the colorimetry ffmpegwrapper put on caps.
- `[av.decoder.select] mime=... force=N ffmpegSupports(N)=N -> NAME`
  — confirms which decoder claimed the stream. Use
  `-PforceFfmpeg=true` (sets `OPENJFX_MEDIA_FORCE_FFMPEG`) to route
  every supported codec through ffmpegwrapper when triaging
  colour / metadata problems.

## Master CPU/GPU switch — `Application.setDecodeMethod`

The whole pipeline (ffmpeg decode, D3D11 zero-copy, GPU HDR
tone-mapping, Skia YUV upload) defaults to **AUTO** — best
available on this machine. An application can override it for the
entire process:

```java
@Override
public void init() {
    // On a GPU-less PC, or to A/B test the CPU paths:
    Application.setDecodeMethod(Application.DecodeMethod.CPU);

    // Or force HW, fail fast if not available:
    // Application.setDecodeMethod(Application.DecodeMethod.GPU);
}
```

Equivalent at startup: `java -Dskia.media.decode=CPU ...` (the
property name is exposed as `Application.DECODE_METHOD_PROPERTY`).

### Modes

| Mode | What happens |
|---|---|
| `AUTO` (default) | Best available. ffmpeg uses D3D11VA when env allows, Skia uses zero-copy + GPU tone-map when interop initialises; everything silently falls through to CPU on any single-layer failure. |
| `GPU_PREFERRED` | Like AUTO but logs each per-layer fallback. Useful for "want GPU but never fail playback". |
| `GPU` | Strictly require GPU. Streams fail-fast if hwaccel or zero-copy can't init. For perf testing. |
| `CPU` | Force software decoding + CPU raster upload everywhere. The full pipeline still works: libavcodec SW decode → `imageCreateYuvI420`/`imageCreateRaster` upload (no D3D11 zero-copy, no native GPU HDR). |

### What's wired vs not

Currently wired to honour `setDecodeMethod` at runtime:

- `SkiaMediaTexture.isD3d11ZeroCopyEnabled()` — disables the zero-copy
  path when `CPU` mode is set.
- `SkiaMediaTexture.currentHdrPath()` — forces `HdrPath.CPU` for HDR
  tone-mapping when `CPU` mode is set.
- HDR detection still uses caps metadata; only the *render path*
  changes.

Currently controlled by the legacy env var (read once at decoder
open, can't be changed from Java post-startup):

- `OPENJFX_MEDIA_USE_HWACCEL` — ffmpeg's D3D11VA hardware
  acceleration. For now this must be set at JVM launch (e.g. via
  `-PuseHwaccel=false` on the gradle run task) — `setDecodeMethod`
  doesn't propagate into already-started gstreamer plugins.

A future enhancement could bridge `setDecodeMethod(CPU)` to a
native `setenv` call so the ffmpeg side reacts too — see the
`MediaDecoding` class in `javafx.media` for the property-mirror
that the native side could read at decoder open.

## Forward-looking notes

- **dshowwrapper colorimetry** — currently empty. The DirectShow
  `VIDEOINFOHEADER2` has an extended colorimetry struct
  (`DXVA2_ExtendedFormat`) that the H.264 decoder fills in for HDR
  content; if we ever want HDR through dshowwrapper, parse that
  and emit the matching long-form `R:M:T:P` string.
- **mfwrapper colorimetry** — same story, MFT has
  `MF_MT_VIDEO_PRIMARIES` / `MF_MT_TRANSFER_FUNCTION` /
  `MF_MT_VIDEO_NOMINAL_RANGE` / `MF_MT_YUV_MATRIX` attributes.
  Today mfwrapper emits no colorimetry; the consumer falls back to
  the resolution heuristic.
- **Native HDR build** — `openjfx_skia_image_create_yuv_hdr` is the
  preferred GPU HDR path but only links into the Skia bridge DLL
  when `SKIA_HOME` is set at configure time. Without that env var,
  the Java consumer auto-falls back to the CPU HDR tone-mapper —
  the user sees correct colours either way, just at different
  performance points.
