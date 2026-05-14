package de.thomas_worm.architecture.papyrus.plugins.export;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Snapshot of the font families Java's AWT can see on the current
 * runner, plus the chosen substitute family for fonts that aren't
 * installed locally.
 *
 * <p>Selection order is OS-dependent:
 * <ul>
 *   <li>macOS keeps {@code .AppleSystemUIFont} (San Francisco) at the
 *       top so models authored on macOS render with their native font.</li>
 *   <li>Linux and Windows prefer Inter — the closest freely
 *       redistributable match to San Francisco — followed by Arial and
 *       other metric-compatible families.</li>
 * </ul>
 *
 * <p>Apple's dot-prefixed system font names are treated as unavailable
 * on non-macOS hosts even when Java's font enumeration reports them via
 * alias entries, because the actual paint silently substitutes a
 * different family.
 */
final class FontInventory {

    private static final boolean RUNNING_ON_MACOS = System
            .getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("mac");

    private static final List<String> MACOS_PREFERENCE_ORDER = List.of(
            ".AppleSystemUIFont",
            "Inter",
            "Helvetica",
            "Arial",
            "SansSerif");

    private static final List<String> OTHER_OS_PREFERENCE_ORDER = List.of(
            "Inter",
            "Arial",
            "Liberation Sans",
            "DejaVu Sans",
            "Noto Sans",
            "SansSerif");

    private final Set<String> availableFamilies;
    private final String substituteFamily;

    FontInventory() {
        this.availableFamilies = enumerateAwtFamilies();
        this.substituteFamily = pickFirstAvailable(preferenceOrder());
    }

    /** Whether the JVM is running on macOS. */
    boolean runningOnMacOs() {
        return RUNNING_ON_MACOS;
    }

    /** Number of font families the JVM reports as installed. */
    int availableCount() {
        return availableFamilies.size();
    }

    /** The family chosen as the substitute for unavailable fonts. */
    String substituteFamily() {
        return substituteFamily;
    }

    /**
     * Whether {@code family} is installed locally and usable.
     *
     * <p>On non-macOS, family names starting with a dot are
     * considered unavailable regardless of what AWT claims — Java's
     * font enumeration sometimes reports {@code .AppleSystemUIFont}
     * on Windows via alias entries, but the paint falls back to
     * Segoe UI and the result doesn't match macOS rendering.
     */
    boolean isAvailable(String family) {
        if (!RUNNING_ON_MACOS && family.startsWith(".")) return false;
        if (availableFamilies.contains(family)) return true;
        return availableFamilies.contains(family.toLowerCase(Locale.ROOT));
    }

    private List<String> preferenceOrder() {
        return RUNNING_ON_MACOS ? MACOS_PREFERENCE_ORDER : OTHER_OS_PREFERENCE_ORDER;
    }

    private String pickFirstAvailable(List<String> candidates) {
        for (String candidate : candidates) {
            if (isAvailable(candidate)) return candidate;
        }
        return "SansSerif";
    }

    private static Set<String> enumerateAwtFamilies() {
        Set<String> families = new LinkedHashSet<>();
        try {
            String[] names = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            families.addAll(Arrays.asList(names));
            Set<String> lowercased = new HashSet<>();
            for (String name : names) {
                lowercased.add(name.toLowerCase(Locale.ROOT));
            }
            families.addAll(lowercased);
        } catch (Throwable t) {
            System.err.println("FontInventory: cannot enumerate AWT fonts: " + t);
        }
        families.add("SansSerif");
        families.add("Serif");
        families.add("Monospaced");
        families.add("Dialog");
        families.add("DialogInput");
        return families;
    }
}
