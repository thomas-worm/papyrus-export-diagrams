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
                    // Try to create a TransactionalEditingDomain for this ResourceSet
                    // This is required for GMF export to work
                    try {
                        Class<?> editingDomainClass = Class.forName(
                            "org.eclipse.emf.transaction.TransactionalEditingDomain");
                        Class<?> factoryClass = Class.forName(
                            "org.eclipse.emf.transaction.TransactionalEditingDomain$Factory");
                        
                        // Get the INSTANCE field from Factory
                        java.lang.reflect.Field instanceField = factoryClass.getField("INSTANCE");
                        Object factoryInstance = instanceField.get(null);
                        
                        // Call getEditingDomain(resourceSet) or create(resourceSet)
                        java.lang.reflect.Method getMethod = factoryClass.getMethod("getEditingDomain", ResourceSet.class);
                        Object editingDomain = getMethod.invoke(factoryInstance, rs);
                        
                        System.out.println("GMF: TransactionalEditingDomain created for resource set");
                    } catch (Exception e) {
                        System.err.println("GMF: Could not create editing domain: " + e);
                        // Continue anyway - maybe it will work without it
                    }
                    
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
                        // Ensure the diagram's resource has access to the editing domain
                        // copyToImage() looks for it via the resource
                        try {
                            Resource diagramRes = d.eResource();
                            if (diagramRes != null && diagramRes.getResourceSet() == rs) {
                                // Try to register the editing domain for the diagram's resource
                                Class<?> editingDomainClass = Class.forName(
                                    "org.eclipse.emf.transaction.TransactionalEditingDomain");
                                Class<?> registryClass = Class.forName(
                                    "org.eclipse.emf.transaction.TransactionalEditingDomain$Registry");
                                
                                // Get INSTANCE from editingDomainClass
                                java.lang.reflect.Method getRegistryMethod = 
                                    editingDomainClass.getMethod("getRegistry");
                                Object registry = getRegistryMethod.invoke(null);
                                
                                // Get or create editing domain for this ResourceSet
                                java.lang.reflect.Method getMethod = 
                                    registryClass.getMethod("getEditingDomain", ResourceSet.class);
                                Object editingDomain = getMethod.invoke(registry, rs);
                                
                                System.out.println("GMF: editing domain associated with diagram resource");
                            }
                        } catch (Exception e) {
                            System.err.println("GMF: could not associate editing domain with diagram: " + e);
                        }
                        
                        // CopyToImageUtil.copyToImage() method signature varies across GMF versions.
                        // Attempt to invoke via reflection to handle API differences.
                        var method = findCopyToImageMethod(CopyToImageUtil.class);
                        if (method == null) {
                            System.err.println("GMF: copyToImage method not found for " + stem);
                            r.failed++;
                            continue;
                        }
                        
                        try {
                            // Try static invocation first (common pattern)
                            java.lang.reflect.Modifier.isStatic(method.getModifiers());
                            boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                            
                            org.eclipse.core.runtime.IPath eclipsePath = 
                                    org.eclipse.core.runtime.Path.fromOSString(outFile.toString());
                            
                            // Determine how many parameters the method expects
                            Class<?>[] paramTypes = method.getParameterTypes();
                            Object[] args;
                            
                            if (paramTypes.length == 5) {
                                // Method expects PreferencesHint as 5th parameter
                                // Try to create a default instance or pass null if it's optional
                                try {
                                    Class<?> prefsHintClass = Class.forName(
                                        "org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint");
                                    java.lang.reflect.Constructor<?> ctor = 
                                        prefsHintClass.getConstructor(String.class);
                                    Object prefsHint = ctor.newInstance("Papyrus");
                                    args = new Object[] { d, eclipsePath, gmfFormat, 
                                        new org.eclipse.core.runtime.NullProgressMonitor(), prefsHint };
                                } catch (Exception ex) {
                                    System.err.println("GMF: could not create PreferencesHint, trying without: " + ex);
                                    args = new Object[] { d, eclipsePath, gmfFormat, 
                                        new org.eclipse.core.runtime.NullProgressMonitor(), null };
                                }
                            } else {
                                args = new Object[] { d, eclipsePath, gmfFormat, 
                                    new org.eclipse.core.runtime.NullProgressMonitor() };
                            }
                            
                            if (isStatic) {
                                method.invoke(null, args);
                            } else {
                                CopyToImageUtil util = new CopyToImageUtil();
                                method.invoke(util, args);
                            }
                            
                            System.out.println("exported (GMF): " + outFile);
                            r.exported++;
                        } catch (java.lang.IllegalArgumentException ex) {
                            System.err.println("GMF: argument type mismatch for " + stem + ": " + ex);
                            r.failed++;
                        } catch (java.lang.NullPointerException ex) {
                            // Common when TransactionalEditingDomain is not initialized
                            System.err.println("GMF: NullPointerException for " + stem);
                            System.err.println("  Cause: " + ex.getMessage());
                            if (ex.getMessage() != null && ex.getMessage().contains("editingDomain")) {
                                System.err.println("  Note: GMF export requires TransactionalEditingDomain (editing framework)");
                                System.err.println("        This is not available in headless Papyrus export mode.");
                                System.err.println("        Consider using Sirius-based diagrams (.aird) instead of GMF (.notation).");
                            }
                            r.failed++;
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            System.err.println("GMF: export failed for " + stem + ": " + ex.getCause());
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

    private static java.lang.reflect.Method findCopyToImageMethod(Class<?> utilClass) {
        // Try to find copyToImage method with different signatures to handle API variations
        
        java.lang.reflect.Method[] methods = utilClass.getDeclaredMethods();
        for (java.lang.reflect.Method m : methods) {
            if ("copyToImage".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                
                // Skip methods that expect DiagramEditPart - those require full SWT/UI framework
                if (params.length >= 1 && params[0].getSimpleName().contains("DiagramEditPart")) {
                    System.out.println("GMF: skipping incompatible copyToImage (requires DiagramEditPart UI framework)");
                    continue;
                }
                
                if (params.length == 4 || params.length == 5) {
                    m.setAccessible(true);
                    System.out.println("GMF: found compatible copyToImage method with " + params.length + " params: "
                            + java.util.Arrays.toString(params));
                    return m;
                }
            }
        }
        
        // Log what we found if nothing was compatible
        System.err.println("GMF: no compatible copyToImage methods found in " + utilClass.getName());
        System.err.println("GMF: available copyToImage variants:");
        for (java.lang.reflect.Method m : methods) {
            if ("copyToImage".equals(m.getName())) {
                System.err.println("  - expects " + (m.getParameterTypes().length > 0 ? 
                    m.getParameterTypes()[0].getSimpleName() : "?") + " (incompatible)");
            }
        }
        
        return null;
    }

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
