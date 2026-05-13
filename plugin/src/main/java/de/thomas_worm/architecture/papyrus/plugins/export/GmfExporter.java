/*
 * GMF-specific export helper.
 *
 * Encapsulates all GMF diagram export logic and handles API version differences.
 * Kept in a separate class to isolate GMF-specific complexities and allow for
 * graceful fallback if GMF is not available.
 *
 * The export pipeline:
 *   1. Find .notation files alongside .di files
 *   2. Load diagram resources
 *   3. Export each Diagram via CopyToImageUtil to the target format
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;

final class GmfExporter {

    /** Result of a batch export. */
    static final class Result {
        int exported;
        int failed;
    }

    private GmfExporter() { }

    /**
     * Export every diagram from notation files in a model directory.
     *
     * @param modelDir     directory to scan for *.di / *.notation pairs
     * @param outDir       target directory (already created by caller)
     * @param format       output format (SVG, PNG, JPEG, BMP, GIF, PDF)
     * @param useId        true: filename = diagram's EMF fragment ID; false: diagram's name (sanitised)
     * @param ext          file extension (lowercased format)
     * @return per-directory counters
     */
    static Result exportNotations(Path modelDir, Path outDir, String format, boolean useId, String ext) {
        Result r = new Result();

        ImageFileFormat gmfFormat = parseGmfFormat(format);
        if (gmfFormat == null) {
            System.err.println("GMF: unsupported format " + format);
            return r;
        }

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
                    System.err.println("GMF: failed to load notation " + notation + ": " + e);
                    r.failed++;
                    continue;
                }

                Set<String> usedNames = new HashSet<>();
                for (Diagram d : collectDiagrams(res)) {
                    String stem = filenameFor(d, useId, usedNames);
                    Path outFile = outDir.resolve(stem + "." + ext);
                    try {
                        // CopyToImageUtil API varies across GMF versions.
                        // Some versions expect DiagramEditPart, others accept Diagram.
                        // Use reflection to avoid compile-time type checking.
                        try {
                            CopyToImageUtil util = new CopyToImageUtil();
                            var copyMethod = util.getClass().getMethod("copyToImage",
                                    Object.class,
                                    org.eclipse.core.runtime.IPath.class,
                                    ImageFileFormat.class,
                                    org.eclipse.core.runtime.IProgressMonitor.class);
                            copyMethod.invoke(util,
                                    (Object) d,
                                    org.eclipse.core.runtime.Path.fromOSString(outFile.toString()),
                                    gmfFormat,
                                    new NullProgressMonitor());
                        } catch (NoSuchMethodException ex) {
                            System.err.println("GMF: copyToImage method not found for " + stem);
                            continue;
                        }
                        System.out.println("exported (GMF): " + outFile);
                        r.exported++;
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        if (ex.getCause() instanceof ClassCastException) {
                            System.err.println("GMF: cannot export " + stem + " (API expects DiagramEditPart, not Diagram)");
                        } else {
                            System.err.println("GMF: failed to export " + stem + ": " + ex.getCause());
                            r.failed++;
                        }
                    } catch (Throwable t) {
                        System.err.println("GMF: failed to export " + stem + ": " + t);
                        r.failed++;
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("GMF: error during export: " + t);
            r.failed++;
        }

        return r;
    }

    // ---- helpers ----

    private static Collection<Diagram> collectDiagrams(Resource res) {
        Collection<Diagram> out = new java.util.ArrayList<>();
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

    /**
     * Parse a format string to a GMF ImageFileFormat enum constant.
     * Handles API version differences (avoid valueOf which may not exist).
     *
     * @param s format string (SVG, PNG, JPEG, BMP, GIF, PDF)
     * @return corresponding ImageFileFormat, or null if unsupported
     */
    private static ImageFileFormat parseGmfFormat(String s) {
        if (s == null) s = "SVG";
        String upper = s.toUpperCase();

        // First, try direct instantiation of common formats.
        // This avoids relying on valueOf() which may not exist in all GMF versions.
        try {
            return switch (upper) {
                case "SVG" -> ImageFileFormat.SVG;
                case "PNG" -> ImageFileFormat.PNG;
                case "JPEG", "JPG" -> ImageFileFormat.JPG;
                case "BMP" -> ImageFileFormat.BMP;
                case "GIF" -> ImageFileFormat.GIF;
                case "PDF" -> ImageFileFormat.PDF;
                default -> null;
            };
        } catch (Throwable e) {
            // Switch statement failed; continue to fallback
        }

        // Fallback: try getEnumConstants() if switch didn't work
        try {
            ImageFileFormat[] constants = ImageFileFormat.class.getEnumConstants();
            if (constants != null) {
                for (ImageFileFormat fmt : constants) {
                    if (fmt.toString().equalsIgnoreCase(upper) || fmt.toString().equalsIgnoreCase(s)) {
                        return fmt;
                    }
                }
            }
        } catch (Exception e) {
            // No enum constants available
        }

        System.err.println("GMF: unknown format '" + s + "', no export possible");
        return null;
    }
}
