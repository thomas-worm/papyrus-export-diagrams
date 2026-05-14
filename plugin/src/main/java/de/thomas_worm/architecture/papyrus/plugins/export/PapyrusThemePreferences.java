/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.List;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Writes the Papyrus and Eclipse preferences that pin the diagram
 * rendering theme and switch on anti-aliasing.
 *
 * <p>The Papyrus CSS bundle's {@code ThemePreferenceInitializer} runs
 * when the bundle activates and may overwrite an earlier
 * {@link InstanceScope} value with its own default — callers should
 * therefore invoke {@link #applyAll()} both before and after
 * {@link PapyrusBundleActivator#activateAll()}.
 */
final class PapyrusThemePreferences {

    /**
     * Identifier of the Papyrus CSS theme that produces the modern
     * gradient look (matching the user's Papyrus GUI default).
     */
    private static final String PAPYRUS_THEME_ID =
            "org.eclipse.papyrus.css.papyrus_theme";

    /**
     * Identifier of the Eclipse workbench Classic theme (no E4 CSS,
     * native widget look).
     */
    private static final String ECLIPSE_CLASSIC_THEME_ID =
            "org.eclipse.e4.ui.css.theme.e4_classic";

    /**
     * Preference nodes the Papyrus theme system has historically been
     * observed to read from. The current theme is written to every
     * candidate node so the active runtime always finds the value
     * regardless of Papyrus version differences.
     */
    private static final List<String> PAPYRUS_THEME_NODES = List.of(
            "org.eclipse.papyrus.infra.gmfdiag.css",
            "org.eclipse.papyrus.infra.gmfdiag.css.theme",
            "org.eclipse.papyrus.infra.gmfdiag.css.properties",
            "org.eclipse.papyrus.infra.gmfdiag.style",
            "org.eclipse.papyrus.uml.diagram.css");

    /**
     * Preference keys the Papyrus theme system has historically been
     * observed to read from. The current theme id is written to every
     * candidate key for the same forward-compatibility reason as the
     * node list.
     */
    private static final List<String> THEME_KEY_CANDIDATES = List.of(
            "currentTheme",
            "theme",
            "theme.id",
            "themeId",
            "currentThemeId",
            "current.theme");

    /** Utility class; not instantiable. */
    private PapyrusThemePreferences() {
    }

    /**
     * Pins the Eclipse workbench theme to {@code Classic}, pins the
     * Papyrus diagram CSS theme to {@code Papyrus Theme} (the modern
     * gradient look), and enables GMF anti-aliasing.
     *
     * <p>Writes to every preference node and key combination Papyrus
     * has historically used; only the one the active runtime reads
     * actually matters, the rest are no-ops.
     */
    static void applyAll() {
        writeBothScopes(
                "org.eclipse.e4.ui.css.swt.theme",
                "themeid",
                ECLIPSE_CLASSIC_THEME_ID);

        for (String node : PAPYRUS_THEME_NODES) {
            for (String key : THEME_KEY_CANDIDATES) {
                writeBothScopes(node, key, PAPYRUS_THEME_ID);
            }
        }

        writeBothScopes(
                "org.eclipse.gmf.runtime.diagram.ui",
                "Appearance.enableAntiAlias",
                "true");
    }

    /**
     * Writes the given preference value into both the
     * {@link InstanceScope} (user-facing setting) and the
     * {@link DefaultScope} (initializer-style default).
     *
     * @param node preference node id (typically a bundle symbolic name)
     * @param key the preference key
     * @param value the value to store
     */
    private static void writeBothScopes(String node, String key, String value) {
        writeInstanceScope(node, key, value);
        writeDefaultScope(node, key, value);
    }

    /**
     * Writes the preference into the workspace's instance scope and
     * flushes the node. Failures are silent because some candidate
     * nodes only exist in certain Papyrus versions.
     *
     * @param node preference node id
     * @param key preference key
     * @param value value to store
     */
    private static void writeInstanceScope(String node, String key, String value) {
        try {
            IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(node);
            preferences.put(key, value);
            preferences.flush();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Writes the preference into the default scope so the value
     * survives an {@code AbstractPreferenceInitializer} firing during
     * bundle activation. Failures are silent.
     *
     * @param node preference node id
     * @param key preference key
     * @param value value to store
     */
    private static void writeDefaultScope(String node, String key, String value) {
        try {
            DefaultScope.INSTANCE.getNode(node).put(key, value);
        } catch (Throwable ignored) {
        }
    }
}
