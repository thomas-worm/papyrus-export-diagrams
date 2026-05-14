/*
 * GMF-specific export helper.
 *
 * Each *.di file is loaded into a Papyrus ModelSet wired to a fresh
 * ServicesRegistry. Papyrus's GMF edit parts call
 * ServiceUtilsForResourceSet.getServiceRegistry(rs) during refresh and
 * require both — a plain TransactionalEditingDomain alone makes them
 * throw ServiceNotFoundException and the renderer aborts mid-traversal.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
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
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.papyrus.infra.core.resource.ModelSet;
import org.eclipse.papyrus.infra.core.services.ServicesRegistry;

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
        // Papyrus diagrams refresh themselves by calling
        // ServiceUtilsForResourceSet.getServiceRegistry(rs), which only
        // succeeds when the resource set is a Papyrus ModelSet that has
        // been registered with a ServicesRegistry. Bring both up here.
        ServicesRegistry registry = new ServicesRegistry();
        ModelSet modelSet = new ModelSet();
        TransactionalEditingDomain editingDomain;
        try {
            // ModelSet inherits from a transactional resource set but
            // does not create its TransactionalEditingDomain itself —
            // in stock Papyrus a separate IService does. We bind one
            // here so TransactionUtil.getEditingDomain(diagram) returns
            // it during GMF rendering.
            editingDomain = TransactionalEditingDomain.Factory.INSTANCE
                    .createEditingDomain(modelSet);
            registry.add(ModelSet.class, 10, modelSet);
            // PapyrusDiagramEditPart.refresh() looks up an
            // IMultiDiagramEditor in the registry during rendering.
            // We don't have one — we're rendering off-screen — but
            // missing it logs a "ServiceNotFoundException: Unexpected
            // Error" stack trace every time. Register a no-op proxy
            // so the lookup succeeds; refresh() ends up with the same
            // null/default values it would have used after catching
            // the exception, just without the noise.
            registerNoopMultiDiagramEditor(registry);
            registry.startRegistry();

            // Install the CSS notation resource factory on this
            // ModelSet's local registry BEFORE we load anything. The
            // global factory override on EMF doesn't get picked up by
            // ModelSet's local registry, so .notation files would
            // otherwise load as plain GMFResource and
            // CSSNotationResource.getEngine(res) returns null —
            // meaning no CSS engine is attached to the loaded diagram,
            // and gradients render white→white because the engine
            // never sees the stylesheet.
            installCssSupport(modelSet);
        } catch (Throwable t) {
            System.err.println("GMF: failed to start Papyrus services for "
                    + notation + ": " + t);
            t.printStackTrace(System.err);
            r.failed++;
            return;
        }

        try {
            Resource notationRes;
            try {
                // Load the .di first if loadModels(URI) is available — it
                // wires up the di/uml/notation triplet through Papyrus's
                // IModel implementations. Fall back to loading the notation
                // and uml resources directly if that overload is absent or
                // throws.
                URI diURI = URI.createFileURI(di.toAbsolutePath().toString());
                try {
                    Method loadModels = ModelSet.class.getMethod("loadModels", URI.class);
                    loadModels.invoke(modelSet, diURI);
                } catch (NoSuchMethodException nsme) {
                    Path uml = di.resolveSibling(base + ".uml");
                    if (Files.exists(uml)) {
                        modelSet.getResource(
                                URI.createFileURI(uml.toAbsolutePath().toString()), true);
                    }
                    modelSet.getResource(
                            URI.createFileURI(notation.toAbsolutePath().toString()), true);
                }
                notationRes = modelSet.getResource(
                        URI.createFileURI(notation.toAbsolutePath().toString()), true);
                EcoreUtil.resolveAll(modelSet);
            } catch (Exception e) {
                Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null
                        ? ite.getCause() : e;
                System.err.println("GMF: failed to load notation " + notation + ": " + cause);
                cause.printStackTrace(System.err);
                r.failed++;
                return;
            }

            // Non-fatal: rewrite any FontStyle that refers to a font the
            // JVM can't find (e.g. .AppleSystemUIFont on Linux). If this
            // can't run for some reason, we still try to render with the
            // original fonts — labels may overflow but the diagram still
            // exports.
            try {
                FontFallback.remap(editingDomain, modelSet.getResources());
            } catch (Throwable t) {
                System.err.println("GMF: font remap failed (continuing with original fonts): " + t);
            }

            // Diagnostic: log resource and diagram impl classes.
            System.out.println("CSS: notation resource impl is "
                    + notationRes.getClass().getName());
            // Reset the CSS engine attached to each loaded diagram so it
            // re-parses the active theme stylesheets, and ask each diagram
            // to drop its cached element adapters. Without this, the
            // engine instance set up at resource-load time may still hold
            // a stale stylesheet list.
            try {
                Class<?> cnrClass = Class.forName(
                        "org.eclipse.papyrus.infra.gmfdiag.css.resource.CSSNotationResource");
                java.lang.reflect.Method isCssEnabled =
                        cnrClass.getMethod("isCSSEnabled",
                                org.eclipse.emf.ecore.resource.Resource.class);
                java.lang.reflect.Method getEngine =
                        cnrClass.getMethod("getEngine",
                                org.eclipse.emf.ecore.resource.Resource.class);
                boolean cssOn = (Boolean) isCssEnabled.invoke(null, notationRes);
                Object engine = getEngine.invoke(null, notationRes);
                System.out.println("CSS: notation resource impl is "
                        + notationRes.getClass().getName()
                        + ", CSSEnabled=" + cssOn
                        + ", engine=" + engine);
                if (engine != null) {
                    try { engine.getClass().getMethod("reset").invoke(engine); } catch (Throwable ignore) { }
                    try { engine.getClass().getMethod("resetCache").invoke(engine); } catch (Throwable ignore) { }
                }
                try {
                    Class<?> diagImpl = Class.forName(
                            "org.eclipse.papyrus.infra.gmfdiag.css.notation.CSSDiagramImpl");
                    java.lang.reflect.Method resetCss = diagImpl.getMethod("resetCSS");
                    for (Diagram d : collectDiagrams(notationRes)) {
                        if (diagImpl.isInstance(d)) resetCss.invoke(d);
                    }
                } catch (Throwable ignore) { }
            } catch (Throwable t) {
                System.err.println("CSS: engine reset failed: " + t);
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
                    FontFallback.postProcessSvg(outFile);
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
            try { registry.disposeRegistry(); } catch (Throwable ignore) { }
            try { editingDomain.dispose(); } catch (Throwable ignore) { }
        }
    }

    // ---- helpers ----

    private static void installCssSupport(ModelSet modelSet) {
        try {
            Class<?> helper = Class.forName(
                    "org.eclipse.papyrus.infra.gmfdiag.css.helper.CSSHelper");
            // Some Papyrus builds expose it as ResourceSet, some as
            // ModelSet — try the more general signature first.
            java.lang.reflect.Method install = null;
            for (java.lang.reflect.Method m : helper.getMethods()) {
                if (!"installCSSSupport".equals(m.getName())) continue;
                if (m.getParameterCount() == 1) {
                    install = m;
                    if (m.getParameterTypes()[0].isAssignableFrom(modelSet.getClass())) {
                        break;
                    }
                }
            }
            if (install == null) {
                System.err.println("CSS: CSSHelper.installCSSSupport not found");
                return;
            }
            install.invoke(null, modelSet);
            System.out.println("CSS: installed CSS support on ModelSet via " + install);
        } catch (ClassNotFoundException e) {
            // CSS bundle not on classpath — fine; we just won't get gradients.
            System.err.println("CSS: helper bundle not present, gradients disabled");
        } catch (Throwable t) {
            System.err.println("CSS: installCSSSupport failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerNoopMultiDiagramEditor(ServicesRegistry registry) {
        try {
            Class<?> editorIface = Class.forName(
                    "org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor");
            InvocationHandler handler = (proxy, method, args) -> {
                Class<?> ret = method.getReturnType();
                if ("getServicesRegistry".equals(method.getName())) return registry;
                if (ret == boolean.class) return Boolean.FALSE;
                if (ret == byte.class)    return (byte) 0;
                if (ret == short.class)   return (short) 0;
                if (ret == int.class)     return 0;
                if (ret == long.class)    return 0L;
                if (ret == float.class)   return 0f;
                if (ret == double.class)  return 0d;
                if (ret == char.class)    return '\0';
                return null;
            };
            Object stub = Proxy.newProxyInstance(
                    editorIface.getClassLoader(),
                    new Class<?>[] { editorIface },
                    handler);
            registry.add((Class<Object>) editorIface, 1, stub);
        } catch (ClassNotFoundException e) {
            // Bundle without the editor interface (Papyrus Classic without
            // the multi-diagram editor) — nothing to register, nothing to
            // log against.
        } catch (Throwable t) {
            System.err.println("Could not register no-op IMultiDiagramEditor: " + t);
        }
    }

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
