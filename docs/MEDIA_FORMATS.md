# Supported media formats

> Status: experimental, Windows-first (like the rest of the skia-fx
> media work). Linux/macOS wiring exists for parts of this table but is
> untested.

The media engine has two tiers:

1. **Default** — what plays on a stock install using the platform
   decoders (DirectShow / Media Foundation on Windows) and the
   built-in gstreamer-lite demuxers/parsers. No external dependencies.
2. **With ffmpeg** — what additionally plays once the ffmpeg runtime
   DLLs are available. Check with `Media.isFfmpegAvailable()`,
   diagnose with `Media.getFfmpegStatus()`, configure with
   `Media.setFfmpegDirectory(dir)` or the `OPENJFX_MEDIA_FFMPEG_DIR`
   environment variable.

**Missing ffmpeg never breaks the default tier.** The loader failing
(absent DLLs, ABI mismatch, mixed builds) logs one warning, the
ffmpeg-dependent formats below fail with a clear `MediaException`, and
everything in the default tier keeps playing.

## Default tier (no ffmpeg required)

| Container | Extensions | Video | Audio |
|---|---|---|---|
| MP4 family | .mp4 .m4a .m4v .m4b | H.264 (DirectShow), H.265/HEVC (Media Foundation) | AAC |
| MP3 | .mp3 | — | MP3 (incl. ID3v2-tagged) |
| ADTS AAC | .aac | — | AAC (incl. ID3v2-tagged) |
| WAV | .wav | — | PCM / IEEE float |
| AIFF | .aif .aiff | — | PCM |
| FLV | .flv .fxm | H.264 | AAC, MP3 |
| HLS | .m3u8 .m3u | H.264 (MP2T / fMP4 segments) | AAC, MP3 |

## With ffmpeg

Everything above, plus:

| Container | Extensions | Video | Audio |
|---|---|---|---|
| WebM | .webm .weba | VP8, VP9, AV1 | Opus, Vorbis |
| Matroska | .mkv .mka .mks | H.264, H.265, VP8/9, AV1, MPEG-4, … | Opus, Vorbis, FLAC, AAC, MP3, AC-3, E-AC-3 |
| FLAC | .flac | — | FLAC (with vorbis-comment metadata) |
| AVI | .avi | H.264 (annex-B), MPEG-4/DivX/Xvid, MJPEG, … (any libavcodec video) | MP3, AC-3, PCM a/µ-law, … |
| FLV (extended) | .flv | H.264, Sorenson H.263, VP6* | AAC, MP3 |
| MP4 (extended) | .mp4 | AV1 (ffmpeg preferred over MF) | Opus, Vorbis, FLAC (decoder auto-swap) |
| **Any other container** | (catch-all) | any libavcodec video | any libavcodec audio |

\* anything libavcodec decodes and the demuxer announces; exotic
codecs not listed are best-effort.

### The catch-all (any container ffmpeg can open)

When the ffmpeg runtime is loaded, a container with **no dedicated
demuxer above** still plays: it routes to a `libavformat`-backed
GStreamer demuxer (`ffmpegdemux`). This covers Ogg (Opus/Vorbis/Theora),
MPEG program/transport streams (`.mpg`, raw `.ts`), ASF/WMV/WMA, MXF,
RealMedia, and anything else `avformat_open_input` accepts — without
re-encoding. It works for single-source playback and for the
`Media(audio, video)` dual-source companion path.

This is **hybrid, not a takeover**: the dedicated demuxers above
(qtdemux, matroskademux, avidemux, flvdemux, flacparse, …) stay the
preferred path for the formats they already handle. Only an
otherwise-unsupported container falls through to `ffmpegdemux`. Without
ffmpeg loaded the gate stays closed — an unrecognized file fails with
the same clear `MediaException` as before.

ffmpeg also powers:

- **Dual-source playback** — `Media(audioURL, videoURL)` for
  adaptive-streaming pairs (see `docs/DUAL_SOURCE_MEDIA.md`).
- **MediaMixer** — lossless audio+video → MP4 merge (see
  `docs/MEDIA_MIXER.md`).
- **CPU decode mode** — `Media.setDecodeMethod(CPU)` AV1 via libdav1d.

## Metadata (`Media.getMetadata()`)

Java-side head-of-file parsers (`com.sun.media.jfxmediaimpl.platform.java`)
populate the metadata map before playback starts; each stops at the
media data, so only the file head is read:

| Format | Parser | What it yields |
|---|---|---|
| MP3, ADTS AAC | `ID3MetadataParser` | ID3v2 frames: title, artist, album, genre, year, track, cover art, … |
| FLAC | `FlacMetadataParser` | duration (STREAMINFO), vorbis comments (title, artist, album, …), cover art (PICTURE) |
| WebM / Matroska | `MatroskaMetadataParser` | duration + segment title (Info), SimpleTag pairs (TITLE, ARTIST, ALBUM, …) |
| FLV | `FlvMetadataParser` | the AMF0 `onMetaData` map: duration, width, height, framerate, encoder, … |
| AVI | `AviMetadataParser` | duration (`avih`), RIFF INFO tags (INAM title, IART artist, IPRD album, …) |

Standard keys (`title`, `artist`, `album`, `duration`, `image`, …)
follow `MetadataParser`'s tag-name constants; FLV's container-specific
keys (width/height/…) pass through under their AMF names. Files whose
tags sit *after* the media data (rare Matroska muxes needing a
SeekHead jump) yield only what the head contains.

## Not supported

- **Ogg** (.ogg .oga .opus standalone) — oggdemux lives in
  gst-plugins-base and needs libogg; not imported. Opus/Vorbis play
  fine from WebM/MKV/MP4 containers.
- 5.1/7.1 passthrough — everything downmixes to the stereo S16
  pipeline.
- DRM of any kind.

## How routing works (for maintainers)

- Content type is sniffed from the file signature
  (`MediaUtils.fileSignatureToContentType`, ID3v2-aware), falling back
  to the URL extension; `GSTPlatform.CONTENT_TYPES` is the platform
  gate.
- `GstPipelineFactory::CreatePlayerPipeline` dispatches per content
  type: matroska/webm → `matroskademux`, avi → `avidemux`, flv →
  `flvdemux` (all from the pinned gst-plugins-good sources fetched by
  `skiafx.matroska-conventions`), raw flac → `flacparse`.
- Video decoders are picked per announced caps in
  `CGstAVPlaybackPipeline::LoadDecoder` (ffmpeg preferred where the
  platform decoder can't handle the container's stream format — e.g.
  H.264-in-AVI is annex-B without the `avcC` header DirectShow needs).
- Audio in AV containers prefers `ffmpegwrapper`, falling back to
  `dshowwrapper` when ffmpeg isn't loaded; `SwapAudioDecoderIfNeeded`
  corrects the preset when the demuxer announces a codec the preset
  can't decode (Opus/Vorbis/FLAC in MP4).
- `OPENJFX_MEDIA_FORCE_FFMPEG=1` forces ffmpeg for every codec it
  supports (diagnostic).

Verification: `gradlew :samples:ensemble:runMediaStress
-Dstress.mode=corpus -Dstress.dir=<dir>` requires every file in a
directory to reach READY or a clean MediaException (see
`docs/MEDIA_HARDENING.md`).
