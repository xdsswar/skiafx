> **Status: DESIGN SPEC — not yet implemented. Planned post-core module.**
>
> This is design *intent*, not a finalized contract. The implementation is
> not worked out yet — class names, the JNI signatures in §15, the PDFium
> build/provenance, and how rendering binds to the core Skia surface may all
> change once we actually build it. Treat every signature below as
> illustrative. The architectural anchoring (how it attaches to the skia-fx
> core, what stays general, build ordering) lives in
> [FUTURE_MODULES.md](FUTURE_MODULES.md); read that first. Revise this file
> as the real implementation lands; don't let it ossify.

---

# `javafx.pdf` — Module Design Specification

A JavaFX PDF viewer/editor module backed by a native **C++ / PDFium** engine
with **Skia** rendering. JavaFX is a thin, observable mirror; the native side
owns the document and executes all rendering and mutation.

---

## 1. Design Principles

1. **One public control.** `PdfViewer` is the only entry-point control. Everything
   else (page nodes, layouts, thumbnails, JNI) is internal implementation.
2. **Native owns truth, Skia renders.** PDFium owns the document, annotations,
   form fields, fonts, and images. It rasterizes pages into bitmaps that Skia
   composites. JavaFX never draws document content.
3. **Properties are the API.** Callers bind and listen to JavaFX properties.
   Navigation, zoom, view mode, etc. are property mutations, not method soup.
4. **No native handles in the public API.** No `long` pointers, no PDFium int
   codes leak out. Handles live in non-exported packages; JPMS enforces it.
5. **Pixels never cross JNI.** Java sends indices, zoom, rects, and commands.
   Native renders into a Skia surface/texture and returns opaque image references.
6. **Document owns, viewer edits, page renders.** Annotations/fonts/images are
   owned by `PdfDocument`; `PdfViewer` exposes and edits them; transient
   `PdfPageNode`s only render.

---

## 2. Module Layout

```
javafx.pdf            (EXPORTED — public API only)
javafx.pdf.skin       (NOT exported — control skin + internal scene graph)
javafx.pdf.internal   (NOT exported — JNI bridge, native bindings, caches)
```

```java
module javafx.pdf {
    requires javafx.controls;
    requires javafx.graphics;

    exports javafx.pdf;          // public surface only
    // javafx.pdf.skin / javafx.pdf.internal intentionally NOT exported
    // (optionally: opens javafx.pdf to javafx.fxml;)
}
```

---

## 3. Public API Surface

### 3.1 Control

| Class | Kind | Role |
|-------|------|------|
| `PdfViewer` | `Control` | The only public control. Properties + skin. |
| `PdfDocument` | class | Observable handle; **owns** annotations/fonts/images. |

### 3.2 Models (opaque — no handles exposed)

| Class | Role |
|-------|------|
| `Annotation` (abstract) | Base for markup annotations. |
| `HighlightAnnotation`, `InkAnnotation`, `TextNoteAnnotation`, `StampAnnotation` | Concrete annotation types. |
| `FormField` | Observable mirror of a PDFium form field. |
| `PdfFont` | Opaque font reference (document resource). |
| `PdfImage` | Opaque image content reference. |

### 3.3 Enums & support types

`ViewMode`, `ZoomMode`, `AnnotationTool`, `AnnotSubtype`, `FontFormat`,
`SubmitFormat`, `FormFieldType`, `AutoCompleteItem`, `FormAutoCompleteContext`,
`FormAutoCompleteProvider`.

---

## 4. `PdfViewer` Properties

### Document & navigation
| Property | Type | Notes |
|----------|------|-------|
| `document` | `ObjectProperty<PdfDocument>` | swap docs live |
| `currentPage` | `IntegerProperty` | read/write = navigate |
| `pageCount` | `ReadOnlyIntegerProperty` | derived from doc |
| `scrollDirection` | `ObjectProperty<Orientation>` | vertical / horizontal |

### Zoom & layout
| Property | Type | Notes |
|----------|------|-------|
| `zoom` | `DoubleProperty` | bindable to a slider |
| `zoomMode` | `ObjectProperty<ZoomMode>` | `CUSTOM / FIT_WIDTH / FIT_PAGE / FIT_HEIGHT / ACTUAL` |
| `viewMode` | `ObjectProperty<ViewMode>` | `SINGLE / CONTINUOUS / TWO_UP / TWO_UP_COVER` |

### Thumbnails
| Property | Type | Notes |
|----------|------|-------|
| `thumbnailsVisible` | `BooleanProperty` | toggle side strip |
| `selectedThumbnail` | `ReadOnlyIntegerProperty` | mirrors `currentPage` |

### Forms
| Property | Type | Notes |
|----------|------|-------|
| `formFillingEnabled` | `BooleanProperty` | enable interactive fill |
| `formFields` | `ReadOnlyObjectProperty<ObservableList<FormField>>` | enumerated fields |
| `focusedField` | `ObjectProperty<FormField>` | currently focused |
| `formModified` | `BooleanProperty` | dirty flag |
| `autoCompleteProvider` | `ObjectProperty<FormAutoCompleteProvider>` | suggestion hook |

### Annotations
| Property | Type | Notes |
|----------|------|-------|
| `annotationTool` | `ObjectProperty<AnnotationTool>` | active drawing tool |
| `selectedAnnotation` | `ObjectProperty<Annotation>` | selection |
| `annotationsVisible` | `BooleanProperty` | render embedded annots |

### Text selection & search
| Property | Type | Notes |
|----------|------|-------|
| `textSelectionEnabled` | `BooleanProperty` | drag to select |
| `selectedText` | `ReadOnlyStringProperty` | bind to copy button |
| `searchQuery` | `StringProperty` | current find term |
| `searchMatchCount` | `ReadOnlyIntegerProperty` | hits |
| `currentSearchMatch` | `IntegerProperty` | navigate hits |

### Methods (kept minimal — most behavior is property-driven)
```java
void copySelection();
HighlightAnnotation highlightSelection(Color color);
void setFieldValue(String name, String value);
String getFieldValue(String name);
PdfImage addImage(int page, Image image, Rectangle2D bounds);
void removeImage(PdfImage image);
PdfFont loadFont(Path file);
void findNext(); void findPrevious();
void saveAs(Path out);        // editable
void flattenAndSaveAs(Path out);
boolean canUndo(); void undo();
boolean canRedo(); void redo();
```

---

## 5. `PdfDocument` — the Owner

```java
public class PdfDocument {
    // lifecycle / state
    ReadOnlyBooleanProperty loadingProperty();
    ReadOnlyBooleanProperty loadedProperty();
    ReadOnlyObjectProperty<Throwable> errorProperty();
    ReadOnlyBooleanProperty passwordRequiredProperty();
    ReadOnlyIntegerProperty pageCountProperty();

    // owned resources (mirrors of native truth)
    ObservableList<Annotation> getAnnotations();
    FilteredList<Annotation>   annotationsOnPage(int page);
    ObservableList<PdfFont>    getFonts();
    ObservableList<PdfImage>   getImages();

    // factories
    static PdfDocument open(Path path);
    static PdfDocument open(Path path, String password);
    static PdfDocument open(InputStream in);

    // resources
    PdfFont loadFont(Path file);
    PdfFont loadFont(byte[] data, FontFormat fmt);

    // persistence
    void saveAs(Path out);
    void flattenAndSaveAs(Path out);

    void close();   // AutoCloseable — deterministic native cleanup
}
```

**Ownership rationale:** annotations/fonts/images persist on save, exist
independent of any view, and are shared if a doc is opened in two viewers.
The viewer is a *view*; page nodes are *transient* (virtualized). So the
**document** is the only correct owner.

---

## 6. Internal Scene Graph (`javafx.pdf.skin`)

```
PdfViewerSkin                       implements Skin<PdfViewer>
 ├─ PdfNode                         scrolling page area, input routing
 │   ├─ PdfPageNode (virtualized)   renders one page bitmap (Skia)
 │   │   └─ (overlays)
 │   │        ├─ TextSelectionOverlay   transient selection rects (CSS-styleable)
 │   │        └─ SearchHighlightOverlay  find-result rects
 │   └─ AutoCompletePopup           field autocomplete dropdown (CSS-styleable)
 └─ PdfThumbnailList                virtualized side strip
      └─ PdfThumbnailNode           one low-DPI preview per page
```

### Layout strategy (open for extension)
```java
interface PdfLayout {
    List<PageFrame> layout(PdfDocument doc, double zoom, Size viewport);
    double contentHeight();
}
// ContinuousLayout, SinglePageLayout, TwoUpLayout, HorizontalLayout ...
```
`PdfNode` picks a layout per `viewMode`, asks where pages go, and **only
instantiates page nodes whose frame intersects the viewport** (virtualization).

---

## 7. Rendering Pipeline

### Crisp/live dual-image zoom (pixel-perfect)
- **Static view:** re-rasterize the page from PDFium at exact `zoom × dpiScale`
  (vector → crisp). Draw 1:1 at integer origin, nearest sampling.
- **During active zoom:** GPU-scale the last crisp image (linear sampling) for
  smooth 100fps feedback.
- **On zoom settle (~debounce):** re-render crisp at final zoom on a worker
  thread, swap in.
- **Very high zoom (> max texture):** tile — render only the visible rect.

### Two-pass page render (forms)
```
FPDF_RenderPageBitmap(...)   // page content + embedded annotations
FPDF_FFLDraw(form, ...)      // form fields / widgets on top
→ wrap bitmap as SkImage → composite
```

### Invalidate loop
PDFium's `FFI_Invalidate` callback reports a dirty rect (caret blink, typed
char, checkbox toggle, show/hide). Bridge it to Java → re-render just that
page region. Keeps typing cheap.

> Implementation note (TBD): "wrap bitmap as SkImage → composite" runs on a
> worker thread and posts only an **opaque image reference** to the FX thread —
> pixels never cross JNI, and no draw happens on the live core surface off the
> render thread. This is the safe pattern relative to the render-thread-scoped
> surface rule in [FUTURE_MODULES.md §2.1](FUTURE_MODULES.md). Exact texture
> hand-off to the core compositor is settled when this module is built.

---

## 8. Annotations (mirror pattern, no handles)

```
Java Annotation object ──mutate──► JNI command ──► PDFium executes + GenerateContent
        ▲                                                   │
        └────────── sync back (binding id) ◄────────────────┘
                    page re-render → Skia composite
```

- `Annotation` exposes only observable properties (`bounds`, `color`,
  `contents`, read-only `page`) and an `AnnotSubtype` **enum**.
- The native link lives in a package-private `AnnotationBinding`
  (in `javafx.pdf.internal`, **not exported**). No `long` is reachable.
- **Identity** = an internal **registry id** (not a raw pointer) → stale objects
  fail safely instead of crashing the JVM.
- Add via `document.getAnnotations().add(a)`; remove via list removal.
  A **sync guard** prevents native→Java population from re-triggering creation.

---

## 9. Forms

- Initialized via PDFium **FormFill** (`FPDFDOC_InitFormFillEnvironment`).
- Fully functional **without JavaScript / V8** — text, checkbox, radio, combo,
  list, pushbutton, focus/tab order, programmatic get/set, save, flatten.
- **JavaScript actions are out of scope** (no V8 build). Calculations and
  validation, if needed, are implemented host-side in Java.
- Two fill paths, same native result: direct (mouse/keyboard → `FORM_On*`) and
  programmatic (`setFieldValue` → set string value). Both trigger invalidate →
  re-render.

### Field autocomplete hook
```java
viewer.setAutoCompleteProvider(ctx -> {
    if (!ctx.getFieldName().equals("customer_name")) return List.of();
    return customer.properties().stream()
        .filter(p -> p.startsWith(ctx.getTypedText()))
        .map(p -> new AutoCompleteItem(p, p))
        .toList();
});
```
- `FormAutoCompleteContext` carries: field name, typed text, caret, page, and
  the field's **bounds in screen coords** for popup anchoring.
- **Anchor to the focused-field rect** (cached on focus), not the caret —
  stable for single-line fields, browser-style UX.
- **Async-safe:** debounce (~120 ms) + monotonic sequence token to discard
  stale results.
- **Accept** = write the full value once (`set string value`) → re-render → close.
  Tab accepts and advances to the next field.
- Popup is a JavaFX node → CSS-styleable.

---

## 10. Fonts, Images, Text Selection

| Feature | Owner | Native | Render | Java role |
|---------|-------|--------|--------|-----------|
| Custom fonts | `PdfDocument` | `FPDFText_LoadFont` (embed + subset on save) | baked into page | command |
| Add images | `PdfDocument` | `FPDFPageObj_NewImageObj` | baked into page | command |
| Text selection | transient | `FPDFText_*` geometry | **Java overlay** | interactive |
| Permanent highlight | `PdfDocument` | annotation | baked into page | command |

**Text selection** is the one non-native render path: PDFium supplies char
geometry (`GetCharIndexAtPos`, `CountRects`, `GetRect`, `GetText`); a transient
**JavaFX overlay** paints the translucent rects (fast, CSS-styleable). A
selection can be promoted into a persistent native `HighlightAnnotation`.
Note: `GetRect` returns one rect per line; char order is PDF-internal, not
always visual.

---

## 11. Coordinate Transform (shared utility)

Built **once**, both directions, well-tested — reused by forms, autocomplete,
text selection, links, search, and annotations.

```
PDF points (bottom-left, Y-up)
   → page-local device px  (× zoom × dpiScale, Y-flip)
   → viewer-local          (+ page origin − scroll offset)
   → screen                (localToScreen)
```
Provide the inverse (screen → PDF) for click/drag routing. Validate by drawing
a debug rect over a focused field at multiple zooms and scroll positions.

> Cross-module note: the per-monitor backing-scale + Y-flip primitives this
> needs overlap with what `javafx.svg` keys its render cache on. See
> [FUTURE_MODULES.md §3.4](FUTURE_MODULES.md) — keep these reusable rather than
> private to one module.

---

## 12. Additional Viewer Features

| Feature | Native | Notes |
|---------|--------|-------|
| **Search** | `FPDFText_FindStart/FindNext` | reuse selection-rect highlight overlay |
| **Links** | link annotations | internal goto / URI → host handler |
| **Bookmarks / outline** | `FPDFBookmark_*` | navigation sidebar tree |
| **Printing** | render at print DPI | → JavaFX `PrinterJob` |
| **Page ops** | delete / import / rotate | insert, delete, reorder, rotate |
| **Passwords** | `FPDF_LoadDocument(password)` | `passwordRequired` property |

### Page order
`PdfPageNode` carries an immutable physical index + a mutable, observable
**display-order** property. Layout sorts by display order; the physical PDFium
index stays fixed for rendering.

---

## 13. Cross-Cutting Concerns

### Threading model
- **PDFium is not thread-safe** → serialize all native calls per document
  (one render/command queue per `FPDF_DOCUMENT`).
- Heavy rasterization runs on a **worker thread**; finished `SkImage`s are
  posted back to the FX thread.
- JS-style callbacks (dialogs) — N/A (JS dropped). Form/UI callbacks run on the
  FX thread.
- `FPDF_InitLibrary` once at module init; teardown at shutdown.

### Memory & lifecycle
- All native resources (page bitmaps, text pages, fonts, the doc) need explicit
  cleanup — JavaFX gives no finalizer guarantees.
- `PdfDocument` is `AutoCloseable`; `close()` is deterministic.
- Tile/bitmap cache uses **LRU eviction** so large docs don't exhaust memory.
- Closing a doc invalidates all annotation/font/image bindings (zero the ids).

### Undo / redo (decide now — architectural)
- Every mutation (add/remove/edit annotation, fill field, insert/remove image,
  page op) is a **command** with do/undo.
- A per-document command stack drives `canUndo/undo/canRedo/redo`.
- Retrofitting later touches every feature — bake it in from the start.

### HiDPI
- Render at physical pixels (`zoom × backingScale`). Account for per-monitor
  scale factors (multi-display) in print, thumbnails, and the transform.

---

## 14. CSS Styleability

`PdfViewer` declares `CssMetaData`; the skin consumes it. Internals stay hidden.

```css
.pdf-viewer {
    -pdf-page-gap: 12;
    -pdf-thumbnail-width: 140;
    -fx-background-color: #2b2b2b;
}
.pdf-page          { -fx-effect: dropshadow(gaussian, rgba(0,0,0,.4), 8, 0, 0, 2); }
.pdf-thumbnail:selected     { -fx-border-color: dodgerblue; -fx-border-width: 2; }
.pdf-text-selection         { -fx-fill: rgba(51,153,255,.35); }
.pdf-autocomplete-popup     { -fx-background-radius: 6; }
.pdf-autocomplete-item:selected { -fx-background-color: dodgerblue; }
```

Styleable properties: page gap, background, page border/shadow, thumbnail width,
selection color, autocomplete styling, page display-order.

---

## 15. JNI Boundary Contract

Thin: indices, zoom, rects, enums, opaque ids — **never pixels or raw pointers
in the public path.**

```java
// internal — javafx.pdf.internal
class NativePdf {
    static native long  openDocument(String path, String password);
    static native int   pageCount(long doc);
    static native float[] pageSize(long doc, int index);

    static native long  renderPage(long doc, int index, float zoom, float dpi,
                                   float clipX, float clipY, float clipW, float clipH,
                                   int priority);   // → opaque image ref, NOT pixels
    static native void  cancelRender(long token);
    static native void  releaseImage(long imageRef);

    // forms
    static native FormFieldInfo[] getFormFields(long doc);
    static native String getFieldValue(long form, String name);
    static native void   setFieldValue(long form, String name, String value);
    static native void   formOnChar(long form, int page, char c);

    // annotations (registry-id based)
    static native long  annotCreate(long doc, int page, int subtype,
                                    double l, double t, double r, double b, int argb);
    static native void  annotRemove(long doc, int page, long id);

    // text
    static native int   charIndexAtPos(long textPage, double x, double y);
    static native double[] textRects(long textPage, int start, int count);
    static native String   textRange(long textPage, int start, int count);

    static native void  closeDocument(long doc);
}
```

> Implementation note (TBD): JNI vs. FFM, the exact handle/opaque-id encoding,
> and whether `renderPage` returns a core texture handle or a private one are
> all unsettled. The project prefers FFM for new native interop; revisit when
> the module is built. The native lib (`javafx_pdf`) loads through
> `com.sun.glass.utils.NativeLibLoader` with a per-module `checksums.properties`
> manifest, same as the core libs ([FUTURE_MODULES.md §2.2](FUTURE_MODULES.md)).

---

## 16. Build Order (de-risked, dependency-ordered)

| Phase | Deliverable | Why |
|-------|-------------|-----|
| 0 | **Coordinate transform** (both directions) + threading/lifecycle contract | load-bearing for everything |
| 1 | `PdfDocument` + JNI open/close/pageSize | foundation |
| 2 | `PdfPageNode` rendering one page | prove pixel pipeline over JNI |
| 3 | `PdfNode` + `ContinuousLayout` + virtualization | the viewer; scroll |
| 4 | Zoom (crisp/live) + `ZoomMode` | pixel-perfect requirement |
| 5 | CSS metadata on the control | styling once stable |
| 6 | Page display-order + sorted layout | builds on layout |
| 7 | Thumbnail queue + side list | separate render path |
| 8 | FormFill init + two-pass render + invalidate loop | interactive forms |
| 9 | Input router → `FORM_On*` + autocomplete hook | fill by typing |
| 10 | Field enumeration → `FormField` list | programmatic fill / panel |
| 11 | Text selection overlay + extraction | copy/search foundation |
| 12 | Search highlight | reuse text layer |
| 13 | Annotation mirror (read → add → edit → remove) | with sync guard |
| 14 | Fonts + images (authoring commands) | content editing |
| 15 | Undo/redo command stack | wraps all mutations |
| 16 | Save / flatten | persist work |
| 17 | Bookmarks, links, printing, page ops | navigation + output |

---

## 17. Out of Scope

- **PDF JavaScript / V8** — dropped. Forms work fully without it; any
  calculation/validation is done host-side in Java.
