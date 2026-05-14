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

    private static final long GLOBAL_HALT_DELAY_MILLIS = 10L * 60L * 1000L;
    private static final long POST_RETURN_HALT_DELAY_MILLIS = 5_000L;
    private static final int USAGE_ERROR_EXIT_CODE = 2;
    private static final int GLOBAL_HALT_EXIT_CODE = 99;

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

    @Override
    public void stop() {
    }

    private static String[] rawArgumentsOf(IApplicationContext context) {
        return (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
    }

    private static void applyThemeBundleAndPreferences() {
        PapyrusThemePreferences.applyAll();
        PapyrusBundleActivator.activateAll();
        PapyrusThemePreferences.applyAll();
    }

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

    private static void disposeQuietly(Display display) {
        if (display.isDisposed()) return;
        try {
            display.dispose();
        } catch (Throwable ignored) {
        }
    }

    private static void reportFinalSummary(ExportCounts counts, int workbenchReturnCode) {
        System.out.println("Done. " + counts.summary());
        System.out.println("Workbench return code: " + workbenchReturnCode);
    }
}
