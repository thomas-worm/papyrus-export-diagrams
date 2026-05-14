/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Applies a sequence of cosmetic transformations to a freshly written
 * SVG file so the result is portable and visually consistent across
 * runners.
 *
 * <p>Passes, applied in order:
 * <ol>
 *   <li>{@code font-family="'Dialog'"} is rewritten to whichever real
 *       family is most-used elsewhere in the document, so unstyled text
 *       matches the rest of the diagram instead of inheriting Batik's
 *       JVM-logical placeholder.</li>
 *   <li>{@code image-rendering="auto"} is set to
 *       {@code optimizeQuality} so embedded raster images are bicubic
 *       interpolated when the SVG is scaled.</li>
 *   <li>Apple-private font-family declarations
 *       ({@code .AppleSystemUIFont} etc.) get {@code 'Inter'} and
 *       {@code sans-serif} appended as fallbacks so they render outside
 *       macOS.</li>
 *   <li>On non-macOS, every remaining single-family declaration is
 *       canonicalised to the inventory's substitute family so the SVG
 *       reads as platform-agnostic.</li>
 *   <li>Every embedded {@code data:image/png;base64} payload is decoded,
 *       redrawn at four times its native resolution with bicubic
 *       interpolation, and re-encoded. The display dimensions don't
 *       change; only the embedded pixel count does, which lets viewers
 *       zoom several steps before pixelation becomes visible.</li>
 *   <li>If the SVG references the Inter font (directly or via the
 *       Apple-fallback chain), a {@code @font-face} block embedding the
 *       bundled Inter Regular WOFF2 is injected right after the root
 *       {@code <svg>} tag so viewers without Inter installed render
 *       identically.</li>
 * </ol>
 */
final class SvgPostProcessor {

    /**
     * Resolution multiplier applied to every embedded raster image.
     * The display dimensions stay the same; only the encoded pixel
     * count grows.
     */
    private static final int RASTER_UPSCALE_FACTOR = 4;

    /**
     * Matches a single-quoted {@code font-family} attribute,
     * capturing the family name in group 1.
     */
    private static final Pattern FONT_FAMILY_SINGLE_QUOTED = Pattern.compile(
            "font-family=\"'([^']+)'\"");

    /**
     * Matches single-quoted {@code font-family} attributes whose family
     * name starts with a dot (Apple's private name convention).
     */
    private static final Pattern APPLE_PRIVATE_FONT_FAMILY = Pattern.compile(
            "font-family=\"'(\\.[^']+)'\"");

    /**
     * Matches an {@code <image>} {@code xlink:href} attribute carrying
     * a base64-encoded PNG payload. Group 1 captures the prefix up to
     * the data, group 2 the base64 body, and group 3 the closing quote.
     */
    private static final Pattern PNG_DATA_URL = Pattern.compile(
            "(xlink:href=\")data:image/png;base64,([A-Za-z0-9+/=\\s&;#]+?)(\")",
            Pattern.DOTALL);

    /**
     * Matches the opening {@code <svg …>} root element, capturing the
     * whole opening tag so the font-face block can be inserted
     * immediately after it.
     */
    private static final Pattern OPENING_SVG_TAG = Pattern.compile(
            "(<svg\\b[^>]*>)", Pattern.DOTALL);

    /** Local font inventory used to pick the substitute family. */
    private final FontInventory fontInventory;

    /**
     * @param fontInventory snapshot of locally available fonts and the
     *        substitute family chosen for the current OS
     */
    SvgPostProcessor(FontInventory fontInventory) {
        this.fontInventory = fontInventory;
    }

    /**
     * Runs every pass on {@code svgFile} in place.
     *
     * <p>Files whose name doesn't end in {@code .svg} are left
     * untouched. Failures inside an individual pass are logged but
     * never propagate; the file is always either left unchanged or
     * rewritten as a whole.
     *
     * @param svgFile the file to transform, possibly {@code null}
     */
    void process(Path svgFile) {
        if (svgFile == null) return;
        if (!isSvg(svgFile)) return;
        try {
            String original = Files.readString(svgFile);
            String transformed = runAllPasses(original, svgFile);
            if (!transformed.equals(original)) {
                Files.writeString(svgFile, transformed);
            }
        } catch (Throwable t) {
            System.err.println(
                    "SvgPostProcessor: failed to post-process " + svgFile + ": " + t);
        }
    }

    /**
     * Runs the full transformation pipeline on an in-memory copy of
     * the SVG source.
     *
     * @param original source SVG string
     * @param svgFile the file being processed (only used for log lines)
     * @return the transformed SVG, possibly identical to {@code original}
     */
    private String runAllPasses(String original, Path svgFile) {
        String substituteFamily = fontInventory.substituteFamily();
        String content = original;
        content = rewriteDialogFamilyToMostCommon(content, substituteFamily, svgFile);
        content = setImageRenderingToOptimizeQuality(content);
        content = appendInterFallbackForApplePrivateFonts(content);
        if (!fontInventory.runningOnMacOs()) {
            content = canonicaliseFamilyTo(content, substituteFamily);
        }
        content = upscaleEmbeddedRasterImages(content);
        if (referencesInter(content)) {
            content = injectInterFontFace(content);
        }
        return content;
    }

    /**
     * @param svgFile any path
     * @return {@code true} when {@code svgFile}'s lowercase name ends
     *         in {@code .svg}
     */
    private static boolean isSvg(Path svgFile) {
        return svgFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    /**
     * Replaces Batik's {@code font-family="'Dialog'"} placeholder with
     * the most-used explicit family elsewhere in the SVG, so unstyled
     * text matches the rest of the diagram instead of falling back to
     * the viewer's default sans.
     *
     * @param svg input SVG string
     * @param defaultFamily fallback when no explicit family is found
     * @param svgFile file path used for log output
     * @return rewritten SVG
     */
    private static String rewriteDialogFamilyToMostCommon(
            String svg, String defaultFamily, Path svgFile) {
        String replacement = mostCommonExplicitFamily(svg, defaultFamily);
        if ("Dialog".equals(replacement)) return svg;
        String rewritten = svg.replace(
                "font-family=\"'Dialog'\"",
                "font-family=\"'" + replacement + "'\"");
        if (!rewritten.equals(svg)) {
            System.out.println(
                    "SvgPostProcessor: rewrote Batik's 'Dialog' default to '"
                            + replacement + "' in " + svgFile.getFileName());
        }
        return rewritten;
    }

    /**
     * Tallies every explicit single-quoted {@code font-family}
     * declaration in the SVG (ignoring {@code Dialog}) and returns the
     * most frequently used name.
     *
     * @param svg SVG source
     * @param defaultFamily value returned when there are no explicit
     *        families
     * @return winning family name
     */
    private static String mostCommonExplicitFamily(String svg, String defaultFamily) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = FONT_FAMILY_SINGLE_QUOTED.matcher(svg);
        while (matcher.find()) {
            String family = matcher.group(1);
            if ("Dialog".equals(family)) continue;
            counts.merge(family, 1, Integer::sum);
        }
        String winner = defaultFamily;
        int best = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                winner = entry.getKey();
            }
        }
        return winner;
    }

    /**
     * Replaces the root {@code image-rendering="auto"} attribute with
     * {@code optimizeQuality} so viewers bicubic-interpolate the
     * embedded raster images on zoom rather than nearest-neighbouring
     * them.
     *
     * @param svg input SVG
     * @return rewritten SVG
     */
    private static String setImageRenderingToOptimizeQuality(String svg) {
        return svg.replace("image-rendering=\"auto\"", "image-rendering=\"optimizeQuality\"");
    }

    /**
     * Appends {@code 'Inter', sans-serif} as fallbacks to every
     * Apple-private {@code font-family} declaration so the SVG
     * degrades gracefully on non-macOS viewers.
     *
     * @param svg input SVG
     * @return rewritten SVG
     */
    private static String appendInterFallbackForApplePrivateFonts(String svg) {
        Matcher matcher = APPLE_PRIVATE_FONT_FAMILY.matcher(svg);
        if (!matcher.find()) return svg;
        matcher.reset();
        StringBuilder rebuilt = new StringBuilder(svg.length());
        int rewrites = 0;
        while (matcher.find()) {
            matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(
                    "font-family=\"'" + matcher.group(1) + "', 'Inter', sans-serif\""));
            rewrites++;
        }
        matcher.appendTail(rebuilt);
        if (rewrites > 0) {
            System.out.println(
                    "SvgPostProcessor: appended 'Inter', sans-serif fallback to "
                            + rewrites + " Apple-private font-family declaration(s)");
        }
        return rebuilt.toString();
    }

    /**
     * Forces every remaining single-quoted {@code font-family}
     * declaration to {@code targetFamily}, leaving Apple-private
     * (handled by {@link #appendInterFallbackForApplePrivateFonts})
     * and {@code Dialog} (handled by
     * {@link #rewriteDialogFamilyToMostCommon}) declarations alone.
     *
     * @param svg input SVG
     * @param targetFamily family name everything else collapses to
     * @return rewritten SVG
     */
    private static String canonicaliseFamilyTo(String svg, String targetFamily) {
        Matcher matcher = FONT_FAMILY_SINGLE_QUOTED.matcher(svg);
        StringBuilder rebuilt = new StringBuilder(svg.length());
        int rewrites = 0;
        while (matcher.find()) {
            String family = matcher.group(1);
            if (family.equals(targetFamily) || family.startsWith(".") || "Dialog".equals(family)) {
                matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(
                        "font-family=\"'" + targetFamily + "'\""));
                rewrites++;
            }
        }
        matcher.appendTail(rebuilt);
        if (rewrites > 0) {
            System.out.println(
                    "SvgPostProcessor: canonicalised " + rewrites
                            + " platform-default font-family declaration(s) to '"
                            + targetFamily + "'");
        }
        return rebuilt.toString();
    }

    /**
     * Decodes each embedded {@code data:image/png;base64} payload,
     * scales it up by {@link #RASTER_UPSCALE_FACTOR} with bicubic
     * interpolation, re-encodes, and splices it back into the SVG.
     *
     * @param svg input SVG
     * @return rewritten SVG (unchanged when there are no embedded
     *         rasters)
     */
    private static String upscaleEmbeddedRasterImages(String svg) {
        if (RASTER_UPSCALE_FACTOR <= 1) return svg;
        Matcher matcher = PNG_DATA_URL.matcher(svg);
        StringBuilder rebuilt = new StringBuilder(svg.length());
        int upscaled = 0;
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String base64Body = matcher.group(2).replaceAll("[\\s]|&#10;|&#13;", "");
            String suffix = matcher.group(3);
            String replacement = upscalePngIfPossible(prefix, base64Body, suffix);
            if (!replacement.equals(matcher.group(0))) upscaled++;
            matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rebuilt);
        if (upscaled > 0) {
            System.out.println(
                    "SvgPostProcessor: upscaled " + upscaled
                            + " embedded raster image(s) " + RASTER_UPSCALE_FACTOR + "x");
        }
        return rebuilt.toString();
    }

    /**
     * Attempts to base64-decode, upscale, and re-encode a single PNG
     * data URL. Leaves the URL unchanged on any failure.
     *
     * @param prefix the matched {@code xlink:href="} prefix
     * @param base64Body the cleaned base64-encoded PNG bytes
     * @param suffix the matched closing quote
     * @return the fully reconstructed attribute value
     */
    private static String upscalePngIfPossible(String prefix, String base64Body, String suffix) {
        try {
            byte[] sourceBytes = Base64.getDecoder().decode(base64Body);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (source == null) return prefix + "data:image/png;base64," + base64Body + suffix;
            BufferedImage scaled = scaleWithBicubicQuality(source, RASTER_UPSCALE_FACTOR);
            String encoded = encodePng(scaled);
            return prefix + "data:image/png;base64," + encoded + suffix;
        } catch (Throwable t) {
            return prefix + "data:image/png;base64," + base64Body + suffix;
        }
    }

    /**
     * Scales {@code source} into a new {@link BufferedImage} that is
     * {@code factor}× larger in each dimension, using high-quality
     * bicubic interpolation.
     *
     * @param source the original raster
     * @param factor positive integer scale factor
     * @return the scaled raster
     */
    private static BufferedImage scaleWithBicubicQuality(BufferedImage source, int factor) {
        int width = source.getWidth() * factor;
        int height = source.getHeight() * factor;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    /**
     * Encodes a {@link BufferedImage} as a base64-encoded PNG string.
     *
     * @param image the raster to encode
     * @return base64 body (no {@code data:} prefix)
     * @throws java.io.IOException when PNG encoding fails
     */
    private static String encodePng(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", buffer);
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * @param svg SVG source
     * @return {@code true} if any {@code font-family} declaration in
     *         {@code svg} references Inter
     */
    private static boolean referencesInter(String svg) {
        return svg.contains("'Inter'");
    }

    /**
     * Injects the bundled Inter {@code @font-face} declaration right
     * after the opening {@code <svg>} tag so viewers can render Inter
     * without it being installed locally. Returns the input unchanged
     * if the Inter resource isn't on the classpath.
     *
     * @param svg input SVG
     * @return rewritten SVG, possibly identical to {@code svg}
     */
    private static String injectInterFontFace(String svg) {
        Optional<String> fontFaceBlock = BundledInterFont.fontFaceBlock();
        if (fontFaceBlock.isEmpty()) return svg;
        Matcher matcher = OPENING_SVG_TAG.matcher(svg);
        if (!matcher.find()) return svg;
        StringBuilder rebuilt = new StringBuilder(svg.length() + fontFaceBlock.get().length());
        matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(
                matcher.group(1) + fontFaceBlock.get()));
        matcher.appendTail(rebuilt);
        return rebuilt.toString();
    }
}
