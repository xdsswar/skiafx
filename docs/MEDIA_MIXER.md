# MediaMixer — merge downloaded audio + video into one MP4

> Status: experimental, Windows-first (like the rest of the skia-fx
> media work). API may change.

`javafx.scene.media.MediaMixer` merges a separately-downloaded audio
file and video file into a single MP4 by **lossless stream copy** — no
re-encoding, so it is fast (hundreds of MB in seconds) and quality is
untouched. The typical use is recombining adaptive-streaming downloads
(e.g. a video-only WebM and an audio-only WebM/M4A) into one locally
playable file.

## API

```java
MediaMixer mixer = new MediaMixer(audioPath, videoPath, outputPath);
mixer.setListener(new MediaMixerListener() {
    @Override public void onStart()               { /* mixing began */ }
    @Override public void onProgress(double p)    { /* 0.0 .. 1.0 */ }
    @Override public void onFinished(String path) { /* absolute output path */ }
    @Override public void onError(String message) { /* failure or "cancelled" */ }
});
mixer.start();
```

- Inputs and output are local **paths or `file:` URIs**; remote URLs are
  rejected (download first — the dual-source `Media(audio, video)`
  constructor is the streaming path).
- All `MediaMixerListener` methods run on the **FX application thread**
  and have empty defaults.
- `start()` runs the mix on a private daemon thread. To control
  threading (bounded pools, several concurrent mixes), use
  `start(Executor)` — independent `MediaMixer` instances are fully
  concurrent (the engine keeps no shared mutable state).
- `cancel()` stops at the next packet boundary; `onError("cancelled")`
  fires and the partial output file is left on disk.
- A mixer instance runs **once**.

## Requirements

- The ffmpeg runtime must be available — check with
  `Media.isFfmpegAvailable()`; configure with
  `Media.setFfmpegDirectory(dir)` or the `OPENJFX_MEDIA_FFMPEG_DIR`
  environment variable. Without it the mixer fails fast through
  `onError` (and playback of ffmpeg-decoded formats is disabled, while
  MP4/AAC/H.264, MP3 and WAV still play on the platform decoders).
- Streams are **copied**, so both codecs must be MP4-compatible:
  H.264 / H.265 / AV1 / VP9 video and AAC / MP3 / Opus audio cover the
  common adaptive-streaming formats. Incompatible codecs fail with a
  clear `onError` message from the muxer.

## Implementation map

| Layer | Where |
|---|---|
| Public API | `javafx.scene.media.MediaMixer`, `MediaMixerListener` |
| Impl glue (worker, callbacks, cancel flag) | `com.sun.media.jfxmediaimpl.NativeMediaMixer` |
| JNI bridge (jfxmedia.dll) | `jfxmedia/jni/MediaMixerBridge.cpp` — resolves the engine cross-DLL via `GetProcAddress`, same pattern as `MediaFfmpegConfig` |
| Remux engine (fxplugins.dll) | `gstreamer/plugins/ffmpegwrapper/ffmpeg_remux.cpp` — `openjfx_ffmpeg_remux(...)` |
| ffmpeg access | the runtime loader's optional avformat symbol group (`ffmpeg_loader.h`, `remux_ok`) |

The engine opens both inputs, picks the best video stream of the video
file and the best audio stream of the audio file, copies codec
parameters into an mp4 muxer, and writes packets interleaved by
presentation time. Progress is the written timeline over the larger
input duration, reported at ≥1% steps. Every failure path returns a
human-readable message; nothing in the engine can abort the JVM.

## Demo

```
gradlew :samples:ensemble:runMixerDemo ^
    -Pmixer.audio=D:\Music\audio.m4a ^
    -Pmixer.video=D:\Music\video.webm ^
    -Pmixer.out=D:\Music\final-mix.mp4
```

Shows a progress bar and offers to play the result in-place.

## Output details

- **Fast start** is ON by default: the index (moov atom) is relocated
  to the file head at finalize time, so the MP4 can start playing
  before it has fully downloaded when served progressively. The final
  progress step takes slightly longer (the muxer makes one extra pass).
  `setFastStart(false)` opts out for the fastest possible finish on
  local-only files.
- **Opus/Vorbis/FLAC audio inside MP4** plays in skia-fx too: the MP4
  playback pipeline swaps its preset platform AAC decoder for the
  ffmpeg decoder when the demuxer announces caps the preset can't
  handle (`SwapAudioDecoderIfNeeded`, the audio analogue of the video
  side's dynamic decoder loading).
