module openjfx.samples.gradient {
    requires javafx.base;
    requires javafx.graphics;
    requires javafx.controls;

    // The JavaFX launcher (in javafx.graphics) reflectively constructs the
    // Application subclass, so the package must be exported/opened to it.
    exports dev.skiafx.samples.gradient;
    opens dev.skiafx.samples.gradient to javafx.graphics;
}
