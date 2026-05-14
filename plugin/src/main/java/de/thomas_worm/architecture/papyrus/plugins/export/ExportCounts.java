/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * Mutable tally of how many diagrams a single run exported, failed to
 * export, or skipped because Sirius bundles were unavailable.
 *
 * <p>One instance is created per run and shared across both export
 * pipelines so the run-final summary covers everything that happened.
 */
final class ExportCounts {

    /** Number of diagrams that were rendered to disk successfully. */
    private int exported;

    /**
     * Number of diagrams that the renderer attempted but failed to
     * produce (excluding skipped Sirius files).
     */
    private int failed;

    /**
     * Number of {@code .aird} files that were skipped because the
     * Sirius bundles aren't installed on the runtime.
     */
    private int siriusSkipped;

    /**
     * Records that {@code count} additional diagrams were exported.
     *
     * @param count non-negative increment to add
     */
    void addExported(int count) {
        exported += count;
    }

    /**
     * Records that {@code count} additional diagrams failed to export.
     *
     * @param count non-negative increment to add
     */
    void addFailed(int count) {
        failed += count;
    }

    /**
     * Records that {@code count} additional Sirius files were skipped.
     *
     * @param count non-negative increment to add
     */
    void addSiriusSkipped(int count) {
        siriusSkipped += count;
    }

    /** @return how many diagrams the run has exported so far. */
    int exported() {
        return exported;
    }

    /** @return how many diagrams the run has failed to export so far. */
    int failed() {
        return failed;
    }

    /** @return how many Sirius {@code .aird} files were skipped so far. */
    int siriusSkipped() {
        return siriusSkipped;
    }

    /**
     * Renders the counters in the
     * {@code "exported=N failed=M sirius_skipped=K"} format that the
     * action's run step greps for on the launcher's last line.
     *
     * @return human-readable counter summary
     */
    String summary() {
        return "exported=" + exported
                + " failed=" + failed
                + " sirius_skipped=" + siriusSkipped;
    }
}
