# Dual-Source Media (`Media(audioSource, videoSource)`)

> See also: `MEDIA_MIXER.md` — merging downloaded audio + video files
> into one MP4 (`javafx.scene.media.MediaMixer`).

skia-fx adds a dual-source constructor to `javafx.scene.media.Media` that plays a
**video-only** stream and a **separate audio-only** stream synchronized in one
`MediaPlayer` (e.g. YouTube-style adaptive streams that split A/V). Both sources live
in a single `GstPipeline` so they share one `GstClock`.

## Status

| Scenario | State |
|---|---|
| `file://` audio + `file://` video (local) | ✅ Works (smooth, in sync) |
| `file://` / `http(s)://` mix | ✅ Works |
| `http(s)://` audio + `http(s)://` video (both remote) | ✅ Works — incl. YouTube-style adaptive pairs (4K VP9 webm + Opus weba); see "Drip-throttled CDNs", below |

The original crashes and the "won't start without a seek" bug are **fixed** (below),
as is the 2026-06 "plays two notes then freezes at 00:00" wedge — root cause was the
CDN drip-throttling the audio stream (see "Drip-throttled CDNs"). Dual-source
pipelines also default to deep audio buffering (encoded-audio queue 10 s, audio sink
ring buffer 500 ms, 8 s prebuffer); the env knobs still override them.

## Drip-throttled CDNs and chunked HTTP reading

**Root cause of the 2026-06 freeze (measured):** googlevideo rate-limits media
streams per HTTP request. The 4K video stream burst ~86 Mbps for the first ~8 MB
of a request, then clamped to ~22.7 Mbps. The 2.75 MB **audio-only** stream got
**no burst at all** — a steady ~32 KB/s drip from byte 0 (84 s to download a file
that plays for 168 s). The companion's feed starved at the source; because the
audio sink provides the pipeline's master clock (and the directsound clock only
advances while data plays), the position froze at 00:00 with the first video
frame showing, after ~2 notes of audio (the in-flight queue + ring draining). A
manual seek "revived" it for a second because each seek opened a fresh request
(fresh burst).

**Fix:** `URIConnectionHolder` (the media HTTP reader) now reads http(s) media in
**bounded `Range:` chunks**, rotating the request as each chunk is consumed
(HTTP keep-alive reuses the socket). Measured: video 22.7 → 80 Mbps sustained,
audio 32 KB/s → 14 Mbps (1.6 s for the full file). Chunk size scales per stream:
`min(8 MB, max(256 KB, contentLength/8))`; small streams rotate to a bounded
range **immediately** (the initial unbounded GET would drip from byte 0).
Servers that don't honour ranges fall back to the stock single-GET behaviour
automatically. Tunable: `-Dskia.media.httpChunkMB=<n>` (0 disables). This also
benefits single-source http playback.

## Native architecture

Two source mechanisms feed the companion AUDIO (chosen automatically by URL scheme):

- **`http(s)://`** → the Java I/O bridge (`javasource` + `progressbuffer`), the same path
  the primary source uses. Wired via a dedicated JNI accessor
  `CLocator::GetCompanionAudioConnectionHolder` (`Locator.cpp`) →
  `CJavaInputStreamCallbacks` → `SetAudioCallbacks` (`GstMedia.cpp`), so the pipeline runs
  Path A in `GstPipelineFactory::CreatePlayerPipeline`. Decoder/demuxer chosen from the
  companion **content-type** (not file extension) via `PickCompanionAudioDecoder`.
- **`file://`** → native `filesrc` (`CreateCompanionAudioSource`), unchanged.

Java side: `Locator.createCompanionAudioConnectionHolder()` /
`getCompanionAudioContentType()` (`com/sun/media/jfxmedia/locator/Locator.java`).

## Bugs fixed (keep these)

1. **Opus-in-WebM caps crash** (`0xC0000005`). `gst_codec_utils_opus_create_caps_from_header`
   built a `streamheader` GValue-array that dereferenced a garbage pointer. Root-caused by
   crash-dump symbolication (`.map` files). Fixed in `codec-utils.c` by attaching the OpusHead
   as `codec_data` (a plain `GST_TYPE_BUFFER`) instead of the array; ffmpegwrapper reads
   `codec_data` first.
2. **`GST_TYPE_ARRAY == 0`** (the `type id '0' is invalid` GObject CRITICAL / the real cause
   of #1). gstreamer-lite's value-type data globals are declared `GST_EXPORT`, which is
   `dllexport` only when `GST_EXPORTS` is defined. The build defined a dead `LIBGSTREAMER_EXPORTS`
   instead, so they linked `dllimport` and read 0. Fixed by adding `GST_EXPORTS` to the
   **gstreamer-lite-core** target in `gstreamer-win/gstreamer-lite/CMakeLists.txt`.
3. **`disable-mp2t-pts-reset` on matroskademux** — that property is dshowwrapper-only; the
   unconditional `g_object_set` faulted for matroska dual-source. Guarded with
   `g_object_class_find_property` (`GstPipelineFactory::CreateAVPipeline`).
4. **NULL-caps in `on_pad_added`** — a freshly-added demuxer pad can have no caps yet; guard
   added (`GstAVPlaybackPipeline.cpp`).
5. **Premature `m_bAudioSinkReady`** — the video-only main demuxer's `no_more_pads` declared
   audio ready before the companion linked, racing the build; guarded with `AUDIO_PARSER`.
0. **THE 2026-06 "speedy / wavy / drifting audio" — ffmpeg ABI drift (the big one).**
   The runtime DLL fetch used a moving `master-latest` build while the compiled
   headers were pinned at ffmpeg 7.1; the box ran avcodec 62 against avcodec-61
   struct layouts. Deep `AVFrame` fields read garbage — `ch_layout` as 0 — so the
   audio packer fell back to MONO and emitted HALF-size buffers for stereo Opus:
   audio played double-speed in chunks with gaps, in every dual run (and in
   single-source Opus, which had simply never been listened to). Three-layer fix:
   (a) the packer and caps push now use the demux-caps-harvested channel/rate
   values (ABI-stable) instead of deep struct fields; (b) the runtime fetch is
   pinned to the exact header version (gyan.dev 7.1 release build first, BtbN
   release-branch as fallback), clears stale DLLs, and stamps a flavor marker;
   (c) the loader REFUSES an avcodec whose major differs from the build's,
   degrading cleanly to platform decoders with an actionable one-shot message.
   `Media.isFfmpegAvailable()` exposes the state to applications.
0b. **Audio sink clock installed too late** — the pipeline master clock (the
   audio sink's) was only installed once BOTH sinks were ready; the dual
   dynamic build pushed that past the PLAYING transition, and a mid-PLAYING
   `gst_pipeline_set_clock` does not redistribute base_time — every video
   frame computed permanently late, so the video sink rendered at decode
   speed ("video racing in waves" while audio stayed smooth). The clock now
   installs as soon as the audio sink reaches PAUSED, and is never swapped
   once PLAYING.
0c. **`setRate(rate != 1.0)` audio** — gstaudiobasesink only scales ring
   positions, never sample data (stock defect, upstream included), so 0.5×
   played as normal-pitch bursts. The `scaletempo` element (fetched from the
   pinned gst-plugins-good tarball like matroskademux, registered via
   `scaletempo-lite-plugin.c`) now sits between decoder and equalizer and
   time-stretches audio pitch-preserved. Gotcha fixed: scaletempo only toggles
   passthrough when a segment CHANGES the rate, so it must be created in
   passthrough or it WSOLA-processes identity-rate audio audibly.
   `OPENJFX_MEDIA_SCALETEMPO=0` opts out.
6. **"Won't advance until you seek"** — two independent remote sources reach PLAYING with
   misaligned segments, so the audio-master clock never advances. Fixed with a **one-shot
   flushing `SeekPipeline(0)`** (`GstAudioPlaybackPipeline.cpp`, guarded by
   `m_bDualSourceInitialResyncDone`). This realigns both sources — the same effect a manual
   seek had.
   **Revised 2026-06-10:** firing that seek eagerly from the bus callback at the first
   PLAYING transition **raced the dynamic video-chain build** — the flush could land while
   the 4K video bin was still completing its first preroll, wedging the pipeline in async
   limbo (audio ring buffer never restarted, master clock pinned at 0, position stuck at
   00:00, ~0.3 s of audio then silence; not even user seeks recovered it). The resync is now
   **deferred and conditional**: `GetStreamTime` (polled every ~100 ms by the Java side)
   fires it only after the position has been observed pinned at 0 for 5 consecutive polls
   while PLAYING. If the clock advances on its own, no resync happens at all; any explicit
   seek disarms it.
7. **progressbuffer `getrange` killed pull-mode demuxers** — on a cache miss (range not
   downloaded yet) it returned `GST_FLOW_FLUSHING`, which a pull-mode demuxer treats as
   shutdown: its streaming task pauses permanently (the `FX_EVENT_RANGE_READY` custom event
   means nothing to stock demuxers). `getrange` now **blocks** until the range is cached,
   EOS clamps the stream short, or the element shuts down — like queue2's download mode —
   while keeping the source-seek-ahead optimisation. Also: `sink_segment.stop` starts as
   the size hint (the companion wrongly inherits the PRIMARY's size from
   `CLocator::GetSizeHint`); upstream EOS clamps it, and `getrange` serves a short read at
   EOF instead of EOS for partial terminal ranges.
8. **ffmpegwrapper flush race** — `GST_EVENT_FLUSH_STOP` called `avcodec_flush_buffers()`
   with no lock; flush events are out-of-band (no stream lock), so it raced the streaming
   thread inside `avcodec_send_packet/receive_frame` on the same `AVCodecContext`. Now
   wrapped in `GST_PAD_STREAM_LOCK` (same discipline as GStreamer's decoder base classes).
9. **ffmpegwrapper drain-mode trap** — after EOS the wrapper sends the NULL (drain)
   packet; the codec then rejects every later packet with `AVERROR_EOF`, which the chain
   silently tolerated — packets were eaten forever (dead audio after a seek that follows
   EOS). `AVERROR_EOF` from `send_packet` now recovers via `avcodec_flush_buffers` +
   resend.

## Remote audio stutter under load — FIXED via dual-source buffering defaults

**Symptom (historical):** two remote URLs play, but the audio stutters/loops (video stays
smooth) at 4K.
**Diagnosis (not bandwidth):** the video buffers tens of seconds ahead, yet the tiny audio
underruns — its *download/decode thread is starved of CPU* by 4K decode + Skia render
(also shows as a sluggish GUI). Because the audio sink is the master clock, every gap is
audible. Local files don't have decode-vs-download contention and aren't 4K-heavy, so local
is smooth.

**Fixes (in `CGstPipelineFactory::CreateAudioBin`, applied ONLY to non-HLS dual-source
pipelines so single-source playback keeps stock behaviour):**

1. **Encoded-audio queue defaults to a 10 s time limit** (stock: 10 buffers ≈ 200 ms of
   Opus). The companion stream is tiny (~400 KB of queue at 320 kbps), so a multi-second
   feed-thread stall no longer drains the audio path.
2. **Audio sink device ring buffer defaults to 500 ms** (stock GstAudioBaseSink default:
   200 ms). This is the last cushion before the device; sync is clock-based so lip-sync is
   unaffected — the sink just writes further ahead.
3. **Bug fix: `OPENJFX_MEDIA_AUDIO_QUEUE_TIME` was a silent no-op.** The env override was
   applied right after the queue element was created, then unconditionally clobbered back
   to stock limits at the end of `CreateAudioBin`. The override is now applied last, once.

**Env knobs (now override the dual-source defaults; still opt-in for single-source):**

| Env var | Effect | Range |
|---|---|---|
| `OPENJFX_MEDIA_AUDIO_BUFFER_MS` | Audio sink device ring-buffer (`buffer-time`) — absorbs CPU-jitter underruns | 100–10000 ms |
| `OPENJFX_MEDIA_AUDIO_QUEUE_TIME` | Encoded-audio queue depth (by time) | 1–60 s |
| `OPENJFX_MEDIA_PREBUFFER_TIME` | progressbuffer read-ahead cushion (both sources) | 0–20 s |

**Possible follow-ups if stutter ever reappears on weaker machines:**
- Decouple the companion audio from being the master clock (use the system clock) so audio
  thread starvation can't stall the timeline.
- Raise the audio source/decode thread priority so 4K render can't starve it (the sink's
  ring-buffer writer thread already runs at MMCSS "Pro Audio" priority; the feed chain
  upstream of the queue does not).

## Companion-audio codec coverage

The companion's demuxer + decoder are picked from its **file-signature content type**
(`MediaUtils.fileSignatureToContentType`, stamped for every scheme by `GstMedia.cpp`),
with URL-extension sniffing as fallback for `file://` companions whose signature read
failed. Coverage (Windows):

| Container / codec | Elements |
|---|---|
| webm / mkv / weba / mka (Opus, Vorbis, …) | `matroskademux` + `ffmpegwrapper` |
| mp4 / m4a / m4b (AAC) | `qtdemux` + `dshowwrapper` |
| mp3 (incl. ID3) | `mpegaudioparse` + `dshowwrapper` |
| raw ADTS AAC (`audio/aac`, new signature detection) | `aacparse` + `dshowwrapper` |
| wav (PCM) | `wavparse` (no decoder; `audioconvert` normalises) |
| aiff (PCM) | `aiffparse` (no decoder) |
| ogg / flac | ❌ no demuxer/parser in gstreamer-lite (adding one means importing a whole upstream plugin + libogg) |

Raw ADTS AAC is now also playable **single-source** (new `audio/aac` content type →
`CreateAacAudioPipeline`, signature- and `.aac`-extension-detected, added to
`GSTPlatform.CONTENT_TYPES`).

## Debug aids left in place
- `RelWithDebInfo` emits linker `.map` files for crash symbolication without a debugger
  (`/MAP`, guarded by `$<$<CONFIG:RelWithDebInfo>>` in the three native CMakeLists). Parse a
  `%LOCALAPPDATA%\CrashDumps` minidump's fault offset against the `.map`.
