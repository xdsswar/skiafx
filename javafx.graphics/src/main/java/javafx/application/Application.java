/*
 * Copyright (c) 2010, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package javafx.application;

import java.io.File;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import javafx.application.Preloader.PreloaderNotification;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.Stylesheet;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.sun.javafx.application.ApplicationAccessor;
import com.sun.javafx.application.LauncherImpl;
import com.sun.javafx.application.ParametersImpl;
import com.sun.javafx.application.PlatformImpl;

/**
 * Application class from which JavaFX applications extend.
 *
 * <p><b>Life-cycle</b></p>
 * <p>
 * The entry point for JavaFX applications is the Application class. The
 * JavaFX runtime does the following, in order, whenever an application is
 * launched:
 * </p>
 * <ol>
 * <li>Starts the JavaFX runtime, if not already started
 * (see {@link Platform#startup(Runnable)} for more information)</li>
 * <li>Constructs an instance of the specified Application class</li>
 * <li>Calls the {@link #init} method</li>
 * <li>Calls the {@link #start} method</li>
 * <li>Waits for the application to finish, which happens when either of
 * the following occur:
 * <ul>
 * <li>the application calls {@link Platform#exit}</li>
 * <li>the last window has been closed and the {@code implicitExit}
 * attribute on {@code Platform} is true</li>
 * </ul></li>
 * <li>Calls the {@link #stop} method</li>
 * </ol>
 * <p>Note that the {@code start} method is abstract and must be overridden.
 * The {@code init} and {@code stop} methods have concrete implementations
 * that do nothing.</p>
 * <p>The {@code Application} subclass must be declared public and must have a
 * public no-argument constructor.</p>
 *
 * <p>Calling {@link Platform#exit} is the preferred way to explicitly terminate
 * a JavaFX Application. Directly calling {@link System#exit} is
 * an acceptable alternative, but doesn't allow the Application {@link #stop}
 * method to run.
 * </p>
 *
 * <p>A JavaFX Application should not attempt to use JavaFX after the
 * FX toolkit has terminated or from a ShutdownHook, that is, after the
 * {@link #stop} method returns or {@link System#exit} is called.
 * </p>
 *
 * <p><b>Note:</b> The JavaFX classes must be loaded from a set of
 * named {@code javafx.*} modules on the <em>module path</em>.
 * Loading the JavaFX classes from the classpath is not supported.
 * See {@link Platform#startup(Runnable) Platform.startup}
 * for more information.
 *
 * <p><b>Deploying an Application as a Module</b></p>
 * <p>
 * If the {@code Application} subclass is in a named module then that class
 * must be accessible to the {@code javafx.graphics} module.
 * Otherwise, an exception will be thrown when the application is launched.
 * This means that
 * in addition to the class itself being declared public, the module must
 * {@link Module#isExported(String,Module) export}
 * (or {@link Module#isOpen(String,Module) open}) the containing package to
 * at least the {@code javafx.graphics} module.
 * </p>
 * <p>
 * For example, if {@code com.foo.MyApplication} is in the {@code foo.app}
 * module, the {@code module-info.java} might look like this:
 * </p>
<pre>{@code module foo.app {
    exports com.foo to javafx.graphics;
}}</pre>
*
 * <p><b>Parameters</b></p>
 * <p>
 * Application parameters are available by calling the {@link #getParameters}
 * method from the {@link #init} method, or any time after the {@code init}
 * method has been called.
 * </p>
 *
 * <p><b>Threading</b></p>
 * <p>
 * JavaFX creates an application thread for running the application start
 * method, processing input events, and running animation timelines. Creation
 * of JavaFX {@link Scene} and {@link Stage} objects as well as modification of
 * scene graph operations to <em>live</em> objects (those objects already
 * attached to a scene) must be done on the JavaFX application thread.
 * </p>
 *
 * <p>
 * The Java launcher loads and initializes the specified Application class
 * on the JavaFX Application Thread. If there is no main method in the
 * Application class, or if the main method calls Application.launch(), then
 * an instance of the Application is then constructed on the JavaFX Application
 * Thread.
 * </p>
 *
 * <p>
 * The {@code init} method is called on the launcher thread, not on the
 * JavaFX Application Thread.
 * This means that an application must not construct a {@link Scene}
 * or a {@link Stage} in the {@code init} method.
 * An application may construct other JavaFX objects in the {@code init}
 * method.
 * </p>
 *
 * <p>
 * All the unhandled exceptions on the JavaFX application thread that occur during
 * event dispatching, running animation timelines, or any other code, are forwarded
 * to the thread's {@link java.lang.Thread.UncaughtExceptionHandler uncaught
 * exception handler}.
 * </p>
 *
 * <p><b>Example</b></p>
 * <p>The following example will illustrate a simple JavaFX application.</p>
 * <pre>{@code
import javafx.application.Application;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

public class MyApp extends Application {
    public void start(Stage stage) {
        Circle circ = new Circle(40, 40, 30);
        Group root = new Group(circ);
        Scene scene = new Scene(root, 400, 300);

        stage.setTitle("My JavaFX Application");
        stage.setScene(scene);
        stage.show();
    }
}
 * }</pre>
 *
 * <p>The above example will produce the following:</p>
 * <p><img src="doc-files/Application.png" alt="A black circle in the top left
 * corner of scene"></p>
 *
 * <p><b>Custom primary stage (skia-fx extension)</b></p>
 * <p>
 * {@code Application} is parameterized on the stage type {@code W}, which
 * must be a {@link Stage} or a subclass of it. This lets an application
 * use a <em>custom window class</em> as its primary stage — for example a
 * {@code Stage} subclass that draws its own title bar (see
 * {@code StageStyle.CUSTOM}) or adds app-specific window API. The
 * {@link #start(Stage) start} method then receives that exact type:
 * </p>
 * <pre>{@code
 * public class MyApp extends Application<MyStage> {
 *     public void start(MyStage stage) {   // typed primary stage
 *         stage.setScene(new Scene(new Group(), 400, 300));
 *         stage.show();
 *     }
 * }
 * }</pre>
 * <p>
 * That is all that is required: the JavaFX launcher resolves {@code W}
 * from the {@code extends Application<MyStage>} declaration, constructs
 * {@code MyStage} via its public no-argument constructor on the FX
 * Application Thread, and passes it to {@code start}. The custom stage
 * class must therefore be {@code public} with a {@code public} no-arg
 * constructor and accessible to the {@code javafx.graphics} module.
 * </p>
 * <p>
 * Stage construction is delegated to {@link #createPrimaryStage()}, which
 * you may override for full control — for stages without a no-arg
 * constructor, or when {@code W} cannot be reified (e.g. it remains a
 * type variable through an intermediate generic superclass).
 * </p>
 * <p>
 * Existing code that writes {@code extends Application} (the raw type) and
 * {@code public void start(Stage stage)} continues to compile and run
 * unchanged: the type parameter erases to {@code Stage}, so the bytecode
 * signatures ({@code Application}, {@code start(Stage)}) are identical to
 * stock OpenJFX and drop-in compatibility is preserved. The only effect
 * of not parameterizing is an unchecked-warning on the raw type.
 * </p>
 *
 * @param <W> the type of the primary {@link Stage}; defaults to
 *            {@code Stage} for applications that don't specialize it
 *
 * @see Platform
 *
 * @since JavaFX 2.0
 */
public abstract class Application<W extends Stage> {

    static {
        // Bridge our protected createPrimaryStage() hook to the internal
        // launcher (different package) without widening it to public.
        // See ApplicationAccessor and docs/CUSTOM_PRIMARY_STAGE.md.
        ApplicationAccessor.setAccessor(Application::createPrimaryStage);
    }
    /**
     * Constant for user agent stylesheet for the "Caspian" theme. Caspian
     * is the theme that shipped as default in JavaFX 2.x.
     * @since JavaFX 8.0
     */
    public static final String STYLESHEET_CASPIAN = "CASPIAN";
    /**
     * Constant for user agent stylesheet for the "Modena" theme. Modena
     * is the default theme for JavaFX 8.x.
     * @since JavaFX 8.0
     */
    public static final String STYLESHEET_MODENA = "MODENA";

    // ------------------------------------------------------------------------
    //  skia-fx extension: runtime VSync control (global, observable).
    //
    //  Stock JavaFX has no runtime present-mode control. The skia-fx pipeline can
    //  present either vblank-synced (no tearing) or uncapped (Present(0,
    //  ALLOW_TEARING) — escapes the DWM windowed-vsync cap, the basis for the
    //  uncapped/high-fps frame-rate policy). This observable property toggles
    //  between them at runtime for EVERY window; the render thread reads a
    //  volatile mirror each present, and flipping it only changes the per-Present
    //  sync interval (no swap-chain rebuild), so it is safe to change at any time.
    //  Default true (vsync on — normal apps never tear). Existing JavaFX code is
    //  unaffected.
    // ------------------------------------------------------------------------

    // Render-thread mirror of the property below — read off the FX thread by the
    // present path (com.sun.prism.skia.SkiaPresentable). Volatile is sufficient:
    // it is a lock-free flag whose only effect is the next frame's sync interval.
    private static volatile boolean vsyncEnabledFlag = true;

    // FX-thread source of truth; kept in sync with the render-thread mirror via
    // invalidated() so set(), bind() and listeners all propagate.
    private static final BooleanProperty vsyncEnabled =
            new SimpleBooleanProperty(null, "vsyncEnabled", true) {
                @Override protected void invalidated() {
                    vsyncEnabledFlag = get();
                }
            };

    /**
     * The global VSync property (skia-fx extension): {@code true} presents
     * vblank-synced (no tearing), {@code false} presents uncapped
     * ({@code Present(0, ALLOW_TEARING)}). Bindable and observable so an app can
     * drive it from a control or listen for changes. Applies to all windows and
     * takes effect on the next rendered frame.
     *
     * <p>Modify on the JavaFX Application Thread.</p>
     *
     * @return the VSync-enabled property (default {@code true})
     */
    public static BooleanProperty vsyncEnabledProperty() {
        return vsyncEnabled;
    }

    /**
     * Enables or disables VSync at runtime for all windows (skia-fx extension).
     * Convenience for {@code vsyncEnabledProperty().set(value)}.
     *
     * @param value {@code true} to present vblank-synced (no tearing),
     *              {@code false} for uncapped presentation
     */
    public static void setVsyncEnabled(boolean value) {
        vsyncEnabled.set(value);
    }

    /**
     * Returns whether VSync is currently enabled (skia-fx extension). Safe to
     * call from any thread; it reads the value the render thread uses.
     *
     * @return {@code true} if presenting vblank-synced, {@code false} if uncapped
     */
    public static boolean isVsyncEnabled() {
        return vsyncEnabledFlag;
    }

    /**
     * Launch a standalone application. This method is typically called
     * from the {@code main} method. It must not be called more than once or an
     * exception will be thrown.
     *
     * <p>
     * The launch method does not return until the application has exited,
     * either via a call to {@link Platform#exit()} or all of the application windows
     * have been closed.
     * The class specified by the {@code appClass} argument must be
     * a public subclass of {@code Application}
     * with a public no-argument constructor, in a package that is
     * {@link Module#isExported(String,Module) exported}
     * (or {@link Module#isOpen(String,Module) open}) to at least the
     * {@code javafx.graphics} module, or a RuntimeException will be thrown.
     *
     * <p>
     * Typical usage is:
     * <pre>
     *     public static void main(String[] args) {
     *         Application.launch(MyApp.class, args);
     *     }
     * </pre>
     * where <code>MyApp</code> is a subclass of Application.
     *
     * @param appClass the application class that is constructed and executed
     *        by the launcher.
     * @param args the command line arguments passed to the application.
     *             An application may get these parameters using the
     *             {@link #getParameters()} method.
     *
     * @throws IllegalStateException if this method is called more than once.
     * @throws IllegalStateException if this method is called from the JavaFX application thread.
     * @throws IllegalArgumentException if <code>appClass</code> is not a
     *         subclass of <code>Application</code>.
     * @throws RuntimeException if there is an error launching the
     * JavaFX runtime, or if the application class cannot be constructed
     * (e.g., if the class is not public or is not in an exported package), or
     * if an Exception or Error is thrown by the Application constructor, init
     * method, start method, or stop method.
     */
    public static void launch(Class<? extends Application> appClass, String... args) {
        LauncherImpl.launchApplication(appClass, args);
    }

    /**
     * Launch a standalone application. This method is typically called
     * from the {@code main} method. It must not be called more than once or an
     * exception will be thrown.
     * This is equivalent to {@code launch(TheClass.class, args)} where
     * {@code TheClass} is the
     * immediately enclosing class of the method that called launch.
     * It must be a public subclass of {@code Application}
     * with a public no-argument constructor, in a package that is
     * {@link Module#isExported(String,Module) exported}
     * (or {@link Module#isOpen(String,Module) open}) to at least the
     * {@code javafx.graphics} module, or a RuntimeException will be thrown.
     *
     * <p>
     * The launch method does not return until the application has exited,
     * either via a call to {@link Platform#exit()} or all of the application windows
     * have been closed.
     *
     * <p>
     * Typical usage is:
     * <pre>
     *     public static void main(String[] args) {
     *         Application.launch(args);
     *     }
     * </pre>
     *
     * @param args the command line arguments passed to the application.
     *             An application may get these parameters using the
     *             {@link #getParameters()} method.
     *
     * @throws IllegalStateException if this method is called more than once.
     * @throws IllegalStateException if this method is called from the JavaFX application thread.
     * @throws RuntimeException if there is an error launching the
     * JavaFX runtime, or if the application class cannot be constructed
     * (e.g., if the class is not public or is not in an exported package), or
     * if an Exception or Error is thrown by the Application constructor, init
     * method, start method, or stop method.
     */
    public static void launch(String... args) {
        // Figure out the right class to call
        StackTraceElement[] cause = Thread.currentThread().getStackTrace();

        boolean foundThisMethod = false;
        String callingClassName = null;
        for (StackTraceElement se : cause) {
            // Skip entries until we get to the entry for this class
            String className = se.getClassName();
            String methodName = se.getMethodName();
            if (foundThisMethod) {
                callingClassName = className;
                break;
            } else if (Application.class.getName().equals(className)
                    && "launch".equals(methodName)) {

                foundThisMethod = true;
            }
        }

        if (callingClassName == null) {
            throw new RuntimeException("Error: unable to determine Application class");
        }

        try {
            Class theClass = Class.forName(callingClassName, false,
                               Thread.currentThread().getContextClassLoader());
            if (Application.class.isAssignableFrom(theClass)) {
                Class<? extends Application> appClass = theClass;
                LauncherImpl.launchApplication(appClass, args);
            } else {
                throw new RuntimeException("Error: " + theClass
                        + " is not a subclass of javafx.application.Application");
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Constructs a new {@code Application} instance.
     */
    public Application() {
    }

    /**
     * The application initialization method. This method is called immediately
     * after the Application class is loaded and constructed. An application may
     * override this method to perform initialization prior to the actual starting
     * of the application.
     *
     * <p>
     * The implementation of this method provided by the Application class does nothing.
     * </p>
     *
     * <p>
     * NOTE: This method is not called on the JavaFX Application Thread. An
     * application must not construct a Scene or a Stage in this
     * method.
     * An application may construct other JavaFX objects in this method.
     * </p>
     * @throws java.lang.Exception if something goes wrong
     */
    public void init() throws Exception {
    }

    /**
     * The main entry point for all JavaFX applications.
     * The start method is called after the init method has returned,
     * and after the system is ready for the application to begin running.
     *
     * <p>
     * NOTE: This method is called on the JavaFX Application Thread.
     * </p>
     *
     * @param primaryStage the primary stage for this application, onto which
     * the application scene can be set. Its concrete type is the application's
     * stage type parameter {@code W} (produced by {@link #createPrimaryStage()}).
     * Applications may create other stages, if needed, but they will not be
     * primary stages.
     * @throws java.lang.Exception if something goes wrong
     */
    public abstract void start(W primaryStage) throws Exception;

    /**
     * Creates the primary {@link Stage} for this application.
     *
     * <p>The JavaFX launcher calls this method on the JavaFX Application
     * Thread to obtain the primary stage, marks it as primary, and then
     * passes it to {@link #start(Stage) start}. Override it to use a
     * custom {@code Stage} subclass as the primary window — for instance
     * an undecorated stage that paints its own title bar, or one that
     * exposes application-specific window API.</p>
     *
     * <p><b>You normally do not need to override this.</b> The default
     * implementation resolves the stage type {@code W} from your
     * {@code extends Application<MyStage>} declaration (via the reified
     * generic superclass) and constructs it with its public no-argument
     * constructor. So {@code extends Application<MyStage>} plus
     * {@code start(MyStage)} is all that is required — the custom stage
     * is created for you. When {@code W} is {@code Stage} (including raw
     * {@code extends Application}) it simply returns {@code new Stage()}.</p>
     *
     * <p>Override this method only when the default cannot build your
     * stage: the type parameter can't be reified (e.g. it's still a type
     * variable through an intermediate generic superclass), or your
     * custom stage has no accessible public no-argument constructor. The
     * default throws {@link IllegalStateException} with guidance in those
     * cases.</p>
     *
     * <p>Requirements for automatic instantiation: the custom stage class
     * must be {@code public} with a {@code public} no-argument
     * constructor, and be accessible to the {@code javafx.graphics}
     * module (typically satisfied by the same package export/open you
     * already declare for your {@code Application} subclass).</p>
     *
     * <p>NOTE: This method is called on the JavaFX Application Thread, so
     * it is a valid place to construct a {@code Stage}. Do not call it
     * yourself; the launcher manages the primary stage's lifecycle.</p>
     *
     * @return the primary stage instance; must not be {@code null}
     * @throws IllegalStateException if {@code W} resolves to a custom
     *         {@code Stage} subclass that cannot be instantiated via a
     *         public no-argument constructor
     * @implSpec The returned stage must be a freshly constructed,
     *           not-yet-shown {@code Stage} (or subclass).
     * @since skia-fx
     */
    protected W createPrimaryStage() {
        Class<? extends Stage> stageType = resolvePrimaryStageType();
        if (stageType == Stage.class) {
            @SuppressWarnings("unchecked")
            W stage = (W) new Stage();
            return stage;
        }
        try {
            @SuppressWarnings("unchecked")
            W stage = (W) stageType.getConstructor().newInstance();
            return stage;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not create the primary stage of type "
                    + stageType.getName() + " via its public no-argument "
                    + "constructor. Make it a public class with a public "
                    + "no-arg constructor accessible to the javafx.graphics "
                    + "module, or override Application.createPrimaryStage().", e);
        }
    }

    /**
     * Resolves the concrete primary-stage class from this application's
     * {@code extends Application<...>} declaration. Walks up the class
     * hierarchy to the direct {@code Application} subclass and reads the
     * reified type argument. Returns {@code Stage.class} when the
     * argument is {@code Stage}, is not reified (a type variable or
     * wildcard), or no parameterization is present (raw subtype).
     */
    private Class<? extends Stage> resolvePrimaryStageType() {
        for (Class<?> c = getClass(); c != null && c != Application.class; c = c.getSuperclass()) {
            Type generic = c.getGenericSuperclass();
            if (generic instanceof ParameterizedType pt && pt.getRawType() == Application.class) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> k && Stage.class.isAssignableFrom(k)) {
                    return k.asSubclass(Stage.class);
                }
                break; // type variable / wildcard — fall back to Stage
            }
        }
        return Stage.class;
    }

    /**
     * This method is called when the application should stop, and provides a
     * convenient place to prepare for application exit and destroy resources.
     *
     * <p>
     * The implementation of this method provided by the Application class does nothing.
     * </p>
     *
     * <p>
     * NOTE: This method is called on the JavaFX Application Thread.
     * </p>
     * @throws java.lang.Exception if something goes wrong
     */
    public void stop() throws Exception {
    }

    private HostServices hostServices = null;

    /**
     * Gets the HostServices provider for this application. This provides
     * the ability to get the code base and document base for this application,
     * and to show a web page in a browser.
     *
     * @return the HostServices provider
     */
    public final HostServices getHostServices() {
        synchronized (this) {
            if (hostServices == null) {
                hostServices = new HostServices(this);
            }
            return hostServices;
        }
    }

    /**
     * Retrieves the parameters for this Application, including any arguments
     * passed on the command line.
     *
     * <p>
     * NOTE: this method should not be called from the Application constructor,
     * as it will return null. It may be called in the init() method or any
     * time after that.
     * </p>
     *
     * @return the parameters for this Application, or null if called from the
     * constructor.
     */
    public final Parameters getParameters() {
        return ParametersImpl.getParameters(this);
    }

    /**
     * Notifies the preloader with an application-generated notification.
     * Application code calls this method with a PreloaderNotification that is
     * delivered to the
     * {@link Preloader#handleApplicationNotification
     * Preloader.handleApplicationNotification} method.
     * This is primarily useful for cases where an application wants the
     * preloader to show progress during a long application initialization
     * step.
     *
     * <p>
     * NOTE: the notification will be delivered only to the preloader's
     * handleApplicationNotification() method; this means, for example, that
     * if this method is called with a ProgressNotification, that notification
     * will not be delivered to the {@link Preloader#handleProgressNotification
     * Preloader.handleProgressNotification}
     * method.
     * </p>
     *
     * @param info the application-generated preloader notification
     */
    public final void notifyPreloader(PreloaderNotification info) {
        LauncherImpl.notifyPreloader(this, info);
    }

    /**
     * Encapsulates the set of parameters for an application. This includes
     * arguments passed on the command line.
     *
     * <p>
     * Note that the application and the preloader both get the same set
     * of parameters for a given run of an application.
     * </p>
     * @since JavaFX 2.0
     */
    public static abstract class Parameters {

        /**
         * Constructs a new {@code Parameters} instance.
         */
        public Parameters() {
        }

        /**
         * Retrieves a read-only list of the raw arguments. This list
         * may be empty, but is never null. In the case of a standalone
         * application, it is the ordered list of arguments specified on the
         * command line.
         * For
         * named parameters, each &lt;name,value&gt; pair is represented as
         * a single argument of the form: "--name=value".
         *
         * @return a read-only list of raw application arguments
         */
        public abstract List<String> getRaw();

        /**
         * Retrieves a read-only list of the unnamed parameters. This list
         * may be empty, but is never null. The named parameters, that is
         * the parameters that are represented as &lt;name,value&gt; pairs, are
         * filtered out.
         *
         * @return a read-only list of unnamed parameters.
         */
        public abstract List<String> getUnnamed();

        /**
         * Retrieves a read-only map of the named parameters. It may be
         * empty, but is never null.
         * Named parameters include any command line
         * arguments of the form: "--name=value".
         *
         * @return a read-only map of named parameters.
         */
        public abstract Map<String, String> getNamed();

    }

    private static String userAgentStylesheet = null;

    /**
     * Get the user agent stylesheet used by the whole application. This is
     * used to provide default styling for all ui controls and other nodes.
     * A value of null means the platform default stylesheet is being used.
     * <p>
     * NOTE: This method must be called on the JavaFX Application Thread.
     * </p>
     *
     * @return The URL to the stylesheet as a String.
     * @since JavaFX 8.0
     */
    public static String getUserAgentStylesheet() {
        return userAgentStylesheet;
    }

    /**
     * Set the user agent stylesheet used by the whole application. This is used
     * to provide default styling for all ui controls and other nodes. Each
     * release of JavaFX may have a new default value for this so if you need
     * to guarantee consistency you will need to call this method and choose
     * what default you would like for your application. A value of null will
     * restore the platform default stylesheet. This property can also be set
     * on the command line with {@code -Djavafx.userAgentStylesheetUrl=[URL]}
     * Setting it on the command line overrides anything set using this method
     * in code.
     * <p>
     * The URL is a hierarchical URI of the form [scheme:][//authority][path]. If the URL
     * does not have a [scheme:] component, the URL is considered to be the [path] component only.
     * Any leading '/' character of the [path] is ignored and the [path] is treated as a path relative to
     * the root of the application's classpath.
     * <p>
     * The RFC 2397 "data" scheme for URLs is supported in addition to the protocol handlers that
     * are registered for the application.
     * If a URL uses the "data" scheme and the MIME type is either empty, "text/plain", or "text/css",
     * the payload will be interpreted as a CSS file.
     * If the MIME type is "application/octet-stream", the payload will be interpreted as a binary
     * CSS file (see {@link Stylesheet#convertToBinary(File, File)}).
     * <p>
     * NOTE: This method must be called on the JavaFX Application Thread.
     *
     * @param url The URL to the stylesheet as a String.
     * @since JavaFX 8.0
     */
    public static void setUserAgentStylesheet(String url) {
        userAgentStylesheet = url;
        if (url == null) {
            PlatformImpl.setDefaultPlatformUserAgentStylesheet();
        } else {
            PlatformImpl.setPlatformUserAgentStylesheet(url);
        }
    }
}
