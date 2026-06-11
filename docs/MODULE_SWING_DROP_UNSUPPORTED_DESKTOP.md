# javafx.swing — dropping the `jdk.unsupported.desktop` dependency

> Status: experimental, like the rest of skia-fx. Not a stable API surface.

## Why

`javafx.swing` used to declare:

```java
requires jdk.unsupported.desktop;
```

`jdk.unsupported.desktop` is a JDK module that exists for exactly one purpose:
to expose a small, curated set of wrapper classes (`jdk.swing.interop.*`) over
JDK internals so JavaFX could implement `JFXPanel` and `SwingNode` without
`--add-exports`. That module is **deprecated and slated for removal** from the
JDK. To keep moving forward across JDK updates, skia-fx no longer depends on it.

## What changed

The six wrapper classes we used were ported, verbatim, out of the JDK module and
into `javafx.swing` itself, under a new package:

```
com.sun.javafx.embed.swing.interop
    LightweightFrameWrapper      -> wraps sun.swing.JLightweightFrame
    LightweightContentWrapper    -> implements sun.swing.LightweightContent
    DragSourceContextWrapper     -> extends sun.awt.dnd.SunDragSourceContextPeer
    DropTargetContextWrapper     -> implements java.awt.dnd.peer.DropTargetContextPeer
    DispatcherWrapper            -> implements sun.awt.FwDispatcher
    SwingInterOpUtils            -> sun.awt.SunToolkit grab/ungrab + UngrabEvent
```

These are faithful copies of the OpenJDK sources (GPL v2 + Classpath
exception — the same license as the surrounding JavaFX code), repackaged and
commented. The behaviour is intentionally identical to the old interop layer.

The four `newimpl` consumers only changed their imports
(`jdk.swing.interop.*` → `com.sun.javafx.embed.swing.interop.*`):

- `JFXPanelInteropN`
- `SwingNodeInteropN` (also updated the `Class.forName(...)` string used by the
  glass native `overrideNativeWindowHandle` JNI lookup)
- `FXDnDInteropN`
- `SwingFXUtilsImplInteropN`

`module-info.java` dropped the `requires jdk.unsupported.desktop;` line.

## The cost: `--add-exports`

The wrappers reach JDK-internal packages that `java.desktop` does **not** export
to us. `jdk.unsupported.desktop` got them via *qualified exports*; we can't,
so we re-open exactly the same four packages with `--add-exports`:

```
--add-exports java.desktop/sun.swing=javafx.swing
--add-exports java.desktop/sun.awt=javafx.swing
--add-exports java.desktop/sun.awt.dnd=javafx.swing
--add-exports java.desktop/java.awt.dnd.peer=javafx.swing
```

This is the unavoidable trade: a named module accessing a non-exported package
of another named module needs `--add-exports` (or `--add-opens` for reflection)
at **both** compile and runtime. There is no manifest-based escape for modules.

### Compile time

Wired in `javafx.swing/build.gradle`. Note that `--add-exports` against a
**system** module is rejected under `--release`, so this module compiles with
explicit `-source/-target 25` instead of `--release 25` (which also keeps
`--enable-preview` valid).

### Runtime — REQUIRED for any app using JFXPanel or SwingNode

Any application that uses `JFXPanel` or `SwingNode` must pass the same four
flags on the launch command:

```
java --add-exports java.desktop/sun.swing=javafx.swing \
     --add-exports java.desktop/sun.awt=javafx.swing \
     --add-exports java.desktop/sun.awt.dnd=javafx.swing \
     --add-exports java.desktop/java.awt.dnd.peer=javafx.swing \
     --module-path <sdk>/lib --add-modules javafx.swing,javafx.controls \
     -m your.app/your.Main
```

Without them, the app boots fine but throws `IllegalAccessError` the first time
the interop code runs (e.g. on the first mouse press inside a `JFXPanel`, which
triggers the ungrab listener → `sun.awt.UngrabEvent`).

> Gradle gotcha observed while wiring the demo: the `application` plugin's
> `applicationDefaultJvmArgs` convention is detached from the `run` task as soon
> as anything calls `jvmArgs(...)` on it (our `java-conventions` adds
> `--enable-preview` to every `JavaExec`). Set the `--add-exports` *directly* on
> the `run`/`JavaExec` task, not only via `applicationDefaultJvmArgs`.

## Demo / verification

`samples/swinginterop` exercises the change end to end:

```
./gradlew :samples:swinginterop:runSelfCheck   # headless: prints PASS/FAIL, exits
./gradlew :samples:swinginterop:run            # windowed JFXPanel + SwingNode demo
```

The self-check asserts at runtime that `javafx.swing` no longer requires
`jdk.unsupported.desktop` and that `LightweightFrameWrapper` resolves from the
`javafx.swing` module. The windowed demo embeds a JavaFX scene inside a Swing
`JFrame` via `JFXPanel` (verified rendering through the Skia pipeline) and,
where the native is available, a Swing button inside the FX scene via
`SwingNode`. Headless snapshot for CI:

```
./gradlew :samples:swinginterop:run \
  -Pswinginterop.autoExitMs=8000 \
  -Pswinginterop.shotPath=build/shot.png
```

## Known limitation (NOT caused by this change)

`SwingNode` (embedding Swing *inside* JavaFX) eagerly loads the native
`prism_common` library in its constructor (`Utils.loadNativeSwingLibrary`).
skia-fx does not build `prism_common` yet (upstream sources:
`javafx.graphics/src/main/native-prism/{PrismPrint,SwingInterop}.c`), so
`SwingNode` is currently unavailable in the dev tree and the demo degrades
gracefully with a notice. `JFXPanel` (embedding JavaFX inside Swing) is
unaffected and works. Building `prism_common` is tracked separately.

## Future risk

This approach keeps working for as long as the underlying
`sun.swing.JLightweightFrame` / `sun.awt.dnd.*` classes exist in `java.desktop`
— `--add-exports` works regardless of whether `jdk.unsupported.desktop` is
present. If a future JDK removes those `sun.*` classes themselves (not just the
wrapper module), `SwingNode`/`JFXPanel` would need a deeper reimplementation;
that is out of scope here.
