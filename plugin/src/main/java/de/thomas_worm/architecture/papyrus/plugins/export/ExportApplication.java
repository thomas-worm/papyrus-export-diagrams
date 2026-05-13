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
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class ExportApplication implements IApplication {

    private enum Naming { XMI_ID, NAME }

    @Override
    public Object start(IApplicationContext context) throws Exception {
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

        // Best-effort activation. Failures tolerated — Papyrus's bundle set
        // varies slightly across patch releases and across Desktop vs Classic.
        for (String b : new String[] {
                "org.eclipse.papyrus.infra.core",
                "org.eclipse.papyrus.infra.gmfdiag.common",
                "org.eclipse.gmf.runtime.diagram.ui.render",
                "org.eclipse.sirius",
                "org.eclipse.sirius.ui",
                "org.eclipse.sirius.diagram.ui",
        }) {
            try {
                var bundle = Platform.getBundle(b);
                if (bundle != null) bundle.start();
            } catch (Throwable ignore) { }
        }

        int exported = 0;
        int failed   = 0;
        int sirius_skipped = 0;

        // ---- 1. Legacy GMF diagrams via *.notation next to *.di --------
        try {
            GmfExporter.Result gmfResult = GmfExporter.exportNotations(
                    modelDir, outDir, format, naming == Naming.XMI_ID, fileExt);
            exported += gmfResult.exported;
            failed   += gmfResult.failed;
        } catch (LinkageError e) {
            System.err.println("GMF classes not available: " + e.getMessage());
        } catch (Throwable t) {
            System.err.println("Unexpected error during GMF export: " + t);
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
                    sirius_skipped++;
                    continue;
                }
                try {
                    // Loading SiriusExporter forces classloading of Sirius
                    // classes; catching NoClassDefFoundError here means a
                    // stripped-down install (no Sirius) still gets through
                    // the GMF pipeline above.
                    SiriusExporter.Result r = SiriusExporter.exportAird(
                            aird, outDir, format, naming == Naming.XMI_ID, fileExt);
                    exported += r.exported;
                    failed   += r.failed;
                } catch (NoClassDefFoundError e) {
                    System.err.println("WARNING: Sirius export unavailable for " + aird);
                    System.err.println("  Missing class: " + e);
                    System.err.println("  Note: Papyrus Desktop 7.1.0 may not include full Sirius bundles.");
                    System.err.println("        Ensure org.eclipse.sirius* plugins are installed if needed.");
                    sirius_skipped++;
                } catch (LinkageError e) {
                    System.err.println("Sirius linkage error for " + aird + ": " + e);
                    sirius_skipped++;
                } catch (Throwable t) {
                    System.err.println("Unexpected failure exporting Sirius file "
                            + aird + ": " + t);
                    failed++;
                }
            }
        }

        System.out.println("Done. exported=" + exported
                + " failed=" + failed
                + " sirius_skipped=" + sirius_skipped);
        
        // Provide diagnostic info when nothing was exported
        if (exported == 0) {
            if (failed > 0 && sirius_skipped == 0) {
                System.out.println("ERROR: All diagrams failed to export. Check .notation file compatibility.");
                System.out.println("  GMF export requires TransactionalEditingDomain (not available in headless mode).");
            } else if (sirius_skipped > 0 && failed == 0) {
                System.out.println("Note: No exports completed. Sirius bundles not available in this Papyrus install.");
                System.out.println("  To enable Sirius export, install org.eclipse.sirius* plugins.");
            } else if (failed > 0 && sirius_skipped > 0) {
                System.out.println("ERROR: Both GMF and Sirius export failed:");
                System.out.println("  - GMF (.notation): Requires editing domain (headless limitation)");
                System.out.println("  - Sirius (.aird): Missing required bundles");
            }
        }
        
        int exitCode = failed == 0 ? 0 : 1;
        System.out.println("Forcing application shutdown with exit code: " + exitCode);
        exitWithCode(exitCode, context);
        return Integer.valueOf(exitCode);
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