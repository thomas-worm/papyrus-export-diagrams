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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;

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
            return Integer.valueOf(2);
        }
        Files.createDirectories(outDir);

        ImageFileFormat gmfFormat = parseGmfFormat(format);
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
        try (Stream<Path> walk = Files.walk(modelDir)) {
            List<Path> diFiles = walk
                    .filter(p -> p.getFileName().toString().endsWith(".di"))
                    .sorted()
                    .toList();

            for (Path di : diFiles) {
                String base = stripExtension(di.getFileName().toString());
                Path notation = di.resolveSibling(base + ".notation");
                if (!Files.exists(notation)) continue;

                Resource res;
                ResourceSet rs = new ResourceSetImpl();
                try {
                    res = rs.getResource(URI.createFileURI(notation.toAbsolutePath().toString()), true);
                    Path uml = di.resolveSibling(base + ".uml");
                    if (Files.exists(uml)) {
                        rs.getResource(URI.createFileURI(uml.toAbsolutePath().toString()), true);
                    }
                    EcoreUtil.resolveAll(rs);
                } catch (Exception e) {
                    System.err.println("failed to load " + notation + ": " + e);
                    failed++;
                    continue;
                }

                Set<String> usedNames = new HashSet<>();
                for (Diagram d : collectDiagrams(res)) {
                    String stem = filenameFor(d, naming, usedNames);
                    Path outFile = outDir.resolve(stem + "." + fileExt);
                    try {
                        new CopyToImageUtil().copyToImage(
                                d,
                                org.eclipse.core.runtime.Path.fromOSString(outFile.toString()),
                                gmfFormat,
                                new NullProgressMonitor());
                        System.out.println("exported (GMF): " + outFile);
                        exported++;
                    } catch (Throwable t) {
                        System.err.println("failed to export GMF diagram " + stem + ": " + t);
                        failed++;
                    }
                }
            }
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
                } catch (NoClassDefFoundError | LinkageError e) {
                    System.err.println("Sirius classes not loadable for " + aird + ": " + e);
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
        return failed == 0 ? IApplication.EXIT_OK : Integer.valueOf(1);
    }

    @Override public void stop() { /* nothing */ }

    // ---------------- helpers ----------------

    private static String[] stripBeforeDoubleDash(String[] all) {
        if (all == null) return new String[0];
        for (int i = 0; i < all.length; i++) {
            if ("--".equals(all[i])) {
                String[] out = new String[all.length - i - 1];
                System.arraycopy(all, i + 1, out, 0, out.length);
                return out;
            }
        }
        return all;
    }

    private static List<Diagram> collectDiagrams(Resource res) {
        List<Diagram> out = new ArrayList<>();
        for (EObject o : res.getContents()) {
            if (o instanceof Diagram d) out.add(d);
        }
        return out;
    }

    private static String filenameFor(Diagram d, Naming naming, Set<String> used) {
        String base;
        if (naming == Naming.NAME && d.getName() != null && !d.getName().isBlank()) {
            base = d.getName().trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        } else {
            String id = EcoreUtil.getURI(d).fragment();
            base = (id == null ? "diagram" : id).replaceAll("[^A-Za-z0-9._-]+", "_");
        }
        String candidate = base;
        int n = 1;
        while (!used.add(candidate)) {
            candidate = base + "_" + (++n);
        }
        return candidate;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static ImageFileFormat parseGmfFormat(String s) {
        try { return ImageFileFormat.valueOf(s); }
        catch (IllegalArgumentException e) {
            System.err.println("Unknown format '" + s + "', falling back to SVG");
            return ImageFileFormat.SVG;
        }
    }
}