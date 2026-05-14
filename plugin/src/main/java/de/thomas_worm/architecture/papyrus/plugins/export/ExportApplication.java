/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Files;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Entry point of the headless Papyrus diagram export application.
 *
 * <p>Wires together command-line parsing, OSGi bundle activation,
 * theme-preference seeding, last-resort JVM halt scheduling, the
 * workbench bring-up, and the export pipelines.
 *
 * <p>Targets Papyrus-Desktop 7.1.0 (Eclipse 2025-06, Java 21,
 * JustJ JRE). The application reads its own command-line arguments
 * after a literal {@code --} separator:
 *
 * <pre>
 *     --modelDir &lt;path&gt;   Directory scanned recursively for *.di / *.aird.
 *     --outDir   &lt;path&gt;   Where to write the image files.
 *     --format   &lt;name&gt;   SVG | PNG | JPEG | BMP | GIF | PDF (default SVG).
 *     --naming   &lt;mode&gt;   xmiId | name (default xmiId)
 * </pre>
 */
public class ExportApplication implements IApplication {

    /**
     * Upper bound on the entire JVM lifetime. If any stage (argument
     * parsing, bundle activation, workbench bring-up, export, OSGi
     * shutdown) takes longer than this, the daemon halt timer fires
     * and the JVM aborts.
     */
    private static final long GLOBAL_HALT_DELAY_MILLIS = 10L * 60L * 1000L;

    /**
     * Backup delay scheduled right before {@code start()} returns.
     * Fires if Eclipse's OSGi shutdown wedges after the application
     * returns; without this the runner watchdog would have to reap
     * the JVM 180 seconds later with no diagnostic.
     */
    private static final long POST_RETURN_HALT_DELAY_MILLIS = 5_000L;

    /**
     * Exit code returned when required command-line arguments are
     * missing. The action's {@code Run export action} step propagates
     * this as the launcher exit status.
     */
    private static final int USAGE_ERROR_EXIT_CODE = 2;

    /**
     * Exit code used by the global halt timer when it fires. Distinct
     * from the normal success / failure codes so failures attributable
     * to a wedge are visible in the runner log.
     */
    private static final int GLOBAL_HALT_EXIT_CODE = 99;

    /**
     * {@inheritDoc}
     *
     * <p>Returns an {@link Integer} exit code: {@code 0} on a
     * successful run, {@code 1} on partial export failure,
     * {@link #USAGE_ERROR_EXIT_CODE} on argument errors.
     */
    @Override
    public Object start(IApplicationContext context) throws Exception {
        JvmHaltScheduler.scheduleHalt(GLOBAL_HALT_EXIT_CODE, GLOBAL_HALT_DELAY_MILLIS);

        ExportArguments arguments;
        try {
            arguments = ExportArguments.parse(rawArgumentsOf(context));
        } catch (IllegalArgumentException usageError) {
            System.err.println(usageError.getMessage());
            return Integer.valueOf(USAGE_ERROR_EXIT_CODE);
        }
        Files.createDirectories(arguments.outputDirectory());

        applyThemeBundleAndPreferences();

        ExportCounts counts = new ExportCounts();
        int workbenchReturnCode = runExportThroughWorkbench(arguments, counts);

        reportFinalSummary(counts, workbenchReturnCode);
        int exitCode = counts.failed() == 0 ? 0 : 1;
        JvmHaltScheduler.scheduleHalt(exitCode, POST_RETURN_HALT_DELAY_MILLIS);
        return Integer.valueOf(exitCode);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The application has no long-running background activity that
     * outlives {@link #start}; this method is intentionally empty.
     */
    @Override
    public void stop() {
    }

    /**
     * Pulls the application argument array out of the
     * {@code IApplicationContext}.
     *
     * @param context the Eclipse application context
     * @return the raw arguments
     */
    private static String[] rawArgumentsOf(IApplicationContext context) {
        return (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
    }

    /**
     * Seeds the Papyrus theme preferences, activates the required
     * OSGi bundles, and re-seeds the preferences once more so
     * Papyrus's CSS-bundle initializer can't overwrite our values
     * during its lazy startup.
     */
    private static void applyThemeBundleAndPreferences() {
        PapyrusThemePreferences.applyAll();
        PapyrusBundleActivator.activateAll();
        PapyrusThemePreferences.applyAll();
    }

    /**
     * Brings the workbench up under the configured display and runs
     * the export from inside the {@link WorkbenchExportAdvisor}'s
     * {@code postStartup} callback.
     *
     * @param arguments parsed CLI arguments
     * @param counts shared counters that the advisor mutates
     * @return the workbench's own return code (passed straight to the
     *         log line)
     */
    private static int runExportThroughWorkbench(ExportArguments arguments, ExportCounts counts) {
        ExportRunner runner = new ExportRunner(arguments);
        WorkbenchExportAdvisor advisor = new WorkbenchExportAdvisor(runner, counts);
        Display display = PlatformUI.createDisplay();
        try {
            return PlatformUI.createAndRunWorkbench(display, advisor);
        } finally {
            disposeQuietly(display);
        }
    }

    /**
     * Disposes {@code display} unless it's already disposed,
     * swallowing any error.
     *
     * @param display the SWT display to dispose
     */
    private static void disposeQuietly(Display display) {
        if (display.isDisposed()) return;
        try {
            display.dispose();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Prints the run-final summary line that the action's run step
     * looks for ({@code "Done. exported=N failed=M sirius_skipped=K"})
     * and the workbench return code.
     *
     * @param counts the populated counters
     * @param workbenchReturnCode the value returned by
     *        {@link PlatformUI#createAndRunWorkbench}
     */
    private static void reportFinalSummary(ExportCounts counts, int workbenchReturnCode) {
        System.out.println("Done. " + counts.summary());
        System.out.println("Workbench return code: " + workbenchReturnCode);
    }
}
