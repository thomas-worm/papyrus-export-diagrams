package de.thomas_worm.architecture.papyrus.plugins.export;

import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Provides access to the Inter Regular WOFF2 file shipped inside the
 * plugin JAR, and renders it as an SVG {@code @font-face} declaration
 * with a {@code data:font/woff2} URL.
 *
 * <p>The encoded block is computed once on first access and cached for
 * the lifetime of the JVM — every export of a given format reuses it.
 */
final class BundledInterFont {

    private static final String CLASSPATH_RESOURCE = "/fonts/Inter-Regular.woff2";

    private static volatile String cachedFontFaceBlock;

    private BundledInterFont() { }

    /**
     * Returns the {@code <defs><style>@font-face { … }</style></defs>}
     * block ready to be inserted right after a {@code <svg>} opening
     * tag, or {@link Optional#empty()} if the WOFF2 resource isn't on
     * the classpath.
     */
    static Optional<String> fontFaceBlock() {
        String cached = cachedFontFaceBlock;
        if (cached != null) return Optional.of(cached);

        byte[] fontBytes = readResourceBytes();
        if (fontBytes == null) return Optional.empty();

        String encoded = Base64.getEncoder().encodeToString(fontBytes);
        String block = buildFontFaceBlock(encoded);
        cachedFontFaceBlock = block;

        System.out.println(
                "BundledInterFont: encoded Inter Regular ("
                        + fontBytes.length + " bytes raw / "
                        + encoded.length() + " bytes base64)");
        return Optional.of(block);
    }

    private static byte[] readResourceBytes() {
        try (InputStream in = BundledInterFont.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                System.err.println(
                        "BundledInterFont: " + CLASSPATH_RESOURCE
                                + " not on the classpath; viewers without Inter "
                                + "installed will see fallback fonts");
                return null;
            }
            return in.readAllBytes();
        } catch (Throwable t) {
            System.err.println("BundledInterFont: failed to read WOFF2: " + t);
            return null;
        }
    }

    private static String buildFontFaceBlock(String base64WoffBody) {
        return "<defs><style type=\"text/css\">"
                + "@font-face{"
                + "font-family:'Inter';"
                + "font-style:normal;"
                + "font-weight:400;"
                + "src:url(data:font/woff2;base64," + base64WoffBody + ") format('woff2');"
                + "}"
                + "</style></defs>";
    }
}
