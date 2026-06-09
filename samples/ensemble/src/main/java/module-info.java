module openjfx.samples.ensemble {
    // `requires transitive javafx.graphics` because Application and
    // Stage appear in the public signatures of the exported sample
    // classes (extends Application; void start(Stage)). Without the
    // transitive, javac emits an [exports] warning per signature.
    // The samples module is a leaf app, but the launcher reflects on
    // these classes through the module system, so the exports stay.
    requires javafx.base;
    requires transitive javafx.graphics;
    requires javafx.controls;
    // glTF model loading for Model3DDemo (javafx.scene.model3d.ModelLoader).
    requires javafx.scene3d;
    // FXML drives the Showcase shell (showcase.fxml + ShowcaseController).
    requires javafx.fxml;
    requires javafx.media;
    requires javafx.web;
    // For the WebViewDemo JS-bridge panel: netscape.javascript.JSObject.
    requires jdk.jsobject;
    // For the freeze watchdog: ThreadMXBean to dump every thread's
    // stack on UI hangs.
    requires java.management;
    // AtlantaFX PrimerLight theme (applied as the user-agent stylesheet in
    // ShowcaseApp) — a CSS-heavy modern control theme to stress the Skia path.
    requires atlantafx.base;
    exports org.openjfx.samples.ensemble;
    // FXML reflects on the controller (and FXML-instantiated nodes) at runtime.
    opens org.openjfx.samples.ensemble to javafx.fxml;
}
