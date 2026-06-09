/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.tk;

import java.lang.reflect.Method;
import javafx.collections.ObservableSet;
import javafx.print.Printer;
import javafx.print.PrinterJob;

import com.sun.javafx.print.PrinterJobImpl;

public abstract class PrintPipeline {

    private static PrintPipeline ppl = null;

    public static PrintPipeline getPrintPipeline() {
        if (ppl != null) {
            return ppl;
        }
        // openjfx-skia: the legacy com.sun.prism.j2d print bridge has
        // been removed as part of the "zero Prism backends" migration.
        // A Skia-based print pipeline (SkSurface raster → BufferedImage
        // → AWT print spooler) is planned as a follow-up; until then,
        // javafx.print APIs throw the message below at first use.
        // Apps that don't print are unaffected.
        throw new UnsupportedOperationException(
            "javafx.print is not yet wired in openjfx-skia. The Prism "
          + "j2d print bridge was removed alongside the d3d/es2/mtl/sw "
          + "rendering backends; a Skia-backed replacement is tracked "
          + "as a follow-up task. Workaround: render via Skia onto a "
          + "javafx.scene.image.WritableImage and feed it to your own "
          + "print path.");
    }

    public abstract Printer getDefaultPrinter();
    public abstract ObservableSet<Printer> getAllPrinters();
    public abstract PrinterJobImpl createPrinterJob(PrinterJob job);
}
