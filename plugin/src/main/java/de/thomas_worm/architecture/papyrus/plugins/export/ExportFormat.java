/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.Locale;

import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;

/**
 * The image formats the export pipeline can produce.
 *
 * <p>Each value maps to a lowercase file extension (used to build the
 * output file name) and to the corresponding {@link ImageFileFormat}
 * that GMF's {@code CopyToImageUtil} expects as an argument.
 *
 * <p>{@code PDF} is intentionally excluded: the GMF runtime shipped
 * with Papyrus-Desktop 7.1.0 does not bundle a PDF transcoder, and
 * {@code CopyToImageUtil.copyToImage(..., ImageFileFormat.PDF, ...)}
 * throws {@code CoreException("PDF export is not supported in this
 * version")} on every diagram. Users who need PDF should convert one
 * of the supported formats afterwards (e.g. with
 * {@code rsvg-convert}, Inkscape, or headless Chromium).
 */
enum ExportFormat {

    /** Scalable Vector Graphics, the default format. */
    SVG("svg", ImageFileFormat.SVG),

    /** Portable Network Graphics. */
    PNG("png", ImageFileFormat.PNG),

    /**
     * JPEG (a.k.a. JFIF). The user-facing alias {@code JPG} is
     * accepted by {@link #parse} and maps to this enum constant.
     */
    JPEG("jpg", ImageFileFormat.JPG),

    /** Windows bitmap. */
    BMP("bmp", ImageFileFormat.BMP),

    /** Graphics Interchange Format. */
    GIF("gif", ImageFileFormat.GIF);

    /** Lowercase file extension, without leading dot. */
    private final String fileExtension;

    /** GMF runtime constant for this format. */
    private final ImageFileFormat gmfFormat;

    /**
     * @param fileExtension lowercase extension this format writes
     * @param gmfFormat GMF runtime identifier for the format
     */
    ExportFormat(String fileExtension, ImageFileFormat gmfFormat) {
        this.fileExtension = fileExtension;
        this.gmfFormat = gmfFormat;
    }

    /**
     * @return the lowercase file extension (without leading dot) for
     *         files of this format
     */
    String fileExtension() {
        return fileExtension;
    }

    /** @return the GMF runtime's identifier for this format. */
    ImageFileFormat toGmfFormat() {
        return gmfFormat;
    }

    /**
     * Resolves a user-supplied format name. Case-insensitive;
     * {@code JPG} is accepted as a synonym for {@code JPEG}; {@code null}
     * returns {@link #SVG}.
     *
     * @param name command-line value of the {@code --format} flag,
     *        possibly {@code null}
     * @return the matching format
     * @throws IllegalArgumentException if {@code name} doesn't match
     *         any known format
     */
    static ExportFormat parse(String name) {
        if (name == null) return SVG;
        String upper = name.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SVG" -> SVG;
            case "PNG" -> PNG;
            case "JPEG", "JPG" -> JPEG;
            case "BMP" -> BMP;
            case "GIF" -> GIF;
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + name + "' "
                            + "(expected one of SVG, PNG, JPEG, BMP, GIF)");
        };
    }
}
