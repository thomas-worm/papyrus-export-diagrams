/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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
 * the lifetime of the JVM — every export of a given run reuses it.
 */
final class BundledInterFont {

    /**
     * Path of the bundled Inter Regular WOFF2 file, relative to the
     * JAR root. Must be packaged into the export plugin by the
     * action's jar step.
     */
    private static final String CLASSPATH_RESOURCE = "/fonts/Inter-Regular.woff2";

    /**
     * Lazily-computed {@code <defs><style>@font-face …</style></defs>}
     * block; the same string is reused for every SVG. {@code null}
     * means the lazy build hasn't happened yet — the read code
     * distinguishes that from {@link Optional#empty()} (which
     * indicates a missing resource).
     */
    private static volatile String cachedFontFaceBlock;

    /** Utility class; not instantiable. */
    private BundledInterFont() {
    }

    /**
     * @return the {@code <defs><style>@font-face { … }</style></defs>}
     *         block ready to be inserted right after a {@code <svg>}
     *         opening tag, or {@link Optional#empty()} if the WOFF2
     *         resource isn't on the classpath
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

    /**
     * Reads the Inter WOFF2 bytes from the classpath.
     *
     * @return the WOFF2 bytes, or {@code null} if the resource is
     *         missing or unreadable
     */
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

    /**
     * Builds the SVG {@code <defs><style>} block declaring Inter from
     * a base64-encoded WOFF2 payload.
     *
     * @param base64WoffBody the WOFF2 bytes, already base64-encoded
     * @return the ready-to-inject markup
     */
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
