/*
 * Sirius-specific export helper.
 *
 * Each *.aird file is opened via Sirius's SessionManager to get the
 * representations refreshed against the current semantic model. After
 * that we don't go through DialectUIManager.export — its SVG generator
 * has a fixed cast to SiriusGraphicsSVG that breaks when the diagram
 * contains embedded SVG figures recursed through MapModeGraphics. We
 * instead resolve the backing GMF Diagram for each representation and
 * push it through GMF's CopyToImageUtil, the same path the *.notation
 * pipeline uses — produces real vector SVG and supports the full
 * format set (SVG/PNG/JPEG/BMP/GIF/PDF).
 *
 * Kept in a separate class so that, if any of the optional Sirius
 * bundles is missing at runtime, the resulting NoClassDefFoundError
 * happens here (where the caller catches it) rather than during GMF
 * loading.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.swt.widgets.Display;

final class SiriusExporter {

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
     * @param format   one of SVG, PNG, JPEG, BMP, GIF, PDF
     * @param useId    true: filename = descriptor's UUID; false: descriptor name (sanitised)
     * @param ext      file extension to use (lowercased format string from the caller)
     */
    static Result exportAird(Path aird, Path outDir, String format, boolean useId, String ext) {
        Result r = new Result();

        ImageFileFormat gmfFormat = parseGmfFormat(format);
        if (gmfFormat == null) {
            System.err.println("Sirius: unsupported format " + format);
            r.failed++;
            return r;
        }

        Method copyToImage = findCopyToImageMethod();
        if (copyToImage == null) {
            System.err.println("Sirius: no compatible CopyToImageUtil.copyToImage(Diagram,...) on classpath");
            r.failed++;
            return r;
        }

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

        Collection<DRepresentationDescriptor> descriptors;
        try {
            descriptors = DialectManager.INSTANCE.getAllRepresentationDescriptors(session);
        } catch (Throwable t) {
            System.err.println("Sirius: cannot enumerate representations of " + aird + ": " + t);
            r.failed++;
            return r;
        }

        // Rewrite any FontStyle entries that reference a font the JVM
        // can't find — the source model was edited on macOS and uses
        // .AppleSystemUIFont, which has no Linux equivalent. Failures
        // are non-fatal; we still try to render with the original fonts.
        try {
            FontFallback.remap(session.getTransactionalEditingDomain(),
                    session.getAllSessionResources());
        } catch (Throwable t) {
            System.err.println("Sirius: font remap failed (continuing with original fonts): " + t);
        }

        // Build a map from DRepresentation -> backing GMF Diagram by scanning
        // every loaded session resource. Sirius stores Diagrams alongside the
        // DDiagrams in the .aird (or its sub-resources); a Diagram references
        // its DDiagram via diagram.getElement().
        Map<EObject, Diagram> repToDiagram = collectGmfDiagramsByElement(session);

        Set<String> usedNames = new HashSet<>();
        final Display display = getOrCreateDisplay();
        final AtomicInteger exportedCount = new AtomicInteger();
        final AtomicInteger failedCount   = new AtomicInteger();

        for (DRepresentationDescriptor desc : descriptors) {
            final DRepresentation rep;
            try {
                rep = desc.getRepresentation();
            } catch (Throwable t) {
                System.err.println("Sirius: cannot resolve representation for descriptor "
                        + desc.getName() + ": " + t);
                failedCount.incrementAndGet();
                continue;
            }
            if (rep == null) continue;

            // Refresh the representation so the GMF Diagram reflects the
            // current state of the semantic model. Failures are tolerated
            // — the unrefreshed diagram still renders.
            try {
                DialectManager.INSTANCE.refresh(rep, monitor);
            } catch (Throwable t) {
                System.err.println("Sirius: refresh failed for " + safeName(desc)
                        + " (continuing with stale diagram): " + t);
            }

            // After refresh the map may need to pick up freshly created
            // Diagrams that weren't there at scan time.
            Diagram d = repToDiagram.get(rep);
            if (d == null) {
                repToDiagram = collectGmfDiagramsByElement(session);
                d = repToDiagram.get(rep);
            }
            if (d == null) {
                System.err.println("Sirius: no backing GMF Diagram for representation "
                        + safeName(desc) + " — skipping");
                failedCount.incrementAndGet();
                continue;
            }

            final Diagram diagram = d;
            final String stem = filenameFor(desc, useId, usedNames);
            final Path outFile = outDir.resolve(stem + "." + ext);
            final IPath ePath = org.eclipse.core.runtime.Path.fromOSString(outFile.toString());

            display.syncExec(() -> {
                try {
                    Object[] args;
                    if (copyToImage.getParameterCount() == 5) {
                        args = new Object[] { diagram, ePath, gmfFormat,
                                new NullProgressMonitor(), PreferencesHint.USE_DEFAULTS };
                    } else {
                        args = new Object[] { diagram, ePath, gmfFormat, new NullProgressMonitor() };
                    }
                    CopyToImageUtil util = new CopyToImageUtil();
                    if (Modifier.isStatic(copyToImage.getModifiers())) {
                        copyToImage.invoke(null, args);
                    } else {
                        copyToImage.invoke(util, args);
                    }
                    System.out.println("exported (Sirius/GMF): " + outFile);
                    exportedCount.incrementAndGet();
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    System.err.println("Sirius: failed to export " + stem + ": " + cause);
                    cause.printStackTrace(System.err);
                    failedCount.incrementAndGet();
                }
            });
        }

        try { session.close(monitor); } catch (Throwable ignore) { }

        r.exported = exportedCount.get();
        r.failed   = failedCount.get();
        return r;
    }

    // ---------------- helpers ----------------

    private static Map<EObject, Diagram> collectGmfDiagramsByElement(Session session) {
        Map<EObject, Diagram> map = new IdentityHashMap<>();
        for (Resource res : session.getAllSessionResources()) {
            for (Iterator<EObject> it = res.getAllContents(); it.hasNext(); ) {
                EObject o = it.next();
                if (o instanceof Diagram d) {
                    EObject elem = d.getElement();
                    if (elem != null) map.put(elem, d);
                }
            }
        }
        // Some Sirius layouts also publish the diagrams as session-level
        // semantic resources; walk those too.
        try {
            Resource sr = session.getSessionResource();
            if (sr != null) {
                for (Iterator<EObject> it = sr.getAllContents(); it.hasNext(); ) {
                    EObject o = it.next();
                    if (o instanceof Diagram d) {
                        EObject elem = d.getElement();
                        if (elem != null) map.putIfAbsent(elem, d);
                    }
                }
            }
        } catch (Throwable ignore) { }
        return map;
    }

    private static Method findCopyToImageMethod() {
        Method match4 = null;
        Method match5 = null;
        for (Method m : CopyToImageUtil.class.getMethods()) {
            if (!"copyToImage".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 4 && params.length != 5) continue;
            if (!Diagram.class.isAssignableFrom(params[0])) continue;
            m.setAccessible(true);
            if (params.length == 5) match5 = m; else match4 = m;
        }
        return match5 != null ? match5 : match4;
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

    private static ImageFileFormat parseGmfFormat(String s) {
        if (s == null) s = "SVG";
        return switch (s.toUpperCase()) {
            case "SVG" -> ImageFileFormat.SVG;
            case "PNG" -> ImageFileFormat.PNG;
            case "JPEG", "JPG" -> ImageFileFormat.JPG;
            case "BMP" -> ImageFileFormat.BMP;
            case "GIF" -> ImageFileFormat.GIF;
            case "PDF" -> ImageFileFormat.PDF;
            default -> null;
        };
    }
}
