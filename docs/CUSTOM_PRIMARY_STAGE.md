# Custom primary stage — `Application<W extends Stage>`

**Status:** implemented (skia-fx extension over stock OpenJFX 25)
**Modules:** `javafx.graphics`
**Public API touched:** `javafx.application.Application`, `javafx.application.Preloader`

## What this is

Stock OpenJFX hard-codes the type of the primary window. The launcher does
`new Stage()` and calls `Application.start(Stage)`, so an application can
never receive a *custom* `Stage` subclass as its primary window — even
though secondary windows can be any subclass.

skia-fx makes `Application` generic on its stage type so the primary
window can be a custom `Stage` subclass (e.g. an undecorated stage that
paints its own title bar via `StageStyle.CUSTOM`, or one that exposes
app-specific window API) — **without writing any factory code**:

```java
public abstract class Application<W extends Stage> {
    public abstract void start(W primaryStage) throws Exception;
    protected W createPrimaryStage();   // default: reflectively builds W
}
```

You just declare `extends Application<MyStage>` and implement
`start(MyStage)`. The launcher resolves the concrete stage type from the
generic superclass and instantiates it for you — you do **not** create
the custom stage yourself.

## API

| Member | Stock | skia-fx |
|---|---|---|
| Class | `Application` | `Application<W extends Stage>` |
| `start` | `start(Stage)` | `start(W)` |
| primary-stage factory | *(none)* | `protected W createPrimaryStage()` |

- **`W extends Stage`** — the primary stage type. Defaults to `Stage` for
  apps that don't specialize it (including raw `extends Application`).
- **`start(W primaryStage)`** — receives the primary stage as the exact
  type `W`.
- **`createPrimaryStage()`** — called by the launcher *on the FX
  Application Thread* to build the primary stage. The default resolves
  `W` from your `extends Application<MyStage>` declaration and constructs
  it via its public no-arg constructor (or `new Stage()` when `W` is
  `Stage`). **Override is optional** — only for stages without a no-arg
  constructor, or when `W` can't be reified.

## Usage

### Custom stage as primary — no factory needed

```java
public final class MyStage extends Stage {        // public, public no-arg ctor
    public MyStage() {
        initStyle(StageStyle.CUSTOM);   // we paint our own chrome
        // ... custom window API ...
    }
}

public final class MyApp extends Application<MyStage> {
    @Override
    public void start(MyStage stage) {            // typed primary stage
        stage.setScene(new Scene(new Group(), 800, 500));
        stage.show();
    }

    public static void main(String[] args) { launch(args); }
}
```

The launcher reads `MyStage` from `extends Application<MyStage>` (via the
reified generic superclass), builds it with its public no-arg constructor
on the FX Application Thread, and passes it to `start`.

### Requirements & when to override

For the automatic path, the custom stage class must be:

- **`public`** with a **`public` no-argument constructor**, and
- **accessible to the `javafx.graphics` module** (normally already true —
  it's the same package export/open you declare for your `Application`
  subclass).

Override `createPrimaryStage()` only when the default can't build your
stage:

```java
@Override
protected MyStage createPrimaryStage() {
    return new MyStage(someDependency);   // no public no-arg ctor
}
```

Cases that need an override: a stage with no accessible no-arg
constructor, or a `W` that isn't reified (e.g. it's still a type variable
through an intermediate generic superclass like
`class Base<T extends Stage> extends Application<T>`). The default throws
an `IllegalStateException` with guidance in the no-arg-constructor case
and falls back to `Stage` for an unreified `W`.

## Backward compatibility — drop-in preserved

This is **source- and binary-compatible** with stock OpenJFX:

- **Erasure.** `W extends Stage` erases to `Stage`, so the compiled
  signatures are identical to stock: the class is still `Application`, and
  the abstract method is still `start(Stage)`. Existing **compiled** apps
  linked against stock OpenJFX run unchanged on the skia-fx jars.
- **Raw usage still compiles.** Existing source that writes
  `class MyApp extends Application` with `public void start(Stage stage)`
  continues to compile and run; it just uses the raw type (one
  unchecked-on-raw-type warning, no behavior change). `start(Stage)`
  overrides the erased abstract method; `createPrimaryStage()` defaults to
  `new Stage()`.
- **`launch(...)` unchanged.** `Application.launch(Class<? extends Application>, String...)`
  keeps its exact signature (raw bound), so callers are unaffected.

Lint is enabled but **not** `-Werror` in this project (see
`skiafx.java-conventions.gradle`), so the raw-type/unchecked warnings the
generification introduces in internal code (`LauncherImpl`, `Preloader`)
do not fail the build.

## How the launcher wiring works

The launcher lives in a different package (`com.sun.javafx.application.LauncherImpl`)
and must invoke the **protected** `createPrimaryStage()` hook on the app
instance. To do that without widening the hook to `public`, skia-fx uses
the standard JavaFX "Helper/Accessor" shim:

1. `Application`'s static initializer registers an accessor that closes
   over the protected method:
   ```java
   static { ApplicationAccessor.setAccessor(Application::createPrimaryStage); }
   ```
2. `com.sun.javafx.application.ApplicationAccessor` (internal) exposes a
   same-package static entry point.
3. `LauncherImpl` builds the primary stage through it:
   ```java
   final Stage primaryStage = ApplicationAccessor.createPrimaryStage(theApp);
   StageHelper.setPrimary(primaryStage, true);
   theApp.start(primaryStage);     // erased start(Stage) → bridge → start(W)
   ```

The application instance is constructed (which initializes the
`Application` class and runs its static initializer) **before** the
launcher creates the primary stage, so the accessor is always registered
by the time it is read. `ApplicationAccessor` additionally force-initializes
`Application` as a defensive fallback.

## Preloader

`Preloader` is now declared `extends Application<Stage>` (its primary
stage is always a plain `Stage`). Concrete preloaders implement
`start(Stage)` exactly as before. The preloader's stage is still created
directly as `new Stage()` in the launcher — custom primary stages apply
to the *application*, not the preloader.

## Files

| File | Change |
|---|---|
| `javafx.graphics/.../javafx/application/Application.java` | generic `Application<W>`, `start(W)`, `createPrimaryStage()`, accessor registration, javadoc |
| `javafx.graphics/.../javafx/application/Preloader.java` | `extends Application<Stage>` (+ `Stage` import) |
| `javafx.graphics/.../com/sun/javafx/application/ApplicationAccessor.java` | **new** — protected-hook bridge for the launcher |
| `javafx.graphics/.../com/sun/javafx/application/LauncherImpl.java` | primary stage built via `ApplicationAccessor.createPrimaryStage(theApp)` |
