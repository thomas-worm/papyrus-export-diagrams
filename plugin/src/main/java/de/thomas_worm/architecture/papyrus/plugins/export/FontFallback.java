/*
 * Font fallback helper.
 *
 * Diagrams authored on macOS frequently store fonts like ".AppleSystemUIFont"
 * or "Helvetica" that don't exist on a Linux GitHub runner. Java then falls
 * back to the logical "Dialog" font (typically DejaVu Sans), which has
 * different metrics from the original font — labels overflow their box
 * bounds in the rendered SVG.
 *
 * This helper walks every loaded notation:FontStyle in a set of resources
 * and substitutes any font that isn't installed locally with the first
 * available font from a fallback list. The change is in-memory only; it
 * never touches the source files.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.notation.FontStyle;

final class FontFallback {

    // Search order for replacing an unavailable font. The first family in
    // this list that the JVM actually has wins. Arial / Liberation Sans
    // are metric-compatible with each other and roughly with Helvetica
    // and macOS's system font; if none of those is present we land on
    // SansSerif (Java's logical sans-serif) which always exists.
    private static final List<String> FALLBACK_ORDER = List.of(
            "Arial",
            "Liberation Sans",
            "DejaVu Sans",
            "Noto Sans",
            "SansSerif");

    private final Set<String> available;
    private final String fallback;
    private int rewrites;

    private FontFallback() {
        this.available = collectAvailableFamilies();
        this.fallback = chooseFallback();
    }

    /**
     * Walk every {@link FontStyle} in the supplied resources and replace
     * any unavailable font name with the configured fallback.
     * Returns the number of rewrites performed.
     */
    static int remap(Iterable<? extends Resource> resources) {
        FontFallback ff = new FontFallback();
        System.out.println("FontFallback: using '" + ff.fallback
                + "' for unavailable fonts (" + ff.available.size() + " families on classpath)");
        for (Resource res : resources) {
            ff.remapResource(res);
        }
        if (ff.rewrites > 0) {
            System.out.println("FontFallback: rewrote " + ff.rewrites + " FontStyle entries");
        }
        return ff.rewrites;
    }

    private void remapResource(Resource res) {
        Iterator<EObject> it = res.getAllContents();
        while (it.hasNext()) {
            EObject o = it.next();
            if (o instanceof FontStyle fs) {
                String name = fs.getFontName();
                if (name == null || name.isBlank()) continue;
                if (isAvailable(name)) continue;
                fs.setFontName(fallback);
                rewrites++;
            }
        }
    }

    private boolean isAvailable(String family) {
        if (available.contains(family)) return true;
        // case-insensitive match
        return available.contains(family.toLowerCase(Locale.ROOT));
    }

    private String chooseFallback() {
        for (String candidate : FALLBACK_ORDER) {
            if (isAvailable(candidate)) return candidate;
        }
        // SansSerif is a Java logical family — always present.
        return "SansSerif";
    }

    private static Set<String> collectAvailableFamilies() {
        Set<String> out = new LinkedHashSet<>();
        try {
            String[] names = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            out.addAll(Arrays.asList(names));
            // Also store lowercased variants for case-insensitive lookup.
            Set<String> lower = new HashSet<>();
            for (String s : names) lower.add(s.toLowerCase(Locale.ROOT));
            out.addAll(lower);
        } catch (Throwable t) {
            System.err.println("FontFallback: cannot enumerate AWT fonts: " + t);
        }
        // Java's logical families are always available even when AWT
        // enumeration is restricted.
        out.add("SansSerif");
        out.add("Serif");
        out.add("Monospaced");
        out.add("Dialog");
        out.add("DialogInput");
        return out;
    }

}
