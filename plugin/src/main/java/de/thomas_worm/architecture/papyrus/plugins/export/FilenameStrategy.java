/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

/**
 * How exported image filenames are derived from each diagram.
 *
 * <p>The mode is selected by the {@code --naming} command-line flag and
 * defaults to {@link #XMI_ID} when not given.
 */
enum FilenameStrategy {

    /**
     * Use the diagram's stable EMF XMI fragment id (e.g.
     * {@code _Sb6kkE75EfG0Uc8F_5hRNQ}). Always unique inside one model
     * but cryptic.
     */
    XMI_ID,

    /**
     * Use the diagram's user-facing name, sanitised to file-safe
     * characters ({@code A-Z}, {@code a-z}, {@code 0-9}, {@code .},
     * {@code _}, {@code -}). The caller stays responsible for keeping
     * names unique across the model.
     */
    NAME;

    /**
     * Resolves a user-supplied strategy name.
     *
     * @param value command-line value of the {@code --naming} flag;
     *        case-insensitive, {@code null}-safe
     * @return {@link #NAME} when {@code value} equals {@code "name"},
     *         {@link #XMI_ID} otherwise
     */
    static FilenameStrategy parse(String value) {
        return "name".equalsIgnoreCase(value) ? NAME : XMI_ID;
    }
}
