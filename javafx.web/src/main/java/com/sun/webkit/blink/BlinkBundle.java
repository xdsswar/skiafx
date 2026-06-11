/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package com.sun.webkit.blink;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the {@code skia-fx-webview} engine runtime bundle (exe, dll/so/dylib,
 * .pak, ICU/V8 data, GPU libs) from the {@code javafx.web} jar to a per-user
 * cache directory, then hands back the engine executable path.
 *
 * <p>Unlike {@code com.sun.glass.utils.NativeLibLoader} (which can only extract
 * {@code System.mapLibraryName} libraries), the engine bundle includes data
 * files and an executable, so this is a dedicated extractor. It mirrors
 * NativeLibLoader's cache strategy: the jar-root {@code checksums.properties}
 * (SHA-256 per file, generated at build time) is the <b>authoritative file
 * list</b> — we extract exactly the entries it names and skip re-extraction
 * when an on-disk {@code .sha256} sidecar already matches. Extraction is
 * idempotent and thread-safe.
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
final class BlinkBundle {

    private static final Object LOCK = new Object();
    private static volatile Path enginePath;

    private BlinkBundle() { }

    /** Engine executable name for the host OS. */
    private static String engineExeName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "skia-fx-webview.exe" : "skia-fx-webview";
    }

    /**
     * Same cache directory the rest of skia-fx uses for native libs (see
     * {@code com.sun.glass.utils.NativeLibLoader}): the engine bundle is
     * extracted alongside {@code glass.dll}/{@code javafx_font.dll}/etc. in
     * {@code ~/.skia-fx/cache/<javafx.runtime.version>/<os.arch>/} (overridable
     * via the {@code javafx.cachedir} property), with a tmpdir fallback when the
     * home dir is not writable.
     */
    private static Path cacheDir() throws IOException {
        String jfxVersion = System.getProperty("javafx.runtime.version", "versionless").replace(":", "-");
        String arch = System.getProperty("os.arch", "unknown");
        String userCache = System.getProperty("javafx.cachedir", "");
        Path dir = userCache.isEmpty()
            ? Path.of(System.getProperty("user.home"), ".skia-fx", "cache", jfxVersion, arch)
            : Path.of(userCache);
        if (isUsable(dir)) {
            return dir;
        }
        // Home dir not usable — mirror NativeLibLoader's tmpdir fallback.
        String user = System.getProperty("user.name", "anonymous");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"),
            ".skia-fx_" + user, "cache", jfxVersion, arch);
        if (!isUsable(tmp)) {
            throw new IOException("no writable cache directory (tried " + dir + " and " + tmp + ")");
        }
        return tmp;
    }

    private static boolean isUsable(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isDirectory(dir) && Files.isReadable(dir) && Files.isWritable(dir);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extracts the bundle once and returns the absolute path to the engine
     * executable. Subsequent calls return the cached path without rework.
     *
     * @throws IOException if the bundle manifest or any file is missing
     */
    static Path ensureExtracted() throws IOException {
        Path cached = enginePath;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (enginePath != null) {
                return enginePath;
            }
            // Dev/test override: point straight at a built engine output dir
            // (e.g. .chromium/.../out/skiafxweb) — skips jar extraction.
            String override = System.getProperty("skia.webview.engineDir");
            if (override != null && !override.isBlank()) {
                Path exe = Path.of(override).resolve(engineExeName());
                if (!Files.exists(exe)) {
                    throw new IOException("skia.webview.engineDir has no " + engineExeName() + ": " + exe);
                }
                if (!engineExeName().endsWith(".exe")) {
                    exe.toFile().setExecutable(true, true);
                }
                enginePath = exe;
                return exe;
            }
            try (BundleSource source = BundleSource.resolve()) {
                Properties manifest = loadManifest(source);
                Path dir = cacheDir();
                Files.createDirectories(dir);

                for (String key : manifest.stringPropertyNames()) {
                    // keys are jar-root resource paths, e.g. "/skia-fx-webview.dll"
                    String resource = key.startsWith("/") ? key : "/" + key;
                    String name = resource.substring(resource.lastIndexOf('/') + 1);
                    if (name.isEmpty()) {
                        continue;
                    }
                    extractOne(source, resource, name, dir, manifest.getProperty(key));
                }

                Path exe = dir.resolve(engineExeName());
                if (!Files.exists(exe)) {
                    throw new IOException("engine executable missing after extraction: " + exe);
                }
                if (!engineExeName().endsWith(".exe")) {
                    exe.toFile().setExecutable(true, true);
                }
                enginePath = exe;
                return exe;
            }
        }
    }

    private static Properties loadManifest(BundleSource source) throws IOException {
        try (InputStream in = source.open("/checksums.properties")) {
            if (in == null) {
                throw new IOException("/checksums.properties not found" + source.describe() + " — "
                    + "the skia-fx-webview native bundle is not packaged "
                    + "(build with -PbuildWebNative=true).");
            }
            Properties p = new Properties();
            p.load(in);
            if (p.isEmpty()) {
                throw new IOException("/checksums.properties is empty — engine bundle not packaged.");
            }
            return p;
        }
    }

    private static void extractOne(BundleSource source, String resource, String name, Path dir, String wantHash)
            throws IOException {
        Path target = dir.resolve(name);
        Path sidecar = dir.resolve(name + ".sha256");

        // Skip if the on-disk copy already matches the shipped hash.
        if (wantHash != null && Files.exists(target) && wantHash.equals(readSidecar(sidecar))) {
            return;
        }

        try (InputStream in = source.open(resource)) {
            if (in == null) {
                // A manifest entry with no packaged resource is non-fatal: the
                // engine executable is the hard requirement (verified by the
                // caller after extraction). Optional in-process helpers (e.g.
                // skia_web_frame) degrade gracefully when absent, so skip with
                // a warning rather than aborting engine startup.
                System.getLogger(BlinkBundle.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "bundle resource missing from jar, skipping: " + resource);
                return;
            }
            Path tmp = dir.resolve(name + ".tmp");
            String actual = streamToFileWithHash(in, tmp);
            if (wantHash != null && !wantHash.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(tmp);
                throw new IOException("checksum mismatch for " + name
                    + " (manifest=" + wantHash + ", actual=" + actual + ")");
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (wantHash != null) {
                Files.writeString(sidecar, wantHash);
            }
        }
    }

    /**
     * Backing store for the engine bundle. The bundle (the {@code
     * checksums.properties} manifest + every engine file) ships at the root
     * of the {@code javafx.web} module jar itself (the single per-platform
     * jar, e.g. {@code javafx.web-<version>-win-x64.jar}, classes and
     * binaries together), so the primary path — reading this class's own
     * resources via {@code getResourceAsStream} — works on both the flat
     * classpath and the JPMS module path (a class can always read its own
     * module's resources).
     *
     * <p>The on-disk lookup below is retained as a fallback for the older
     * two-jar layout (a separate natives-only classifier jar beside the
     * classes jar), where JPMS resource encapsulation hid the second jar
     * from {@code getResourceAsStream} on the module path.</p>
     */
    private static final class BundleSource implements Closeable {
        // Non-null => read entries from this jar; null => read from the classpath.
        private final ZipFile zip;

        private BundleSource(ZipFile zip) {
            this.zip = zip;
        }

        static BundleSource resolve() {
            // Prefer the classpath: covers flat-classpath / legacy layouts and
            // is the cheap path when the resource is directly visible.
            if (BlinkBundle.class.getResource("/checksums.properties") != null) {
                return new BundleSource(null);
            }
            Path jar = locateNativesJar();
            if (jar != null) {
                try {
                    return new BundleSource(new ZipFile(jar.toFile()));
                } catch (IOException e) {
                    // Fall through: the classpath source's open() returns null and
                    // loadManifest throws the helpful "not packaged" error.
                }
            }
            return new BundleSource(null);
        }

        /** Opens a bundle entry, or returns {@code null} if it is absent. */
        InputStream open(String resource) throws IOException {
            if (zip == null) {
                return BlinkBundle.class.getResourceAsStream(resource);
            }
            String entryName = resource.startsWith("/") ? resource.substring(1) : resource;
            ZipEntry e = zip.getEntry(entryName);
            return e == null ? null : zip.getInputStream(e);
        }

        /** Human-readable source, woven into the "not packaged" diagnostic. */
        String describe() {
            return zip == null ? " on the classpath" : " in " + zip.getName();
        }

        @Override
        public void close() throws IOException {
            if (zip != null) {
                zip.close();
            }
        }
    }

    /** Host triple ({@code <platform>-<arch>}) matching the build's jar classifier. */
    private static String hostTriple() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String plat = os.contains("win") ? "win"
            : (os.contains("mac") || os.contains("darwin")) ? "mac"
            : "linux";
        String arch = System.getProperty("os.arch", "");
        String a = (arch.equals("x86_64") || arch.equals("amd64")) ? "x64" : "arm64";
        return plat + "-" + a;
    }

    /**
     * Locates the per-platform natives classifier jar
     * ({@code javafx.web-<version>-<triple>.jar}) on disk, anchored on this
     * module's own code-source location so it resolves for both the packaged
     * SDK ({@code sdk/lib/}) and the in-tree dev build ({@code build/libs/}).
     * Returns {@code null} when it cannot be found (caller then degrades to the
     * classpath source + helpful error).
     */
    private static Path locateNativesJar() {
        try {
            var cs = BlinkBundle.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path self = Path.of(cs.getLocation().toURI());
            String triple = hostTriple();

            // Loaded from a jar: the classifier jar sits beside it; derive its
            // exact name, then fall back to a directory scan.
            if (Files.isRegularFile(self)) {
                String n = self.getFileName().toString();
                if (n.endsWith(".jar")) {
                    Path sibling = self.resolveSibling(
                        n.substring(0, n.length() - 4) + "-" + triple + ".jar");
                    if (Files.isRegularFile(sibling)) {
                        return sibling;
                    }
                }
                Path hit = findInDir(self.getParent(), triple);
                if (hit != null) {
                    return hit;
                }
            }

            // Loaded from a classes directory (dev tree): probe nearby libs dirs.
            Path p = self;
            for (int i = 0; i < 6 && p != null; i++) {
                Path hit = findInDir(p.resolve("libs"), triple);
                if (hit != null) {
                    return hit;
                }
                p = p.getParent();
            }
        } catch (Exception ignore) {
            // best-effort discovery; null falls back to the classpath source
        }
        return null;
    }

    /** First {@code javafx.web*-<triple>.jar} in {@code dir}, or {@code null}. */
    private static Path findInDir(Path dir, String triple) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> {
                    String n = f.getFileName().toString();
                    return n.startsWith("javafx.web") && n.endsWith("-" + triple + ".jar");
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readSidecar(Path sidecar) {
        try {
            return Files.exists(sidecar) ? Files.readString(sidecar).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String streamToFileWithHash(InputStream in, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
        byte[] buf = new byte[8192];
        try (var out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
