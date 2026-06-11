/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.glass.utils;

import com.sun.javafx.PlatformUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class NativeLibLoader {

    private static final HashSet<String> loaded = new HashSet<>();

    public static synchronized void loadLibrary(String libname) {
        if (!loaded.contains(libname)) {
            StackWalker walker = StackWalker.
                getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            Class caller = walker.getCallerClass();
            loadLibraryInternal(libname, null, caller);
            loaded.add(libname);
        }
    }

    public static synchronized void loadLibrary(String libname, List<String> dependencies) {
        if (!loaded.contains(libname)) {
            StackWalker walker = StackWalker.
                getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            Class caller = walker.getCallerClass();
            loadLibraryInternal(libname, dependencies, caller);
            loaded.add(libname);
        }
    }

    private static boolean verbose = false;

    private static File libDir = null;
    private static String libPrefix = "";
    private static String libSuffix = "";

    static {
        verbose = Boolean.getBoolean("javafx.verbose");
    }

    private static String[] initializePath(String propname) {
        String ldpath = System.getProperty(propname, "");
        String ps = File.pathSeparator;
        int ldlen = ldpath.length();
        int i, j, n;
        // Count the separators in the path
        i = ldpath.indexOf(ps);
        n = 0;
        while (i >= 0) {
            n++;
            i = ldpath.indexOf(ps, i + 1);
        }

        // allocate the array of paths - n :'s = n + 1 path elements
        String[] paths = new String[n + 1];

        // Fill the array with paths from the ldpath
        n = i = 0;
        j = ldpath.indexOf(ps);
        while (j >= 0) {
            if (j - i > 0) {
                paths[n++] = ldpath.substring(i, j);
            } else if (j - i == 0) {
                paths[n++] = ".";
            }
            i = j + 1;
            j = ldpath.indexOf(ps, i);
        }
        paths[n] = ldpath.substring(i, ldlen);
        return paths;
    }

    private static void loadLibraryInternal(String libraryName, List<String> dependencies, Class caller) {
        // The search order for native library loading is:
        // - try to load the native library from either ${java.home}
        //   (for jlinked javafx modules) or from the same folder as
        //   this jar (if using modular jars)
        // - if the native library comes bundled as a resource it is extracted
        //   and loaded
        // - the java.library.path is searched for the library in definition
        //   order
        // - the library is loaded via System#loadLibrary
        // - on iOS native library is staticly linked and detected from the
        //   existence of a JNI_OnLoad_libraryname funtion
        try {
            // FIXME: JIGSAW -- We should eventually remove this legacy path,
            // since it isn't applicable to Jigsaw.
            loadLibraryFullPath(libraryName);
        } catch (UnsatisfiedLinkError ex) {
            if (verbose) {
                System.err.println("WARNING: " + ex);
            }

            // if the library is available in the jar, copy it to cache and load it from there
            if (loadLibraryFromResource(libraryName, dependencies, caller)) {
                return;
            }

            // NOTE: First attempt to load the libraries from the java.library.path.
            // This allows FX to find more recent versions of the shared libraries
            // from java.library.path instead of ones that might be part of the JRE
            //
            String [] libPath = initializePath("java.library.path");
            for (int i=0; i<libPath.length; i++) {
                try {
                    String path = libPath[i];
                    if (!path.endsWith(File.separator)) path += File.separator;
                    String fileName = System.mapLibraryName(libraryName);
                    File libFile = new File(path + fileName);
                    System.load(libFile.getAbsolutePath());
                    if (verbose) {
                        System.err.println("Loaded " + libFile.getAbsolutePath()
                                + " from java.library.path");
                    }
                    return;
                } catch (UnsatisfiedLinkError ex3) {
                    // Fail silently and try the next directory in java.library.path
                }
            }

            // Finally we will use System.loadLibrary.
            try {
                System.loadLibrary(libraryName);
                if (verbose) {
                    System.err.println("System.loadLibrary("
                            + libraryName + ") succeeded");
                }
            } catch (UnsatisfiedLinkError ex2) {
                //On iOS we link all libraries staticaly. Presence of library
                //is recognized by existence of JNI_OnLoad_libraryname() C function.
                //If libraryname contains hyphen, it needs to be translated
                //to underscore to form valid C function indentifier.
                if (PlatformUtil.isIOS() && libraryName.contains("-")) {
                    libraryName = libraryName.replace("-", "_");
                    try {
                        System.loadLibrary(libraryName);
                        return;
                    } catch (UnsatisfiedLinkError ex3) {
                        throw ex3;
                    }
                }
                // Rethrow exception
                throw ex2;
            }
        }
    }

   /**
    * If there is a library with the platform-correct name at the
    * root of the resources in this jar, use that.
    */
    private static boolean loadLibraryFromResource(String libraryName, List<String> dependencies, Class caller) {
        return installLibraryFromResource(libraryName, dependencies, caller, true);
    }

   /**
    * If there is a library with the platform-correct name at the
    * root of the resources in this jar, install it. If load is true, also load it.
    */
    private static boolean installLibraryFromResource(String libraryName, List<String> dependencies, Class caller, boolean load) {
        try {
            // first preload dependencies
            if (dependencies != null) {
                for (String dep: dependencies) {
                    boolean hasdep = installLibraryFromResource(dep, null, caller, false);
                }
            }
            String reallib = "/"+System.mapLibraryName(libraryName);
            InputStream is = openBundledResource(caller, reallib);
            if (is != null) {
                String fp = cacheLibrary(is, reallib, caller);
                if (load) {
                    System.load(fp);
                    if (verbose) {
                        System.err.println("Loaded library " + reallib + " from resource");
                    }
                } else if (verbose) {
                    System.err.println("Unpacked library " + reallib + " from resource");
                }
                return true;
            }
        } catch (Throwable t) {
            // we should only be here if the resource exists in the module, but
            // for some reasons it can't be loaded.
            System.err.println("Loading library " + libraryName + " from resource failed: " + t);
            t.printStackTrace();
        }
        return false;
    }

    /**
     * Opens a native-bundle resource for {@code caller}, transparently handling
     * the per-platform natives split. skia-fx ships each module's native libs
     * (and its {@code /checksums.properties}) in a SEPARATE classifier jar
     * (e.g. {@code javafx.graphics-<version>-win-x64.jar}) rather than the main
     * classes jar. Resolution order:
     *
     * <ol>
     *   <li>{@code caller.getResourceAsStream} — succeeds on a flat classpath
     *       (the classifier jar's entries are visible) and for the legacy
     *       single-jar layout where natives sat in the main jar.</li>
     *   <li>Otherwise — typically the JPMS module path, where a module may only
     *       read resources from its OWN jar — locate the sibling classifier jar
     *       on disk (beside the caller module's jar, or in {@code build/libs}
     *       for the in-tree dev build) and read the entry straight from the
     *       zip.</li>
     * </ol>
     *
     * This is what lets a downstream app launch with no JVM args on either the
     * classpath or {@code --module-path sdk/lib} and still find the natives.
     * Returns {@code null} if neither route has the resource, so the existing
     * java.library.path / System.loadLibrary fallbacks still apply.
     */
    private static InputStream openBundledResource(Class caller, String resource) {
        InputStream is = caller.getResourceAsStream(resource);
        if (is != null) {
            return is;
        }
        Path jar = locateNativesJar(caller);
        if (jar == null) {
            return null;
        }
        try {
            final ZipFile zf = new ZipFile(jar.toFile());
            String entry = resource.startsWith("/") ? resource.substring(1) : resource;
            ZipEntry e = zf.getEntry(entry);
            if (e == null) {
                zf.close();
                return null;
            }
            // Close the backing zip when the returned stream is closed.
            return new FilterInputStream(zf.getInputStream(e)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zf.close();
                    }
                }
            };
        } catch (IOException ex) {
            if (verbose) {
                System.err.println("WARNING: reading " + resource + " from " + jar + " failed: " + ex);
            }
            return null;
        }
    }

    /** Host triple ({@code <platform>-<arch>}) matching the build's jar classifier. */
    private static String hostTriple() {
        String plat = PlatformUtil.isWindows() ? "win"
                : PlatformUtil.isMac() ? "mac"
                : "linux";
        String arch = System.getProperty("os.arch", "");
        String a = (arch.equals("x86_64") || arch.equals("amd64")) ? "x64" : "arm64";
        return plat + "-" + a;
    }

    /** Per-classloader cache of the located natives classifier jar (null = none found). */
    private static final Map<ClassLoader, Path> nativesJarCache = new HashMap<>();

    private static synchronized Path locateNativesJar(Class caller) {
        ClassLoader cl = caller.getClassLoader();
        if (nativesJarCache.containsKey(cl)) {
            return nativesJarCache.get(cl);
        }
        Path found = findNativesJar(caller);
        nativesJarCache.put(cl, found);
        return found;
    }

    private static Path findNativesJar(Class caller) {
        try {
            var cs = caller.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path self = Path.of(cs.getLocation().toURI());
            String triple = hostTriple();
            String modulePrefix = (caller.getModule() != null && caller.getModule().isNamed())
                    ? caller.getModule().getName() : null;

            // Loaded from a jar: the classifier jar sits beside it. Derive the
            // exact name first, then fall back to a directory scan.
            if (Files.isRegularFile(self)) {
                String n = self.getFileName().toString();
                if (n.endsWith(".jar")) {
                    Path sibling = self.resolveSibling(
                            n.substring(0, n.length() - 4) + "-" + triple + ".jar");
                    if (Files.isRegularFile(sibling)) {
                        return sibling;
                    }
                }
                Path hit = firstClassifierJar(self.getParent(), triple, modulePrefix);
                if (hit != null) {
                    return hit;
                }
            }

            // Loaded from a classes directory (in-tree dev build): probe nearby
            // libs dirs walking up a few levels (build/classes/java/main ->
            // build/libs).
            Path p = self;
            for (int i = 0; i < 6 && p != null; i++) {
                Path hit = firstClassifierJar(p.resolve("libs"), triple, modulePrefix);
                if (hit != null) {
                    return hit;
                }
                p = p.getParent();
            }
        } catch (Exception ignore) {
            // best-effort discovery; null falls back to the other load paths
        }
        return null;
    }

    /** First {@code <modulePrefix>*-<triple>.jar} in {@code dir}, or {@code null}. */
    private static Path firstClassifierJar(Path dir, String triple, String modulePrefix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> {
                        String n = f.getFileName().toString();
                        if (!n.endsWith("-" + triple + ".jar")) {
                            return false;
                        }
                        return modulePrefix == null || n.startsWith(modulePrefix);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String cacheLibrary(InputStream is, String name, Class caller) throws IOException {
        // Ensure the resource stream is always closed, covering every branch
        // below — including the manifest-driven write=false early-out (which
        // never reads the stream) and the legacy MD5 path (which reassigns
        // `is` to a freshly opened stream after closing the original). The
        // finally closes whichever stream `is` points to on exit, so each
        // opened stream is closed exactly once. Files.copy does not close its
        // source, so this is the one place that owns the stream's lifecycle.
        try {
        String jfxVersion = System.getProperty("javafx.runtime.version", "versionless");
        // This fixes an issue with windows - ":" is not allowed for file on Windows.
        jfxVersion = jfxVersion.replace(":", "-");
        String userCache = System.getProperty("javafx.cachedir", "");
        String arch = System.getProperty("os.arch");
        if (userCache.isEmpty()) {
            // skia-fx: cache under ~/.skia-fx/cache/ instead of stock
            // ~/.openjfx/cache/ so a developer/user who has both the
            // stock OpenJFX SDK and the skia-fx SDK on their machine
            // doesn't get the two builds' native libs colliding in
            // the same dir (different glass.dll, javafx_font.dll
            // versions, etc.). The javafx.cachedir system property
            // still works for explicit override / drop-in scenarios.
            userCache = System.getProperty("user.home") + "/.skia-fx/cache/" + jfxVersion + "/" + arch;
        }
        File cacheDir = new File(userCache);
        boolean cacheDirOk = true;
        if (cacheDir.exists()) {
            if (!cacheDir.isDirectory()) {
                System.err.println("Cache exists but is not a directory: "+cacheDir);
                cacheDirOk = false;
            }
        } else {
            if (!cacheDir.mkdirs()) {
                System.err.println("Can not create cache at "+cacheDir);
                cacheDirOk = false;
            }
        }
        if (!cacheDir.canRead()) {
            // on some systems, directories in user.home can be written but not read.
            cacheDirOk = false;
        }
        if (!cacheDirOk) {
            String username = System.getProperty("user.name", "anonymous");
            // skia-fx: tmpdir fallback also uses /.skia-fx_<user>/
            // (was /.openjfx_<user>/) so the two SDKs stay separate
            // even when home-dir isn't writable.
            String tmpCache = System.getProperty("java.io.tmpdir") + "/.skia-fx_" + username
                    + "/cache/" + jfxVersion + "/" + arch;
            cacheDir = new File(tmpCache);
            if (cacheDir.exists()) {
                if (!cacheDir.isDirectory()) {
                    throw new IOException("Cache exists but is not a directory: "+cacheDir);
                }
            } else {
                if (!cacheDir.mkdirs()) {
                    throw new IOException("Can not create cache at "+cacheDir);
                }
            }
        }
        // we have a cache directory. Add the file here
        File f = new File(cacheDir, name);

        // skia-fx: prefer the build-time SHA-256 manifest
        // (checksums.properties, shipped at the jar root next to the
        // native libs) to decide whether the cached copy is current.
        //
        // When the manifest has an entry for this lib we compare its
        // hash against a ".sha256" sidecar written next to the cached
        // copy. If they match and the file is present, extraction is
        // skipped WITHOUT reading the (possibly large) resource stream.
        // If they differ — e.g. the native lib was just recompiled —
        // the cached copy is overwritten and the sidecar refreshed, so a
        // new build always wins. Libs absent from the manifest (or jars
        // shipping no manifest) fall back to the legacy MD5 comparison.
        String manifestHash = checksumManifest(caller).get(name);
        File hashSidecar = new File(cacheDir, name + ".sha256");

        // if it exists, calculate checksum and keep if same as inputstream.
        boolean write = true;
        if (manifestHash != null) {
            if (f.exists() && manifestHash.equals(readSidecar(hashSidecar))) {
                // Cached copy already matches the shipped binary — skip.
                write = false;
            }
            // else: (re)extract below, then refresh the sidecar.
        } else if (f.exists()) {
            byte[] isHash;
            byte[] fileHash;
            try {
                DigestInputStream dis = new DigestInputStream(is, MessageDigest.getInstance("MD5"));
                dis.getMessageDigest().reset();
                byte[] buffer = new byte[4096];
                while (dis.read(buffer) != -1) { /* empty loop body is intentional */ }
                isHash = dis.getMessageDigest().digest();
                is.close();
                is = openBundledResource(caller, name); // mark/reset not supported, we have to reread
            }
            catch (NoSuchAlgorithmException nsa) {
                isHash = new byte[1];
            }
            fileHash = calculateCheckSum(f);
            if (!Arrays.equals(isHash, fileHash)) {
                Files.delete(f.toPath());
            } else {
                // hashes are the same, we already have the file.
                write = false;
            }
        }
        if (write) {
            Path path = f.toPath();
            File lockFile = new File(cacheDir, ".lock");
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(lockFile, "rw");
                 FileChannel fileChannel = randomAccessFile.getChannel();
                 FileLock fileLock = fileChannel.lock()) {
                try {
                    // For a manifest-driven override the stale copy (if
                    // any) must be replaced, so delete it first; the
                    // legacy MD5 path above already deleted on mismatch.
                    if (manifestHash != null) {
                        Files.deleteIfExists(path);
                    }
                    if (!Files.exists(path)) {
                        Files.copy(is, path);
                    }
                } finally {
                    if (fileLock != null) {
                        fileLock.release();
                    }
                }
            } catch (IOException ex) {
                throw new IOException("Error copying library " + path + " to cache: " + ex.getMessage(), ex);
            }
            if (manifestHash != null) {
                writeSidecar(hashSidecar, manifestHash);
            }
        }

        String fp = f.getAbsolutePath();
        return fp;
        } finally {
            is.close();
        }
    }

    /**
     * Per-classloader cache of parsed native-lib SHA-256 manifests
     * ({@code /checksums.properties}). Maps a jar-root resource path
     * (e.g. {@code "/glass.dll"}) to its hex-encoded SHA-256. The map is
     * empty when the caller's jar ships no manifest.
     */
    private static final Map<ClassLoader, Map<String, String>> manifestCache = new HashMap<>();

    /**
     * Reads (and caches) the {@code /checksums.properties} manifest
     * bundled in the caller's jar. The manifest is generated at build
     * time by the {@code generateChecksums} Gradle task and lists the
     * SHA-256 of every native lib shipped at the jar root.
     *
     * @param caller the class whose module/jar is searched for the manifest
     * @return an immutable-style view mapping resource path to hex SHA-256;
     *         empty if no manifest is present
     */
    private static synchronized Map<String, String> checksumManifest(Class caller) {
        ClassLoader cl = caller.getClassLoader();
        Map<String, String> cached = manifestCache.get(cl);
        if (cached != null) {
            return cached;
        }
        Map<String, String> manifest = new HashMap<>();
        try (InputStream in = openBundledResource(caller, "/checksums.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    manifest.put(key, props.getProperty(key).trim());
                }
                if (verbose) {
                    System.err.println("Loaded native checksums manifest ("
                            + manifest.size() + " entries) for " + caller);
                }
            }
        } catch (IOException ex) {
            if (verbose) {
                System.err.println("WARNING: failed reading checksums.properties: " + ex);
            }
        }
        manifestCache.put(cl, manifest);
        return manifest;
    }

    /**
     * Reads the SHA-256 hash stored in a cache-side {@code .sha256}
     * sidecar. Returns an empty string if the file is missing or
     * unreadable.
     */
    private static String readSidecar(File hashFile) {
        try {
            if (hashFile.exists()) {
                return Files.readString(hashFile.toPath()).strip();
            }
        } catch (IOException ex) {
            // Corrupted / unreadable — treat as no cached hash.
        }
        return "";
    }

    /**
     * Writes the SHA-256 hash to a cache-side {@code .sha256} sidecar so
     * the next launch can short-circuit extraction. Failures are
     * non-fatal: a missing sidecar just forces a re-hash next time.
     */
    private static void writeSidecar(File hashFile, String hash) {
        try {
            Files.writeString(hashFile.toPath(), hash);
        } catch (IOException ex) {
            if (verbose) {
                System.err.println("WARNING: failed writing " + hashFile + ": " + ex);
            }
        }
    }

    static byte[] calculateCheckSum(File file) {
        try {
                // not looking for security, just a checksum. MD5 should be faster than SHA
                try (final InputStream stream = new FileInputStream(file);
                    final DigestInputStream dis = new DigestInputStream(stream, MessageDigest.getInstance("MD5")); ) {
                    dis.getMessageDigest().reset();
                    byte[] buffer = new byte[4096];
                    while (dis.read(buffer) != -1) { /* empty loop body is intentional */ }
                    return dis.getMessageDigest().digest();
                }

        } catch (IllegalArgumentException | NoSuchAlgorithmException | IOException | SecurityException e) {
            // IOException also covers MalformedURLException
            // SecurityException means some untrusted app

            // Fall through...
        }
        return new byte[0];
    }


    private static File libDirForJRT() {
        String javaHome = System.getProperty("java.home");

        if (javaHome == null || javaHome.isEmpty()) {
            throw new UnsatisfiedLinkError("Cannot find java.home");
        }

        // Set the native directory based on the OS
        String relativeDir = null;
        if (PlatformUtil.isWindows()) {
            relativeDir = "bin/javafx";
        } else if (PlatformUtil.isMac()) {
            relativeDir = "lib";
        } else if (PlatformUtil.isLinux()) {
            relativeDir = "lib";
        }

        // Location of native libraries relative to java.home
        return new File(javaHome + "/" + relativeDir);
    }

    private static File libDirForJarFile(String classUrlString) throws Exception {
        // Strip out the "jar:" and everything after and including the "!"
        String tmpStr = classUrlString.substring(4, classUrlString.lastIndexOf('!'));
        // Strip everything after the last "/" or "\" to get rid of the jar filename
        int lastIndexOfSlash = Math.max(tmpStr.lastIndexOf('/'), tmpStr.lastIndexOf('\\'));

        // Set the native directory based on the OS
        String relativeDir = null;
        if (PlatformUtil.isWindows()) {
            relativeDir = "../bin";
        } else if (PlatformUtil.isMac()) {
            relativeDir = ".";
        } else if (PlatformUtil.isLinux()) {
            relativeDir = ".";
        }

        // Location of native libraries relative to jar file
        String libDirUrlString = tmpStr.substring(0, lastIndexOfSlash)
                + "/" + relativeDir;
        return new File(new URI(libDirUrlString).getPath());
    }

    /**
     * Load the native library either from the same directory as the jar file
     * containing this class, or from the Java runtime.
     */
    private static void loadLibraryFullPath(String libraryName) {
        try {
            if (libDir == null) {
                // Get the URL for this class, if it is a jar URL, then get the
                // filename associated with it.
                String theClassFile = "NativeLibLoader.class";
                Class theClass = NativeLibLoader.class;
                String classUrlString = theClass.getResource(theClassFile).toString();
                if (classUrlString.startsWith("jrt:")) {
                    libDir = libDirForJRT();
                } else if (classUrlString.startsWith("jar:file:") && classUrlString.indexOf('!') > 0) {
                    libDir = libDirForJarFile(classUrlString);
                } else {
                    throw new UnsatisfiedLinkError("Invalid URL for class: " + classUrlString);
                }

                // Set the lib prefix and suffix based on the OS
                if (PlatformUtil.isWindows()) {
                    libPrefix = "";
                    libSuffix = ".dll";
                } else if (PlatformUtil.isMac()) {
                    libPrefix = "lib";
                    libSuffix = ".dylib";
                } else if (PlatformUtil.isLinux()) {
                    libPrefix = "lib";
                    libSuffix = ".so";
                }
            }

            File libFile = new File(libDir, libPrefix + libraryName + libSuffix);
            String libFileName = libFile.getCanonicalPath();
            try {
                System.load(libFileName);
                if (verbose) {
                    System.err.println("Loaded " + libFile.getAbsolutePath()
                            + " from relative path");
                }
            } catch(UnsatisfiedLinkError ex) {
                throw ex;
            }
        } catch (Exception e) {
            // Throw UnsatisfiedLinkError for best compatibility with System.loadLibrary()
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError().initCause(e);
        }
    }

}
