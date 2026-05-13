/*
 * GMF-specific export helper.
 *
 * Encapsulates all GMF diagram export logic. Each .di file is loaded into a
 * fresh TransactionalEditingDomain so CopyToImageUtil can locate it via
 * TransactionUtil.getEditingDomain(diagram) — without that domain in place
 * the renderer NPEs deep inside its off-screen edit-part setup.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;

final class GmfExporter {

    static final class Result {
        int exported;
        int failed;
    }

    private GmfExporter() { }

    static Result exportNotations(Path modelDir, Path outDir, String format, boolean useId, String ext) {
        Result r = new Result();

        ImageFileFormat gmfFormat = parseGmfFormat(format);
        if (gmfFormat == null) {
            System.err.println("GMF: unsupported format " + format);
            return r;
        }

        Method copyToImage = findCopyToImageMethod();
        if (copyToImage == null) {
            System.err.println("GMF: no compatible CopyToImageUtil.copyToImage(Diagram,...) method on classpath");
            System.err.println("GMF: available overloads:");
            for (Method m : CopyToImageUtil.class.getMethods()) {
                if ("copyToImage".equals(m.getName())) {
                    System.err.println("  - " + m);
                }
            }
            return r;
        }
        System.out.println("GMF: using " + copyToImage);

        try (Stream<Path> walk = Files.walk(modelDir)) {
            List<Path> diFiles = walk
                    .filter(p -> p.getFileName().toString().endsWith(".di"))
                    .sorted()
                    .toList();

            for (Path di : diFiles) {
                String base = stripExtension(di.getFileName().toString());
                Path notation = di.resolveSibling(base + ".notation");
                if (!Files.exists(notation)) continue;

                exportOneNotation(notation, di, base, outDir, gmfFormat, ext, useId, copyToImage, r);
            }
        } catch (Throwable t) {
            System.err.println("GMF: error during export: " + t);
            r.failed++;
        }

        return r;
    }

    private static void exportOneNotation(Path notation, Path di, String base, Path outDir,
                                          ImageFileFormat gmfFormat, String ext, boolean useId,
                                          Method copyToImage, Result r) {
        // CopyToImageUtil resolves the editing domain via
        // TransactionUtil.getEditingDomain(diagram) — which walks the resource
        // set's adapter list looking for the TransactionalEditingDomain adapter.
        // We must therefore load the diagram into a ResourceSet that already
        // has an editing domain attached to it (createEditingDomain() owns
        // its own ResourceSet and attaches itself as the required adapter).
        TransactionalEditingDomain editingDomain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain();
        try {
            ResourceSet rs = editingDomain.getResourceSet();
            Resource notationRes;
            try {
                notationRes = rs.getResource(
                        URI.createFileURI(notation.toAbsolutePath().toString()), true);
                Path uml = di.resolveSibling(base + ".uml");
                if (Files.exists(uml)) {
                    rs.getResource(URI.createFileURI(uml.toAbsolutePath().toString()), true);
                }
                EcoreUtil.resolveAll(rs);
            } catch (Exception e) {
                System.err.println("GMF: failed to load notation " + notation + ": " + e);
                r.failed++;
                return;
            }

            Set<String> usedNames = new HashSet<>();
            CopyToImageUtil util = new CopyToImageUtil();
            for (Diagram d : collectDiagrams(notationRes)) {
                String stem = filenameFor(d, useId, usedNames);
                Path outFile = outDir.resolve(stem + "." + ext);
                IPath eclipsePath = org.eclipse.core.runtime.Path.fromOSString(outFile.toString());
                try {
                    Object[] args;
                    if (copyToImage.getParameterCount() == 5) {
                        args = new Object[] { d, eclipsePath, gmfFormat,
                                new NullProgressMonitor(), PreferencesHint.USE_DEFAULTS };
                    } else {
                        args = new Object[] { d, eclipsePath, gmfFormat, new NullProgressMonitor() };
                    }
                    if (Modifier.isStatic(copyToImage.getModifiers())) {
                        copyToImage.invoke(null, args);
                    } else {
                        copyToImage.invoke(util, args);
                    }
                    System.out.println("exported (GMF): " + outFile);
                    r.exported++;
                } catch (InvocationTargetException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    System.err.println("GMF: export failed for " + stem + ": " + cause);
                    cause.printStackTrace(System.err);
                    r.failed++;
                } catch (Throwable t) {
                    System.err.println("GMF: failed to export " + stem + ": " + t);
                    t.printStackTrace(System.err);
                    r.failed++;
                }
            }
        } finally {
            try { editingDomain.dispose(); } catch (Throwable ignore) { }
        }
    }

    // ---- helpers ----

    private static Method findCopyToImageMethod() {
        // CopyToImageUtil has several overloads; we want one that takes a
        // Diagram (not a DiagramEditPart, which would require a pre-built
        // edit part we don't have in headless mode). The 4-arg variant is
        // (Diagram, IPath, ImageFileFormat, IProgressMonitor); the 5-arg
        // variant adds a trailing PreferencesHint. Prefer 5-arg when both
        // exist — it lets us pass USE_DEFAULTS so styling preferences are
        // initialised even outside a workbench.
        Method match4 = null;
        Method match5 = null;
        for (Method m : CopyToImageUtil.class.getMethods()) {
            if (!"copyToImage".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 4 && params.length != 5) continue;
            if (!Diagram.class.isAssignableFrom(params[0])) continue;
            m.setAccessible(true);
            if (params.length == 5) {
                match5 = m;
            } else {
                match4 = m;
            }
        }
        return match5 != null ? match5 : match4;
    }

    private static Collection<Diagram> collectDiagrams(Resource res) {
        Collection<Diagram> out = new ArrayList<>();
        for (EObject o : res.getContents()) {
            if (o instanceof Diagram d) out.add(d);
        }
        return out;
    }

    private static String filenameFor(Diagram d, boolean useId, Set<String> used) {
        String base;
        if (!useId && d.getName() != null && !d.getName().isBlank()) {
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
