package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.Locale;

import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;

/**
 * The image formats the export pipeline can produce.
 *
 * <p>Each value maps to a lowercase file extension and to the
 * corresponding {@link ImageFileFormat} that GMF's
 * {@code CopyToImageUtil} expects.
 */
enum ExportFormat {

    SVG("svg", ImageFileFormat.SVG),
    PNG("png", ImageFileFormat.PNG),
    JPEG("jpg", ImageFileFormat.JPG),
    BMP("bmp", ImageFileFormat.BMP),
    GIF("gif", ImageFileFormat.GIF),
    PDF("pdf", ImageFileFormat.PDF);

    private final String fileExtension;
    private final ImageFileFormat gmfFormat;

    ExportFormat(String fileExtension, ImageFileFormat gmfFormat) {
        this.fileExtension = fileExtension;
        this.gmfFormat = gmfFormat;
    }

    /**
     * Returns the lowercase file extension (without leading dot) for
     * files of this format.
     */
    String fileExtension() {
        return fileExtension;
    }

    /** Returns the GMF runtime's identifier for this format. */
    ImageFileFormat toGmfFormat() {
        return gmfFormat;
    }

    /**
     * Resolves a user-supplied format name (case-insensitive,
     * {@code JPG} accepted as a synonym for {@code JPEG}). Returns
     * {@code SVG} if {@code name} is {@code null}.
     *
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
            case "PDF" -> PDF;
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + name + "' "
                            + "(expected one of SVG, PNG, JPEG, BMP, GIF, PDF)");
        };
    }
}
