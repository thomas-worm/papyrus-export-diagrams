/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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

    /** Target image format every diagram is rendered as. */
    private final ExportFormat format;

    /** Strategy used to derive each output filename stem. */
    private final FilenameStrategy filenameStrategy;

    /**
     * Snapshot of locally-available fonts. Passed to
     * {@link ModelFontRemapper} for FontStyle rewriting.
     */
    private final FontInventory fontInventory;

    /**
     * Post-processor applied to every SVG file after the GMF renderer
     * writes it.
     */
    private final SvgPostProcessor svgPostProcessor;

    /**
     * @param format target image format
     * @param filenameStrategy how to derive filename stems
     * @param fontInventory locally available font families
     * @param svgPostProcessor SVG post-processor to apply after each
     *        successful export
     */
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
     * {@code .di} file that has a {@code .notation} sibling, and
     * writes every diagram into {@code outputDirectory}. Mutates
     * {@code counts} as it goes.
     *
     * @param modelDirectory directory to scan
     * @param outputDirectory directory to write into (must already exist)
     * @param counts shared counters updated per diagram
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

    /**
     * Recursively scans {@code modelDirectory} for {@code .di} files
     * with an accompanying {@code .notation} sibling.
     *
     * @param modelDirectory the model directory
     * @param counts counters; incremented on scan failure
     * @return discovered file paths, sorted lexicographically
     */
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

    /**
     * @param path any path
     * @return {@code true} when {@code path} is a {@code .di} file
     *         and a sibling file with the same base name and a
     *         {@code .notation} extension exists on disk
     */
    private static boolean hasNotationSibling(Path path) {
        if (!path.getFileName().toString().endsWith(".di")) return false;
        String base = stripExtension(path.getFileName().toString());
        return Files.exists(path.resolveSibling(base + ".notation"));
    }

    /**
     * Exports every diagram of a single {@code .di} / {@code .notation}
     * pair into {@code outputDirectory}.
     *
     * @param diFile path to the {@code .di} entry point file
     * @param outputDirectory where exported images go
     * @param invocation resolved {@code copyToImage} method
     * @param filenames shared filename generator (so collision
     *        de-duplication spans all diagrams in the run)
     * @param counts shared counters updated per diagram
     */
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

    /**
     * Loads the {@code .di} + companion resources into the supplied
     * environment and returns the notation resource that holds the
     * diagrams.
     *
     * @param diFile entry point file
     * @param environment freshly created environment
     * @return loaded notation resource, or {@code null} on failure
     */
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

    /**
     * Prefers Papyrus's {@code ModelSet.loadModels(URI)} which wires
     * up the {@code .di}/{@code .uml}/{@code .notation} triplet
     * through registered {@code IModel}s. Falls back to loading the
     * {@code .uml} and {@code .notation} directly when the API is
     * absent.
     *
     * @param modelSet target model set
     * @param diFile path to the {@code .di} file
     * @param base shared base name of the triplet
     * @throws Exception when the reflective load throws
     */
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

    /**
     * Loads the {@code .uml} (if present) and {@code .notation}
     * resources directly into {@code modelSet}.
     *
     * @param modelSet target model set
     * @param diFile path to the {@code .di} file
     * @param base shared base name of the triplet
     */
    private static void loadCompanionResources(ModelSet modelSet, Path diFile, String base) {
        Path umlFile = diFile.resolveSibling(base + ".uml");
        if (Files.exists(umlFile)) {
            modelSet.getResource(URI.createFileURI(umlFile.toAbsolutePath().toString()), true);
        }
        Path notationFile = diFile.resolveSibling(base + ".notation");
        modelSet.getResource(URI.createFileURI(notationFile.toAbsolutePath().toString()), true);
    }

    /**
     * Runs the font remapper inside the environment's editing domain
     * with errors swallowed — fonts are a render-quality concern, not
     * a correctness one, and we'd rather export with the wrong font
     * than skip the diagram entirely.
     *
     * @param environment the loaded environment
     */
    private void remapFontsQuietly(PapyrusModelEnvironment environment) {
        try {
            new ModelFontRemapper(fontInventory)
                    .remapResources(environment.editingDomain(), environment.modelSet().getResources());
        } catch (Throwable t) {
            System.err.println(
                    "NotationDiagramExporter: font remap failed (continuing): " + t);
        }
    }

    /**
     * Forces Papyrus's per-diagram CSS engine to drop its cached
     * stylesheets and element adapters, so the freshly-set theme
     * takes effect at first paint.
     *
     * @param notationResource the loaded notation resource
     */
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

    /**
     * Invokes the {@code reset} and {@code resetCache} methods on the
     * CSS engine when present; silent when {@code engine} is
     * {@code null} or the methods aren't available.
     *
     * @param engine an instance of Papyrus's
     *        {@code ExtendedCSSEngine}, possibly {@code null}
     */
    private static void resetEngineIfPresent(Object engine) {
        if (engine == null) return;
        try { engine.getClass().getMethod("reset").invoke(engine); } catch (Throwable ignored) { }
        try { engine.getClass().getMethod("resetCache").invoke(engine); } catch (Throwable ignored) { }
    }

    /**
     * Invokes {@code CSSDiagramImpl.resetCSS()} on every diagram in
     * the resource that's a {@code CSSDiagramImpl}.
     *
     * @param notationResource the loaded notation resource
     * @throws Exception when reflection itself fails (class loading,
     *         method resolution)
     */
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

    /**
     * Exports every {@link Diagram} from the resource into
     * {@code outputDirectory}, updating {@code counts} for each.
     *
     * @param notationResource the resource holding the diagrams
     * @param invocation resolved {@code copyToImage} method
     * @param filenames shared filename generator
     * @param outputDirectory directory exported files go into
     * @param counts shared counters
     */
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

    /**
     * @param resource the resource to inspect
     * @return every top-level {@link Diagram} the resource contains
     */
    private static Collection<Diagram> collectDiagrams(Resource resource) {
        Collection<Diagram> diagrams = new ArrayList<>();
        for (EObject element : resource.getContents()) {
            if (element instanceof Diagram diagram) diagrams.add(diagram);
        }
        return diagrams;
    }

    /**
     * Unwraps the typical {@link InvocationTargetException} that
     * reflective calls produce so log lines show the actual underlying
     * cause.
     *
     * @param t the caught throwable
     * @return either {@code t} unchanged or its causal exception
     */
    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return t;
    }

    /**
     * Removes the last {@code .extension} segment from a file name.
     *
     * @param filename the raw file name (no path)
     * @return the base name
     */
    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
