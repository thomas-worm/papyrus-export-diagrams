/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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

    /** Parsed command-line arguments that configure this run. */
    private final ExportArguments arguments;

    /**
     * @param arguments parsed application arguments
     */
    ExportRunner(ExportArguments arguments) {
        this.arguments = arguments;
    }

    /**
     * Runs both pipelines and accumulates results into {@code counts}.
     *
     * @param counts shared counters updated as work progresses
     */
    void runInto(ExportCounts counts) {
        FontInventory fontInventory = new FontInventory();
        SvgPostProcessor svgPostProcessor = new SvgPostProcessor(fontInventory);
        runNotationPipeline(fontInventory, svgPostProcessor, counts);
        runSiriusPipeline(fontInventory, svgPostProcessor, counts);
    }

    /**
     * Runs the {@code .notation} pipeline; tolerates any failure so
     * the caller can still attempt the Sirius pipeline afterwards.
     *
     * @param fontInventory shared font inventory
     * @param svgPostProcessor shared SVG post-processor
     * @param counts shared counters
     */
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

    /**
     * Discovers every {@code .aird} file in the model directory and
     * runs the Sirius pipeline over each, skipping silently when the
     * Sirius bundles are absent.
     *
     * @param fontInventory shared font inventory
     * @param svgPostProcessor shared SVG post-processor
     * @param counts shared counters
     */
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

    /**
     * @return whether the core Sirius bundles are installed on the
     *         running Papyrus instance
     */
    private static boolean isSiriusInstalled() {
        return Platform.getBundle("org.eclipse.sirius") != null
                && Platform.getBundle("org.eclipse.sirius.ui") != null;
    }

    /**
     * Recursively scans {@code modelDirectory} for {@code .aird} files.
     *
     * @param modelDirectory the directory to scan
     * @param counts counters incremented on scan failure
     * @return discovered file paths, sorted lexicographically
     */
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

    /**
     * Prints a single warning line explaining that Sirius isn't
     * present on the current Papyrus install.
     *
     * @param airdFile the file being skipped
     */
    private static void warnSiriusMissing(Path airdFile) {
        System.err.println(
                "WARNING: " + airdFile + " is a Sirius representations container, "
                        + "but the Sirius bundles are not present in this Papyrus install. Skipping.");
    }

    /**
     * Wraps the Sirius pipeline call so that classpath-shape errors
     * (e.g. {@link NoClassDefFoundError} for a missing Sirius helper
     * class) translate into a {@code siriusSkipped} increment rather
     * than aborting the rest of the run.
     *
     * @param exporter the Sirius exporter
     * @param airdFile the file to render
     * @param outputDirectory directory to write into
     * @param counts shared counters
     */
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
