/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.tk.quantum;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCombination;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.*;
import com.sun.glass.ui.Window.Level;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.sun.javafx.PlatformUtil;
import com.sun.javafx.iio.common.PushbroomScaler;
import com.sun.javafx.iio.common.ScalerFactory;
import com.sun.javafx.stage.HeaderButtonMetrics;
import com.sun.javafx.stage.StagePeerListener;
import com.sun.javafx.tk.FocusCause;
import com.sun.javafx.tk.TKScene;
import com.sun.javafx.tk.TKStage;
import com.sun.javafx.tk.TKStageListener;
import com.sun.prism.Image;
import com.sun.prism.PixelFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public class WindowStage extends GlassStage {

    protected Window platformWindow;

    protected javafx.stage.Stage fxStage;

    private StageStyle style;
    private GlassStage owner = null;
    private Modality modality = Modality.NONE;

    private OverlayWarning warning = null;
    private boolean rtl = false;
    private boolean transparent = false;
    private boolean darkFrame = false;
    private boolean isPrimaryStage = false;
    private boolean isPopupStage = false;
    private boolean isInFullScreen = false;
    private boolean isAlwaysOnTop = false;

    // skia-fx: paint-before-show coordination.
    //
    // On a fresh Stage.show(), we open the render gate (super
    // .setVisible -> scene added to PaintCollector dirty list)
    // BEFORE calling the native ShowWindow, drive a synchronous
    // pulse to schedule a render, then wait briefly for the
    // render thread to paint + Present into the swap chain. The
    // native HWND is created at Stage construction time, so the
    // swap chain can be allocated and Presented to while the
    // window is still hidden — DWM picks the Present into the
    // window's redirection bitmap, and when ShowWindow fires the
    // standard zoom-in show animation reveals real scene content
    // instead of flashing the default (white) bitmap.
    //
    // {@code paintBeforeShow} is consulted by ViewPainter so it
    // bypasses the isWindowVisible gate during this window. The
    // {@code firstPresentLatch} is signalled by PresentingPainter
    // after the first successful Present for the associated
    // scene, allowing the FX thread to proceed to the native
    // ShowWindow call.
    //
    // {@link #PAINT_BEFORE_SHOW_TIMEOUT_MS} is the safety cap on
    // the FX-thread wait; if first paint takes longer (decode of
    // a giant image, blocked native init, etc.) we give up and
    // call ShowWindow anyway — degrades gracefully to the stock
    // behaviour (brief flash) instead of hanging the show.
    //
    // Opt-out: -Dskia.preshow.paint=false reverts to stock order
    // (platformWindow.setVisible first, render later).
    private volatile boolean paintBeforeShow = false;
    private volatile CountDownLatch firstPresentLatch;
    // 500 ms covers first-paint latency for the CUSTOM-decorated
    // case where the subclassed CustomWinProc + initial pulse +
    // SkiaPresentable creation + paint + present can take ~200-300
    // ms cold. Stock windows usually finish in ~150 ms but the
    // common cap is sized for the slower path.
    private static final long PAINT_BEFORE_SHOW_TIMEOUT_MS = Long.getLong(
        "skia.preshow.timeoutMs", 500L);
    private static final boolean PAINT_BEFORE_SHOW_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty(
            "skia.preshow.paint", "true"));

    /** True while this stage is waiting for its first frame to
     *  reach the swap chain before the native ShowWindow is
     *  called. ViewPainter checks this to bypass its
     *  isWindowVisible gate. */
    public boolean isPaintBeforeShow() {
        return paintBeforeShow;
    }

    /** Called from PresentingPainter after the first successful
     *  Present for this stage's scene. Counts down the latch so
     *  the FX thread blocked in setVisible can proceed to call
     *  the native ShowWindow. Safe to call from any thread; safe
     *  to call after the latch is already counted down. */
    void notifyFirstPresented() {
        CountDownLatch latch = firstPresentLatch;
        if (latch != null) latch.countDown();
    }

    // An active window is visible && enabled && focusable.
    // The list is maintained in the z-order, so that the last element
    // represents the topmost window (or more accurately, the last
    // focused window, which we assume is very close to the last topmost one).
    private static List<WindowStage> activeWindows = new LinkedList<>();

    private static Map<Window, WindowStage> platformWindows = new HashMap<>();

    private static final Locale LOCALE = Locale.getDefault();

    private static final ResourceBundle RESOURCES =
        ResourceBundle.getBundle(WindowStage.class.getPackage().getName() +
                                 ".QuantumMessagesBundle", LOCALE);

    public WindowStage(javafx.stage.Window peerWindow, final StageStyle stageStyle, Modality modality,
                       TKStage owner, boolean darkFrame) {
        this.style = stageStyle;
        this.owner = (GlassStage)owner;
        this.modality = modality;
        this.darkFrame = darkFrame;

        if (peerWindow instanceof javafx.stage.Stage) {
            fxStage = (Stage)peerWindow;
        } else {
            fxStage = null;
        }

        transparent = stageStyle == StageStyle.TRANSPARENT;
        if (owner == null) {
            if (this.modality == Modality.WINDOW_MODAL) {
                this.modality = Modality.NONE;
            }
        }
    }

    final void setIsPrimary() {
        isPrimaryStage = true;
    }

    final void setIsPopup() {
        isPopupStage = true;
    }

    // Called by QuantumToolkit, so we can override initPlatformWindow in subclasses
    public final WindowStage init(GlassSystemMenu sysmenu) {
        initPlatformWindow();
        platformWindow.setEventHandler(new GlassWindowEventHandler(this));
        if (sysmenu.isSupported()) {
            sysmenu.createMenuBar();
            platformWindow.setMenuBar(sysmenu.getMenuBar());
        }
        return this;
    }

    private void initPlatformWindow() {
        if (platformWindow == null) {
            Application app = Application.GetApplication();

            Window ownerWindow = null;
            if (owner instanceof WindowStage) {
                ownerWindow = ((WindowStage)owner).platformWindow;
            }
            boolean resizable = fxStage != null && fxStage.isResizable();
            boolean focusable = true;
            int windowMask = rtl ? Window.RIGHT_TO_LEFT : 0;
            if (isPopupStage) { // TODO: make it a stage style?
                windowMask |= Window.POPUP;
                if (style == StageStyle.TRANSPARENT) {
                    windowMask |= Window.TRANSPARENT;
                }
                focusable = false;
                resizable = false;
            } else {
                // Downgrade conditional stage styles if not supported
                if (style == StageStyle.UNIFIED && !app.supportsUnifiedWindows()) {
                    style = StageStyle.DECORATED;
                } else if (style == StageStyle.EXTENDED && !app.supportsExtendedWindows()) {
                    style = StageStyle.DECORATED;
                }

                switch (style) {
                    case UNIFIED:
                        windowMask |= Window.UNIFIED;
                        // fall through
                    case DECORATED:
                        windowMask |= Window.TITLED | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE;
                        break;
                    case EXTENDED:
                        windowMask |= Window.EXTENDED | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE;
                        break;
                    case UTILITY:
                        windowMask |=  Window.TITLED | Window.UTILITY | Window.CLOSABLE;
                        break;
                    case CUSTOM:
                        // skia-fx: no platform decorations at all — application owns the
                        // title bar via the scene graph. CLOSABLE / MINIMIZABLE /
                        // MAXIMIZABLE are still set so the system menu (right-click on
                        // a caption region) shows the correct enabled actions.
                        windowMask |= Window.UNTITLED
                                    | Window.CUSTOM_DECORATIONS
                                    | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE;
                        break;
                    default:
                        windowMask |= (transparent ? Window.TRANSPARENT : Window.UNTITLED) | Window.CLOSABLE;
                        break;
                }

                if (ownerWindow != null || modality != Modality.NONE) {
                    windowMask &= ~(Window.MINIMIZABLE | Window.MAXIMIZABLE);
                }
            }

            if (modality != Modality.NONE) {
                windowMask |= Window.MODAL;
            }

            if (darkFrame) {
                windowMask |= Window.DARK_FRAME;
            }

            platformWindow = app.createWindow(ownerWindow, Screen.getMainScreen(), windowMask);
            platformWindow.setResizable(resizable);
            platformWindow.setFocusable(focusable);

            // skia-fx: install the per-window hit-test provider for
            // StageStyle.CUSTOM. The native WM_NCHITTEST handler
            // invokes Window.notifyHitTest → provider.hitTest(...)
            // on every non-client hit; the provider walks the
            // volatile snapshot maintained by the application via
            // Stage.set*Region(...).
            if (style == StageStyle.CUSTOM) {
                installCustomDecorationsHitTestProvider();
                applyDwmAccentsFromStage();
            }

            if (platformWindow.isExtendedWindow()) {
                platformWindow.headerButtonOverlayProperty().subscribe(overlay -> {
                    ViewScene scene = getViewScene();
                    if (scene != null) {
                        scene.setOverlay(isInFullScreen ? null : overlay);
                    }
                });

                platformWindow.headerButtonMetricsProperty().subscribe(this::notifyHeaderButtonMetricsChanged);
            }

            if (fxStage != null && fxStage.getScene() != null) {
                javafx.scene.paint.Paint paint = fxStage.getScene().getFill();
                if (paint instanceof javafx.scene.paint.Color) {
                    javafx.scene.paint.Color color = (javafx.scene.paint.Color) paint;
                    platformWindow.setBackground((float) color.getRed(), (float) color.getGreen(), (float) color.getBlue());
                } else if (paint instanceof javafx.scene.paint.LinearGradient) {
                    javafx.scene.paint.LinearGradient lgradient = (javafx.scene.paint.LinearGradient) paint;
                    computeAndSetBackground(lgradient.getStops());
                } else if (paint instanceof javafx.scene.paint.RadialGradient) {
                    javafx.scene.paint.RadialGradient rgradient = (javafx.scene.paint.RadialGradient) paint;
                    computeAndSetBackground(rgradient.getStops());
                }
            }

        }
        platformWindows.put(platformWindow, this);
    }

    private void computeAndSetBackground(List<javafx.scene.paint.Stop> stops) {
        if (stops.size() == 1) {
            javafx.scene.paint.Color color = stops.get(0).getColor();
            platformWindow.setBackground((float) color.getRed(),
                    (float) color.getGreen(), (float) color.getBlue());
        } else if (stops.size() > 1) {
            // A simple attempt to find a reasonable average color that is
            // within the stops arrange.
            javafx.scene.paint.Color color = stops.get(0).getColor();
            javafx.scene.paint.Color color2 = stops.get(stops.size() - 1).getColor();
            platformWindow.setBackground((float) ((color.getRed() + color2.getRed()) / 2.0),
                    (float) ((color.getGreen() + color2.getGreen()) / 2.0),
                    (float) ((color.getBlue() + color2.getBlue()) / 2.0));
        }
    }

    private void notifyHeaderButtonMetricsChanged() {
        if (stageListener instanceof StagePeerListener listener && platformWindow != null) {
            var metrics = platformWindow.headerButtonMetricsProperty().get();
            listener.changedHeaderButtonMetrics(
                new HeaderButtonMetrics(metrics.leftInset(), metrics.rightInset(), metrics.minHeight()));
        }
    }

    /**
     * skia-fx: installs a {@link Window.HitTestProvider} on the
     * platform window that resolves WM_NCHITTEST (or platform
     * equivalent) hits to {@link Window#HT_CAPTION}, HT_CLOSE, etc.
     * by walking the immutable snapshot held by the Stage's
     * {@link com.sun.javafx.stage.custom.CustomDecorations}.
     *
     * <p>The provider re-reads the snapshot reference on every
     * call, so application code can mutate the regions at any time
     * (from the FX thread) and the next non-client mouse event
     * will see the new layout.</p>
     */
    /**
     * skia-fx: push the application-set DWM accent values
     * (titlebar color, caption text color, border color, corner
     * preference) from the Stage's {@link com.sun.javafx.stage.custom.CustomDecorations}
     * to the platform window. Called from
     * {@link #initPlatformWindow} once the {@code platformWindow}
     * exists and again from {@code Stage.set*Color(...)} setters
     * so live updates are visible.
     *
     * <p>No-op on platforms whose Glass {@code Window} doesn't
     * implement the DWM-accent setters (currently Windows only).</p>
     */
    public void applyDwmAccentsFromStage() {
        if (fxStage == null || platformWindow == null) return;
        var accessor = com.sun.javafx.stage.StageHelper.getStageAccessor();
        if (accessor == null) return;
        var decorations = accessor.getCustomDecorations(fxStage);
        if (decorations == null) return;

        // Setters are no-ops on platforms whose Glass Window doesn't
        // implement DWM accents (currently Windows only); passing
        // -1 = "platform default" leaves the OS-painted accent
        // alone when the user hasn't customised the colour.
        platformWindow.setDwmCaptionColor(toArgb(decorations.getTitleBarColor()));
        platformWindow.setDwmTextColor(toArgb(decorations.getCaptionTextColor()));
        platformWindow.setDwmBorderColor(toArgb(decorations.getBorderColor()));
        platformWindow.setDwmCornerPreference(cornerPrefToInt(decorations.getCornerPreference()));

        // Also push the title-bar colour to the HWND class background
        // brush so transient OS-driven erases (resize / maximize /
        // restore where the OS reallocates the redirection bitmap)
        // fill with the application's accent colour instead of the
        // default white. CustomWinProc's WM_ERASEBKGND uses this
        // brush. No-op if the user didn't customise the colour.
        var bg = decorations.getTitleBarColor();
        if (bg != null) {
            platformWindow.setBackground((float) bg.getRed(),
                                         (float) bg.getGreen(),
                                         (float) bg.getBlue());
        }
    }

    private static int toArgb(javafx.scene.paint.Color c) {
        if (c == null) return -1;  // DWM "default" sentinel
        int a = (int) Math.round(c.getOpacity() * 255.0);
        int r = (int) Math.round(c.getRed()     * 255.0);
        int g = (int) Math.round(c.getGreen()   * 255.0);
        int b = (int) Math.round(c.getBlue()    * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int cornerPrefToInt(javafx.stage.WindowCornerPreference p) {
        if (p == null) return 0; // DEFAULT
        return switch (p) {
            case DEFAULT     -> 0;
            case SQUARE      -> 1; // DWMWCP_DONOTROUND
            case ROUND       -> 2; // DWMWCP_ROUND
            case ROUND_SMALL -> 3; // DWMWCP_ROUNDSMALL
        };
    }

    private void installCustomDecorationsHitTestProvider() {
        if (fxStage == null || platformWindow == null) return;
        platformWindow.setHitTestProvider(new Window.HitTestProvider() {
            @Override
            public int hitTest(int sceneX, int sceneY, boolean onResizeBorder) {
                var accessor = com.sun.javafx.stage.StageHelper.getStageAccessor();
                if (accessor == null) return Window.HT_CLIENT;
                var decorations = accessor.getCustomDecorations(fxStage);
                if (decorations == null) {
                    return onResizeBorder ? Window.HT_TOP : Window.HT_CLIENT;
                }

                // Find the matching hit region (if any) and route the
                // hover pseudo-class through the same call. updateHovered
                // is a no-op when the hovered region didn't change, so
                // it's cheap to invoke on every WM_NCHITTEST.
                var match = decorations.findRegionAt(sceneX, sceneY);
                decorations.updateHovered(match);
                if (match != null) {
                    int code = match.htCode();
                    // When the cursor sits inside the implicit top
                    // resize band, the band wins over a caption hit
                    // so OS resize engages on the top edge of the
                    // application's drawn title bar. Button hits
                    // (close / min / max / fullscreen / sysmenu)
                    // still win — they sit visually on the caption
                    // but should always click, not resize.
                    if (onResizeBorder && code == com.sun.javafx.stage.custom.HitRegion.HT_CAPTION) {
                        return Window.HT_TOP;
                    }
                    return code;
                }
                // No region matched — caller is over the bare client
                // area. Promote to HT_TOP when the cursor lies in the
                // implicit top resize border so OS resize still works
                // through the application's drawn title bar.
                return onResizeBorder ? Window.HT_TOP : Window.HT_CLIENT;
            }

            @Override
            public void clear() {
                // Native fired WM_NCMOUSELEAVE (cursor verified outside
                // the window, no modal sizing/moving loop active).
                // Drop the hovered region so any sticky :ht-*
                // pseudo-class is removed.
                var accessor = com.sun.javafx.stage.StageHelper.getStageAccessor();
                if (accessor == null) return;
                var decorations = accessor.getCustomDecorations(fxStage);
                if (decorations != null) {
                    decorations.updateHovered(null);
                }
            }
        });
    }

    public final Window getPlatformWindow() {
        return platformWindow;
    }

    static WindowStage findWindowStage(Window platformWindow) {
        return platformWindows.get(platformWindow);
    }

    protected GlassStage getOwner() {
        return owner;
    }

    protected ViewScene getViewScene() {
        return (ViewScene)getScene();
    }

    StageStyle getStyle() {
        return style;
    }

    @Override
    public void setTKStageListener(TKStageListener listener) {
        super.setTKStageListener(listener);
        notifyHeaderButtonMetricsChanged();
    }

    @Override public TKScene createTKScene(boolean depthBuffer, boolean msaa) {
        ViewScene scene = new ViewScene(fxStage != null ? fxStage.getScene() : null, depthBuffer, msaa);

        // The window-provided overlay is not visible in full-screen mode.
        if (!isInFullScreen) {
            scene.setOverlay(platformWindow.headerButtonOverlayProperty().get());
        }

        return scene;
    }

    /**
     * Set the scene to be displayed in this stage
     *
     * @param scene The peer of the scene to be displayed
     */
    @Override public void setScene(TKScene scene) {
        GlassScene oldScene = getScene();
        if (oldScene == scene) {
            // Nothing to do
            return;
        }
        // JDK-8126842, JDK-8124937
        // We don't support scene changes in full-screen mode.
        exitFullScreen();
        super.setScene(scene);
        if (scene != null) {
            GlassScene newScene = getViewScene();
            View view = newScene.getPlatformView();
            QuantumToolkit.runWithRenderLock(() -> {
                platformWindow.setView(view);
                if (oldScene != null) oldScene.updateSceneState();
                newScene.updateSceneState();
                return null;
            });
        } else {
            QuantumToolkit.runWithRenderLock(() -> {
                // platformWindow can be null here, if this window is owned,
                // and its owner is being closed.
                if (platformWindow != null) {
                    platformWindow.setView(null);
                }
                if (oldScene != null) {
                    oldScene.updateSceneState();
                }
                return null;
            });
        }
        if (oldScene != null) {
            disposeScenePainter((ViewScene) oldScene);
        }
    }

    private static void disposeScenePainter(ViewScene oldScene) {
        ViewPainter painter = oldScene.getPainter();
        // getPainter() can be null if the scene was never painted (e.g. a stage
        // shown and immediately replaced); guard before dereferencing.
        if (painter == null) {
            return;
        }
        QuantumRenderer.getInstance().disposePresentable(painter.presentable);   // latched on RT
    }

    @Override public void setBounds(float x, float y, boolean xSet, boolean ySet,
                                    float w, float h, float cw, float ch,
                                    float xGravity, float yGravity,
                                    float renderScaleX, float renderScaleY)
    {
        if (renderScaleX > 0.0 || renderScaleY > 0.0) {
            // We set the render scale first since the call to setBounds()
            // below can induce a recursive update on the scales if it moves
            // the window to a new screen and we will then end up being called
            // back with a new scale.  We do not want to set these old scale
            // values after that recursion happens.
            if (renderScaleX > 0.0) {
                platformWindow.setRenderScaleX(renderScaleX);
            }
            if (renderScaleY > 0.0) {
                platformWindow.setRenderScaleY(renderScaleY);
            }
            ViewScene vscene = getViewScene();
            if (vscene != null) {
                vscene.updateSceneState();
                vscene.entireSceneNeedsRepaint();
            }
        }
        if (xSet || ySet || w > 0 || h > 0 || cw > 0 || ch > 0) {
            platformWindow.setBounds(x, y, xSet, ySet, w, h, cw, ch, xGravity, yGravity);
        }
    }

    @Override
    public float getPlatformScaleX() {
        return platformWindow.getPlatformScaleX();
    }

    @Override
    public float getPlatformScaleY() {
        return platformWindow.getPlatformScaleY();
    }

    @Override
    public float getOutputScaleX() {
        return platformWindow.getOutputScaleX();
    }

    @Override
    public float getOutputScaleY() {
        return platformWindow.getOutputScaleY();
    }

    @Override public void setMinimumSize(int minWidth, int minHeight) {
        minWidth  = (int) Math.ceil(minWidth  * getPlatformScaleX());
        minHeight = (int) Math.ceil(minHeight * getPlatformScaleY());
        platformWindow.setMinimumSize(minWidth, minHeight);
    }

    @Override public void setMaximumSize(int maxWidth, int maxHeight) {
        maxWidth  = (int) Math.ceil(maxWidth  * getPlatformScaleX());
        maxHeight = (int) Math.ceil(maxHeight * getPlatformScaleY());
        platformWindow.setMaximumSize(maxWidth, maxHeight);
    }

    static Image findBestImage(java.util.List icons, int width, int height) {
        Image image = null;
        double bestSimilarity = 3; //Impossibly high value
        for (Object icon : icons) {
            //Iterate imageList looking for best matching image.
            //'Similarity' measure is defined as good scale factor and small insets.
            //best possible similarity is 0 (no scale, no insets).
            //It's found by experimentation that good-looking results are achieved
            //with scale factors x1, x3/4, x2/3, xN, x1/N.
            //Check to make sure the image/image format is correct.
            Image im = (Image)icon;
            if (im == null || !(im.getPixelFormat() == PixelFormat.BYTE_RGB ||
                im.getPixelFormat() == PixelFormat.BYTE_BGRA_PRE ||
                im.getPixelFormat() == PixelFormat.BYTE_GRAY))
            {
                continue;
            }

            int iw = im.getWidth();
            int ih = im.getHeight();

            if (iw > 0 && ih > 0) {
                //Calc scale factor
                double scaleFactor = Math.min((double)width / (double)iw,
                                              (double)height / (double)ih);
                //Calculate scaled image dimensions
                //adjusting scale factor to nearest "good" value
                int adjw;
                int adjh;
                double scaleMeasure = 1; //0 - best (no) scale, 1 - impossibly bad
                if (scaleFactor >= 2) {
                    //Need to enlarge image more than twice
                    //Round down scale factor to multiply by integer value
                    scaleFactor = Math.floor(scaleFactor);
                    adjw = iw * (int)scaleFactor;
                    adjh = ih * (int)scaleFactor;
                    scaleMeasure = 1.0 - 0.5 / scaleFactor;
                } else if (scaleFactor >= 1) {
                    //Don't scale
                    scaleFactor = 1.0;
                    adjw = iw;
                    adjh = ih;
                    scaleMeasure = 0;
                } else if (scaleFactor >= 0.75) {
                    //Multiply by 3/4
                    scaleFactor = 0.75;
                    adjw = iw * 3 / 4;
                    adjh = ih * 3 / 4;
                    scaleMeasure = 0.3;
                } else if (scaleFactor >= 0.6666) {
                    //Multiply by 2/3
                    scaleFactor = 0.6666;
                    adjw = iw * 2 / 3;
                    adjh = ih * 2 / 3;
                    scaleMeasure = 0.33;
                } else {
                    //Multiply size by 1/scaleDivider
                    //where scaleDivider is minimum possible integer
                    //larger than 1/scaleFactor
                    double scaleDivider = Math.ceil(1.0 / scaleFactor);
                    scaleFactor = 1.0 / scaleDivider;
                    adjw = (int)Math.round(iw / scaleDivider);
                    adjh = (int)Math.round(ih / scaleDivider);
                    scaleMeasure = 1.0 - 1.0 / scaleDivider;
                }
                double similarity = ((double)width - (double)adjw) / width +
                    ((double)height - (double)adjh) / height + //Large padding is bad
                    scaleMeasure; //Large rescale is bad
                if (similarity < bestSimilarity) {
                    bestSimilarity = similarity;
                    image = im;
                }
                if (similarity == 0) break;
            }
        }
        return image;
    }

    @Override public void setIcons(java.util.List icons) {

        int SMALL_ICON_HEIGHT = 32;
        int SMALL_ICON_WIDTH = 32;
        if (PlatformUtil.isMac()) { //Mac Sized Icons
            SMALL_ICON_HEIGHT = 128;
            SMALL_ICON_WIDTH = 128;
        } else if (PlatformUtil.isWindows()) { //Windows Sized Icons
            SMALL_ICON_HEIGHT = 32;
            SMALL_ICON_WIDTH = 32;
        } else if (PlatformUtil.isLinux()) { //Linux icons
            SMALL_ICON_HEIGHT = 128;
            SMALL_ICON_WIDTH = 128;
        }

        if (icons == null || icons.size() < 1) { //no icons passed in
            platformWindow.setIcon(null);
            return;
        }

        Image image = findBestImage(icons, SMALL_ICON_WIDTH, SMALL_ICON_HEIGHT);
        if (image == null) {
            //No images were found, possibly all are broken
            return;
        }

        PushbroomScaler scaler = ScalerFactory.createScaler(image.getWidth(), image.getHeight(),
                                                            image.getBytesPerPixelUnit(),
                                                            SMALL_ICON_WIDTH, SMALL_ICON_HEIGHT, true);

        //shrink the image and convert the format to INT_ARGB_PRE
        ByteBuffer buf = (ByteBuffer) image.getPixelBuffer();
        byte bytes[] = new byte[buf.limit()];

        int iheight = image.getHeight();

        //Iterate through each scanline of the image
        //and pass it one at a time to the scaling object
        for (int z = 0; z < iheight; z++) {
            buf.position(z*image.getScanlineStride());
            buf.get(bytes, 0, image.getScanlineStride());
            scaler.putSourceScanline(bytes, 0);
        }

        buf.rewind();

        final Image img = image.iconify(scaler.getDestination(), SMALL_ICON_WIDTH, SMALL_ICON_HEIGHT);
        platformWindow.setIcon(PixelUtils.imageToPixels(img));
    }

    @Override public void setTitle(String title) {
        platformWindow.setTitle(title);
    }

    @Override public void setVisible(final boolean visible) {
        // Before setting visible to false on the native window, we unblock
        // other windows.
        if (!visible) {
            removeActiveWindow(this);
            if (modality == Modality.WINDOW_MODAL) {
                if (owner != null && owner instanceof WindowStage) {
                    ((WindowStage) owner).setEnabled(true);
                }
            } else if (modality == Modality.APPLICATION_MODAL) {
                windowsSetEnabled(true);
            }
            // Note: This method is required to workaround a glass issue
            // mentioned in JDK-8112637
            // If the hiding stage is unfocusable (i.e. it's a PopupStage),
            // then we don't do this to avoid stealing the focus.
            // JDK-8210973: APPLICATION_MODAL window can have owner.
            if (!isPopupStage && owner != null && owner instanceof WindowStage) {
                WindowStage ownerStage = (WindowStage)owner;
                ownerStage.requestToFront();
            }
        }

        // skia-fx: paint-before-show. See the class-level field
        // comment on paintBeforeShow for the architecture. Only
        // engaged on a fresh first show — re-shows from
        // iconified state already have a populated redirection
        // bitmap and go through the stock path.
        //
        // StageStyle.CUSTOM: WS_OVERLAPPEDWINDOW windows have a DWM
        // redirection bitmap separate from the Skia swap chain, and the
        // OS show animation reveals that bitmap — which a flip-model
        // swap-chain Present alone cannot reach. We still paint-before-
        // show them: PresentingPainter calls SkiaPresentable
        // .primeWindowForShow() (native GDI blit of the rendered frame
        // onto the redirection bitmap, the same surface CustomWinProc's
        // WM_ERASEBKGND fills) just before Present, so the OS-native show
        // animation reveals the real first frame instead of a flash.
        //
        // Disabled for popup stages (ComboBox/Menu/ColorPicker/DatePicker
        // dropdowns, tooltips, context menus): Window.POPUP windows have no
        // OS show animation / DWM redirection-bitmap reveal, so paint-before-
        // show buys them nothing — it only blocks the show on the first
        // render+present (up to PAINT_BEFORE_SHOW_TIMEOUT_MS, 500 ms, when the
        // first-present latch isn't signalled), which is the perceived
        // "popup takes a moment to appear" lag. Popups are transparent, so the
        // stock path (show immediately, paint on the next pulse) shows no flash.
        final boolean preShowPaint =
            PAINT_BEFORE_SHOW_ENABLED
            && visible
            && !isPopupStage
            && platformWindow != null
            && !platformWindow.isVisible()
            && getScene() != null;

        if (preShowPaint) {
            firstPresentLatch = new CountDownLatch(1);
            paintBeforeShow = true;
            try {
                // Open the render gate. super.setVisible runs
                // GlassStage.setVisible -> scene.stageVisible(true)
                // which adds the scene to PaintCollector's dirty
                // list. Required for any paint to happen.
                QuantumToolkit.runWithRenderLock(() -> {
                    super.setVisible(true);
                    return null;
                });

                // Drive a full pulse synchronously on the FX
                // thread. firePulse alone runs only the FX-side
                // listeners (CSS / layout / syncPeer); the
                // render-thread trigger lives in pulse()'s
                // PaintCollector.renderAll() call after firePulse.
                ((QuantumToolkit) QuantumToolkit.getToolkit()).pulse(true);

                // Wait briefly for the render thread to Present
                // into the swap chain.
                try {
                    firstPresentLatch.await(
                        PAINT_BEFORE_SHOW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    // Deliberately do NOT re-assert the interrupt on the FX
                    // Application Thread. This is a long-lived event-loop thread
                    // that never consumes its interrupt status, and a leftover
                    // interrupt flag makes the next *interruptible* NIO read fail —
                    // including a lazy class load from an exploded module dir,
                    // which then surfaces as a spurious ClassNotFoundException for
                    // whatever class happens to load next (observed:
                    // ScrollEvent$HorizontalTextScrollUnits on the first scroll
                    // after an interrupted show). The latch wait is best-effort
                    // (paint-before-show); on interrupt we simply proceed to
                    // ShowWindow. Catching here also clears any pre-existing stray
                    // interrupt so subsequent classloading on this thread is safe.
                    // See docs/DPI_MONITOR_MOVE_AND_BUG_FIX_PLAN.md.
                }
            } finally {
                paintBeforeShow = false;
                firstPresentLatch = null;
            }

            // Native ShowWindow now; the OS show animation reveals
            // whatever the redirection bitmap holds, which DWM
            // picked up from the pre-show Present.
            QuantumToolkit.runWithRenderLock(() -> {
                if (platformWindow != null) {
                    platformWindow.setVisible(true);
                }
                return null;
            });

            // skia-fx: the pre-show Present targeted DWM's redirection
            // bitmap while the window was hidden. When another window
            // keeps the render thread busy (a live WebView presenting
            // every pulse), that Present can miss the latch timeout, so
            // the just-revealed window shows a stale/blank bitmap and —
            // created at its final size, with no further WM_SIZE — never
            // gets a corrective repaint (renderAll already dropped it
            // from the dirty set). Now that the HWND is actually visible,
            // force one full repaint so real content lands in the live
            // window. One-shot per fresh show; async (no latch), so it
            // can be delayed by a frame under contention but never lost.
            // Does not touch the steady-state render path.
            GlassScene gs = getScene();
            if (gs != null) {
                gs.entireSceneNeedsRepaint();
            }
        } else {
            QuantumToolkit.runWithRenderLock(() -> {
                // platformWindow can be null here, if this window is owned,
                // and its owner is being closed.
                if (platformWindow != null) {
                    platformWindow.setVisible(visible);
                }
                super.setVisible(visible);
                return null;
            });
        }
        // After setting visible to true on the native window, we block
        // other windows.
        if (visible) {
            if (modality == Modality.WINDOW_MODAL) {
                if (owner != null && owner instanceof WindowStage) {
                    ((WindowStage) owner).setEnabled(false);
                }
            } else if (modality == Modality.APPLICATION_MODAL) {
                windowsSetEnabled(false);
            }
        }

        applyFullScreen();
    }

    @Override boolean isVisible() {
        return platformWindow.isVisible();
    }

    @Override public void setOpacity(float opacity) {
        platformWindow.setAlpha(opacity);
        GlassScene gs = getScene();
        if (gs != null) {
            gs.entireSceneNeedsRepaint();
        }
    }

    public boolean needsUpdateWindow() {
        return transparent && (Application.GetApplication().shouldUpdateWindow());
    }

    @Override public void setIconified(boolean iconified) {
        if (platformWindow.isMinimized() == iconified) {
            return;
        }
        platformWindow.minimize(iconified);
    }

    @Override public void setMaximized(boolean maximized) {
        if (platformWindow.isMaximized() == maximized) {
            return;
        }
        platformWindow.maximize(maximized);
    }

    @Override
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop == alwaysOnTop) {
            return;
        }

        if (alwaysOnTop) {
            platformWindow.setLevel(Level.FLOATING);
        } else {
            platformWindow.setLevel(Level.NORMAL);
        }
        isAlwaysOnTop = alwaysOnTop;
    }

    @Override public void setResizable(boolean resizable) {
        platformWindow.setResizable(resizable);
        // note: for child windows this is ignored and we fail silently
    }

    // Safely exit full screen
    void exitFullScreen() {
        setFullScreen(false);
    }

    private KeyCombination savedFullScreenExitKey = null;

    public final KeyCombination getSavedFullScreenExitKey() {
        return savedFullScreenExitKey;
    }

    private void applyFullScreen() {
        if (platformWindow == null) {
            // applyFullScreen() can be called from setVisible(false), while the
            // platformWindow has already been destroyed.
            return;
        }
        View v = platformWindow.getView();
        if (isVisible() && v != null && v.isInFullscreen() != isInFullScreen) {
            if (isInFullScreen) {
                v.enterFullscreen(false, false, false);
                if (warning != null && warning.inWarningTransition()) {
                    warning.setView(getViewScene());
                } else {
                    boolean showWarning = true;

                    KeyCombination key = null;
                    String exitMessage = null;

                    if (fxStage != null) {
                        // copy the user set definitions for later use.
                        key = fxStage.getFullScreenExitKeyCombination();

                        exitMessage = fxStage.getFullScreenExitHint();
                    }

                    savedFullScreenExitKey =
                            key == null
                            ? defaultFullScreenExitKeycombo
                            : key;

                    if (
                        // the hint is ""
                        "".equals(exitMessage) ||
                        // if the key is NO_MATCH
                        (savedFullScreenExitKey.equals(KeyCombination.NO_MATCH))
                            ) {
                        showWarning = false;
                    }

                    // the hint is not set, use the key for the message
                    if (showWarning && exitMessage == null) {
                        if (key == null) {
                            exitMessage = RESOURCES.getString("OverlayWarningESC");
                        } else {
                            String f = RESOURCES.getString("OverlayWarningKey");
                            exitMessage = f.format(f, savedFullScreenExitKey.toString());
                        }
                    }

                    if (showWarning && warning == null) {
                        setWarning(new OverlayWarning(getViewScene()));
                    }

                    if (showWarning && warning != null) {
                        warning.warn(exitMessage);
                    }
                }
            } else {
                if (warning != null) {
                    warning.cancel();
                }

                setWarning(null);
                v.exitFullscreen(false);
            }
        } else if (!isVisible() && warning != null) {
            // if the window is closed - re-open with fresh warning
            warning.cancel();
            setWarning(null);
        }
    }

    void setWarning(OverlayWarning newWarning) {
        this.warning = newWarning;
        if (newWarning != null) {
            getViewScene().setOverlay(newWarning);
        } else if (!isInFullScreen) {
            getViewScene().setOverlay(platformWindow.headerButtonOverlayProperty().get());
        }
    }

    @Override public void setFullScreen(boolean fullScreen) {
        if (isInFullScreen == fullScreen) {
            return;
        }

        GlassStage fsWindow = activeFSWindow.get();
        if (fullScreen && (fsWindow != null)) {
            fsWindow.setFullScreen(false);
        }
        isInFullScreen = fullScreen;
        applyFullScreen();
        if (fullScreen) {
            activeFSWindow.set(this);
        }
    }

    void fullscreenChanged(final boolean fs) {
        if (!fs) {
            if (activeFSWindow.compareAndSet(this, null)) {
                isInFullScreen = false;
            }
        } else {
            isInFullScreen = true;
            activeFSWindow.set(this);
        }
        if (stageListener != null) {
            stageListener.changedFullscreen(fs);
        }
    }

    @Override public void toBack() {
        platformWindow.toBack();
    }

    @Override public void toFront() {
        platformWindow.requestFocus(); // JDK-8128222
        platformWindow.toFront();
    }

    private boolean isClosePostponed = false;
    private Window deadWindow = null;

    @Override
    public void postponeClose() {
        isClosePostponed = true;
    }

    @Override
    public void closePostponed() {
        if (deadWindow != null) {
            deadWindow.close();
            deadWindow = null;
        }
    }

    @Override public void close() {
        super.close();
        QuantumToolkit.runWithRenderLock(() -> {
            // prevents closing a closed platform window
            if (platformWindow != null) {
                platformWindows.remove(platformWindow);
                if (isClosePostponed) {
                    deadWindow = platformWindow;
                } else {
                    platformWindow.close();
                }
                platformWindow = null;
            }
            ViewScene oldScene = getViewScene();
            if (oldScene != null) {
                oldScene.updateSceneState();
                disposeScenePainter(oldScene);
            }
            return null;
        });
    }

    // setPlatformWindowClosed is only set upon receiving platform window has
    // closed notification. This state is necessary to prevent the platform
    // window from being closed more than once.
    void setPlatformWindowClosed() {
        if (platformWindow != null) {
            platformWindows.remove(platformWindow);
            platformWindow = null;
        }
    }

    static void addActiveWindow(WindowStage window) {
        activeWindows.remove(window);
        activeWindows.add(window);
    }

    static void removeActiveWindow(WindowStage window) {
        activeWindows.remove(window);
    }

    final void handleFocusDisabled() {
        if (activeWindows.isEmpty()) {
            return;
        }
        WindowStage window = activeWindows.get(activeWindows.size() - 1);
        window.setIconified(false);
        window.requestToFront();
        window.requestFocus();
    }

    @Override public boolean grabFocus() {
        return platformWindow.grabFocus();
    }

    @Override public void ungrabFocus() {
        platformWindow.ungrabFocus();
    }

    @Override public void requestFocus() {
        platformWindow.requestFocus();
    }

    @Override public void requestFocus(FocusCause cause) {
        switch (cause) {
            case TRAVERSED_FORWARD:
                platformWindow.requestFocus(WindowEvent.FOCUS_GAINED_FORWARD);
                break;
            case TRAVERSED_BACKWARD:
                platformWindow.requestFocus(WindowEvent.FOCUS_GAINED_BACKWARD);
                break;
            case ACTIVATED:
                platformWindow.requestFocus(WindowEvent.FOCUS_GAINED);
                break;
            case DEACTIVATED:
                platformWindow.requestFocus(WindowEvent.FOCUS_LOST);
                break;
        }
    }

    @Override
    protected void setPlatformEnabled(boolean enabled) {
        super.setPlatformEnabled(enabled);
        if (platformWindow != null) {
            platformWindow.setEnabled(enabled);
        }
        if (enabled) {
            // Check if window is really enabled - to handle nested case
            if (platformWindow != null && platformWindow.isEnabled()
                    && modality == Modality.APPLICATION_MODAL) {
                requestToFront();
            }
        } else {
            removeActiveWindow(this);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        if ((owner != null) && (owner instanceof WindowStage)) {
            ((WindowStage) owner).setEnabled(enabled);
        }
        /*
         * JDK-8128168 - exit if stage is closed from under us as
         *            any further access to the Glass layer
         *            will throw an exception
         */
        if (enabled && (platformWindow == null || platformWindow.isClosed())) {
            return;
        }
        setPlatformEnabled(enabled);
    }

    @Override
    public long getRawHandle() {
       return platformWindow.getRawHandle();
    }

    // Note: This method is required to workaround a glass issue mentioned in JDK-8112637
    protected void requestToFront() {
        if (platformWindow != null) {
            platformWindow.toFront();
            platformWindow.requestFocus();
        }
    }

    @Override
    public void requestInput(String text, int type, double width, double height,
                        double Mxx, double Mxy, double Mxz, double Mxt,
                        double Myx, double Myy, double Myz, double Myt,
                        double Mzx, double Mzy, double Mzz, double Mzt) {
        platformWindow.requestInput(text, type, width, height,
                                    Mxx, Mxy, Mxz, Mxt,
                                    Myx, Myy, Myz, Myt,
                                    Mzx, Mzy, Mzz, Mzt);
    }

    @Override
    public void releaseInput() {
        platformWindow.releaseInput();
    }

    @Override public void setRTL(boolean b) {
        rtl = b;
    }

    @Override
    public void setPrefHeaderButtonHeight(double height) {
        if (platformWindow != null) {
            platformWindow.setPrefHeaderButtonHeight(height);
        }
    }

    @Override
    public void setDarkFrame(boolean value) {
        darkFrame = value;

        if (platformWindow != null) {
            platformWindow.setDarkFrame(value);
        }
    }
}
