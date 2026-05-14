/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;

/**
 * Builds filename stems (without extension) for exported diagrams,
 * choosing between the diagram's user-facing name and its stable XMI
 * fragment id depending on the configured {@link FilenameStrategy} and
 * de-duplicating any collisions by appending {@code _2}, {@code _3},
 * etc.
 *
 * <p>One instance is created per export run to share the
 * de-duplication state across all diagrams in the same output
 * directory.
 */
final class DiagramFilenameGenerator {

    /** Strategy used to derive each filename stem before deduplication. */
    private final FilenameStrategy strategy;

    /**
     * Already-issued stems for the current run. {@link #dedupe} updates
     * this set on every call so subsequent collisions get a numeric
     * suffix.
     */
    private final Set<String> usedStems = new HashSet<>();

    /**
     * Creates a generator for one export run.
     *
     * @param strategy the strategy used to derive filename stems
     */
    DiagramFilenameGenerator(FilenameStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Generates the filename stem for a GMF diagram, sanitising and
     * de-duplicating as needed.
     *
     * @param diagram the GMF {@link Diagram} being exported
     * @return the filename stem (without extension)
     */
    String stemFor(Diagram diagram) {
        return dedupe(rawStemFor(diagram));
    }

    /**
     * Generates the filename stem for a Sirius representation
     * descriptor, sanitising and de-duplicating as needed.
     *
     * @param descriptor the descriptor being exported
     * @return the filename stem (without extension)
     */
    String stemFor(DRepresentationDescriptor descriptor) {
        return dedupe(rawStemFor(descriptor));
    }

    /**
     * Computes the sanitised but un-deduplicated stem for a GMF
     * diagram.
     *
     * @param diagram the diagram to derive a stem from
     * @return sanitised stem candidate
     */
    private String rawStemFor(Diagram diagram) {
        if (strategy == FilenameStrategy.NAME && hasUsableName(diagram.getName())) {
            return sanitise(diagram.getName());
        }
        String fragment = EcoreUtil.getURI(diagram).fragment();
        return sanitise(fragment != null ? fragment : "diagram");
    }

    /**
     * Computes the sanitised but un-deduplicated stem for a Sirius
     * representation descriptor.
     *
     * @param descriptor the descriptor to derive a stem from
     * @return sanitised stem candidate
     */
    private String rawStemFor(DRepresentationDescriptor descriptor) {
        if (strategy == FilenameStrategy.NAME && hasUsableName(descriptor.getName())) {
            return sanitise(descriptor.getName());
        }
        return sanitise(resourceFragmentOf(descriptor));
    }

    /**
     * Resolves the XMI fragment of a Sirius descriptor, falling back
     * to a literal {@code "representation"} when the descriptor isn't
     * yet attached to a resource.
     *
     * @param descriptor the descriptor whose fragment is needed
     * @return the descriptor's fragment, or a default identifier
     */
    private static String resourceFragmentOf(DRepresentationDescriptor descriptor) {
        if (descriptor.eResource() == null) return "representation";
        String fragment = descriptor.eResource().getURIFragment(descriptor);
        return fragment == null || fragment.isBlank() ? "representation" : fragment;
    }

    /**
     * @param name candidate user-visible name
     * @return {@code true} if {@code name} is non-{@code null} and
     *         non-blank
     */
    private static boolean hasUsableName(String name) {
        return name != null && !name.isBlank();
    }

    /**
     * Replaces any run of non-{@code [A-Za-z0-9._-]} characters in
     * {@code raw} with a single underscore. Leading and trailing
     * whitespace is trimmed first.
     *
     * @param raw input string
     * @return file-safe replacement
     */
    private static String sanitise(String raw) {
        return raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /**
     * Appends {@code _2}, {@code _3}, … until the candidate is unique
     * within {@link #usedStems}, then records and returns it.
     *
     * @param stem the desired stem
     * @return the unique stem, possibly suffixed
     */
    private String dedupe(String stem) {
        String candidate = stem;
        int suffix = 1;
        while (!usedStems.add(candidate)) {
            candidate = stem + "_" + (++suffix);
        }
        return candidate;
    }
}
