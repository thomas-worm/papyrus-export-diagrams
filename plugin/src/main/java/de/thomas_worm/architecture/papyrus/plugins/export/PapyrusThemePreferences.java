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

    private static final String PAPYRUS_THEME_ID =
            "org.eclipse.papyrus.css.papyrus_theme";
    private static final String ECLIPSE_CLASSIC_THEME_ID =
            "org.eclipse.e4.ui.css.theme.e4_classic";

    private static final List<String> PAPYRUS_THEME_NODES = List.of(
            "org.eclipse.papyrus.infra.gmfdiag.css",
            "org.eclipse.papyrus.infra.gmfdiag.css.theme",
            "org.eclipse.papyrus.infra.gmfdiag.css.properties",
            "org.eclipse.papyrus.infra.gmfdiag.style",
            "org.eclipse.papyrus.uml.diagram.css");

    private static final List<String> THEME_KEY_CANDIDATES = List.of(
            "currentTheme",
            "theme",
            "theme.id",
            "themeId",
            "currentThemeId",
            "current.theme");

    private PapyrusThemePreferences() { }

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

    private static void writeBothScopes(String node, String key, String value) {
        writeInstanceScope(node, key, value);
        writeDefaultScope(node, key, value);
    }

    private static void writeInstanceScope(String node, String key, String value) {
        try {
            IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(node);
            preferences.put(key, value);
            preferences.flush();
        } catch (Throwable ignored) {
        }
    }

    private static void writeDefaultScope(String node, String key, String value) {
        try {
            DefaultScope.INSTANCE.getNode(node).put(key, value);
        } catch (Throwable ignored) {
        }
    }
}
