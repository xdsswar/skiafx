# SkPicture caching for the Skia effect engine — design

## Why

The Phase-3 Skia effect engine works correctly at idle (100+ fps with
all Modena shadows rendering via Skia, `copies=0` per frame), but
drops to single-digit fps during a continuous `WM_SIZE` drag-resize
even with:

- ✅ Hot-path peers implemented (no passthrough churn)
- ✅ 128-px-bucketed `ImagePool` (`allocs=0`/sec at idle and drag)
- ✅ Per-peer filter handle caching (no `sk_sp<SkImageFilter>` rebuild
  per call)

The remaining cost is structural: every effect-bearing node runs
`SkCanvas::saveLayer + SkImageFilter + restore` *per frame*. For
Modena scrollbar arrows (radius-1 dropshadow on every arrow) +
scrollbar thumb shadow, that's ~3 effect dispatches per frame at the
window's update cadence (~80 Hz during drag). Each `saveLayer` is a
Skia/Ganesh synchronization barrier and the GPU pipeline can't
overlap them; each filter pass allocates a transient Ganesh-side
offscreen surface that the cache misses on when the bounds shift
slightly each frame.

The structural fix (per the project's caching/zero-copy goals):

> *"Static subtree caching (SkPicture) — record stable subtrees
> once, replay each frame. Massive win for high-fps redraws of
> mostly-static UI."*

Most JFX effect output is in fact static at the pixel level for the
duration of a drag — the scrollbar arrow's icon and its dropshadow
look identical every frame; only its position within the window
changes. If we can record the effect output once and replay (just
matrix-translate) on subsequent frames, the GPU cost collapses to
~the cost of a `drawPicture` (a single-pass blit, no saveLayer, no
filter recomputation).

## Native bridge additions (5 functions)

`modules/javafx.graphics/src/main/native-skia/shared/openjfx_skia_bridge.{h,cpp}`:

```c
/* SkPictureRecorder lifecycle. Recorder objects are owned by the
 * native side; Java holds an opaque uintptr_t handle. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_create(void);

/* Start recording into the recorder. Returns an SkCanvas handle that
 * Java can use as a draw target (the existing surface_* draw calls
 * accept a canvas handle, not just a surface). Bounds clip the
 * recording. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_begin(
    uintptr_t recorderHandle, float x, float y, float w, float h);

/* Finish recording and produce an SkPicture handle.
 * Recorder is reusable. Returns 0 on failure. */
OPENJFX_API uintptr_t openjfx_skia_picture_recorder_finish(
    uintptr_t recorderHandle);

OPENJFX_API void     openjfx_skia_picture_destroy(uintptr_t pictureHandle);
OPENJFX_API void     openjfx_skia_picture_recorder_destroy(uintptr_t recorderHandle);

/* Draw a recorded SkPicture onto a surface (or canvas) with an
 * optional matrix transform. dx,dy translate the picture's origin. */
OPENJFX_API int32_t openjfx_skia_surface_draw_picture(
    uintptr_t surfaceHandle, uintptr_t pictureHandle,
    float dx, float dy);
```

Plus matching FFM bindings in `NativeBridge.java` — same pattern as the
existing `filterBlur/filterDropShadow/...` wrappers.

## Per-peer cache state

Each peer instance maintains:

```java
private MemorySegment cachedPicture;   // SkPicture handle (or null)
private long          cachedInputKey;  // identity hash of last input
private int           cachedInputGen;  // generation counter at record time
private long          cachedStateKey;  // sigma/color/etc state hash
private Rectangle     cachedBounds;    // input bounds at record time
```

## Cache key — input identity

The hard part. "Same input" means: same source content + same source
bounds. We have two options:

### Option A — drawable identity + generation counter (recommended)

Each `SkiaPrDrawable` exposes a `volatile long generation` field that
increments every time its surface is drawn into (i.e., `createGraphics`
issues a draw command). Peers cache by:

```java
inputKey = System.identityHashCode(input.getUntransformedImage())
inputGen = ((SkiaPrDrawable) input.getUntransformedImage()).getGeneration()
```

Cache hit ⇔ `inputKey == cachedInputKey && inputGen == cachedInputGen`.

Requires augmenting `SkiaPrDrawable` with the generation counter,
plus a hook in `createGraphics()`-returned Graphics so the counter
ticks on actual draws. The pulse listener can also reset generations
each frame for fresh inputs.

### Option B — content hash (rejected)

Hashing the input's pixel content per call requires a GPU readback,
which is exactly what we eliminated in the rest of the project. Don't
do this.

## Peer flow

```java
public ImageData filter(...) {
    long inK = identityHashCode(input);
    int  inG = ((SkiaPrDrawable) input.getUntransformedImage()).getGeneration();
    long stK = stateHash(rstate);

    if (cachedPicture != null
        && inK == cachedInputKey
        && inG == cachedInputGen
        && stK == cachedStateKey) {
        // Cache hit — replay onto a fresh small destination.
        SkiaPrDrawable dst = (SkiaPrDrawable)
            getRenderer().getCompatibleImage(cachedBounds.width, cachedBounds.height);
        MemorySegment dstSeg = MemorySegment.ofAddress(dst.getSurfaceHandle());
        NativeBridge.surfaceDrawPicture(dstSeg, cachedPicture, 0, 0);
        return new ImageData(fctx, dst, cachedBounds);
    }

    // Cache miss — record fresh.
    if (cachedPicture != null) NativeBridge.pictureDestroy(cachedPicture);
    MemorySegment rec = NativeBridge.pictureRecorderCreate();
    MemorySegment canvas = NativeBridge.pictureRecorderBegin(rec,
        dstBounds.x, dstBounds.y, dstBounds.width, dstBounds.height);
    // ... do the saveLayer+filter+restore against the recorder canvas
    //     instead of a destination surface ...
    cachedPicture = NativeBridge.pictureRecorderFinish(rec);
    NativeBridge.pictureRecorderDestroy(rec);
    cachedInputKey = inK;
    cachedInputGen = inG;
    cachedStateKey = stK;
    cachedBounds   = dstBounds;

    // Replay into destination for this frame.
    // ... (same as cache-hit branch)
}
```

## Cache eviction

Per-peer caches hold at most **one** picture (the most recent). Memory
footprint is bounded by `peer_count × avg_picture_size`. With ~13
distinct peer classes and most pictures < 100 KB, total bound is
< 2 MB. No LRU needed at this scale.

If we later want multi-entry caches (e.g. a peer instance serves
many different scrollbars with different sigmas simultaneously), a
size-bounded LRU per peer is a small addition.

## Invalidation

Picture is automatically invalidated by the input-generation check on
the next call. We don't need to listen for input changes — the cache
key takes care of it.

A peer's `dispose()` releases its cached picture:

```java
@Override public void dispose() {
    if (cachedFilter != null)  { NativeBridge.filterDestroy(cachedFilter); ... }
    if (cachedPicture != null) { NativeBridge.pictureDestroy(cachedPicture); ... }
}
```

## Risk: the `Generation` plumbing

The biggest piece of work is the generation counter on
`SkiaPrDrawable`. It needs to tick on every draw command issued
through that drawable's Graphics. The current `SkiaGraphics` doesn't
have this hook. Adding it:

1. `SkiaPrDrawable` holds a `volatile long generation`.
2. `SkiaPrDrawable.createGraphics()` returns a `SkiaGraphics` wrapped
   such that every draw call increments the counter. We can either
   wrap the existing `SkiaGraphics` in a delegating Graphics that
   bumps on each method, or augment `SkiaGraphics` itself to take a
   "generation owner" reference and self-bump.
3. `SkiaGraphics.dispose()` (or equivalent) is the natural place to
   bump generation once per draw session, rather than per-call.

Choice between per-call vs per-session bumping affects cache hit
rate: per-call is more granular but expensive; per-session under-
counts but is essentially free. For effect peer caching, per-session
is correct because peers only consume their input after the input's
Graphics has been disposed.

## Verification plan

1. **Per-peer pixel-diff test** — render a node with each effect at
   identical params twice; second call should produce byte-identical
   output via cache replay.
2. **Stutter test** — drag-resize the demo with effects on; per-second
   `[skia]` log should show fps roughly equal to the no-effects
   baseline (currently ~80 fps), with `allocs=0` AND `checkouts` at a
   low number (~1 per peer per frame for replay, vs. ~3 per peer per
   frame currently).
3. **Memory bound** — long-running soak test (drag for 30 seconds);
   total GPU memory should not climb above the steady-state by more
   than the per-peer cache size sum.

## Out of scope for this design

- Sharing pictures *across* peer instances (a global picture cache
  keyed by state hash). Could be a follow-on but isn't necessary for
  the drag-resize stutter.
- `SkPicture` recording inside JFX's existing intrinsic peers
  (PrCropPeer, PrFloodPeer, PrMergePeer, PrReflectionPeer). Those are
  pipeline-agnostic and don't suffer the per-call filter cost.
- Effect bounds expansion math (`outputClip`) edge cases — peers
  already handle these correctly for the non-cached path; the cached
  path replays the same recorded bounds.
