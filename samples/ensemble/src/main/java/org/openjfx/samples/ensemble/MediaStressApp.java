/*
 * MediaStressApp — skia-fx media hardening harness (experimental).
 *
 * Two modes, selected with -Dstress.mode:
 *
 *   stress  (default) — loops create / play / seek / setRate / dispose
 *       against one media source, racing operations from a worker
 *       thread against player lifecycle, including disposes mid-preroll
 *       and mid-play. PASS = all iterations finish, no hang, no crash.
 *
 *       -Dstress.media=<path or url>   the source (required)
 *       -Dstress.iterations=<n>        default 30
 *
 *   corpus — plays every file in a directory and asserts the outcome
 *       is READY/playing or a MediaException — never a JVM crash and
 *       never a hang. Optionally generates malformed variants
 *       (truncations, bit flips, zeroed header) from a seed file first.
 *
 *       -Dstress.dir=<directory>       the corpus (required)
 *       -Dstress.media=<seed file>     with -Dstress.generate=true,
 *                                      variants are written to stress.dir
 *
 * Exit code 0 = PASS, 1 = FAIL. Findings print to stdout.
 */
package org.openjfx.samples.ensemble;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

public class MediaStressApp extends Application {

    private static final long PER_FILE_TIMEOUT_SEC = 25;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // A minimal stage keeps the toolkit (and pulse) alive.
        stage.setScene(new Scene(new StackPane(), 320, 120));
        stage.setTitle("skia-fx media stress harness (experimental)");
        stage.show();

        Thread driver = new Thread(() -> {
            boolean pass;
            try {
                String mode = System.getProperty("stress.mode", "stress");
                pass = "corpus".equalsIgnoreCase(mode) ? runCorpus() : runStress();
            } catch (Throwable t) {
                System.out.println("[stress] FAIL: harness error: " + t);
                t.printStackTrace();
                pass = false;
            }
            System.out.println(pass ? "[stress] PASS" : "[stress] FAIL");
            boolean failed = !pass;
            Platform.runLater(() -> {
                Platform.exit();
                System.exit(failed ? 1 : 0);
            });
        }, "stress-driver");
        driver.setDaemon(true);
        driver.start();
    }

    // ------------------------------------------------------------------
    // stress mode
    // ------------------------------------------------------------------

    private boolean runStress() throws Exception {
        String source = System.getProperty("stress.media");
        if (source == null || source.isEmpty()) {
            System.out.println("[stress] -Dstress.media=<path or url> is required");
            return false;
        }
        String uri = toUri(source);
        int iterations = Integer.getInteger("stress.iterations", 30);
        Random rng = new Random(42); // reproducible schedule

        for (int i = 0; i < iterations; i++) {
            System.out.println("[stress] iteration " + (i + 1) + "/" + iterations);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();

            MediaPlayer player;
            try {
                Media media = new Media(uri);
                player = new MediaPlayer(media);
            } catch (Exception e) {
                System.out.println("[stress] FAIL: constructor threw: " + e);
                return false;
            }
            MediaPlayer p = player;
            p.setOnError(() -> {
                // Errors are an acceptable outcome (remote sources can
                // drop) — hangs and crashes are not.
                System.out.println("[stress]   error event: " + p.getError());
                done.countDown();
            });
            p.setMute(true);
            p.play();

            // A scripted burst of racing operations. Some iterations
            // dispose almost immediately (mid-preroll), some after
            // playback has had time to start.
            int script = i % 5;
            try {
                switch (script) {
                    case 0 -> { /* immediate dispose — mid-preroll */ }
                    case 1 -> Thread.sleep(150 + rng.nextInt(250));
                    case 2 -> {
                        Thread.sleep(600 + rng.nextInt(600));
                        p.seek(javafx.util.Duration.seconds(rng.nextInt(30)));
                        Thread.sleep(rng.nextInt(200));
                    }
                    case 3 -> {
                        Thread.sleep(600 + rng.nextInt(600));
                        p.setRate(rng.nextBoolean() ? 0.5 : 2.0);
                        Thread.sleep(rng.nextInt(300));
                        p.seek(javafx.util.Duration.seconds(rng.nextInt(30)));
                    }
                    case 4 -> {
                        Thread.sleep(400);
                        p.pause();
                        Thread.sleep(rng.nextInt(200));
                        p.play();
                        Thread.sleep(rng.nextInt(400));
                    }
                }
            } catch (Exception raceLoss) {
                // Racing ops against dispose/error may throw — that is
                // the point of the test; only hangs/crashes fail it.
                System.out.println("[stress]   op threw (ok): " + raceLoss);
            }

            // Dispose from this worker thread, racing whatever is
            // still in flight, and require it to come back promptly.
            Thread disposer = new Thread(p::dispose, "stress-disposer");
            disposer.start();
            disposer.join(TimeUnit.SECONDS.toMillis(PER_FILE_TIMEOUT_SEC));
            if (disposer.isAlive()) {
                System.out.println("[stress] FAIL: dispose hung on iteration " + (i + 1)
                        + " (script " + script + ")");
                return false;
            }
            if (failure.get() != null) {
                System.out.println("[stress] FAIL: " + failure.get());
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // corpus mode
    // ------------------------------------------------------------------

    private boolean runCorpus() throws Exception {
        String dirProp = System.getProperty("stress.dir");
        if (dirProp == null || dirProp.isEmpty()) {
            System.out.println("[stress] -Dstress.dir=<directory> is required for corpus mode");
            return false;
        }
        File dir = new File(dirProp);

        if (Boolean.getBoolean("stress.generate")) {
            String seed = System.getProperty("stress.media");
            if (seed == null || !new File(seed).isFile()) {
                System.out.println("[stress] -Dstress.media=<seed file> is required with stress.generate");
                return false;
            }
            generateCorpus(new File(seed), dir);
        }

        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            System.out.println("[stress] no files in " + dir);
            return false;
        }
        Arrays.sort(files);

        List<String> hung = new ArrayList<>();
        for (File f : files) {
            System.out.println("[stress] corpus: " + f.getName());
            if (!playToVerdict(f)) {
                hung.add(f.getName());
            }
        }
        if (!hung.isEmpty()) {
            System.out.println("[stress] FAIL: no READY/ERROR verdict (hang?) for: " + hung);
            return false;
        }
        // Reaching this line at all also proves none of the corpus
        // crashed the JVM.
        return true;
    }

    /** @return true when the file reaches READY or ERROR within the timeout. */
    private boolean playToVerdict(File file) {
        CountDownLatch verdict = new CountDownLatch(1);
        MediaPlayer player = null;
        try {
            Media media = new Media(file.toURI().toString());
            player = new MediaPlayer(media);
            MediaPlayer p = player;
            p.setMute(true);
            p.setOnError(() -> {
                System.out.println("[stress]   -> error (ok): " + p.getError());
                verdict.countDown();
            });
            p.setOnReady(() -> {
                System.out.println("[stress]   -> ready (ok)");
                if (!media.getMetadata().isEmpty()) {
                    System.out.println("[stress]   metadata: " + media.getMetadata());
                }
                verdict.countDown();
            });
            media.setOnError(verdict::countDown);
            p.play();
            return verdict.await(PER_FILE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception constructorError) {
            // A synchronous exception is a clean verdict too.
            System.out.println("[stress]   -> threw (ok): " + constructorError);
            return true;
        } finally {
            if (player != null) {
                player.dispose();
            }
        }
    }

    /** Truncations, bit flips and a zeroed header from one good seed file. */
    private static void generateCorpus(File seed, File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        byte[] data = Files.readAllBytes(seed.toPath());
        String base = seed.getName();
        Random rng = new Random(1234); // reproducible corpus

        for (int pct : new int[] { 1, 10, 50, 90 }) {
            int len = Math.max(1, (int) ((long) data.length * pct / 100));
            write(dir, "trunc-" + pct + "-" + base, Arrays.copyOf(data, len));
        }
        for (int round = 1; round <= 3; round++) {
            byte[] flipped = data.clone();
            for (int i = 0; i < 200; i++) {
                int at = rng.nextInt(flipped.length);
                flipped[at] ^= (byte) (1 << rng.nextInt(8));
            }
            write(dir, "bitflip-" + round + "-" + base, flipped);
        }
        byte[] zeroHead = data.clone();
        Arrays.fill(zeroHead, 0, Math.min(256, zeroHead.length), (byte) 0);
        write(dir, "zerohead-" + base, zeroHead);

        write(dir, "empty-" + base, new byte[0]);
        System.out.println("[stress] corpus generated in " + dir);
    }

    private static void write(File dir, String name, byte[] bytes) throws IOException {
        Path p = dir.toPath().resolve(name);
        Files.write(p, bytes);
    }

    private static String toUri(String source) {
        if (source.indexOf("://") > 0 || source.startsWith("file:")) {
            return source;
        }
        return new File(source).toURI().toString();
    }
}
