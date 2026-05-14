/*
 * Headless Papyrus diagram export application.
 *
 * Targets Papyrus-Desktop 7.1.0 (Eclipse 2025-06, Java 21, JustJ JRE).
 *
 * Two export pipelines run in sequence:
 *
 *   1. Legacy GMF diagrams stored in *.notation files (Papyrus-Classic style;
 *      still produced by some Papyrus-Desktop workflows). Rendered through
 *      GMF's CopyToImageUtil to SVG/PNG/JPEG/BMP/GIF/PDF.
 *
 *   2. Sirius representations stored in *.aird files (Papyrus 7.x default).
 *      Handled by SiriusExporter via DialectUIManager.export, run on the SWT
 *      UI thread. If the Sirius bundles are not present at runtime (e.g. on
 *      a Papyrus-Classic install) the Sirius path is skipped with a notice.
 *
 * Command line (after a literal `--`):
 *     --modelDir  <path>   Directory scanned recursively for *.di / *.aird.
 *     --outDir    <path>   Where to write the image files.
 *     --format    <name>   SVG | PNG | JPEG | BMP | GIF | PDF (default SVG).
 *     --naming    <mode>   xmiId   -> notation xmi:id / Sirius descriptor UUID (default)
 *                          name    -> human-readable name (you must keep unique)
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;

public class ExportApplication implements IApplication {

    private enum Naming { XMI_ID, NAME }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // Last-resort kill switch: if anything in the pipeline (workbench
        // bring-up, export, shutdown) wedges, halt the JVM so the runner
        // doesn't have to reap us at the 180s watchdog with no diagnostic.
        scheduleForceHalt(99, 10 * 60 * 1000);

        String[] all = (String[]) context.getArguments()
                .get(IApplicationContext.APPLICATION_ARGS);
        String[] args = stripBeforeDoubleDash(all);

        Path modelDir = null;
        Path outDir   = null;
        String format = "SVG";
        Naming naming = Naming.XMI_ID;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--modelDir" -> modelDir = Path.of(args[++i]);
                case "--outDir"   -> outDir   = Path.of(args[++i]);
                case "--format"   -> format   = args[++i].toUpperCase(Locale.ROOT);
                case "--naming"   -> naming   = "name".equalsIgnoreCase(args[++i])
                                                    ? Naming.NAME : Naming.XMI_ID;
                default -> { /* ignore unknown flags */ }
            }
        }

        if (modelDir == null || outDir == null) {
            System.err.println("usage: --modelDir <dir> --outDir <dir> "
                    + "[--format SVG|PNG|JPEG|BMP|GIF|PDF] [--naming xmiId|name]");
            exitWithCode(2, context);
            return Integer.valueOf(2);
        }
        Files.createDirectories(outDir);

        String fileExt = format.toLowerCase(Locale.ROOT);

        // Pin Papyrus's diagram theme so the headless render matches
        // the look the user picked in the GUI editor (set both before
        // and after bundle activation; the CSS bundle's
        // ThemePreferenceInitializer may overwrite our value during
        // its startup).
        applyThemePreferences();

        // Best-effort activation. Failures tolerated — Papyrus's bundle set
        // varies slightly across patch releases and across Desktop vs Classic.
        // The CSS bundles must be activated before any .notation file is
        // loaded so CSSHelper.installCSSSupport can give the model set
        // CSS-aware resource factories (which is what makes Diagram impls
        // emit gradient fills during paint).
        for (String b : new String[] {
                "org.eclipse.papyrus.infra.core",
                "org.eclipse.papyrus.infra.gmfdiag.common",
                "org.eclipse.papyrus.infra.gmfdiag.css",
                "org.eclipse.papyrus.uml.diagram.css",
                "org.eclipse.papyrus.infra.gmfdiag.style",
                "org.eclipse.gmf.runtime.diagram.ui.render",
                "org.eclipse.sirius",
                "org.eclipse.sirius.ui",
                "org.eclipse.sirius.diagram.ui",
        }) {
            try {
                var bundle = Platform.getBundle(b);
                if (bundle != null) bundle.start();
            } catch (Throwable t) {
                System.err.println("Could not activate " + b + ": " + t);
            }
        }
        applyThemePreferences();

        // Papyrus's GMF edit parts hard-reference PlatformUI.getWorkbench()
        // from static initializers (e.g. ArchitectureFrameworkCustomizationManagerUpdater)
        // so we cannot just run our pipeline straight from IApplication.
        // Instead, spin up a real workbench under Xvfb, do the export from
        // the advisor's postStartup() callback, then close the workbench.
        final Counters counters = new Counters();
        final boolean useId = naming == Naming.XMI_ID;
        final ExportAdvisor advisor = new ExportAdvisor(
                modelDir, outDir, format, fileExt, useId, counters);

        final Display display = PlatformUI.createDisplay();
        int returnCode;
        try {
            returnCode = PlatformUI.createAndRunWorkbench(display, advisor);
        } finally {
            if (!display.isDisposed()) {
                try { display.dispose(); } catch (Throwable ignore) { }
            }
        }

        System.out.println("Done. exported=" + counters.exported
                + " failed=" + counters.failed
                + " sirius_skipped=" + counters.siriusSkipped);
        System.out.println("Workbench return code: " + returnCode);

        int exitCode = counters.failed == 0 ? 0 : 1;
        // Eclipse's OSGi shutdown is occasionally stuck on a bundle that
        // refuses to stop cleanly, which the runner watchdog then reaps
        // 180s later. Schedule a hard halt() that fires if normal shutdown
        // takes longer than 5s.
        scheduleForceHalt(exitCode, 5_000);
        return Integer.valueOf(exitCode);
    }

    private static void applyThemePreferences() {
        // Eclipse-wide theme: classic == no E4 CSS theme, native look.
        putPreference("org.eclipse.e4.ui.css.swt.theme", "themeid",
                "org.eclipse.e4.ui.css.theme.e4_classic");

        // Papyrus diagram CSS / styling theme. The preference key has
        // shifted across Papyrus minor versions, so set every plausible
        // candidate to the Papyrus Theme id (the modern gradient look)
        // and let the actual reader pick.
        String themeId = "org.eclipse.papyrus.css.papyrus_theme";
        for (String node : new String[] {
                "org.eclipse.papyrus.infra.gmfdiag.css",
                "org.eclipse.papyrus.infra.gmfdiag.css.theme",
                "org.eclipse.papyrus.infra.gmfdiag.css.properties",
                "org.eclipse.papyrus.infra.gmfdiag.style",
                "org.eclipse.papyrus.uml.diagram.css",
        }) {
            for (String key : new String[] {
                    "currentTheme", "theme", "theme.id", "themeId",
                    "currentThemeId", "current.theme",
            }) {
                putPreference(node, key, themeId);
            }
        }

        // Anti-aliasing on. Without this GMF rasterises shape figures
        // (SVGFigure raster fallback path) with one-pixel-wide aliased
        // edges, which is why Sirius diagrams with Papyrus's symbol
        // shapes (actor, use case) showed visible staircase pixels.
        putPreference("org.eclipse.gmf.runtime.diagram.ui",
                "Appearance.enableAntiAlias", "true");
    }

    private static void putPreference(String node, String key, String value) {
        try {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(node);
            prefs.put(key, value);
            prefs.flush();
        } catch (Throwable t) {
            // Preference node may not exist on a Papyrus build that doesn't
            // ship the corresponding bundle; that's fine.
        }
        try {
            org.eclipse.core.runtime.preferences.IEclipsePreferences defaults =
                    org.eclipse.core.runtime.preferences.DefaultScope.INSTANCE.getNode(node);
            defaults.put(key, value);
        } catch (Throwable ignore) { }
    }

    private static void scheduleForceHalt(int code, long delayMillis) {
        Thread t = new Thread("ForceHalt") {
            @Override
            public void run() {
                try { Thread.sleep(delayMillis); } catch (InterruptedException ignore) { return; }
                System.err.println("ForceHalt: aborting JVM after " + delayMillis + "ms");
                Runtime.getRuntime().halt(code);
            }
        };
        t.setDaemon(true);
        t.start();
    }

    static final class Counters {
        int exported;
        int failed;
        int siriusSkipped;
    }

    /**
     * WorkbenchAdvisor that runs the entire export from postStartup() and
     * immediately closes the workbench. Returning null from the perspective
     * lookup leaves the initial window without an open perspective, which
     * the workbench tolerates and skips the perspective-bar UI for.
     */
    static final class ExportAdvisor extends WorkbenchAdvisor {
        private final Path modelDir;
        private final Path outDir;
        private final String format;
        private final String fileExt;
        private final boolean useId;
        private final Counters counters;

        ExportAdvisor(Path modelDir, Path outDir, String format, String fileExt,
                      boolean useId, Counters counters) {
            this.modelDir = modelDir;
            this.outDir = outDir;
            this.format = format;
            this.fileExt = fileExt;
            this.useId = useId;
            this.counters = counters;
        }

        @Override
        public String getInitialWindowPerspectiveId() {
            return null;
        }

        @Override
        public void initialize(IWorkbenchConfigurer configurer) {
            super.initialize(configurer);
            configurer.setSaveAndRestore(false);
        }

        @Override
        public void postStartup() {
            try {
                runExport();
            } catch (Throwable t) {
                System.err.println("Export aborted: " + t);
                t.printStackTrace(System.err);
                counters.failed++;
            } finally {
                // Backup halt for the case where workbench.close() itself
                // hangs (e.g. on a bundle's Activator.stop()) — without
                // this the runner watchdog would have to reap us 180s
                // later with no diagnostic.
                scheduleForceHalt(counters.failed == 0 ? 0 : 1, 30_000);
                try { PlatformUI.getWorkbench().close(); } catch (Throwable ignore) { }
            }
        }

        private void runExport() {
            // ---- 1. Legacy GMF diagrams via *.notation next to *.di --------
            try {
                GmfExporter.Result gmfResult = GmfExporter.exportNotations(
                        modelDir, outDir, format, useId, fileExt);
                counters.exported += gmfResult.exported;
                counters.failed   += gmfResult.failed;
            } catch (LinkageError e) {
                System.err.println("GMF classes not available: " + e.getMessage());
            } catch (Throwable t) {
                System.err.println("Unexpected error during GMF export: " + t);
                t.printStackTrace(System.err);
            }

            // ---- 2. Sirius representations via *.aird ------------------------
            boolean siriusAvailable = Platform.getBundle("org.eclipse.sirius") != null
                                  && Platform.getBundle("org.eclipse.sirius.ui") != null;

            try (Stream<Path> walk = Files.walk(modelDir)) {
                List<Path> airds = walk
                        .filter(p -> p.getFileName().toString().endsWith(".aird"))
                        .sorted()
                        .toList();

                for (Path aird : airds) {
                    if (!siriusAvailable) {
                        System.err.println("WARNING: Sirius representations container "
                                + aird + " was found, but the Sirius bundles are not "
                                + "present in this Papyrus install. Skipping.");
                        counters.siriusSkipped++;
                        continue;
                    }
                    try {
                        SiriusExporter.Result rs = SiriusExporter.exportAird(
                                aird, outDir, format, useId, fileExt);
                        counters.exported += rs.exported;
                        counters.failed   += rs.failed;
                    } catch (NoClassDefFoundError e) {
                        System.err.println("WARNING: Sirius export unavailable for " + aird);
                        System.err.println("  Missing class: " + e);
                        counters.siriusSkipped++;
                    } catch (LinkageError e) {
                        System.err.println("Sirius linkage error for " + aird + ": " + e);
                        counters.siriusSkipped++;
                    } catch (Throwable t) {
                        System.err.println("Unexpected failure exporting Sirius file "
                                + aird + ": " + t);
                        t.printStackTrace(System.err);
                        counters.failed++;
                    }
                }
            } catch (java.io.IOException ioe) {
                System.err.println("I/O error while scanning " + modelDir + ": " + ioe);
                counters.failed++;
            }
        }
    }

    private void exitWithCode(int code, IApplicationContext context) {
        System.out.flush();
        System.err.flush();
        
        System.err.println("DEBUG: About to exit with code " + code);
        System.err.flush();
        
        // Start a daemon thread that will force halt() after 2 seconds
        // This ensures termination even if System.exit() gets stuck
        Thread forceExitThread = new Thread("ForceExit") {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                    System.err.println("DEBUG: Timeout - forcing halt()");
                    System.err.flush();
                    Runtime.getRuntime().halt(code);
                } catch (Exception e) {
                    // ignore
                }
            }
        };
        forceExitThread.setDaemon(true);
        forceExitThread.start();
        
        // Now try normal exit
        System.err.println("DEBUG: Calling System.exit(" + code + ")");
        System.err.flush();
        System.exit(code);
    }

    @Override public void stop() { /* nothing */ }

    // ---------------- helpers ----------------

    private static String[] stripBeforeDoubleDash(String[] all) {
        if (all == null) return new String[0];
        // Look for a literal "--" separator and return everything after it
        for (int i = 0; i < all.length; i++) {
            if ("--".equals(all[i])) {
                String[] out = new String[all.length - i - 1];
                System.arraycopy(all, i + 1, out, 0, out.length);
                return out;
            }
        }
        // No "--" found; if first arg starts with "--", assume these are app args (not launcher args)
        if (all.length > 0 && all[0].startsWith("--")) {
            return all;
        }
        // Otherwise return empty array (no recognized args)
        return new String[0];
    }
}