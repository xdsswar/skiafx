# Media hardening

> Status: experimental, Windows-first (like the rest of the skia-fx
> media work).

Defenses that keep the media stack failing *loudly and recoverably*
instead of freezing, corrupting, or crashing. Added 2026-06 on the
`media` branch.

## Failure visibility

- **Source read errors are player errors.** A Java exception in the
  stream-callback read path used to pause the GStreamer source task
  silently and permanently (frozen player, no event). `javasource` now
  posts a bus `GST_MESSAGE_ERROR`, which reaches the app as an ordinary
  `MediaException`.
- **Stall / preroll watchdog.** A pipeline that sits in Stalled, or
  stuck pre-Ready, with *zero* progress (no download movement, no
  position movement) posts a bus error after a timeout instead of
  hanging forever. This converts every future "player silently
  freezes" bug into a catchable error.
  - `OPENJFX_MEDIA_STALL_TIMEOUT=<seconds>` tunes it (default 45,
    `0` disables). Progress of any kind resets the countdown, so slow
    networks don't false-positive.
- **`Media.getFfmpegStatus()`** returns the loader's human-readable
  state: where ffmpeg loaded from and its versions, or precisely why it
  didn't (missing DLLs, ABI mismatch, mixed builds). Pair with
  `Media.isFfmpegAvailable()`. A FAILED ffmpeg load is retryable after
  `Media.setFfmpegDirectory(dir)` points somewhere new (success
  latches; failure no longer poisons the process).

## Stream integrity

- **`Content-Range` verification.** Every chunked-HTTP range rotation
  checks that the 206 response actually starts at the requested byte; a
  contradicting offset is treated as a failed rotation (retried, then
  open-ended range, then clean EOS + one-shot warning) instead of
  feeding misaligned bytes into the demuxer.
- **ID3v2-aware sniffing.** A leading ID3v2 tag is skipped (bounded at
  4 MB) and the first real frame is sniffed, so tagged ADTS `.aac`
  files route to `aacparse` instead of dying in the MP3 parser. An
  unreadable/oversized tag falls back to the legacy "ID3 means MP3"
  assumption.
- **ffmpeg DLL loading** is deterministic: a configured directory is
  canonicalized to an absolute path and loaded with
  `LOAD_LIBRARY_SEARCH_*` flags (a DLL's own dependencies resolve from
  its own directory first); the PATH fallback walks `PATH` entries
  explicitly and never loads from the process working directory
  (classic planting vector).

## Lifecycle

- **Disposed players are inert.** `dispose()` races events already
  queued on the FX thread; every `GSTMediaPlayer` operation snapshots
  the media reference and no-ops when it is gone (was: FX-thread
  NullPointerException — found by the harness below on its first run).
- **Native leak counters** (`SKIA_MEDIA_DEBUG=1`): key media native
  objects count constructions/destructions and print a balance at
  process exit; any imbalance is flagged `<-- LEAK`.

## The harness

`samples/ensemble` ships `MediaStressApp` (gradle task
`runMediaStress`). Exit code 0 = PASS.

```
# Dispose-under-load: 30 iterations of create/play/seek/setRate/dispose
# races, incl. disposes mid-preroll. Hung dispose or crash = FAIL.
gradlew :samples:ensemble:runMediaStress -Dstress.mode=stress ^
    -Dstress.media=D:\Music\final-mix.mp4 -Dstress.iterations=30

# Malformed-input corpus: generates truncated / bit-flipped / zeroed /
# empty variants from a seed file, then requires every one to reach
# READY or a MediaException — never a crash, never a hang.
gradlew :samples:ensemble:runMediaStress -Dstress.mode=corpus ^
    -Dstress.dir=F:\tmp\media-corpus ^
    -Dstress.media=D:\Music\final-mix.mp4 -Dstress.generate=true
```

Run with `SKIA_MEDIA_DEBUG=1` to get the leak balance at the end;
`OPENJFX_MEDIA_VERBOSE=1` adds the pipeline diagnostics.

Baseline results (2026-06-10, Windows): stress 30/30 PASS, corpus 9/9
clean verdicts, leak counters balanced in both modes.

## Known limits

- Audio-device removal/switch mid-playback relies on directsoundsink's
  error propagation (pipeline error → `MediaException`) or, if the sink
  wedges instead, the stall watchdog. Seamless device-switch recovery
  (rebuild the sink, reinstall the clock) is future work.
- The watchdog monitors Stalled and preroll only; a frozen-but-Playing
  pipeline is covered by the dual-source resync detector, not the
  watchdog.
