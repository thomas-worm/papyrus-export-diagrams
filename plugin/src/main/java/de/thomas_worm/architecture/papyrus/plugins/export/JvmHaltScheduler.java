/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * Schedules a non-blocking, last-resort {@link Runtime#halt} on a
 * daemon thread.
 *
 * <p>Bounds the JVM lifetime when normal shutdown paths (workbench
 * close, OSGi framework shutdown) refuse to terminate — a guarantee
 * the CI runner watchdog otherwise has to provide by killing us at
 * its 180-second silence threshold with no diagnostic.
 */
final class JvmHaltScheduler {

    /** Utility class; not instantiable. */
    private JvmHaltScheduler() {
    }

    /**
     * Starts a daemon timer that calls
     * {@code Runtime.getRuntime().halt(exitCode)} after
     * {@code delayMillis} milliseconds. Returns immediately; the
     * caller's thread is never blocked.
     *
     * @param exitCode value to pass to {@code Runtime.halt}
     * @param delayMillis how long to wait before halting
     */
    static void scheduleHalt(int exitCode, long delayMillis) {
        Thread halter = new Thread("ForceHalt") {
            /**
             * Sleeps for {@code delayMillis} and then calls
             * {@link Runtime#halt} unless the sleep was interrupted.
             *
             * <p>Intentionally does not print before halting: Eclipse's
             * shutdown logging holds the {@code System.err} monitor for
             * extended periods, and a {@code println} here would block
             * indefinitely instead of reaching the {@code halt} call.
             */
            @Override
            public void run() {
                if (!sleepQuietly(delayMillis)) return;
                Runtime.getRuntime().halt(exitCode);
            }
        };
        halter.setDaemon(true);
        halter.start();
    }

    /**
     * Sleeps for {@code millis} milliseconds, treating
     * {@link InterruptedException} as a signal to abort the halt.
     *
     * @param millis sleep duration
     * @return {@code true} if the sleep completed normally,
     *         {@code false} if interrupted
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ignored) {
            return false;
        }
    }
}
