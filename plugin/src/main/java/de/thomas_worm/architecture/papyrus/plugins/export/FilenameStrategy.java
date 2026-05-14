package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * How exported image filenames are derived from each diagram.
 */
enum FilenameStrategy {

    /**
     * Use the diagram's stable EMF XMI fragment id. Always unique but
     * cryptic.
     */
    XMI_ID,

    /**
     * Use the diagram's user-facing name (sanitised). You stay
     * responsible for keeping names unique across the model.
     */
    NAME;

    /**
     * Returns {@link #NAME} when {@code value} equals {@code "name"}
     * (case-insensitive); {@link #XMI_ID} otherwise.
     */
    static FilenameStrategy parse(String value) {
        return "name".equalsIgnoreCase(value) ? NAME : XMI_ID;
    }
}
