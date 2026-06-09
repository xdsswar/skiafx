/*
 * DashboardDemo — a polished, web-style admin dashboard for skia-fx.
 *
 * A StageStyle.CUSTOM window with a self-drawn title bar, an animated
 * collapsible icon-rail sidebar (icons-only when collapsed), a top bar with
 * search + profile, and several fully-styled sections: an analytics
 * dashboard, a crypto-style "Swap" panel, a multi-step registration form,
 * a control gallery, charts and settings. Every glyph is an SVGPath so the
 * chrome stays crisp at any DPI, exactly like a real web dashboard.
 *
 * The shell is declared in FXML (dashboard-demo.fxml); the look is a single
 * self-contained stylesheet (dashboard-demo.css) layered over AtlantaFX
 * PrimerLight for baseline control styling. DashboardController owns the
 * animations, navigation and the section bodies.
 *
 * Run: ./gradlew :samples:ensemble:runDashboard
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

public final class DashboardDemo extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // PrimerLight gives every stock control a clean light baseline;
        // dashboard-demo.css (added to the scene below) layers the emerald
        // brand, the custom chrome and the dashboard-specific surfaces on top
        // and wins where selectors overlap.
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        URL fxml = locate("dashboard-demo.fxml");
        if (fxml == null) {
            throw new IllegalStateException("dashboard-demo.fxml not found on the module path");
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        Parent root = loader.load();
        DashboardController controller = loader.getController();

        Scene scene = new Scene(root, 1280, 820);
        URL css = locate("dashboard-demo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.initStyle(StageStyle.CUSTOM);
        stage.setTitle("Numa · Dashboard");
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.setScene(scene);

        controller.installStage(stage);
        stage.show();
    }

    /** Resolve a module-root resource across the split classes/resources layout. */
    private static URL locate(String name) {
        URL u = ClassLoader.getSystemResource(name);
        if (u != null) return u;
        u = DashboardDemo.class.getClassLoader().getResource(name);
        if (u != null) return u;
        return DashboardDemo.class.getResource("/" + name);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
