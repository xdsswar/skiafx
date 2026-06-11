# Showcase Dashboard + Stress Benchmark

A standalone sample app (under `samples/ensemble/`, **no library module touched**)
that doubles as a wide-coverage rendering stress test for the Skia pipeline and
as an A/B benchmark harness against a stock OpenJFX 25 SDK.

## Run

```bash
# Full showcase: custom title bar, animated sidebar, all node types, FPS badge
./gradlew :samples:ensemble:runShowcase

# Portable stress benchmark (writes a metrics CSV)
./gradlew :samples:ensemble:runBench
```

## What's in it

| File | Role |
|---|---|
| `ShowcaseApp.java` | `Application` entry; `StageStyle.CUSTOM`, loads the FXML shell, attaches the stylesheet. |
| `showcase.fxml` | Shell layout: custom title bar + collapsible sidebar + content `StackPane` + status bar. |
| `ShowcaseController.java` | All behaviour: animated sidebar toggle, responsive auto-collapse, loader-gated section switching, every section's nodes, global FPS badge, title-bar hit-region install. |
| `showcase.css` | Single light-theme stylesheet (design tokens → title bar → sidebar → controls → charts → status/FPS → loader). |
| `StressScene.java` | Shared, **stock-API-only** particle field + `BenchPanel` (metrics) + CSV `Recorder`. |
| `ShowcaseBenchmark.java` | Portable `DECORATED` benchmark window — runs on a stock SDK for true A/B. |

### Sections (sidebar)

Overview (KPI cards, mini charts, pulsing heartbeat) · Controls (≈every
`javafx.controls` node) · Charts (all 8 `javafx.scene.chart` types) · Shapes &
Canvas (all shape primitives + an animated `GraphicsContext` wave) · Effects
(`javafx.scene.effect` gallery + live blur) · Animation Lab (every `Transition`
type) · Benchmark (`StressScene.BenchPanel`).

Each section is built lazily and revealed behind a `DualLoader` spinner with a
fade/translate/scale entrance.

## Metrics CSV (for A/B charting)

The Benchmark section and `ShowcaseBenchmark` append one row per ~500 ms window:

```
t_ms,fps,frame_ms,avg_ms,p99_ms,nodes,pipeline
```

Default path: `build/bench/showcase-metrics-<label>.csv`.

System properties (forwarded by the gradle tasks from `-P`/`-D`):

| Property | Default | Meaning |
|---|---|---|
| `showcase.bench.label` | `skia-fx` (gradle) / sniffed | Tag written into the `pipeline` column + filename. |
| `showcase.bench.nodes` | `1500` (bench) / `1200` (section) | Initial particle count. |
| `showcase.bench.out` | `build/bench/showcase-metrics-<label>.csv` | Override output file. |
| `showcase.bench.record` | `true` | `false` disables logging. |

### A/B vs stock OpenJFX 25

`ShowcaseBenchmark` + `StressScene` use only stock JavaFX API, so the identical
workload runs on a vanilla SDK:

```bash
# skia-fx run → build/bench/showcase-metrics-skia-fx.csv
./gradlew :samples:ensemble:runBench

# stock run → build/bench/showcase-metrics-stock.csv
java --module-path <javafx-sdk-25>/lib --add-modules javafx.controls \
     -Dshowcase.bench.label=stock \
     -cp samples/ensemble/build/classes/java/main \
     org.openjfx.samples.ensemble.ShowcaseBenchmark
```

Load both CSVs into any charting tool and compare `fps` / `frame_ms` / `p99_ms`
at matching `nodes`.

> Note: the showcase shell (custom title bar via `StageStyle.CUSTOM` + caption
> hit regions) uses skia-fx-only API and is **not** part of the portable
> benchmark — that's deliberate, so the benchmark stays stock-runnable.
