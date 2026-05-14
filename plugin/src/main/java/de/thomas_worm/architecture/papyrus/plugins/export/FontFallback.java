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

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.FontStyle;
import org.eclipse.gmf.runtime.notation.NotationPackage;

final class FontFallback {

    /**
     * After Batik writes the SVG, the JVM's logical "Dialog" font ends
     * up declared on the root &lt;svg&gt; element as the family for
     * unstyled text — viewers don't know "Dialog" and substitute their
     * default sans, which gives inconsistent widths across platforms.
     * Replace it with the resolved fallback so every text node points
     * at a real, widely-available family.
     */
    /** Resolution multiplier applied to each embedded raster image. */
    private static final int RASTER_UPSCALE = 4;

    static void postProcessSvg(java.nio.file.Path svgFile) {
        if (svgFile == null) return;
        String name = svgFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".svg")) return;
        FontFallback ff = new FontFallback();
        try {
            String content = java.nio.file.Files.readString(svgFile);
            String original = content;

            // (a) Resolve Java's logical "Dialog" family to a real font.
            // Pick whichever font appears most often in the SVG's
            // explicit font-family attributes — that way unstyled text
            // matches what's already in the picture instead of being
            // overridden with our generic fallback (e.g. on macOS, the
            // model's .AppleSystemUIFont is kept everywhere instead of
            // having Arial bleed into the default-styled labels).
            String dialogReplacement = pickDialogReplacement(content, ff.fallback);
            if (!"Dialog".equals(dialogReplacement)) {
                content = content.replace(
                        "font-family=\"'Dialog'\"",
                        "font-family=\"'" + dialogReplacement + "'\"");
                System.out.println("SvgPostProcess: rewrote Batik's 'Dialog' default to '"
                        + dialogReplacement + "' in " + svgFile.getFileName());
            }

            // (b) Tell viewers to interpolate the embedded PNGs instead
            // of nearest-neighbouring them.
            content = content.replace(
                    "image-rendering=\"auto\"",
                    "image-rendering=\"optimizeQuality\"");

            // (c) Apple-private font names (`.AppleSystemUIFont` etc.) only
            // resolve on macOS. Append Inter (our shipped fallback) and
            // `sans-serif` to those font-family declarations so viewers
            // on Linux/Windows still get a faithful render.
            content = addInterFallbackForAppleFonts(content);

            // (d) Upscale every embedded raster image to RASTER_UPSCALE×
            // its native resolution using bicubic interpolation. The
            // SVG display dimensions (the <image> width/height) stay
            // the same — only the PNG inside the data: URL has more
            // pixels, so zooming in shows interpolated rather than
            // blocky output.
            content = upscaleEmbeddedPngs(content, RASTER_UPSCALE);

            // (e) When the SVG references the Inter font (directly or
            // as an Apple-private-font fallback), inline the WOFF2 we
            // ship in the plugin as an @font-face data URL so viewers
            // without Inter installed render the same way.
            if (content.contains("'Inter'") || dialogReplacement.equals("Inter")) {
                content = embedInterFontFace(content);
            }

            if (!content.equals(original)) {
                java.nio.file.Files.writeString(svgFile, content);
            }
        } catch (Throwable t) {
            System.err.println("FontFallback: SVG post-process failed for "
                    + svgFile + ": " + t);
        }
    }

    private static final Pattern FONT_FAMILY = Pattern.compile(
            "font-family=\"'([^']+)'\"");

    private static final Pattern APPLE_PRIVATE_FONT_FAMILY = Pattern.compile(
            "font-family=\"'(\\.[^']+)'\"");

    private static String addInterFallbackForAppleFonts(String svg) {
        Matcher m = APPLE_PRIVATE_FONT_FAMILY.matcher(svg);
        if (!m.find()) return svg;
        m.reset();
        StringBuilder out = new StringBuilder(svg.length());
        int rewrites = 0;
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "font-family=\"'" + m.group(1) + "', 'Inter', sans-serif\""));
            rewrites++;
        }
        m.appendTail(out);
        if (rewrites > 0) {
            System.out.println("SvgPostProcess: appended 'Inter', sans-serif fallback to "
                    + rewrites + " Apple-private font-family declaration(s)");
        }
        return out.toString();
    }

    private static volatile String CACHED_INTER_FONT_FACE;

    private static String embedInterFontFace(String svg) {
        try {
            String fontFace = CACHED_INTER_FONT_FACE;
            if (fontFace == null) {
                try (java.io.InputStream in = FontFallback.class
                        .getResourceAsStream("/fonts/Inter-Regular.woff2")) {
                    if (in == null) {
                        System.err.println("SvgPostProcess: Inter-Regular.woff2 "
                                + "not on the classpath; viewers without Inter "
                                + "installed will see fallback fonts");
                        return svg;
                    }
                    byte[] bytes = in.readAllBytes();
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    fontFace = "<defs><style type=\"text/css\">"
                            + "@font-face{"
                            + "font-family:'Inter';"
                            + "font-style:normal;"
                            + "font-weight:400;"
                            + "src:url(data:font/woff2;base64," + b64 + ") format('woff2');"
                            + "}"
                            + "</style></defs>";
                    CACHED_INTER_FONT_FACE = fontFace;
                    System.out.println("SvgPostProcess: embedded Inter Regular ("
                            + bytes.length + " bytes raw / "
                            + b64.length() + " bytes base64)");
                }
            }
            // Inject right after the opening <svg ...> tag so the
            // @font-face is parsed before any text uses it.
            Matcher m = Pattern.compile("(<svg\\b[^>]*>)", Pattern.DOTALL).matcher(svg);
            if (m.find()) {
                StringBuilder out = new StringBuilder(svg.length() + fontFace.length());
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + fontFace));
                m.appendTail(out);
                return out.toString();
            }
        } catch (Throwable t) {
            System.err.println("SvgPostProcess: embedding Inter failed: " + t);
        }
        return svg;
    }

    private static String pickDialogReplacement(String svg, String defaultFallback) {
        // Tally explicit font-family declarations, ignoring "Dialog"
        // itself; whichever real family is most used wins.
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        Matcher m = FONT_FAMILY.matcher(svg);
        while (m.find()) {
            String fam = m.group(1);
            if ("Dialog".equals(fam)) continue;
            counts.merge(fam, 1, Integer::sum);
        }
        String winner = defaultFallback;
        int best = -1;
        for (var e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                winner = e.getKey();
            }
        }
        return winner;
    }

    private static final Pattern PNG_DATA_URL = Pattern.compile(
            "(xlink:href=\")data:image/png;base64,([A-Za-z0-9+/=\\s&;#]+?)(\")",
            Pattern.DOTALL);

    private static String upscaleEmbeddedPngs(String svg, int factor) {
        if (factor <= 1) return svg;
        Matcher m = PNG_DATA_URL.matcher(svg);
        StringBuilder out = new StringBuilder(svg.length());
        int upscaled = 0;
        while (m.find()) {
            String pre = m.group(1);
            String b64 = m.group(2).replaceAll("[\\s]|&#10;|&#13;", "");
            String post = m.group(3);
            String replacement = m.group(0); // fallback
            try {
                byte[] bytes = Base64.getDecoder().decode(b64);
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
                if (src != null) {
                    int w = src.getWidth() * factor;
                    int h = src.getHeight() * factor;
                    BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.drawImage(src, 0, 0, w, h, null);
                    g.dispose();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(scaled, "PNG", baos);
                    String newB64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                    replacement = pre + "data:image/png;base64," + newB64 + post;
                    upscaled++;
                }
            } catch (Throwable t) {
                // Leave the original data URL alone if anything fails.
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        if (upscaled > 0) {
            System.out.println("SvgPostProcess: upscaled " + upscaled
                    + " embedded raster image(s) " + factor + "x");
        }
        return out.toString();
    }


    // Search order for replacing an unavailable font. The first family
    // in this list that the JVM actually has wins.
    //
    // On macOS, `.AppleSystemUIFont` (San Francisco) is the platform's
    // native UI font and what models authored on macOS expect. Keep it
    // at the top so labels that fell off the model's explicit FontStyle
    // re-resolve to the same family, then Inter as a close metric match
    // for anything where SF can't be loaded directly.
    //
    // On Linux/Windows, Inter is the closest freely-redistributable
    // match for SF, which setup-papyrus installs system-wide. Arial /
    // Liberation Sans come next as metric-compatible backups; SansSerif
    // (Java's logical sans-serif) is the final always-present fallback.
    private static final List<String> FALLBACK_ORDER_MACOS = List.of(
            ".AppleSystemUIFont",
            "Inter",
            "Helvetica",
            "Arial",
            "SansSerif");
    private static final List<String> FALLBACK_ORDER_OTHER = List.of(
            "Inter",
            "Arial",
            "Liberation Sans",
            "DejaVu Sans",
            "Noto Sans",
            "SansSerif");
    private static List<String> fallbackOrder() {
        return IS_MACOS ? FALLBACK_ORDER_MACOS : FALLBACK_ORDER_OTHER;
    }

    private final Set<String> available;
    private final String fallback;
    private int rewrites;

    private FontFallback() {
        this.available = collectAvailableFamilies();
        this.fallback = chooseFallback();
    }

    /**
     * Walk every {@link FontStyle} in the supplied resources and replace
     * any unavailable font name with the configured fallback. Mutations
     * run inside a {@link RecordingCommand} on {@code editingDomain} so
     * the transactional resource set doesn't reject the writes. Returns
     * the number of rewrites performed.
     */
    static int remap(TransactionalEditingDomain editingDomain,
                     Iterable<? extends Resource> resources) {
        FontFallback ff = new FontFallback();
        Runnable body = () -> {
            for (Resource res : resources) {
                ff.remapResource(res);
            }
        };
        if (editingDomain != null) {
            editingDomain.getCommandStack().execute(new RecordingCommand(editingDomain, "Remap fonts") {
                @Override
                protected void doExecute() { body.run(); }
            });
        } else {
            body.run();
        }
        if (ff.rewrites > 0) {
            System.out.println("FontFallback: " + ff.rewrites
                    + " FontStyle entries referenced fonts not installed locally "
                    + "(of " + ff.available.size() + " families available); rewrote them to '"
                    + ff.fallback + "'");
        } else {
            System.out.println("FontFallback: all model FontStyle entries reference fonts "
                    + "already installed locally (" + ff.available.size() + " families available); "
                    + "no remapping needed");
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
                boolean explicit = fs.eIsSet(NotationPackage.eINSTANCE.getFontStyle_FontName());
                // Remap when:
                //   * the named font isn't installed locally (regardless
                //     of who set it), OR
                //   * on non-macOS, the FontStyle wasn't explicitly set
                //     in the notation file. Implicit FontStyles inherit
                //     the platform's GMF default (Tahoma / Segoe UI /
                //     etc.) which doesn't match how the same model looks
                //     on macOS. Forcing Inter there keeps the visual
                //     result consistent across runners.
                boolean implicitOnNonMac = !IS_MACOS && !explicit;
                if (!isAvailable(name) || (implicitOnNonMac && !fallback.equals(name))) {
                    fs.setFontName(fallback);
                    rewrites++;
                }
            }
        }
    }

    private static final boolean IS_MACOS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private boolean isAvailable(String family) {
        // Apple's dot-prefixed system font names (".AppleSystemUIFont",
        // ".SFCompactDisplay-Regular", …) are macOS-private. Java on
        // Windows sometimes reports these as "available" via alias
        // entries in unrelated installed fonts, but the actual paint
        // falls back to a different family (typically Segoe UI). Force
        // remap on non-macOS so the substitution and the @font-face
        // embedding actually fire.
        if (!IS_MACOS && family.startsWith(".")) return false;
        if (available.contains(family)) return true;
        return available.contains(family.toLowerCase(Locale.ROOT));
    }

    private String chooseFallback() {
        for (String candidate : fallbackOrder()) {
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
