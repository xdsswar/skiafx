/*
 * Copyright (c) 2009, 2025, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.prism;

import com.sun.glass.ui.Screen;
import com.sun.javafx.font.FontFactory;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.prism.impl.PrismSettings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class GraphicsPipeline {

    public static enum ShaderType {
        /**
         * The pipeline supports shaders built with the D3D HLSL shader language.
         */
        HLSL,
        /**
         * The pipeline supports shaders built with the OpenGL GLSL shader language
         */
        GLSL,
        /**
         * The pipeline supports shaders built with the Metal shader language
         */
        MSL
    }

    public static enum ShaderModel {
        /**
         * The pipeline supports Shader Model 3 features, including Pixel Shader
         * 3.0 and Vertex Shader 3.0 programs.
         */
        SM3
    }
    private FontFactory fontFactory;
    private final Set<Runnable> disposeHooks = new HashSet<>();

    public abstract boolean init();
    public void dispose() {
        notifyDisposeHooks();
        installedPipeline = null;
    }

    /**
     * Add a dispose hook to be called when the pipeline is disposed.
     *
     * @param runnable the {@link Runnable} to be called when the pipeline is disposed
     */
    public void addDisposeHook(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        synchronized (disposeHooks) {
            disposeHooks.add(runnable);
        }
    }

    private void notifyDisposeHooks() {
        List<Runnable> hooks;
        synchronized (disposeHooks) {
            hooks = new ArrayList<>(disposeHooks);
            disposeHooks.clear();
        }

        for (Runnable hook : hooks) {
            hook.run();
        }
    }

    public abstract int getAdapterOrdinal(Screen screen);

    /*
     * The following method allows to access several graphics adapters individually.
     * Graphics resources are not sharable between different adapters
     */
    public abstract ResourceFactory getResourceFactory(Screen screen);

    /*
     * getDefaultResourceFactory returns system-default graphics device
     */
    public abstract ResourceFactory getDefaultResourceFactory(List<Screen> screens);

    public abstract boolean is3DSupported();

    public boolean isMSAASupported() { return false; }

    public abstract boolean isVsyncSupported();

    /**
     * Returns true iff the graphics objects from this pipeline support
     * the indicated {@link ShaderType}.
     *
     * @param type the desired {@link ShaderType} to be used
     * @return true if the indicated {@code ShaderType} is supported
     */
    public abstract boolean supportsShaderType(ShaderType type);

    /**
     * Returns true iff the graphics objects from this pipeline support
     * the indicated {@link ShaderModel}.  Generally, the pipeline will
     * also support all older or lower-numbered {@code ShaderModel}s as well.
     *
     * @param model the desired {@link ShaderModel} to be used
     * @return true if the indicated {@code ShaderModel} is supported
     */
    public abstract boolean supportsShaderModel(ShaderModel model);

    /**
     * Returns true iff the graphics objects from this pipeline support
     * the indicated {@link ShaderType} and {@link ShaderModel}.  Generally,
     * the pipeline will also support all older or lower-numbered
     * {@code ShaderModel}s as well.
     *
     * @param type the desired {@link ShaderType} to be used
     * @param model the desired {@link ShaderModel} to be used
     * @return true if the indicated {@code ShaderType} and {@code ShaderModel}
     *              are supported
     */
    public boolean supportsShader(ShaderType type, ShaderModel model) {
        return (supportsShaderType(type) && supportsShaderModel(model));
    }

    public static ResourceFactory getDefaultResourceFactory() {
        List<Screen> screens = Screen.getScreens();
        return getPipeline().getDefaultResourceFactory(screens);
    }

    public FontFactory getFontFactory() {
        if (fontFactory == null) {
            fontFactory = PrismFontFactory.getFontFactory();
        }
        return fontFactory;
    }

    protected Map deviceDetails = null;

    /*
     * returns optional device dependant details, may be null.
     */
    public Map getDeviceDetails() {
        return deviceDetails;
    }

    /*
     * sets optional device dependant details, may be null.
     * This should be done very early (like at init time) and then not changed.
     */
    protected void setDeviceDetails(Map details) {
        deviceDetails = details;
    }

    private static GraphicsPipeline installedPipeline;

    public static GraphicsPipeline createPipeline() {
        if (installedPipeline != null) {
            throw new IllegalStateException("pipeline already created:" +
                                            installedPipeline);
        }
        if (PrismSettings.tryOrder.isEmpty()) {
            throw new IllegalStateException(
                "No Prism pipeline configured. openjfx-skia ships with " +
                "SKIAPipeline as the only supported backend — set " +
                "-Dprism.order=skia (the default) and ensure " +
                "openjfx_skia_shared was built with SKIA_HOME pointing at " +
                "a real Skia build.");
        }

        // openjfx-skia: the Prism-era D3D / ES2 / MTL / SW / J2D backends
        // are gone. The discovery loop now only ever loads
        // com.sun.prism.skia.SKIAPipeline (it stays a loop so a user
        // override via -Dprism.order is still honoured at runtime —
        // unknown names just hit ClassNotFoundException and fall
        // through to the hard-fail at the bottom).
        Throwable lastFailure = null;
        for (String prefix : PrismSettings.tryOrder) {
            String className =
                "com.sun.prism."+prefix+"."+prefix.toUpperCase()+"Pipeline";
            try {
                if (PrismSettings.verbose) {
                    System.out.println("Skia pipeline class = " + className);
                }
                Class klass = Class.forName(className);
                if (PrismSettings.verbose) {
                    System.out.println("(X) Got class = " + klass);
                }
                Method m = klass.getMethod("getInstance", (Class[])null);
                GraphicsPipeline newPipeline = (GraphicsPipeline)
                    m.invoke(null, (Object[])null);
                if (newPipeline != null && newPipeline.init()) {
                    if (PrismSettings.verbose) {
                        System.out.println("Skia pipeline initialised: " +
                                           klass.getName());
                    }
                    installedPipeline = newPipeline;
                    return installedPipeline;
                }
                if (newPipeline != null) {
                    newPipeline.dispose();
                }
                lastFailure = new IllegalStateException(
                    className + ".init() returned false");
            } catch (Throwable t) {
                lastFailure = t;
                if (PrismSettings.verbose) {
                    System.err.println("GraphicsPipeline.createPipeline " +
                                       "failed for " + className);
                    t.printStackTrace();
                }
            }
        }

        // Nothing in the tryOrder list could be brought up. With Skia
        // being the only valid backend in this build, this is fatal —
        // historically Prism would just return null and the JavaFX
        // runtime would no-op into a broken state. Hard-fail instead
        // so the user sees the real cause (typically: Skia native lib
        // missing, or the user overrode -Dprism.order with an unknown
        // backend that no longer exists in the tree).
        StringBuilder sBuf = new StringBuilder(
            "Skia pipeline initialization failed. Tried: ");
        boolean first = true;
        for (String prefix : PrismSettings.tryOrder) {
            if (!first) sBuf.append(", ");
            sBuf.append(prefix);
            first = false;
        }
        sBuf.append(". Note that the Prism-era d3d/es2/mtl/sw/j2d ")
            .append("backends have been removed from openjfx-skia; ")
            .append("only 'skia' is valid for -Dprism.order.");
        throw new IllegalStateException(sBuf.toString(), lastFailure);
    }

    public static GraphicsPipeline getPipeline() {
        return installedPipeline;
    }

    public boolean isEffectSupported() {
        return true;
    }

    /**
     * Checks if the GraphicsPipeline uses uploading or presenting painter
     * @return true if the pipeline uses an uploading painter
     */
    public boolean isUploading() {
        return PrismSettings.forceUploadingPainter;
    }
}
