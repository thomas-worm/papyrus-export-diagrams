package de.thomas_worm.architecture.papyrus.plugins.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;

/**
 * Top-level orchestrator for a single export run.
 *
 * <p>Drives, in sequence, the two pipelines:
 * <ol>
 *   <li>{@link NotationDiagramExporter} over every {@code .di} +
 *       {@code .notation} pair found under the model directory,</li>
 *   <li>{@link SiriusRepresentationExporter} over every {@code .aird}
 *       file found there (only when the Sirius bundles are present;
 *       otherwise the {@code .aird} files are counted as
 *       {@link ExportCounts#siriusSkipped()}).</li>
 * </ol>
 */
final class ExportRunner {

    private final ExportArguments arguments;

    ExportRunner(ExportArguments arguments) {
        this.arguments = arguments;
    }

    /** Runs both pipelines and accumulates results into {@code counts}. */
    void runInto(ExportCounts counts) {
        FontInventory fontInventory = new FontInventory();
        SvgPostProcessor svgPostProcessor = new SvgPostProcessor(fontInventory);
        runNotationPipeline(fontInventory, svgPostProcessor, counts);
        runSiriusPipeline(fontInventory, svgPostProcessor, counts);
    }

    private void runNotationPipeline(FontInventory fontInventory,
                                     SvgPostProcessor svgPostProcessor,
                                     ExportCounts counts) {
        NotationDiagramExporter exporter = new NotationDiagramExporter(
                arguments.format(),
                arguments.filenameStrategy(),
                fontInventory,
                svgPostProcessor);
        try {
            exporter.exportAll(arguments.modelDirectory(), arguments.outputDirectory(), counts);
        } catch (LinkageError e) {
            System.err.println("Notation export classes missing: " + e.getMessage());
        } catch (Throwable t) {
            System.err.println("Unexpected error during notation export: " + t);
            t.printStackTrace(System.err);
        }
    }

    private void runSiriusPipeline(FontInventory fontInventory,
                                   SvgPostProcessor svgPostProcessor,
                                   ExportCounts counts) {
        boolean siriusAvailable = isSiriusInstalled();
        List<Path> airdFiles = discoverAirdFiles(arguments.modelDirectory(), counts);
        if (airdFiles.isEmpty()) return;
        SiriusRepresentationExporter exporter = new SiriusRepresentationExporter(
                arguments.format(),
                arguments.filenameStrategy(),
                fontInventory,
                svgPostProcessor);
        for (Path airdFile : airdFiles) {
            if (!siriusAvailable) {
                warnSiriusMissing(airdFile);
                counts.addSiriusSkipped(1);
                continue;
            }
            exportOneAird(exporter, airdFile, arguments.outputDirectory(), counts);
        }
    }

    private static boolean isSiriusInstalled() {
        return Platform.getBundle("org.eclipse.sirius") != null
                && Platform.getBundle("org.eclipse.sirius.ui") != null;
    }

    private static List<Path> discoverAirdFiles(Path modelDirectory, ExportCounts counts) {
        try (Stream<Path> walk = Files.walk(modelDirectory)) {
            return walk
                    .filter(path -> path.getFileName().toString().endsWith(".aird"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("Could not scan " + modelDirectory + " for .aird files: " + e);
            counts.addFailed(1);
            return List.of();
        }
    }

    private static void warnSiriusMissing(Path airdFile) {
        System.err.println(
                "WARNING: " + airdFile + " is a Sirius representations container, "
                        + "but the Sirius bundles are not present in this Papyrus install. Skipping.");
    }

    private static void exportOneAird(SiriusRepresentationExporter exporter,
                                      Path airdFile,
                                      Path outputDirectory,
                                      ExportCounts counts) {
        try {
            exporter.exportAll(airdFile, outputDirectory, counts);
        } catch (NoClassDefFoundError e) {
            System.err.println("WARNING: Sirius export unavailable for " + airdFile);
            System.err.println("  Missing class: " + e);
            counts.addSiriusSkipped(1);
        } catch (LinkageError e) {
            System.err.println("Sirius linkage error for " + airdFile + ": " + e);
            counts.addSiriusSkipped(1);
        } catch (Throwable t) {
            System.err.println(
                    "Unexpected failure exporting Sirius file " + airdFile + ": " + t);
            t.printStackTrace(System.err);
            counts.addFailed(1);
        }
    }
}
