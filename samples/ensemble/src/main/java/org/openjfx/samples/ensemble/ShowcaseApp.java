/*
 * ShowcaseApp — entry point for the skia-fx Showcase Dashboard.
 *
 * Loads the FXML shell, attaches the light stylesheet, opens a custom-
 * decorated (StageStyle.CUSTOM) window and lets the controller register
 * the caption / min / max / close hit regions.
 *
 * Run: ./gradlew :samples:ensemble:runShowcase
 */
package org.openjfx.samples.ensemble;

import java.net.URL;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class ShowcaseApp extends Application<Stage> {

    @Override
    public void start(Stage stage) throws Exception {
        // AtlantaFX PrimerLight as the base control theme (replaces Modena). It's a
        // CSS-heavy modern theme — a good stress test for the Skia CSS/render path.
        // showcase.css is still added to the scene below for the custom chrome and
        // takes precedence over this user-agent stylesheet where selectors overlap.
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        URL fxml = locate("showcase.fxml");
        if (fxml == null) throw new IllegalStateException("showcase.fxml not found on the module path");

        FXMLLoader loader = new FXMLLoader(fxml);
        Parent root = loader.load();
        ShowcaseController controller = loader.getController();

        Scene scene = new Scene(root, 1280, 820);
        URL css = locate("showcase.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.initStyle(StageStyle.CUSTOM);
        stage.setTitle("skia-fx · Showcase Dashboard");
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.setScene(scene);

        controller.installStage(stage);
        stage.setOnHidden(e -> controller.shutdown());
        stage.show();
    }

    /**
     * Resolve a module-root resource. Under the split classes/resources
     * Gradle layout, {@code getClass().getResource("/x")} misses module-root
     * resources; the system class loader walks every module-path entry.
     */
    private static URL locate(String name) {
        URL u = ClassLoader.getSystemResource(name);
        if (u != null) return u;
        u = ShowcaseApp.class.getClassLoader().getResource(name);
        if (u != null) return u;
        return ShowcaseApp.class.getResource("/" + name);
    }

    static void main(String[] args) {
        launch(args);
    }


    @Override
    public void init() throws Exception {
        Application.setVsyncEnabled(true);
    }
}
