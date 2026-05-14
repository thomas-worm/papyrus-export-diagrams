package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.papyrus.infra.core.resource.ModelSet;

/**
 * Exports every diagram stored in Papyrus {@code .notation} files
 * found under the supplied model directory.
 *
 * <p>For each {@code .di} file the exporter spins up a fresh
 * {@link PapyrusModelEnvironment}, loads the related
 * {@code .notation} / {@code .uml} resources, remaps unavailable
 * fonts, resets the CSS engine, and writes each {@link Diagram} via
 * {@link CopyToImageInvocation}. The resulting SVG (if any) is run
 * through {@link SvgPostProcessor} before being reported as exported.
 */
final class NotationDiagramExporter {

    private final ExportFormat format;
    private final FilenameStrategy filenameStrategy;
    private final FontInventory fontInventory;
    private final SvgPostProcessor svgPostProcessor;

    NotationDiagramExporter(ExportFormat format,
                            FilenameStrategy filenameStrategy,
                            FontInventory fontInventory,
                            SvgPostProcessor svgPostProcessor) {
        this.format = format;
        this.filenameStrategy = filenameStrategy;
        this.fontInventory = fontInventory;
        this.svgPostProcessor = svgPostProcessor;
    }

    /**
     * Walks {@code modelDirectory} recursively, finds every
     * {@code .di} file that has a {@code .notation} sibling, and writes
     * every diagram into {@code outputDirectory}. Mutates
     * {@code counts} as it goes.
     */
    void exportAll(Path modelDirectory, Path outputDirectory, ExportCounts counts) {
        Optional<CopyToImageInvocation> invocation = CopyToImageInvocation.locate();
        if (invocation.isEmpty()) {
            counts.addFailed(1);
            return;
        }
        List<Path> notationFilePaths = discoverNotationFiles(modelDirectory, counts);
        DiagramFilenameGenerator filenames = new DiagramFilenameGenerator(filenameStrategy);
        for (Path diFile : notationFilePaths) {
            exportOne(diFile, outputDirectory, invocation.get(), filenames, counts);
        }
    }

    private List<Path> discoverNotationFiles(Path modelDirectory, ExportCounts counts) {
        try (Stream<Path> walk = Files.walk(modelDirectory)) {
            return walk
                    .filter(NotationDiagramExporter::hasNotationSibling)
                    .sorted()
                    .toList();
        } catch (Throwable t) {
            System.err.println("NotationDiagramExporter: failed to scan " + modelDirectory + ": " + t);
            counts.addFailed(1);
            return List.of();
        }
    }

    private static boolean hasNotationSibling(Path path) {
        if (!path.getFileName().toString().endsWith(".di")) return false;
        String base = stripExtension(path.getFileName().toString());
        return Files.exists(path.resolveSibling(base + ".notation"));
    }

    private void exportOne(Path diFile,
                           Path outputDirectory,
                           CopyToImageInvocation invocation,
                           DiagramFilenameGenerator filenames,
                           ExportCounts counts) {
        try (PapyrusModelEnvironment environment = PapyrusModelEnvironment.create()) {
            Resource notationResource = loadNotationModel(diFile, environment);
            if (notationResource == null) {
                counts.addFailed(1);
                return;
            }
            remapFontsQuietly(environment);
            resetCssEngineQuietly(notationResource);
            exportEveryDiagram(notationResource, invocation, filenames, outputDirectory, counts);
        } catch (Throwable t) {
            System.err.println("NotationDiagramExporter: environment setup failed for "
                    + diFile + ": " + t);
            t.printStackTrace(System.err);
            counts.addFailed(1);
        }
    }

    private Resource loadNotationModel(Path diFile, PapyrusModelEnvironment environment) {
        ModelSet modelSet = environment.modelSet();
        String base = stripExtension(diFile.getFileName().toString());
        Path notationFile = diFile.resolveSibling(base + ".notation");
        try {
            loadWithPapyrusOrManually(modelSet, diFile, base);
            Resource notationResource = modelSet.getResource(
                    URI.createFileURI(notationFile.toAbsolutePath().toString()), true);
            EcoreUtil.resolveAll(modelSet);
            return notationResource;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.err.println("NotationDiagramExporter: failed to load notation "
                    + notationFile + ": " + cause);
            cause.printStackTrace(System.err);
            return null;
        }
    }

    private static void loadWithPapyrusOrManually(ModelSet modelSet, Path diFile, String base)
            throws Exception {
        URI diUri = URI.createFileURI(diFile.toAbsolutePath().toString());
        try {
            Method loadModels = ModelSet.class.getMethod("loadModels", URI.class);
            loadModels.invoke(modelSet, diUri);
        } catch (NoSuchMethodException missingApi) {
            loadCompanionResources(modelSet, diFile, base);
        }
    }

    private static void loadCompanionResources(ModelSet modelSet, Path diFile, String base) {
        Path umlFile = diFile.resolveSibling(base + ".uml");
        if (Files.exists(umlFile)) {
            modelSet.getResource(URI.createFileURI(umlFile.toAbsolutePath().toString()), true);
        }
        Path notationFile = diFile.resolveSibling(base + ".notation");
        modelSet.getResource(URI.createFileURI(notationFile.toAbsolutePath().toString()), true);
    }

    private void remapFontsQuietly(PapyrusModelEnvironment environment) {
        try {
            new ModelFontRemapper(fontInventory)
                    .remapResources(environment.editingDomain(), environment.modelSet().getResources());
        } catch (Throwable t) {
            System.err.println(
                    "NotationDiagramExporter: font remap failed (continuing): " + t);
        }
    }

    private static void resetCssEngineQuietly(Resource notationResource) {
        try {
            Class<?> cssNotationResource = Class.forName(
                    "org.eclipse.papyrus.infra.gmfdiag.css.resource.CSSNotationResource");
            Object engine = cssNotationResource
                    .getMethod("getEngine", Resource.class)
                    .invoke(null, notationResource);
            resetEngineIfPresent(engine);
            resetCssOnDiagrams(notationResource);
        } catch (Throwable t) {
            System.err.println("NotationDiagramExporter: CSS engine reset failed: " + t);
        }
    }

    private static void resetEngineIfPresent(Object engine) {
        if (engine == null) return;
        try { engine.getClass().getMethod("reset").invoke(engine); } catch (Throwable ignored) { }
        try { engine.getClass().getMethod("resetCache").invoke(engine); } catch (Throwable ignored) { }
    }

    private static void resetCssOnDiagrams(Resource notationResource) throws Exception {
        Class<?> cssDiagramImpl = Class.forName(
                "org.eclipse.papyrus.infra.gmfdiag.css.notation.CSSDiagramImpl");
        Method resetCss = cssDiagramImpl.getMethod("resetCSS");
        for (Diagram diagram : collectDiagrams(notationResource)) {
            if (cssDiagramImpl.isInstance(diagram)) {
                resetCss.invoke(diagram);
            }
        }
    }

    private void exportEveryDiagram(Resource notationResource,
                                    CopyToImageInvocation invocation,
                                    DiagramFilenameGenerator filenames,
                                    Path outputDirectory,
                                    ExportCounts counts) {
        for (Diagram diagram : collectDiagrams(notationResource)) {
            String stem = filenames.stemFor(diagram);
            Path outputFile = outputDirectory.resolve(stem + "." + format.fileExtension());
            try {
                invocation.exportDiagram(diagram, outputFile, format.toGmfFormat());
                svgPostProcessor.process(outputFile);
                System.out.println("exported (Notation): " + outputFile);
                counts.addExported(1);
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                System.err.println("NotationDiagramExporter: failed to export "
                        + stem + ": " + cause);
                cause.printStackTrace(System.err);
                counts.addFailed(1);
            }
        }
    }

    private static Collection<Diagram> collectDiagrams(Resource resource) {
        Collection<Diagram> diagrams = new ArrayList<>();
        for (EObject element : resource.getContents()) {
            if (element instanceof Diagram diagram) diagrams.add(diagram);
        }
        return diagrams;
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return t;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
