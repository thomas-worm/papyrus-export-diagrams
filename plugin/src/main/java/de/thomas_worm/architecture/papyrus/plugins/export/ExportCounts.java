package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * Mutable tally of how many diagrams a single run exported, failed to
 * export, or skipped because Sirius bundles were unavailable.
 */
final class ExportCounts {

    private int exported;
    private int failed;
    private int siriusSkipped;

    /** Records that {@code count} additional diagrams were exported. */
    void addExported(int count) { exported += count; }

    /** Records that {@code count} additional diagrams failed to export. */
    void addFailed(int count) { failed += count; }

    /** Records that {@code count} additional Sirius files were skipped. */
    void addSiriusSkipped(int count) { siriusSkipped += count; }

    int exported() { return exported; }

    int failed() { return failed; }

    int siriusSkipped() { return siriusSkipped; }

    /**
     * Renders the counters in the {@code "exported=N failed=M sirius_skipped=K"}
     * format that the action's run step greps for at the end.
     */
    String summary() {
        return "exported=" + exported
                + " failed=" + failed
                + " sirius_skipped=" + siriusSkipped;
    }
}
