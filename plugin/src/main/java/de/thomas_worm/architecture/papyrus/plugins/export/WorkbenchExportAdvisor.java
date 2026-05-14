package de.thomas_worm.architecture.papyrus.plugins.export;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;

/**
 * {@link WorkbenchAdvisor} that runs the export once the workbench is
 * fully started and then closes it immediately.
 *
 * <p>Papyrus's GMF edit parts call {@code PlatformUI.getWorkbench()}
 * from static initializers, so the headless export needs a real
 * workbench to be running before any diagram is loaded. This advisor
 * bootstraps that workbench, hands off to {@link ExportRunner} from
 * {@link #postStartup()}, and schedules a backup
 * {@link JvmHaltScheduler#scheduleHalt} so a stuck
 * {@code workbench.close()} cannot wedge the JVM.
 *
 * <p>Returning {@code null} from
 * {@link #getInitialWindowPerspectiveId()} leaves the initial window
 * without an active perspective; the workbench tolerates this and
 * skips the perspective-bar UI.
 */
final class WorkbenchExportAdvisor extends WorkbenchAdvisor {

    private static final long CLOSE_HANG_HALT_DELAY_MILLIS = 30_000L;

    private final ExportRunner runner;
    private final ExportCounts counts;

    WorkbenchExportAdvisor(ExportRunner runner, ExportCounts counts) {
        this.runner = runner;
        this.counts = counts;
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return null;
    }

    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
        configurer.setSaveAndRestore(false);
    }

    @Override
    public void postStartup() {
        try {
            runner.runInto(counts);
        } catch (Throwable t) {
            System.err.println("Export aborted: " + t);
            t.printStackTrace(System.err);
            counts.addFailed(1);
        } finally {
            closeWorkbenchSafely();
        }
    }

    private void closeWorkbenchSafely() {
        JvmHaltScheduler.scheduleHalt(
                counts.failed() == 0 ? 0 : 1,
                CLOSE_HANG_HALT_DELAY_MILLIS);
        try {
            PlatformUI.getWorkbench().close();
        } catch (Throwable ignored) {
        }
    }
}
