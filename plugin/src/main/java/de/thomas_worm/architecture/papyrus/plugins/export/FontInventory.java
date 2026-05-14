/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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

    /**
     * Whether the current JVM is running on macOS, determined once at
     * class load time from {@code os.name}. Drives both the
     * fallback-order selection and the Apple-private-font filter.
     */
    private static final boolean RUNNING_ON_MACOS = System
            .getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("mac");

    /**
     * Preferred substitute families on macOS, in priority order. The
     * first family available locally wins.
     */
    private static final List<String> MACOS_PREFERENCE_ORDER = List.of(
            ".AppleSystemUIFont",
            "Inter",
            "Helvetica",
            "Arial",
            "SansSerif");

    /**
     * Preferred substitute families on Linux and Windows, in priority
     * order. Inter (installed by {@code setup-papyrus}) heads the list
     * because it's the closest freely-redistributable match to macOS's
     * San Francisco system font.
     */
    private static final List<String> OTHER_OS_PREFERENCE_ORDER = List.of(
            "Inter",
            "Arial",
            "Liberation Sans",
            "DejaVu Sans",
            "Noto Sans",
            "SansSerif");

    /**
     * Font families AWT reports as installed locally, plus the
     * always-present Java logical families. Names are kept in their
     * original casing and also indexed in lower case for
     * case-insensitive lookup.
     */
    private final Set<String> availableFamilies;

    /**
     * The first family from {@link #preferenceOrder()} that
     * {@link #isAvailable(String)} returns {@code true} for; falls
     * back to the {@code SansSerif} logical family if none match.
     */
    private final String substituteFamily;

    /**
     * Snapshots the JVM's font inventory and chooses a substitute
     * family for any subsequent remapping.
     */
    FontInventory() {
        this.availableFamilies = enumerateAwtFamilies();
        this.substituteFamily = pickFirstAvailable(preferenceOrder());
    }

    /** @return {@code true} when the JVM is running on macOS. */
    boolean runningOnMacOs() {
        return RUNNING_ON_MACOS;
    }

    /**
     * @return how many font families AWT reports as installed (used
     *         only in log lines).
     */
    int availableCount() {
        return availableFamilies.size();
    }

    /** @return the family chosen as the substitute for unavailable fonts. */
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
     *
     * @param family the family name to check
     * @return {@code true} if the renderer should be able to use it
     */
    boolean isAvailable(String family) {
        if (!RUNNING_ON_MACOS && family.startsWith(".")) return false;
        if (availableFamilies.contains(family)) return true;
        return availableFamilies.contains(family.toLowerCase(Locale.ROOT));
    }

    /**
     * @return the OS-specific preference order for fallback selection.
     */
    private List<String> preferenceOrder() {
        return RUNNING_ON_MACOS ? MACOS_PREFERENCE_ORDER : OTHER_OS_PREFERENCE_ORDER;
    }

    /**
     * Picks the first available family from {@code candidates};
     * returns the Java logical {@code SansSerif} family if none of
     * the candidates is locally installed.
     *
     * @param candidates preference-ordered list of family names
     * @return the resolved substitute family name
     */
    private String pickFirstAvailable(List<String> candidates) {
        for (String candidate : candidates) {
            if (isAvailable(candidate)) return candidate;
        }
        return "SansSerif";
    }

    /**
     * Builds the local font inventory by combining AWT's enumeration
     * with Java's always-present logical families.
     *
     * @return the full set of family names treated as installed
     */
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
