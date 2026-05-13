/*
 * Sirius-specific export helper.
 *
 * Kept in a separate class so that, if any of the optional Sirius bundles is
 * missing at runtime, the resulting NoClassDefFoundError happens here (where
 * the caller catches it) instead of breaking the legacy GMF export path.
 *
 * The export pipeline:
 *   1. SessionManager.INSTANCE.getSession(airdURI, monitor)
 *   2. session.open(monitor)
 *   3. DialectManager.INSTANCE.getAllRepresentationDescriptors(session)
 *   4. For each descriptor: DialectUIManager.INSTANCE.export(rep, session, path, format, monitor)
 *      — must run on the SWT UI thread, so we wrap with Display.syncExec.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.common.tools.api.resource.ImageFileFormat;
import org.eclipse.sirius.ui.business.api.dialect.DialectUIManager;
import org.eclipse.sirius.ui.business.api.dialect.ExportFormat;
import org.eclipse.sirius.ui.business.api.dialect.ExportFormat.ExportDocumentFormat;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.swt.widgets.Display;

final class SiriusExporter {

    /** Result of a batch export. */
    static final class Result {
        int exported;
        int failed;
    }

    private SiriusExporter() { }

    /**
     * Export every representation from a Sirius .aird file.
     *
     * @param aird     absolute path to the .aird representation container
     * @param outDir   target directory (already created by the caller)
     * @param format   one of SVG, PNG, JPEG, BMP, GIF — mapped to Sirius's ImageFileFormat
     * @param useId    true: filename = descriptor's UUID; false: filename = descriptor's name (sanitised)
     * @param ext      file extension to use (lowercased format string from the caller)
     * @return per-aird counters
     */
    static Result exportAird(Path aird, Path outDir, String format, boolean useId, String ext) {
        Result r = new Result();

        URI airdURI = URI.createFileURI(aird.toAbsolutePath().toString());
        IProgressMonitor monitor = new NullProgressMonitor();

        Session session;
        try {
            session = SessionManager.INSTANCE.getSession(airdURI, monitor);
            if (session == null) {
                System.err.println("Sirius: SessionManager returned null for " + aird);
                r.failed++;
                return r;
            }
            if (!session.isOpen()) {
                session.open(monitor);
            }
        } catch (Throwable t) {
            System.err.println("Sirius: failed to open session for " + aird + ": " + t);
            r.failed++;
            return r;
        }

        ImageFileFormat siriusFormat = parseSiriusFormat(format);
        ExportFormat exportFormat = new ExportFormat(ExportDocumentFormat.NONE, siriusFormat);

        Collection<DRepresentationDescriptor> descriptors;
        try {
            descriptors = DialectManager.INSTANCE.getAllRepresentationDescriptors(session);
        } catch (Throwable t) {
            System.err.println("Sirius: cannot enumerate representations of " + aird + ": " + t);
            r.failed++;
            return r;
        }

        Set<String> usedNames = new HashSet<>();
        // The export has to run on the UI thread; collect work first, run inside syncExec.
        final Display display = getOrCreateDisplay();
        final AtomicInteger exportedCount = new AtomicInteger();
        final AtomicInteger failedCount   = new AtomicInteger();

        for (DRepresentationDescriptor desc : descriptors) {
            DRepresentation rep;
            try {
                // Lazy load — only resolved on demand.
                rep = desc.getRepresentation();
            } catch (Throwable t) {
                System.err.println("Sirius: cannot resolve representation for descriptor "
                        + desc.getName() + ": " + t);
                failedCount.incrementAndGet();
                continue;
            }
            if (rep == null) continue;

            // DialectUIManager filters out reps it can't handle. We check first so
            // we can produce a clearer log line and don't waste a UI-thread round trip.
            if (!DialectUIManager.INSTANCE.canHandle(rep)) {
                System.err.println("Sirius: skipping unsupported representation "
                        + safeName(desc) + " (" + rep.getClass().getSimpleName() + ")");
                continue;
            }

            String stem = filenameFor(desc, useId, usedNames);
            Path outFile = outDir.resolve(stem + "." + ext);
            org.eclipse.core.runtime.IPath outPath =
                    org.eclipse.core.runtime.Path.fromOSString(outFile.toString());

            display.syncExec(() -> {
                try {
                    DialectUIManager.INSTANCE.export(rep, session, outPath, exportFormat,
                            new NullProgressMonitor());
                    System.out.println("exported (Sirius): " + outFile);
                    exportedCount.incrementAndGet();
                } catch (Throwable t) {
                    if (siriusFormat == ImageFileFormat.SVG && isSvgFigureCastFailure(t)) {
                        // Sirius's SVG generator paints embedded SVGFigure
                        // instances by casting the outer Graphics to
                        // SiriusGraphicsSVG. When a Papyrus-style diagram
                        // recurses through SiriusRenderedMapModeGraphics
                        // wrappers (common for compartments with shape
                        // providers), the cast fails. PNG export goes
                        // through the raster path and renders cleanly.
                        Path pngOut = outDir.resolve(stem + ".png");
                        org.eclipse.core.runtime.IPath pngPath =
                                org.eclipse.core.runtime.Path.fromOSString(pngOut.toString());
                        ExportFormat pngFormat = new ExportFormat(
                                ExportDocumentFormat.NONE, ImageFileFormat.PNG);
                        try {
                            DialectUIManager.INSTANCE.export(rep, session, pngPath, pngFormat,
                                    new NullProgressMonitor());
                            System.err.println("Sirius: SVG export hit known cast bug "
                                    + "with embedded SVG figures for " + stem
                                    + " — wrote PNG fallback instead: " + pngOut);
                            exportedCount.incrementAndGet();
                            return;
                        } catch (Throwable t2) {
                            System.err.println("Sirius: PNG fallback also failed for "
                                    + stem + ": " + t2);
                        }
                    }
                    System.err.println("Sirius: failed to export " + stem + ": " + t);
                    failedCount.incrementAndGet();
                }
            });
        }

        // Closing the session is good hygiene but we tolerate failures.
        try { session.close(monitor); } catch (Throwable ignore) { }

        r.exported = exportedCount.get();
        r.failed   = failedCount.get();
        return r;
    }

    // ---------------- helpers ----------------

    private static boolean isSvgFigureCastFailure(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ClassCastException
                    && cur.getMessage() != null
                    && cur.getMessage().contains("SiriusGraphicsSVG")) {
                return true;
            }
            // Avoid infinite loops on cyclic causes.
            if (cur.getCause() == cur) break;
        }
        return false;
    }

    private static Display getOrCreateDisplay() {
        Display d = Display.getCurrent();
        return (d != null) ? d : Display.getDefault();
    }

    private static String safeName(DRepresentationDescriptor d) {
        String n = d.getName();
        return (n == null || n.isBlank()) ? "<unnamed>" : n;
    }

    private static String filenameFor(DRepresentationDescriptor desc,
                                      boolean useId,
                                      Set<String> used) {
        String base;
        if (!useId && desc.getName() != null && !desc.getName().isBlank()) {
            base = desc.getName().trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        } else {
            // Use the EMF URI fragment as a stable identifier (getId() was removed in newer Sirius).
            String id = desc.eResource() != null
                    ? desc.eResource().getURIFragment(desc)
                    : "representation";
            if (id == null || id.isBlank()) {
                id = "representation";
            }
            base = id.replaceAll("[^A-Za-z0-9._-]+", "_");
        }
        String candidate = base;
        int n = 1;
        while (!used.add(candidate)) {
            candidate = base + "_" + (++n);
        }
        return candidate;
    }

    private static ImageFileFormat parseSiriusFormat(String s) {
        // Sirius's ImageFileFormat uses JPG (not JPEG) as one canonical name,
        // and additionally supports SVG / SVGZ. Map liberally from user input.
        String u = s == null ? "SVG" : s.toUpperCase(Locale.ROOT);
        switch (u) {
            case "JPEG": case "JPG": return ImageFileFormat.JPG;
            case "PNG":              return ImageFileFormat.PNG;
            case "GIF":              return ImageFileFormat.GIF;
            case "BMP":              return ImageFileFormat.BMP;
            case "SVG":              return ImageFileFormat.SVG;
            case "PDF":
                // Sirius can't export PDF directly via ImageFileFormat in all releases.
                // We fall back to SVG; the user can convert with rsvg-convert/inkscape.
                System.err.println("Sirius: PDF not supported, falling back to SVG.");
                return ImageFileFormat.SVG;
            default:
                System.err.println("Sirius: unknown format '" + s + "', falling back to SVG.");
                return ImageFileFormat.SVG;
        }
    }
}