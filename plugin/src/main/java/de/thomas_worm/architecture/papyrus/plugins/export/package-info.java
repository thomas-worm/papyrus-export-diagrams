/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */

/**
 * Headless Papyrus diagram export plugin.
 *
 * <p>This OSGi bundle plugs into a Papyrus-Desktop installation and
 * exposes a command-line {@code IApplication} that renders every
 * diagram contained in a model directory to image files. Two pipelines
 * run in sequence:
 *
 * <ul>
 *   <li>{@link de.thomas_worm.architecture.papyrus.plugins.export.NotationDiagramExporter}
 *       walks {@code .di} / {@code .notation} pairs and pushes each
 *       GMF {@code Diagram} through
 *       {@code CopyToImageUtil};</li>
 *   <li>{@link de.thomas_worm.architecture.papyrus.plugins.export.SiriusRepresentationExporter}
 *       opens every {@code .aird} file via Sirius's
 *       {@code SessionManager}, refreshes each representation, finds
 *       its backing GMF diagram, and pushes that through the same
 *       {@code CopyToImageUtil} path (bypassing Sirius's own SVG
 *       generator, which has a known cast bug with Papyrus's symbol
 *       shapes).</li>
 * </ul>
 *
 * <p>Every rendered SVG is then post-processed by
 * {@link de.thomas_worm.architecture.papyrus.plugins.export.SvgPostProcessor}
 * for font-family canonicalisation, raster upscaling, and inlined
 * Inter font embedding.
 *
 * <p>{@link de.thomas_worm.architecture.papyrus.plugins.export.ExportApplication}
 * is the entry point; the rest of the package is organised by
 * responsibility and is package-private.
 */
package de.thomas_worm.architecture.papyrus.plugins.export;
