package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * Schedules a non-blocking, last-resort {@link Runtime#halt} on a
 * daemon thread.
 *
 * <p>Bounds the JVM lifetime when normal shutdown paths (workbench
 * close, OSGi framework shutdown) refuse to terminate — a guarantee
 * the CI runner watchdog otherwise has to provide by killing us at
 * its 180-second silence threshold.
 */
final class JvmHaltScheduler {

    private JvmHaltScheduler() { }

    /**
     * Starts a daemon timer that calls
     * {@code Runtime.getRuntime().halt(exitCode)} after {@code delayMillis}
     * milliseconds. Returns immediately.
     */
    static void scheduleHalt(int exitCode, long delayMillis) {
        Thread halter = new Thread("ForceHalt") {
            @Override
            public void run() {
                if (!sleepQuietly(delayMillis)) return;
                System.err.println("ForceHalt: aborting JVM after " + delayMillis + "ms");
                Runtime.getRuntime().halt(exitCode);
            }
        };
        halter.setDaemon(true);
        halter.start();
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ignored) {
            return false;
        }
    }
}
