# Dual-Source Remote Audio Stutter Under 4K Load (open investigation)

Status: **mitigated, with one open follow-up.** Extensive hardening is in place
(below); the remaining case is remote audio stuttering on a weaker/saturated CPU
while decoding 4K video. Companion docs: `DUAL_SOURCE_MEDIA.md` (the dual-source
design and seek hardening), `MEDIA_HARDENING.md` (stall watchdog and recovery),
`MEDIA_FORMATS.md` (format tiers).

## Symptom

`Media(audioSource, videoSource)` played over http(s) — a video-only stream plus
an audio-only companion — stutters on the audio while the video is 4K and the CPU
is saturated. Local (file) playback of the same content is smooth; only remote
playback under heavy decode load stutters.

## Why audio is the sensitive path

Audio is the **pipeline master clock**. `GstAudioPlaybackPipeline` installs the
audio sink's clock when the sink reaches PAUSED and **never swaps it once
PLAYING** (a mid-PLAYING swap does not redistribute `base_time`, which would make
every video frame permanently late — "video racing in waves"). The video chain
syncs to that clock; a watchdog re-seeks only the video chain back onto the audio
position when it stalls or lags. Because the clock rides on audio, an audio
ring-buffer underrun does not just drop sound — it perturbs the master clock and
ripples into A/V sync.

## The starvation chain (leading explanation)

1. 4K video decode (+ Skia render) saturates the CPU.
2. The **companion-audio feed chain** — HTTP source → progressbuffer → demuxer —
   runs at **normal thread priority** and gets starved relative to the 4K decode
   threads, so it can't pull/refill fast enough over the network.
3. The progressbuffer read-ahead drains faster than it refills.
4. The encoded-audio queue drains (even at the 10 s dual-source default a
   multi-second scheduling stall empties it).
5. The audio sink **ring buffer underruns** — the last cushion (500 ms default)
   is spent.
6. Audio glitches; because the sink is the master clock, the clock jumps/pauses
   and video frames computed against it desync.

Local playback avoids this because the file source has no network feed
bottleneck — the kernel page cache absorbs the read jitter, so steps 2–4 don't
bite even under the same decode/render CPU load.

## Hardening already in place

- **Chunked HTTP range reads** (`ConnectionHolder`) — bounded range-request
  rotation defeats per-connection CDN drip-throttling of the audio stream
  (`-Dskia.media.httpChunkMB`, default 8 MB).
- **Dual-source queue sizing** (`GstPipelineFactory`) — encoded-audio queue
  defaults to ~10 s for dual-source (`OPENJFX_MEDIA_AUDIO_QUEUE_TIME`).
- **Ring buffer depth** — ~500 ms for dual-source (`OPENJFX_MEDIA_AUDIO_BUFFER_MS`).
- **Prebuffer read-ahead** — ~8 s for dual-source (`OPENJFX_MEDIA_PREBUFFER_TIME`).
- **Clock-install safety** — clock installed at PAUSED, never swapped mid-PLAYING.
- **Deferred dual-source resync** — a one-shot flushing seek re-segments both
  sources only if a real freeze is detected (polled), never eagerly at PLAYING.
- **Companion cushion tracking** — resume requires both video and audio cushions.
- **Post-seek video prime** — video QoS-sync briefly disabled so late frames
  render and catch up to the audio clock.
- **Seek coalescing** + **stall watchdog** — drag-storm debounce; a frozen
  pipeline becomes a catchable `MediaException` rather than a silent hang.

The sink's ring-buffer *writer* thread already runs at MMCSS "Pro Audio"
priority on Windows. The gap is **upstream** of it: the feed chain that keeps the
ring full does not.

## Open follow-up (not yet implemented)

The dual-source notes already flag this. Two candidate fixes, in order:

1. **Raise the audio feed/decode thread priority** so the 4K render/decode can't
   starve it — the HTTP source, progressbuffer and demuxer for the companion
   audio should run elevated (matching the sink writer's MMCSS tier), so the ring
   stays full under CPU pressure. This keeps audio as the master clock and is the
   minimal, targeted fix.
2. **Decouple audio from the master clock** — drive the timeline off the system
   clock instead, so an audio underrun no longer stalls/perturbs the clock. This
   trades some short-term A/V sync stability for immunity to feed starvation; a
   larger change, only if (1) is insufficient on the weakest targets.

## Tuning knobs (opt-in, for diagnosis)

`OPENJFX_MEDIA_AUDIO_BUFFER_MS`, `OPENJFX_MEDIA_AUDIO_QUEUE_TIME`,
`OPENJFX_MEDIA_PREBUFFER_TIME` (and `OPENJFX_MEDIA_DUAL_DEFAULTS` to gate the
dual-source defaults; `-Dskia.media.httpChunkMB` for the HTTP chunk size).
`OPENJFX_MEDIA_VERBOSE` prints a ~1 Hz per-cycle heartbeat (position, queue fill,
state) to identify which element starves first.

## Key files

- `javafx.media/src/main/native/.../GstAudioPlaybackPipeline.cpp` — clock master,
  resync, cushion tracking, watchdog.
- `javafx.media/src/main/native/.../GstAVPlaybackPipeline.cpp` — dual-source A/V
  watchdog, post-seek video prime.
- `javafx.media/src/main/native/.../GstPipelineFactory.cpp` — queue/ring/prebuffer
  sizing and the env-var overrides.
- `javafx.media/src/main/java/.../Locator.java`,
  `.../ConnectionHolder.java` — companion-audio HTTP fetch + chunked range reads.
