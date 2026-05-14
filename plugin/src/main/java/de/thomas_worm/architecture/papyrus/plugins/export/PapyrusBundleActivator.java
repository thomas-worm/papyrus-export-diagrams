package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Activates the OSGi bundles that the headless export pipeline depends
 * on.
 *
 * <p>Activation is best-effort: bundles that are missing from the
 * install (e.g. a Papyrus-Classic distribution without Sirius) are
 * logged to stderr but do not fail activation. The list is ordered so
 * dependencies activate before their consumers — CSS bundles must be
 * up before any {@code .notation} is loaded so the resource factory
 * override produces CSS-aware diagrams.
 */
final class PapyrusBundleActivator {

    private static final List<String> REQUIRED_BUNDLES = List.of(
            "org.eclipse.papyrus.infra.core",
            "org.eclipse.papyrus.infra.gmfdiag.common",
            "org.eclipse.papyrus.infra.gmfdiag.css",
            "org.eclipse.papyrus.uml.diagram.css",
            "org.eclipse.papyrus.infra.gmfdiag.style",
            "org.eclipse.gmf.runtime.diagram.ui.render",
            "org.eclipse.sirius",
            "org.eclipse.sirius.ui",
            "org.eclipse.sirius.diagram.ui");

    private PapyrusBundleActivator() { }

    /**
     * Activates every required bundle in dependency order.
     */
    static void activateAll() {
        for (String bundleSymbolicName : REQUIRED_BUNDLES) {
            activate(bundleSymbolicName);
        }
    }

    private static void activate(String symbolicName) {
        try {
            Bundle bundle = Platform.getBundle(symbolicName);
            if (bundle != null) bundle.start();
        } catch (Throwable t) {
            System.err.println("Could not activate " + symbolicName + ": " + t);
        }
    }
}
