/*
 * Copyright (c) 2026 skia-fx. All rights reserved.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 */
package com.sun.javafx.application;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Internal bridge that lets {@link com.sun.javafx.application.LauncherImpl}
 * (a different package) invoke {@code Application}'s <em>protected</em>
 * primary-stage factory without forcing that hook to be {@code public}.
 *
 * <p>This is the standard JavaFX "Helper/Accessor" shim pattern (cf.
 * {@code StageHelper}, {@code NodeHelper}): {@code Application}'s static
 * initializer registers an {@link Accessor} that closes over the
 * protected method; {@code LauncherImpl} reads it back through the
 * static entry point below. Because the application instance is
 * constructed (which initializes the {@code Application} class) before
 * the launcher creates the primary stage, the accessor is always set by
 * the time {@link #createPrimaryStage(Application)} is called.</p>
 *
 * <p>skia-fx addition — supports {@code Application<W extends Stage>}
 * with a custom {@code Stage} subclass as the primary window. See
 * {@code docs/CUSTOM_PRIMARY_STAGE.md}.</p>
 */
public final class ApplicationAccessor {

    /** Closes over {@code Application#createPrimaryStage()}. */
    public interface Accessor {
        /**
         * Creates the primary stage for the given application by calling
         * its (overridable, protected) {@code createPrimaryStage()} hook.
         *
         * @param app the application instance being launched
         * @return the primary stage; never {@code null}
         */
        Stage createPrimaryStage(Application<?> app);
    }

    private static Accessor accessor;

    private ApplicationAccessor() {
    }

    /**
     * Registers the accessor. Called once from {@code Application}'s
     * static initializer.
     *
     * @param a the accessor implementation
     */
    public static void setAccessor(Accessor a) {
        accessor = a;
    }

    /**
     * Invokes the application's {@code createPrimaryStage()} factory.
     *
     * @param app the application instance being launched
     * @return the primary stage to hand to {@code Application#start}
     */
    public static Stage createPrimaryStage(Application<?> app) {
        // Force Application initialization so the accessor is registered,
        // covering the (unlikely) path where this is reached before any
        // Application subclass has been initialized.
        if (accessor == null) {
            try {
                Class.forName(Application.class.getName(), true,
                        Application.class.getClassLoader());
            } catch (ClassNotFoundException ignore) {
                // Application is obviously present; ignore.
            }
        }
        if (accessor == null) {
            throw new IllegalStateException(
                    "Application primary-stage accessor not initialized");
        }
        return accessor.createPrimaryStage(app);
    }
}
