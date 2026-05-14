package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
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
 * de-duplication state across all diagrams in the same output directory.
 */
final class DiagramFilenameGenerator {

    private final FilenameStrategy strategy;
    private final Set<String> usedStems = new HashSet<>();

    DiagramFilenameGenerator(FilenameStrategy strategy) {
        this.strategy = strategy;
    }

    /** Returns the filename stem for a GMF {@link Diagram}. */
    String stemFor(Diagram diagram) {
        return dedupe(rawStemFor(diagram));
    }

    /** Returns the filename stem for a Sirius representation descriptor. */
    String stemFor(DRepresentationDescriptor descriptor) {
        return dedupe(rawStemFor(descriptor));
    }

    private String rawStemFor(Diagram diagram) {
        if (strategy == FilenameStrategy.NAME && hasUsableName(diagram.getName())) {
            return sanitise(diagram.getName());
        }
        String fragment = EcoreUtil.getURI(diagram).fragment();
        return sanitise(fragment != null ? fragment : "diagram");
    }

    private String rawStemFor(DRepresentationDescriptor descriptor) {
        if (strategy == FilenameStrategy.NAME && hasUsableName(descriptor.getName())) {
            return sanitise(descriptor.getName());
        }
        return sanitise(resourceFragmentOf(descriptor));
    }

    private static String resourceFragmentOf(DRepresentationDescriptor descriptor) {
        if (descriptor.eResource() == null) return "representation";
        String fragment = descriptor.eResource().getURIFragment(descriptor);
        return fragment == null || fragment.isBlank() ? "representation" : fragment;
    }

    private static boolean hasUsableName(String name) {
        return name != null && !name.isBlank();
    }

    private static String sanitise(String raw) {
        return raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String dedupe(String stem) {
        String candidate = stem;
        int suffix = 1;
        while (!usedStems.add(candidate)) {
            candidate = stem + "_" + (++suffix);
        }
        return candidate;
    }
}
